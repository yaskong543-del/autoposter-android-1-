// Supabase Edge Function (Deno): the ONLY place that holds the AI provider key.
// Secrets (set with `supabase secrets set`, never in the app or the repo): ANTHROPIC_API_KEY, optional AI_MODEL.
// SUPABASE_URL, SUPABASE_ANON_KEY and SUPABASE_SERVICE_ROLE_KEY are injected by Supabase.
import { createClient } from "npm:@supabase/supabase-js@2.50.0";
import { handleRequest, ProviderError } from "./handler.ts";
import type { Deps, Reservation, Usage } from "./handler.ts";

const url = Deno.env.get("SUPABASE_URL")!;
const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const providerKey = Deno.env.get("ANTHROPIC_API_KEY") ?? "";
const model = Deno.env.get("AI_MODEL") ?? "claude-haiku-5-5";
const TIMEOUT_MS = 25_000;

const admin = createClient(url, service, { auth: { persistSession: false, autoRefreshToken: false } });

async function rpc<T>(fn: string, args: Record<string, unknown>): Promise<T> {
  const { data, error } = await admin.rpc(fn, args);
  if (error) throw new Error(`${fn}: ${error.message}`);
  return data as T;
}

const deps: Deps = {
  async authenticate(header) {
    const token = header?.replace(/^Bearer\s+/i, "");
    if (!token) return null;
    const { data, error } = await createClient(url, anon, { auth: { persistSession: false } }).auth.getUser(token);
    return error || !data.user ? null : { userId: data.user.id };
  },
  quota: {
    status: (owner) => rpc<Usage>("ai_usage_status", { p_owner: owner }),
    reserve: (owner, task, estimate): Promise<Reservation> =>
      rpc<Usage & { allowed: boolean; reason: string; request_id: string | null; retry_after: number }>(
        "ai_reserve", { p_owner: owner, p_task: task, p_estimate: estimate },
      ).then((r) => ({ allowed: r.allowed, reason: r.reason, request_id: r.request_id, retry_after: r.retry_after, usage: r })),
    settle: (id, actual) => rpc<Usage>("ai_settle", { p_request: id, p_actual: actual }),
    release: (id, reason, charge) => rpc<Usage>("ai_release", { p_request: id, p_reason: reason, p_charge: charge }),
  },
  provider: {
    configured: () => providerKey.length > 0,
    async complete({ system, user, maxTokens }) {
      const ctl = new AbortController();
      const timer = setTimeout(() => ctl.abort(), TIMEOUT_MS);
      try {
        const r = await fetch("https://api.anthropic.com/v1/messages", {
          method: "POST", signal: ctl.signal,
          headers: { "content-type": "application/json", "x-api-key": providerKey, "anthropic-version": "2023-06-01" },
          body: JSON.stringify({ model, max_tokens: maxTokens, system, messages: [{ role: "user", content: user }] }),
        });
        if (!r.ok) {
          await r.body?.cancel();
          throw new ProviderError(`provider http ${r.status}`, r.status === 429 ? 503 : 502);
        }
        const j = await r.json();
        const text = (j.content ?? []).filter((b: { type: string }) => b.type === "text").map((b: { text: string }) => b.text).join("");
        return { text, inputTokens: j.usage?.input_tokens ?? 0, outputTokens: j.usage?.output_tokens ?? 0 };
      } catch (e) {
        if (e instanceof ProviderError) throw e;
        throw new ProviderError(String(e), 504, (e as Error).name === "AbortError");
      } finally { clearTimeout(timer); }
    },
  },
  // Never log prompts, captions or tokens: ids and error classes only.
  log: (event, data) => console.log(JSON.stringify({ event, ...data })),
};

Deno.serve((req) => handleRequest(req, deps));
