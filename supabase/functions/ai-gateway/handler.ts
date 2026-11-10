// Request orchestration: authenticate -> validate -> reserve tokens -> call the model -> settle (or release on failure).
// All side effects come in through `Deps`, so the whole flow is testable without Deno, Supabase or a model.

import {
  budgetFor, buildUserPrompt, parseModelOutput, parseRequest, SYSTEM_PROMPT, ValidationError,
} from "./core.ts";
import type { GenerateRequest } from "./core.ts";

export interface Usage {
  plan: string; limit: number; used: number; reserved: number; remaining: number;
  period_start: string; resets_at: string; requests_per_minute: number; max_request_tokens: number;
}
export interface Reservation { allowed: boolean; reason: string; request_id: string | null; retry_after: number; usage: Usage }

export class ProviderError extends Error {
  status: number; timeout: boolean; chargedTokens: number;
  constructor(message: string, status: number, timeout = false, chargedTokens = 0) {
    super(message); this.status = status; this.timeout = timeout; this.chargedTokens = chargedTokens;
  }
}

export interface Deps {
  authenticate(authorization: string | null): Promise<{ userId: string } | null>;
  quota: {
    status(userId: string): Promise<Usage>;
    reserve(userId: string, task: string, estimate: number): Promise<Reservation>;
    settle(requestId: string, actualTokens: number): Promise<Usage>;
    release(requestId: string, reason: string, chargedTokens: number): Promise<Usage>;
  };
  provider: {
    configured(): boolean;
    complete(a: { system: string; user: string; maxTokens: number }): Promise<{ text: string; inputTokens: number; outputTokens: number }>;
  };
  log(event: string, data: Record<string, unknown>): void;
}

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization, content-type, apikey, x-client-info",
  "access-control-allow-methods": "POST, OPTIONS",
};

function json(status: number, body: unknown, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-store", ...CORS, ...extra } });
}

const usageBody = (u: Usage) => ({
  plan: u.plan, limit: u.limit, used: u.used, reserved: u.reserved, remaining: u.remaining,
  periodStart: u.period_start, resetsAt: u.resets_at, requestsPerMinute: u.requests_per_minute,
});

export async function handleRequest(req: Request, deps: Deps): Promise<Response> {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  if (req.method !== "POST") return json(405, { error: "method_not_allowed", message: "Use POST" });

  const user = await deps.authenticate(req.headers.get("authorization"));
  if (!user) return json(401, { error: "unauthorized", message: "Sign in again" });

  let parsed;
  try {
    parsed = parseRequest(await req.json());
  } catch (e) {
    if (e instanceof ValidationError) return json(400, { error: "invalid_request", field: e.field, message: e.message });
    return json(400, { error: "invalid_request", message: "Body must be JSON" });
  }

  if (parsed.action === "usage") {
    try { return json(200, { usage: usageBody(await deps.quota.status(user.userId)) }); }
    catch (e) { deps.log("usage_failed", { error: String(e) }); return json(503, { error: "quota_unavailable", message: "Usage is temporarily unavailable" }); }
  }

  if (!deps.provider.configured()) return json(503, { error: "ai_not_configured", message: "AI is not set up on the server yet" });
  return generate(user.userId, parsed, deps);
}

async function generate(userId: string, r: GenerateRequest, deps: Deps): Promise<Response> {
  const budget = budgetFor(r);

  let res: Reservation;
  try { res = await deps.quota.reserve(userId, r.task, budget.reserve); }
  catch (e) { deps.log("reserve_failed", { error: String(e) }); return json(503, { error: "quota_unavailable", message: "Could not check your AI allowance. Nothing was used." }); }

  if (!res.allowed || !res.request_id) {
    const usage = usageBody(res.usage);
    if (res.reason === "rate_limited") {
      return json(429, { error: "rate_limited", message: "Too many AI requests. Wait a moment.", retryAfter: res.retry_after, usage }, { "retry-after": String(res.retry_after) });
    }
    if (res.reason === "too_large") return json(413, { error: "too_large", message: "That request is too large for your plan. Use shorter text.", usage });
    return json(402, { error: "quota_exceeded", message: "You have used this month's AI tokens.", usage });
  }
  const requestId = res.request_id;

  let out;
  try {
    out = await deps.provider.complete({ system: SYSTEM_PROMPT, user: buildUserPrompt(r), maxTokens: budget.maxOutput });
  } catch (e) {
    const pe = e instanceof ProviderError ? e : new ProviderError(String(e), 502);
    let usage: Usage | null = null;
    try { usage = await deps.quota.release(requestId, pe.timeout ? "provider_timeout" : `provider_${pe.status}`, pe.chargedTokens); }
    catch (re) { deps.log("release_failed", { requestId, error: String(re) }); }   // the reservation expires by itself after 5 minutes
    deps.log("provider_failed", { requestId, status: pe.status });
    return json(pe.timeout ? 504 : 502, {
      error: pe.timeout ? "provider_timeout" : "provider_error",
      message: "The AI service failed. Your tokens were not used.", usage: usage ? usageBody(usage) : undefined,
    });
  }

  const actual = Math.max(1, Math.trunc(out.inputTokens) + Math.trunc(out.outputTokens));
  let usage: Usage | null = null;
  for (let attempt = 0; attempt < 2 && !usage; attempt++) {
    try { usage = await deps.quota.settle(requestId, actual); } catch (e) { deps.log("settle_failed", { requestId, attempt, error: String(e) }); }
  }
  const result = parseModelOutput(out.text, r);
  if (result.refused) return json(422, { error: "refused", message: "That request can't be written. Try different wording.", usage: usage ? usageBody(usage) : undefined });
  return json(200, { result, tokens: { charged: actual, input: out.inputTokens, output: out.outputTokens }, usage: usage ? usageBody(usage) : undefined, requestId });
}
