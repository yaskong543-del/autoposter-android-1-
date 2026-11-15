import { test } from 'node:test';
import assert from 'node:assert/strict';
import { type JobRow, type JobStore, type TransitionReq, type WorkerDeps, type NotificationReq, processJob, pollJob, runTick } from './scheduler_core.ts';
import { canTransition, type JobStatus } from './jobs.ts';
import { ProviderError, type Platform } from './types.ts';
import { type ProviderAdapter, type StepResult, type StatusResult, type ProviderState } from './providers/common.ts';
import { ADAPTERS } from './providers/index.ts';

interface Rec extends JobRow { run_at: number; next_attempt: number; lease_expires: number; log: string[]; remote_id?: string; error?: any }
class MemStore implements JobStore {
  jobs = new Map<string, Rec>(); blocked = new Map<string, number>(); reconnect: string[] = []; notes: NotificationReq[] = []; noteKeys = new Set<string>();
  suspended = new Set<string>(); now = 0;
  add(o: Partial<Rec> & { id: string }): Rec {
    const j: Rec = { owner: 'u1', account_id: 'acc1', platform: 'YOUTUBE', post_id: 'p', status: 'scheduled', attempts: 0, max_attempts: 3, payload: { caption: 'c' },
      provider_state: {}, media_path: 'u1/x.mp4', cancel_requested: false, lease_owner: null, account_external_id: 'ext', account_meta: {}, run_at: 0, next_attempt: 0, lease_expires: 0, log: [], ...o } as Rec;
    this.jobs.set(j.id, j); return j;
  }
  async recoverStale(now: number) {
    let n = 0;
    for (const j of this.jobs.values()) if ((j.status === 'leased' || j.status === 'uploading') && j.lease_expires <= now) {
      j.attempts += 1; j.lease_owner = null; j.status = j.attempts >= j.max_attempts ? 'permanent_failure' : 'due'; j.log.push('recovered'); n++;
    }
    return n;
  }
  async promote(now: number) {
    let n = 0;
    for (const j of this.jobs.values()) {
      if (j.status === 'scheduled' && j.run_at <= now) { j.status = 'due'; n++; }
      else if (j.status === 'retryable_failure' && j.next_attempt <= now) { j.status = 'due'; n++; }
    }
    return n;
  }
  async claim(w: string, limit: number, leaseMs: number, now: number) {
    const out: JobRow[] = [];
    for (const j of [...this.jobs.values()].sort((a, b) => a.run_at - b.run_at)) {
      if (out.length >= limit) break;
      if (j.status !== 'due' || j.next_attempt > now) continue;
      if ((this.blocked.get(j.account_id) ?? 0) > now) continue;
      j.status = 'leased'; j.lease_owner = w; j.lease_expires = now + leaseMs; out.push({ ...j });
    }
    return out;
  }
  async claimPolls(w: string, limit: number, leaseMs: number, now: number) {
    const out: JobRow[] = [];
    for (const j of this.jobs.values()) if (out.length < limit && j.status === 'submitted' && j.next_attempt <= now && (!j.lease_owner || j.lease_expires <= now)) {
      j.lease_owner = w; j.lease_expires = now + leaseMs; out.push({ ...j, submitted_at_ms: j.submitted_at_ms ?? 0 });
    }
    return out;
  }
  async transition(t: TransitionReq) {
    const j = this.jobs.get(t.id); if (!j) return false;
    if (j.lease_owner !== t.worker || j.lease_expires <= this.now) return false;
    if (!canTransition(j.status, t.to)) throw new Error(`illegal ${j.status} -> ${t.to}`);
    j.status = t.to; j.log.push(`${t.to}:${t.detail ?? ''}`);
    if (t.providerState) j.provider_state = t.providerState;
    if (t.error !== undefined) j.error = t.error;
    if (t.nextAttemptAtMs !== undefined) j.next_attempt = t.nextAttemptAtMs;
    if (t.incAttempts) j.attempts += 1;
    if (t.remoteId) j.remote_id = t.remoteId;
    if (t.to === 'submitted' && !j.submitted_at_ms) j.submitted_at_ms = this.now;
    if (!['leased', 'uploading', 'submitted'].includes(t.to) || t.to === 'submitted') { if (t.to !== 'submitted') j.lease_owner = null; else j.lease_owner = null; }
    return true;
  }
  async cancelRequested(id: string) { return this.jobs.get(id)?.cancel_requested ?? false; }
  async accountAllowed(j: JobRow) { return !this.suspended.has(j.account_id); }
  async blockAccount(a: string, until: number) { this.blocked.set(a, until); }
  async needsReconnect(a: string) { this.reconnect.push(a); }
  async notify(n: NotificationReq) { if (this.noteKeys.has(n.owner + n.dedupeKey)) return; this.noteKeys.add(n.owner + n.dedupeKey); this.notes.push(n); }
}

function mk(store: MemStore, adapters: Partial<Record<Platform, ProviderAdapter>>, over: Partial<WorkerDeps> = {}, clock = { t: 1_000_000 }): WorkerDeps & { clock: { t: number } } {
  store.now = clock.t;
  return {
    store, workerId: 'w1', fetch: (async () => new Response('')) as typeof fetch, rng: () => 0, budgetBytes: 1000, leaseMs: 60_000, maxStepsPerRun: 5,
    config: { linkedinVersion: '202605' }, clock,
    now: () => { store.now = clock.t; return clock.t; },
    media: () => ({ size: 1, contentType: 'video/mp4', name: 'x', signedUrl: async () => 'u', readRange: async () => new Uint8Array(1) }),
    adapter: (p: Platform) => adapters[p] ?? ADAPTERS[p],
    tokens: { get: async () => 'tok', refresh: async () => null }, ...over,
  };
}
const adapter = (advance: (s: ProviderState, n: number) => StepResult | Promise<StepResult>, status?: () => StatusResult): ProviderAdapter => {
  let n = 0;
  return { platform: 'YOUTUBE', capabilities: { video: true, image: false, maxCaption: 1, asyncProcessing: true, approvalNote: 'x' },
    advance: async (_c, s) => advance(s, n++), status: async () => status ? status() : ({ kind: 'processing', state: {} }) };
};
const fail = (cls: any, code = 'E', extra = {}) => new ProviderError({ platform: 'YOUTUBE', class: cls, code, message: 'm', ...extra });

test('happy path: scheduled -> due -> leased -> uploading -> published, one success notification', async () => {
  const s = new MemStore(); const j = s.add({ id: 'j1', status: 'scheduled' });
  const d = mk(s, { YOUTUBE: adapter(() => ({ kind: 'published', state: { phase: 'done' }, remoteId: 'R' })) });
  const r = await runTick(d, { limit: 5, deadlineMs: Infinity });
  assert.deepEqual(r.outcomes, ['published']); assert.equal(j.status, 'published'); assert.equal(j.remote_id, 'R');
  assert.equal(s.notes.length, 1); assert.equal(s.notes[0].kind, 'publish_success');
  assert.equal(j.lease_owner, null);
});

test('multi-step upload yields and resumes from persisted provider state without burning attempts', async () => {
  const s = new MemStore(); const j = s.add({ id: 'j1', status: 'due' }); const clock = { t: 1_000_000 };
  const d = mk(s, { YOUTUBE: adapter((st) => {
    const off = Number(st.offset ?? 0);
    return off >= 3 ? { kind: 'published', state: { offset: off }, remoteId: 'R' } : { kind: 'progress', state: { offset: off + 1 }, progress: off / 3, waitMs: 0 };
  }) }, { maxStepsPerRun: 2 }, clock);
  assert.deepEqual((await runTick(d, { limit: 1, deadlineMs: Infinity })).outcomes, ['yielded']);
  assert.equal(j.provider_state.offset, 2); assert.equal(j.status, 'due');
  clock.t += 1000;
  assert.deepEqual((await runTick(d, { limit: 1, deadlineMs: Infinity })).outcomes, ['published']);
  assert.equal(j.attempts, 0);
});

test('concurrent workers never claim the same job', async () => {
  const s = new MemStore(); for (let i = 0; i < 10; i++) s.add({ id: `j${i}`, status: 'due' });
  const [a, b] = await Promise.all([s.claim('A', 6, 60_000, 0), s.claim('B', 6, 60_000, 0)]);
  const ids = [...a, ...b].map((j) => j.id);
  assert.equal(new Set(ids).size, ids.length); assert.equal(ids.length, 10);
});

test('transient failure: retry with backoff, then success; exhausted attempts become permanent', async () => {
  const s = new MemStore(); const j = s.add({ id: 'j1', status: 'due', max_attempts: 2 }); const clock = { t: 1_000_000 };
  let calls = 0;
  const d = mk(s, { YOUTUBE: adapter(() => { calls++; if (calls < 3) throw fail('transient', 'NETWORK'); return { kind: 'published', state: {}, remoteId: 'R' }; }) }, {}, clock);
  assert.deepEqual((await runTick(d, { limit: 1, deadlineMs: Infinity })).outcomes, ['retry_scheduled']);
  assert.equal(j.status, 'retryable_failure'); assert.equal(j.attempts, 1); assert.ok(j.next_attempt >= clock.t + 15_000);
  assert.deepEqual((await runTick(d, { limit: 1, deadlineMs: Infinity })).outcomes, []);        // not yet due
  clock.t = j.next_attempt + 1;
  assert.deepEqual((await runTick(d, { limit: 1, deadlineMs: Infinity })).outcomes, ['failed']); // attempt 2 of 2 fails -> permanent
  assert.equal(j.status, 'permanent_failure'); assert.equal(j.error.code, 'NETWORK');
  assert.equal(s.notes.filter((n) => n.kind === 'publish_failure').length, 1);
});

test('quota exhaustion blocks the whole account until reset; other accounts still run', async () => {
  const s = new MemStore(); const clock = { t: 1_000_000 };
  const a = s.add({ id: 'a', account_id: 'acc1', status: 'due' }); const b = s.add({ id: 'b', account_id: 'acc2', status: 'due', run_at: 5 });
  const d = mk(s, { YOUTUBE: adapter((_s, n) => { if (n === 0) throw fail('quota', 'YT_QUOTA_EXCEEDED', { retryAfterMs: 3_600_000 }); return { kind: 'published', state: {}, remoteId: 'R' }; }) }, { maxStepsPerRun: 1 }, clock);
  await runTick(d, { limit: 1, deadlineMs: Infinity });
  assert.equal(a.status, 'retryable_failure'); assert.ok(s.blocked.get('acc1')! >= clock.t + 3_600_000);
  const r = await runTick(d, { limit: 5, deadlineMs: Infinity });
  assert.deepEqual(r.outcomes, ['published']); assert.equal(b.status, 'published');
});

test('auth failure: one refresh attempt; success continues, failure marks the account for reconnect (deduped notification)', async () => {
  const s = new MemStore(); s.add({ id: 'j1', status: 'due' });
  let first = true; let refreshed = 0;
  const d = mk(s, { YOUTUBE: adapter(() => { if (first) { first = false; throw fail('auth', 'AUTH_INVALID'); } return { kind: 'published', state: {}, remoteId: 'R' }; }) },
    { tokens: { get: async () => 'old', refresh: async () => { refreshed++; return 'new'; } } });
  assert.deepEqual((await runTick(d, { limit: 1, deadlineMs: Infinity })).outcomes, ['published']); assert.equal(refreshed, 1);
  const s2 = new MemStore(); s2.add({ id: 'k1', status: 'due' }); s2.add({ id: 'k2', status: 'due', run_at: 1, post_id: 'p2' });
  const d2 = mk(s2, { YOUTUBE: adapter(() => { throw fail('auth', 'AUTH_INVALID'); }) });
  const r = await runTick(d2, { limit: 5, deadlineMs: Infinity });
  assert.deepEqual(r.outcomes, ['failed', 'failed']); assert.equal(s2.reconnect.length, 2);
  assert.equal(s2.notes.filter((n) => n.kind === 'account_error').length, 1);      // same account + code => one notification
});

test('approval-blocked failures never retry and say why', async () => {
  const s = new MemStore(); const j = s.add({ id: 'j1', status: 'due' });
  const d = mk(s, { YOUTUBE: adapter(() => { throw fail('approval_blocked', 'TT_UNAUDITED'); }) });
  assert.deepEqual((await runTick(d, { limit: 1, deadlineMs: Infinity })).outcomes, ['failed']);
  assert.equal(j.error.cls, 'approval_blocked'); assert.equal(j.attempts, 1);
});

test('cancellation: before start, at checkpoint, and too late after the platform has the content', async () => {
  const s = new MemStore(); const a = s.add({ id: 'a', status: 'due', cancel_requested: true });
  const d = mk(s, { YOUTUBE: adapter(() => ({ kind: 'published', state: {}, remoteId: 'R' })) });
  assert.deepEqual((await runTick(d, { limit: 1, deadlineMs: Infinity })).outcomes, ['cancelled']); assert.equal(a.status, 'cancelled');
  const s2 = new MemStore(); const b = s2.add({ id: 'b', status: 'due' });
  const d2 = mk(s2, { YOUTUBE: adapter((st, n) => { if (n === 0) { b.cancel_requested = true; return { kind: 'progress', state: { offset: 1 }, progress: 0.1, waitMs: 0 }; } return { kind: 'published', state: {}, remoteId: 'R' }; }) });
  assert.deepEqual((await runTick(d2, { limit: 1, deadlineMs: Infinity })).outcomes, ['cancelled']); assert.equal(b.status, 'cancelled');
  const s3 = new MemStore(); const c = s3.add({ id: 'c', status: 'due', cancel_requested: true, provider_state: { phase: 'publishing' } });
  const d3 = mk(s3, { YOUTUBE: adapter(() => ({ kind: 'published', state: { phase: 'done' }, remoteId: 'R' })) });
  assert.deepEqual((await runTick(d3, { limit: 1, deadlineMs: Infinity })).outcomes, ['published']);   // committed: cancel ignored
});

test('stale lease recovery: crashed worker job returns to due; lost lease cannot write; exhausted becomes permanent', async () => {
  const s = new MemStore(); const clock = { t: 1_000_000 };
  const j = s.add({ id: 'j1', status: 'due', max_attempts: 2 });
  const dA = mk(s, {}, {}, clock); const [claimed] = await s.claim('crashed', 1, 60_000, clock.t);
  assert.equal(claimed.id, 'j1');
  clock.t += 61_000;
  assert.equal(await s.recoverStale(clock.t), 1); assert.equal(j.status, 'due'); assert.equal(j.attempts, 1);
  const dB = mk(s, { YOUTUBE: adapter(() => ({ kind: 'published', state: {}, remoteId: 'R' })) }, { workerId: 'B' }, clock);
  const [again] = await s.claim('B', 1, 60_000, clock.t);
  // the original (crashed) worker wakes up late and tries to write: must be rejected
  assert.equal(await s.transition({ id: 'j1', worker: 'crashed', to: 'uploading' }), false);
  assert.equal(await processJob(dB, again), 'published');
  void dA;
  const j2 = s.add({ id: 'j2', status: 'due', max_attempts: 1 }); await s.claim('x', 1, 1, clock.t); clock.t += 10;
  await s.recoverStale(clock.t); assert.equal(j2.status, 'permanent_failure');
});

test('plan suspension: over-limit accounts do not publish and the job fails with an actionable message', async () => {
  const s = new MemStore(); const j = s.add({ id: 'j1', status: 'due' }); s.suspended.add('acc1');
  let called = false; const d = mk(s, { YOUTUBE: adapter(() => { called = true; return { kind: 'published', state: {}, remoteId: 'R' }; }) });
  assert.deepEqual((await runTick(d, { limit: 1, deadlineMs: Infinity })).outcomes, ['failed']);
  assert.equal(called, false); assert.equal(j.error.code, 'PLAN_SUSPENDED');
});

test('at-most-once: a crash after the publish checkpoint is reported as ambiguous, never re-published', async () => {
  const s = new MemStore(); const j = s.add({ id: 'j1', status: 'due', platform: 'INSTAGRAM', provider_state: { phase: 'publishing', containerId: 'C1' } });
  let publishCalls = 0;
  const f = (async (url: string) => { if (String(url).includes('media_publish')) publishCalls++; return new Response(JSON.stringify({ status_code: 'PUBLISHED' }), { status: 200 }); }) as typeof fetch;
  const d = mk(s, {}, { fetch: f });
  assert.deepEqual((await runTick(d, { limit: 1, deadlineMs: Infinity })).outcomes, ['failed']);
  assert.equal(publishCalls, 0); assert.equal(j.error.code, 'AMBIGUOUS_PUBLISH');
});

test('submitted jobs are polled until the platform confirms; unconfirmed jobs time out honestly', async () => {
  const s = new MemStore(); const clock = { t: 1_000_000 }; const j = s.add({ id: 'j1', status: 'due' });
  let st: StatusResult = { kind: 'processing', state: {}, waitMs: 1000 };
  const d = mk(s, { YOUTUBE: adapter(() => ({ kind: 'submitted', state: { videoId: 'v' } }), () => st) }, {}, clock);
  await runTick(d, { limit: 1, deadlineMs: Infinity }); assert.equal(j.status, 'submitted');
  assert.notEqual(j.status as JobStatus, 'published');                       // never "published" before confirmation
  clock.t += 31_000; await runTick(d, { limit: 1, deadlineMs: Infinity }); assert.equal(j.status, 'submitted');
  st = { kind: 'published', state: {}, remoteId: 'vid', note: 'visibility:private' };
  clock.t += 61_000; await runTick(d, { limit: 1, deadlineMs: Infinity }); assert.equal(j.status, 'published');
  assert.match(s.notes.at(-1)!.body, /private/);
  // timeout
  const s2 = new MemStore(); const k = s2.add({ id: 'k', status: 'submitted', submitted_at_ms: 0, lease_owner: 'w1', lease_expires: 9e12 });
  const d2 = mk(s2, { YOUTUBE: adapter(() => ({ kind: 'submitted', state: {} }), () => ({ kind: 'processing', state: {} })) }, {}, { t: 7 * 3_600_000 });
  assert.equal(await pollJob(d2, { ...k, submitted_at_ms: 0 }), 'failed'); assert.equal(k.error.code, 'STATUS_TIMEOUT');
});

test('tick deadline returns unstarted claims to the queue instead of dropping them', async () => {
  const s = new MemStore(); const j = s.add({ id: 'j1', status: 'due' });
  const d = mk(s, { YOUTUBE: adapter(() => ({ kind: 'published', state: {}, remoteId: 'R' })) });
  await runTick(d, { limit: 1, deadlineMs: 0 });
  assert.equal(j.status, 'due');
});
