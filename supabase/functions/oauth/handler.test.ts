import { test } from 'node:test';
import assert from 'node:assert/strict';
import { startOAuth, finishOAuth, disconnectAccount, refreshAccount, type OAuthDb, type OAuthDeps, type AccountRow } from './handler.ts';
import { parseKeyRing, b64uEncode, decryptToken, aadFor } from '../_shared/crypto.ts';
import { sha256Hex } from '../_shared/oauth.ts';

function setup(over: { creds?: boolean; limitAfter?: number } = {}) {
  const states = new Map<string, any>(); const accounts = new Map<string, any>(); const calls: string[] = [];
  let limit = over.limitAfter ?? Infinity;
  const db: OAuthDb = {
    insertState: async (r) => { states.set(r.state_hash, r); },
    takeState: async (h) => { const s = states.get(h); if (!s || s.used_at) return null; s.used_at = 'now'; return { ...s, used_at: null }; },
    findAccount: async (o, p, e) => [...accounts.values()].find((a) => a.owner === o && a.platform === p && a.external_id === e) ?? null,
    getAccount: async (o, id) => accounts.get(id)?.owner === o ? accounts.get(id) : null,
    upsertAccount: async (a) => { if (!accounts.has(a.id) && accounts.size >= limit) throw Object.assign(new Error('limit'), { code: 'PL001' }); accounts.set(a.id, { ...a, status: 'CONNECTED' }); },
    saveTokens: async (id, t) => { Object.assign(accounts.get(id), t); },
    setStatus: async (id, s) => { accounts.get(id).status = s; },
    clearTokens: async (id) => { Object.assign(accounts.get(id), { access_token_enc: null, refresh_token_enc: null }); },
  };
  let clock = 1_000_000; let n = 0;
  const f = (async (url: string, init?: RequestInit) => {
    calls.push(String(url));
    const u = String(url);
    if (u === 'https://oauth2.googleapis.com/token') return Response.json(String(init?.body).includes('refresh_token') && !String(init?.body).includes('authorization_code') ? { access_token: 'new-access', expires_in: 3600 } : { access_token: 'A1', refresh_token: 'R1', expires_in: 3600, scope: 'a b' });
    if (u.includes('youtube/v3/channels')) return Response.json({ items: [{ id: 'UC1', snippet: { title: 'My Channel' } }] });
    if (u.startsWith('https://oauth2.googleapis.com/revoke')) return new Response('', { status: 200 });
    return new Response('{}', { status: 500 });
  }) as typeof fetch;
  const deps: OAuthDeps = {
    db, ring: parseKeyRing('k1:' + b64uEncode(crypto.getRandomValues(new Uint8Array(32)))), fetch: f, now: () => clock, uuid: () => `acc-${++n}`,
    credentials: (p) => (over.creds === false ? null : { clientId: 'cid', clientSecret: 'csec' }), redirectUri: 'https://proj.functions.supabase.co/oauth/callback',
    resultUrl: (r) => `autoposter://oauth?platform=${r.platform}&status=${r.status}${r.detail ? `&detail=${r.detail}` : ''}`,
  };
  return { deps, states, accounts, calls, tick: (ms: number) => { clock += ms; } };
}
const stateFrom = (url: string) => new URL(url).searchParams.get('state')!;

test('start: PKCE challenge sent, verifier encrypted at rest, state stored hashed; unconfigured providers say so', async () => {
  const s = setup();
  const r = await startOAuth(s.deps, { owner: 'u1', platform: 'YOUTUBE' });
  assert.ok(r.ok); const u = new URL((r as any).url);
  assert.equal(u.searchParams.get('code_challenge_method'), 'S256'); assert.equal(u.searchParams.get('redirect_uri'), s.deps.redirectUri);
  const st = stateFrom((r as any).url);
  assert.equal(s.states.has(st), false);                                        // raw state is never stored
  const rec = s.states.get(await sha256Hex(st)); assert.ok(rec.code_verifier_enc.startsWith('v1.')); assert.equal(rec.owner, 'u1');
  assert.deepEqual(await startOAuth(setup({ creds: false }).deps, { owner: 'u', platform: 'YOUTUBE' }), { ok: false, code: 'not_configured' });
  assert.deepEqual(await startOAuth(s.deps, { owner: 'u', platform: 'MYSPACE' }), { ok: false, code: 'unsupported_platform' });
});

test('callback: connects the account with encrypted tokens; state is single-use; tampering is rejected', async () => {
  const s = setup();
  const url = (await startOAuth(s.deps, { owner: 'u1', platform: 'YOUTUBE' }) as any).url; const st = stateFrom(url);
  const res = await finishOAuth(s.deps, { platform: 'YOUTUBE', state: st, code: 'CODE', error: null });
  assert.equal(res.connected, 1); assert.match(res.redirect, /status=ok/);
  const acc = [...s.accounts.values()][0];
  assert.equal(acc.external_id, 'UC1'); assert.ok(!Object.values(acc).includes('A1') && !Object.hasOwn(acc, 'access_token') && !Object.hasOwn(acc, 'refresh_token') && acc.access_token_enc.startsWith('v1.'));
  assert.equal(await decryptToken(acc.access_token_enc, s.deps.ring, aadFor('u1', acc.id, 'access')), 'A1');
  assert.equal(await decryptToken(acc.refresh_token_enc, s.deps.ring, aadFor('u1', acc.id, 'refresh')), 'R1');
  const replay = await finishOAuth(s.deps, { platform: 'YOUTUBE', state: st, code: 'CODE', error: null });
  assert.match(replay.redirect, /status=error/); assert.match(replay.redirect, /unknown_state/); assert.equal(s.accounts.size, 1);
  assert.match((await finishOAuth(s.deps, { platform: 'YOUTUBE', state: 'forged', code: 'c', error: null })).redirect, /unknown_state/);
  assert.match((await finishOAuth(s.deps, { platform: 'YOUTUBE', state: null, code: 'c', error: null })).redirect, /missing_state/);
  // platform mismatch: a state issued for YouTube can not be completed as TikTok
  const st2 = stateFrom((await startOAuth(s.deps, { owner: 'u1', platform: 'YOUTUBE' }) as any).url);
  assert.match((await finishOAuth(s.deps, { platform: 'TIKTOK', state: st2, code: 'c', error: null })).redirect, /platform_mismatch/);
});

test('callback: expired state, provider denial, and plan limit are reported without side effects', async () => {
  const s = setup();
  const st = stateFrom((await startOAuth(s.deps, { owner: 'u1', platform: 'YOUTUBE' }) as any).url);
  s.tick(11 * 60_000);
  assert.match((await finishOAuth(s.deps, { platform: 'YOUTUBE', state: st, code: 'c', error: null })).redirect, /expired/);
  const st2 = stateFrom((await startOAuth(s.deps, { owner: 'u1', platform: 'YOUTUBE' }) as any).url);
  assert.match((await finishOAuth(s.deps, { platform: 'YOUTUBE', state: st2, code: null, error: 'access_denied' })).redirect, /provider_error/);
  assert.equal(s.accounts.size, 0);
  const l = setup({ limitAfter: 0 });
  const st3 = stateFrom((await startOAuth(l.deps, { owner: 'u1', platform: 'YOUTUBE' }) as any).url);
  const r = await finishOAuth(l.deps, { platform: 'YOUTUBE', state: st3, code: 'c', error: null });
  assert.match(r.redirect, /plan_limit/); assert.equal(r.connected, 0);
});

test('reconnect reuses the same account id (no new slot)', async () => {
  const s = setup({ limitAfter: 1 });
  for (let i = 0; i < 2; i++) {
    const st = stateFrom((await startOAuth(s.deps, { owner: 'u1', platform: 'YOUTUBE' }) as any).url);
    assert.equal((await finishOAuth(s.deps, { platform: 'YOUTUBE', state: st, code: 'c', error: null })).connected, 1);
  }
  assert.equal(s.accounts.size, 1);
});

test('refresh: proactive refresh persists new encrypted tokens; permanent failure marks NEEDS_RECONNECT; transient does not', async () => {
  const s = setup();
  const st = stateFrom((await startOAuth(s.deps, { owner: 'u1', platform: 'YOUTUBE' }) as any).url);
  await finishOAuth(s.deps, { platform: 'YOUTUBE', state: st, code: 'c', error: null });
  const acc = [...s.accounts.values()][0] as AccountRow;
  assert.equal(await refreshAccount(s.deps, acc), 'A1');                       // not near expiry: no network refresh
  s.tick(3600_000);
  assert.equal(await refreshAccount(s.deps, acc), 'new-access');
  assert.equal(await decryptToken(acc.access_token_enc!, s.deps.ring, aadFor('u1', acc.id, 'access')), 'new-access');
  const bad = setup(); bad.deps.fetch = (async () => new Response('{}', { status: 400 })) as typeof fetch;
  const st2 = stateFrom((await startOAuth(s.deps, { owner: 'u1', platform: 'YOUTUBE' }) as any).url);
  void st2;
  assert.equal(await refreshAccount({ ...s.deps, fetch: bad.deps.fetch }, acc, { force: true }), null);
  assert.equal(s.accounts.get(acc.id).status, 'NEEDS_RECONNECT');
  s.accounts.get(acc.id).status = 'CONNECTED';
  assert.equal(await refreshAccount({ ...s.deps, fetch: (async () => new Response('', { status: 503 })) as typeof fetch }, acc, { force: true }), null);
  assert.equal(s.accounts.get(acc.id).status, 'CONNECTED');
});

test('disconnect: revokes at the provider, wipes tokens, keeps the row; other owners can not disconnect', async () => {
  const s = setup();
  const st = stateFrom((await startOAuth(s.deps, { owner: 'u1', platform: 'YOUTUBE' }) as any).url);
  await finishOAuth(s.deps, { platform: 'YOUTUBE', state: st, code: 'c', error: null });
  const acc = [...s.accounts.values()][0];
  assert.deepEqual(await disconnectAccount(s.deps, { owner: 'u2', accountId: acc.id }), { ok: false, revokedAtProvider: false });
  assert.deepEqual(await disconnectAccount(s.deps, { owner: 'u1', accountId: acc.id }), { ok: true, revokedAtProvider: true });
  assert.equal(acc.status, 'REVOKED'); assert.equal(acc.access_token_enc, null); assert.equal(acc.refresh_token_enc, null);
  assert.ok(s.calls.some((c) => c.includes('oauth2.googleapis.com/revoke')));
});
