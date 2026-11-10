import { test } from "node:test";
import assert from "node:assert/strict";
import { handleRequest, ProviderError } from "./handler.ts";
import type { Deps, Reservation, Usage } from "./handler.ts";

// An in-memory model of the SQL functions in 003_ai_creator_studio.sql (same rules, same order of checks).
// It tests the gateway's orchestration; it does NOT prove the SQL itself (see supabase/tests/003_ai_quota_smoke.sql).
class FakeQuota {
  used = 0; reserved = 0; open = new Map<string, number>(); attempts: number[] = []; seq = 0;
  limit: number; rpm: number; maxReq: number; now: () => number;
  constructor(limit = 5000, rpm = 6, maxReq = 2500, now = () => Date.UTC(2026, 10, 8, 12)) {
    this.limit = limit; this.rpm = rpm; this.maxReq = maxReq; this.now = now;
  }
  usage(): Usage {
    const d = new Date(this.now()); const start = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1));
    const next = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1));
    return { plan: this.limit > 5000 ? "pro" : "free", limit: this.limit, used: this.used, reserved: this.reserved,
      remaining: Math.max(0, this.limit - this.used - this.reserved), period_start: start.toISOString().slice(0, 10),
      resets_at: next.toISOString(), requests_per_minute: this.rpm, max_request_tokens: this.maxReq };
  }
  async reserve(_u: string, _t: string, est: number): Promise<Reservation> {
    const t = this.now(); this.attempts = this.attempts.filter((x) => x > t - 60_000);
    if (this.attempts.length >= this.rpm) return { allowed: false, reason: "rate_limited", request_id: null, retry_after: Math.ceil((this.attempts[0] + 60_000 - t) / 1000), usage: this.usage() };
    if (est > this.maxReq) return { allowed: false, reason: "too_large", request_id: null, retry_after: 0, usage: this.usage() };
    if (this.used + this.reserved + est > this.limit) return { allowed: false, reason: "quota_exceeded", request_id: null, retry_after: 0, usage: this.usage() };
    const id = `req-${++this.seq}`; this.attempts.push(t); this.open.set(id, est); this.reserved += est;
    return { allowed: true, reason: "ok", request_id: id, retry_after: 0, usage: this.usage() };
  }
  async settle(id: string, actual: number) {
    const est = this.open.get(id); if (est !== undefined) { this.reserved -= est; this.used += actual; this.open.delete(id); }
    return this.usage();
  }
  async release(id: string, _r: string, charge: number) {
    const est = this.open.get(id); if (est !== undefined) { this.reserved -= est; this.used += charge; this.open.delete(id); }
    return this.usage();
  }
  async status() { return this.usage(); }
}

function setup(over: Partial<{ quota: FakeQuota; provider: Deps["provider"]; auth: boolean }> = {}) {
  const quota = over.quota ?? new FakeQuota();
  const calls: { system: string; user: string; maxTokens: number }[] = [];
  const logs: string[] = [];
  const deps: Deps = {
    authenticate: async (h) => (over.auth === false || !h ? null : { userId: "u1" }),
    quota,
    provider: over.provider ?? {
      configured: () => true,
      complete: async (a) => { calls.push(a); return { text: '{"items":[{"text":"Sun, sand, chai ☀️"}],"hashtags":["goa"]}', inputTokens: 210, outputTokens: 60 }; },
    },
    log: (e, d) => logs.push(e + JSON.stringify(d)),
  };
  return { quota, calls, logs, deps };
}

const post = (body: unknown, auth = "Bearer jwt") => new Request("https://x/ai", { method: "POST", headers: { authorization: auth, "content-type": "application/json" }, body: JSON.stringify(body) });
const gen = (o: Record<string, unknown> = {}) => ({ action: "generate", task: "caption", tone: "funny", language: "en", input: "trip to Goa", ...o });

test("success: reserves, calls the model, and deducts the ACTUAL tokens (not the reservation)", async () => {
  const { quota, calls, deps } = setup();
  const res = await handleRequest(post(gen()), deps);
  assert.equal(res.status, 200);
  const j = await res.json();
  assert.deepEqual(j.result.items, ["Sun, sand, chai ☀️"]);
  assert.equal(j.tokens.charged, 270);
  assert.equal(quota.used, 270);
  assert.equal(quota.reserved, 0, "reservation fully released");
  assert.equal(j.usage.remaining, 5000 - 270);
  assert.equal(calls.length, 1);
});

test("requires a signed-in user; the model is never called without one", async () => {
  const { calls, deps } = setup({ auth: false });
  const res = await handleRequest(post(gen()), deps);
  assert.equal(res.status, 401);
  assert.equal(calls.length, 0);
  const res2 = await handleRequest(new Request("https://x", { method: "POST", body: "{}" }), deps);
  assert.equal(res2.status, 401);
});

test("invalid requests are rejected before any tokens are reserved", async () => {
  const { quota, calls, deps } = setup();
  const res = await handleRequest(post(gen({ task: "nope" })), deps);
  assert.equal(res.status, 400);
  assert.equal(quota.attempts.length, 0);
  assert.equal(calls.length, 0);
});

test("quota exhausted -> 402 and the model is NOT called", async () => {
  const q = new FakeQuota(); q.used = 4900;
  const { calls, deps } = setup({ quota: q });
  const res = await handleRequest(post(gen()), deps);
  assert.equal(res.status, 402);
  assert.equal((await res.json()).error, "quota_exceeded");
  assert.equal(calls.length, 0);
  assert.equal(q.used, 4900);
});

test("concurrent reservations cannot overshoot the quota", async () => {
  const q = new FakeQuota(5000, 100, 2500);
  const { deps } = setup({ quota: q });
  const results = await Promise.all(Array.from({ length: 20 }, () => handleRequest(post(gen()), deps)));
  const ok = results.filter((r) => r.status === 200).length;
  assert.ok(q.used <= 5000, `used ${q.used}`);
  assert.ok(ok >= 5 && ok < 20, `ok=${ok}`);
  assert.ok(results.filter((r) => r.status !== 200).every((r) => r.status === 402));
});

test("rate limit -> 429 with Retry-After; resets as the window passes", async () => {
  let now = Date.UTC(2026, 10, 8, 12);
  const q = new FakeQuota(500000, 3, 6000, () => now);
  const { deps } = setup({ quota: q });
  for (let i = 0; i < 3; i++) assert.equal((await handleRequest(post(gen()), deps)).status, 200);
  const res = await handleRequest(post(gen()), deps);
  assert.equal(res.status, 429);
  assert.equal(res.headers.get("retry-after"), "60");
  assert.equal((await res.json()).retryAfter, 60);
  now += 61_000;
  assert.equal((await handleRequest(post(gen()), deps)).status, 200);
});

test("failed provider call -> 502, reservation released, nothing charged", async () => {
  const q = new FakeQuota();
  const { deps } = setup({ quota: q, provider: { configured: () => true, complete: async () => { throw new ProviderError("boom", 502); } } });
  const res = await handleRequest(post(gen()), deps);
  assert.equal(res.status, 502);
  assert.equal(q.used, 0);
  assert.equal(q.reserved, 0);
  assert.equal(q.open.size, 0);
});

test("timeout -> 504, nothing charged", async () => {
  const q = new FakeQuota();
  const { deps } = setup({ quota: q, provider: { configured: () => true, complete: async () => { throw new ProviderError("slow", 504, true); } } });
  const res = await handleRequest(post(gen()), deps);
  assert.equal(res.status, 504);
  assert.equal(q.used, 0); assert.equal(q.reserved, 0);
});

test("an unexpected exception from the provider is still released", async () => {
  const q = new FakeQuota();
  const { deps } = setup({ quota: q, provider: { configured: () => true, complete: async () => { throw new TypeError("network"); } } });
  assert.equal((await handleRequest(post(gen()), deps)).status, 502);
  assert.equal(q.reserved, 0);
});

test("a settle failure is retried once, and the caption is still returned", async () => {
  const q = new FakeQuota(); let n = 0; const real = q.settle.bind(q);
  q.settle = async (id, a) => { if (n++ === 0) throw new Error("db blip"); return real(id, a); };
  const { deps } = setup({ quota: q });
  const res = await handleRequest(post(gen()), deps);
  assert.equal(res.status, 200);
  assert.equal(q.used, 270);
});

test("quota backend down -> 503 and the model is not called", async () => {
  const q = new FakeQuota(); q.reserve = async () => { throw new Error("db down"); };
  const { calls, deps } = setup({ quota: q });
  assert.equal((await handleRequest(post(gen()), deps)).status, 503);
  assert.equal(calls.length, 0);
});

test("model refusal -> 422 but the tokens it used are still counted", async () => {
  const q = new FakeQuota();
  const { deps } = setup({ quota: q, provider: { configured: () => true, complete: async () => ({ text: '{"items":[],"refused":true}', inputTokens: 100, outputTokens: 10 }) } });
  assert.equal((await handleRequest(post(gen()), deps)).status, 422);
  assert.equal(q.used, 110);
});

test("not configured -> 503 and nothing reserved", async () => {
  const q = new FakeQuota();
  const { deps } = setup({ quota: q, provider: { configured: () => false, complete: async () => { throw new Error("never"); } } });
  assert.equal((await handleRequest(post(gen()), deps)).status, 503);
  assert.equal(q.attempts.length, 0);
});

test("monthly reset: a new UTC month reports a fresh period", async () => {
  let now = Date.UTC(2026, 10, 30, 23, 59);
  const q = new FakeQuota(5000, 6, 2500, () => now);
  const u1 = q.usage(); assert.equal(u1.period_start, "2026-11-01"); assert.equal(u1.resets_at, "2026-12-01T00:00:00.000Z");
  now = Date.UTC(2026, 11, 1, 0, 0, 1);
  assert.equal(q.usage().period_start, "2026-12-01");
});

test("usage action returns remaining tokens and never calls the model", async () => {
  const q = new FakeQuota(); q.used = 1234;
  const { calls, deps } = setup({ quota: q });
  const res = await handleRequest(post({ action: "usage" }), deps);
  const j = await res.json();
  assert.equal(j.usage.remaining, 3766);
  assert.equal(j.usage.limit, 5000);
  assert.equal(calls.length, 0);
});

test("responses and logs never contain the provider key or the user's text", async () => {
  process.env.FAKE_KEY = "sk-ant-secret-123";
  const { logs, deps } = setup({ provider: { configured: () => true, complete: async () => { throw new ProviderError("sk-ant-secret-123 leaked?", 502); } } });
  const res = await handleRequest(post(gen({ input: "my private launch plan" })), deps);
  const text = await res.text();
  assert.ok(!text.includes("sk-ant"));
  assert.ok(!logs.join("\n").includes("my private launch plan"));
  assert.ok(!logs.join("\n").includes("sk-ant-secret"));
});
