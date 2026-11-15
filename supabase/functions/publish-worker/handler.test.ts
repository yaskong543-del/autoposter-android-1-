import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeJobStore, makeTokenProvider, openMedia, runMaintenance, tick, type Rpc } from './handler.ts';
import { parseKeyRing, b64uEncode, encryptToken, aadFor } from '../_shared/crypto.ts';
import { type OAuthDeps, type AccountRow } from '../oauth/handler.ts';

const ring = parseKeyRing('k1:' + b64uEncode(crypto.getRandomValues(new Uint8Array(32))));
const oauthDeps = (f: typeof fetch = (async () => new Response('{}', { status: 500 })) as typeof fetch, now = () => 1_000_000): OAuthDeps => ({
  db: { insertState: async () => {}, takeState: async () => null, findAccount: async () => null, getAccount: async () => null, upsertAccount: async () => {},
    saveTokens: async () => {}, setStatus: async () => {}, clearTokens: async () => {} } as any,
  ring, fetch: f, now, uuid: () => 'x', credentials: () => ({ clientId: 'c', clientSecret: 's' }), redirectUri: 'https://r', resultUrl: () => 'x',
});

test('job store maps RPC calls and rows exactly (patch shape, seconds, ISO times)', async () => {
  const log: Array<{ fn: string; args: any }> = [];
  const rpc: Rpc = async (fn, args) => {
    log.push({ fn, args });
    if (fn === 'claim_publish_jobs') return { data: [{ id: 'j1', owner: 'u', account_id: 'a', platform: 'X', post_id: 'p', status: 'leased', attempts: 0, max_attempts: 5, payload: { caption: 'c' }, provider_state: null, media_path: 'u/v.mp4', cancel_requested: false, lease_owner: 'w', account_external_id: 'ext', account_meta: null }], error: null };
    if (fn === 'apply_job_transition') return { data: true, error: null };
    if (fn === 'recover_stale_jobs' || fn === 'promote_due_jobs') return { data: 3, error: null };
    return { data: null, error: null };
  };
  const s = makeJobStore(rpc);
  const rows = await s.claim('w', 5, 150_000, 0);
  assert.equal(rows[0].provider_state && Object.keys(rows[0].provider_state).length, 0); assert.equal(rows[0].account_external_id, 'ext');
  assert.equal(log[0].args.p_lease_seconds, 150);
  assert.equal(await s.recoverStale(0), 3); assert.equal(await s.promote(0), 3);
  assert.equal(await s.transition({ id: 'j1', worker: 'w', to: 'retryable_failure', error: { code: 'E', message: 'm', cls: 'transient' }, nextAttemptAtMs: Date.UTC(2026, 10, 12), incAttempts: true, detail: 'retry' }), true);
  const t = log.at(-1)!.args; assert.equal(t.p_to, 'retryable_failure'); assert.equal(t.p_patch.nextAttemptAt, '2026-11-12T00:00:00.000Z'); assert.equal(t.p_patch.incAttempts, true);
  const bad: Rpc = async () => ({ data: null, error: { message: 'boom' } });
  await assert.rejects(() => makeJobStore(bad).promote(0), /promote_due_jobs: boom/);
});

test('token provider: decrypts, refuses revoked/foreign accounts, force-refresh path', async () => {
  const acc: AccountRow = { id: 'a1', owner: 'u1', platform: 'LINKEDIN', external_id: 'e', status: 'CONNECTED', access_token_enc: await encryptToken('TOK', ring, aadFor('u1', 'a1', 'access')), refresh_token_enc: null, token_expires_at: null };
  const tp = makeTokenProvider(async (id) => (id === 'a1' ? acc : null), oauthDeps());
  const job: any = { account_id: 'a1', owner: 'u1' };
  assert.equal(await tp.get(job), 'TOK');
  await assert.rejects(() => tp.get({ ...job, owner: 'u2' }));
  await assert.rejects(() => tp.get({ ...job, account_id: 'nope' }));
  acc.status = 'REVOKED'; await assert.rejects(() => tp.get(job));
});

test('media source: HEAD size, Range reads, signed URL passthrough, missing file errors', async () => {
  const f = (async (u: string, init?: RequestInit) => {
    if (init?.method === 'HEAD') return new Response(null, { status: 200, headers: { 'content-length': '10', 'content-type': 'video/mp4' } });
    const r = new Headers(init?.headers).get('range')!; const [a, b] = r.replace('bytes=', '').split('-').map(Number);
    return new Response(new Uint8Array(b - a + 1), { status: 206 });
  }) as typeof fetch;
  const m = await openMedia('u/clip.mp4', async (p, ttl) => `https://s/${p}?ttl=${ttl}`, f);
  assert.equal(m.size, 10); assert.equal(m.name, 'clip.mp4'); assert.equal((await m.readRange(2, 6)).length, 4);
  assert.equal(await m.signedUrl(3600), 'https://s/u/clip.mp4?ttl=3600');
  await assert.rejects(() => openMedia('u/x', async () => 'https://s/x', (async () => new Response(null, { status: 404 })) as typeof fetch));
});

test('maintenance: expires plans, warns about expiring tokens once per day, reminds about upcoming posts', async () => {
  const notes: any[] = []; let expired = 0; const statuses: Record<string, string> = {};
  const rpc: Rpc = async (fn, a) => { if (fn === 'expire_plans') return { data: 2, error: null }; if (fn === 'create_notification') { notes.push(a); return { data: true, error: null }; } return { data: null, error: null }; };
  const now = Date.UTC(2026, 10, 12, 12);
  const mk = (id: string, platform: any, exp: number | null): AccountRow => ({ id, owner: 'u1', platform, external_id: id, status: 'CONNECTED', access_token_enc: null, refresh_token_enc: null, token_expires_at: exp ? new Date(exp).toISOString() : null });
  const r = await runMaintenance({
    rpc, oauth: oauthDeps(undefined, () => now), now: () => now,
    listAccountsForSweep: async () => [mk('fb', 'FACEBOOK', now + 2 * 86_400_000), mk('li', 'LINKEDIN', now - 1000)],
    listUpcomingJobs: async () => [{ id: 'j1', owner: 'u1', platform: 'X', run_at: new Date(now + 600_000).toISOString() }],
    setAccountStatus: async (id, s) => { statuses[id] = s; },
  });
  assert.equal(r.plansExpired, 2); assert.equal(r.warned, 2); assert.equal(r.reminders, 1);
  assert.deepEqual(statuses, { fb: 'EXPIRING', li: 'EXPIRED' });
  assert.ok(notes.some((n) => n.p_dedupe === 'token_expiring:fb:2026-11-12') && notes.some((n) => n.p_dedupe === 'reminder:j1'));
  void expired;
});

test('tick: media missing fails the job immediately instead of stranding the lease', async () => {
  const calls: Array<{ fn: string; args: any }> = [];
  const rpc: Rpc = async (fn, args) => {
    calls.push({ fn, args });
    if (fn === 'claim_publish_jobs') return { data: [{ id: 'j1', owner: 'u', account_id: 'a', platform: 'X', post_id: 'p', status: 'leased', attempts: 0, max_attempts: 5, payload: {}, provider_state: {}, media_path: 'u/gone.mp4', cancel_requested: false, lease_owner: 'w', account_external_id: 'e', account_meta: {} }], error: null };
    if (fn === 'claim_poll_jobs') return { data: [], error: null };
    return { data: fn === 'apply_job_transition' ? true : 0, error: null };
  };
  const out = await tick({ rpc, oauth: oauthDeps(), fetch: (async () => new Response(null, { status: 404 })) as typeof fetch, loadAccount: async () => null, signUrl: async () => 'https://s/x', now: () => 1_000_000 }, { workerId: 'w' });
  assert.deepEqual(out.outcomes, []);
  const tr = calls.find((c) => c.fn === 'apply_job_transition')!;
  assert.equal(tr.args.p_to, 'permanent_failure'); assert.equal(tr.args.p_patch.error.code, 'MEDIA_MISSING');
  assert.ok(calls.some((c) => c.fn === 'create_notification' && c.args.p_kind === 'publish_failure'));
});
