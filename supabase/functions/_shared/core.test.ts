import { test } from 'node:test';
import assert from 'node:assert/strict';
import { encryptToken, decryptToken, parseKeyRing, aadFor, needsReencrypt, b64uEncode } from './crypto.ts';
import { newCodeVerifier, codeChallengeS256, newState, sha256Hex, buildAuthUrl, OAUTH_SPECS, validateCallback, exchangeCode, refreshTokens, revokeToken, expiryState } from './oauth.ts';
import { classifyHttp, parseRetryAfter, msUntilPacificMidnight } from './errors.ts';
import { backoffMs, decideRetry } from './retry.ts';
import { canTransition, cancelOutcome, idempotencyKey, JOB_STATUSES, TRANSITIONS } from './jobs.ts';
import { ENTITLEMENTS, canAddProfile, canAddDevice, activeSet, uploadAllowed } from './entitlements.ts';
import { mergeChange, POST_POLICY, DOC_POLICY, collapseOutbox, purgeableTombstones } from './sync_core.ts';
import { dedupeKey } from './notify.ts';
import { PLATFORMS } from './types.ts';

const key = () => b64uEncode(crypto.getRandomValues(new Uint8Array(32)));
const H = (o: Record<string, string> = {}) => ({ get: (n: string) => o[n.toLowerCase()] ?? null });

// ---------- crypto ----------
test('token encryption round-trips and binds to AAD', async () => {
  const ring = parseKeyRing(`k1:${key()}`);
  const aad = aadFor('u1', 'a1', 'access');
  const blob = await encryptToken('secret-token', ring, aad);
  assert.ok(blob.startsWith('v1.k1.'));
  assert.ok(!blob.includes('secret-token'));
  assert.equal(await decryptToken(blob, ring, aad), 'secret-token');
  await assert.rejects(() => decryptToken(blob, ring, aadFor('u2', 'a1', 'access')));   // swapped row
  await assert.rejects(() => decryptToken(blob.slice(0, -2) + 'AA', ring, aad));         // tampered
  assert.notEqual(blob, await encryptToken('secret-token', ring, aad));                  // random IV
});
test('key rotation: old ciphertext decrypts, flagged for re-encryption', async () => {
  const k1 = key(), k2 = key();
  const old = await encryptToken('t', parseKeyRing(`k1:${k1}`), 'x');
  const ring = parseKeyRing(`k2:${k2}`, `k1:${k1}`);
  assert.equal(await decryptToken(old, ring, 'x'), 't');
  assert.equal(needsReencrypt(old, ring), true);
  await assert.rejects(() => decryptToken(old, parseKeyRing(`k2:${k2}`), 'x'));
  assert.throws(() => parseKeyRing('k1:AAAA'));
  assert.throws(() => parseKeyRing(undefined));
});

// ---------- OAuth / PKCE ----------
test('PKCE verifier/challenge match the RFC 7636 example', async () => {
  assert.equal(await codeChallengeS256('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'), 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  const v = newCodeVerifier();
  assert.match(v, /^[A-Za-z0-9_-]{43}$/);
  assert.notEqual(newState(), newState());
});
test('auth URLs: PKCE only where the provider documents it; TikTok uses client_key', async () => {
  const ch = await codeChallengeS256(newCodeVerifier());
  const yt = new URL(buildAuthUrl({ spec: OAUTH_SPECS.YOUTUBE, clientId: 'cid', redirectUri: 'https://x/cb', state: 's', codeChallenge: ch }));
  assert.equal(yt.searchParams.get('code_challenge_method'), 'S256');
  assert.equal(yt.searchParams.get('access_type'), 'offline');
  const tt = new URL(buildAuthUrl({ spec: OAUTH_SPECS.TIKTOK, clientId: 'ck', redirectUri: 'https://x/cb', state: 's' }));
  assert.equal(tt.searchParams.get('client_key'), 'ck');
  assert.equal(tt.searchParams.get('client_id'), null);
  assert.equal(tt.searchParams.get('code_challenge'), null);
  assert.equal(tt.searchParams.get('scope'), 'user.info.basic,video.publish');
  assert.throws(() => buildAuthUrl({ spec: OAUTH_SPECS.X, clientId: 'c', redirectUri: 'r', state: 's' }));
  assert.equal(PLATFORMS.length, 7);
  for (const p of PLATFORMS) assert.ok(OAUTH_SPECS[p]);
});
test('callback validation rejects every unsafe case', async () => {
  const rec = { state_hash: 'h', owner: 'u1', platform: 'YOUTUBE' as const, redirect_uri: 'https://x/cb', expires_at: new Date(2_000_000).toISOString(), used_at: null };
  const base = { state: 's', code: 'c', error: null, record: rec, platform: 'YOUTUBE' as const, redirectUri: 'https://x/cb', nowMs: 1_000_000 };
  assert.deepEqual(validateCallback(base), { ok: true });
  const reason = (o: object) => (validateCallback({ ...base, ...o }) as { reason: string }).reason;
  assert.equal(reason({ state: null }), 'missing_state');
  assert.equal(reason({ record: null }), 'unknown_state');
  assert.equal(reason({ record: { ...rec, used_at: 'x' } }), 'replayed');
  assert.equal(reason({ nowMs: 3_000_000 }), 'expired');
  assert.equal(reason({ platform: 'X' }), 'platform_mismatch');
  assert.equal(reason({ redirectUri: 'https://evil/cb' }), 'redirect_mismatch');
  assert.equal(reason({ sessionOwner: 'u2' }), 'owner_mismatch');
  assert.equal(reason({ error: 'access_denied' }), 'provider_error');
  assert.equal(reason({ code: null }), 'missing_code');
  assert.equal((await sha256Hex('abc')).length, 64);
});
test('token exchange/refresh/revoke (fake provider endpoints; mock used only in tests)', async () => {
  const calls: Array<{ url: string; body: string; auth: string | null }> = [];
  const f = (async (url: string, init?: RequestInit) => {
    const hdr = new Headers(init?.headers);
    calls.push({ url: String(url), body: String(init?.body ?? ''), auth: hdr.get('authorization') });
    if (String(url).includes('instagram.com/oauth')) return Response.json({ access_token: 'short', user_id: 17 });
    if (String(url).includes('ig_exchange_token')) return Response.json({ access_token: 'long', expires_in: 5183944 });
    if (String(url).includes('ig_refresh_token')) return Response.json({ access_token: 'long2', expires_in: 5183944 });
    if (String(url).includes('x.com/2/oauth2/token')) return Response.json({ access_token: 'xa', refresh_token: 'xr', expires_in: 7200 });
    return new Response('no', { status: 400 });
  }) as typeof fetch;
  const c = { clientId: 'id', clientSecret: 'sec' };
  const ig = await exchangeCode(OAUTH_SPECS.INSTAGRAM, c, { code: 'c', redirectUri: 'r' }, f, 1000);
  assert.equal(ig.accessToken, 'long'); assert.equal(ig.providerUserId, '17'); assert.equal(ig.expiresAtMs, 1000 + 5183944_000);
  const xx = await exchangeCode(OAUTH_SPECS.X, c, { code: 'c', redirectUri: 'r', codeVerifier: 'v' }, f, 0);
  assert.equal(xx.refreshToken, 'xr');
  assert.ok(calls.at(-1)!.auth!.startsWith('Basic '));
  assert.ok(calls.at(-1)!.body.includes('code_verifier=v'));
  await assert.rejects(() => exchangeCode(OAUTH_SPECS.X, c, { code: 'c', redirectUri: 'r' }, f));   // PKCE verifier required
  assert.equal((await refreshTokens(OAUTH_SPECS.INSTAGRAM, c, { accessToken: 'long' }, f, 0)).accessToken, 'long2');
  const x2 = await refreshTokens(OAUTH_SPECS.X, c, { accessToken: 'a', refreshToken: 'xr' }, f, 0);
  assert.equal(x2.refreshToken, 'xr');
  await assert.rejects(() => refreshTokens(OAUTH_SPECS.FACEBOOK, c, { accessToken: 'a' }, f));
  assert.equal(await revokeToken(OAUTH_SPECS.INSTAGRAM, c, 't', f), false);                          // no revoke endpoint
  assert.equal(expiryState(null, 0), 'unknown'); assert.equal(expiryState(5, 10), 'expired');
  assert.equal(expiryState(10 + 3 * 86_400_000, 10), 'expiring'); assert.equal(expiryState(10 + 30 * 86_400_000, 10), 'ok');
});

// ---------- errors / retry ----------
test('provider error mapping', () => {
  const yt = (reason: string, status = 403) => classifyHttp('YOUTUBE', status, { error: { errors: [{ reason }] } }, H(), Date.UTC(2026, 10, 12, 20));
  assert.equal(yt('quotaExceeded').class, 'quota');
  assert.ok(yt('quotaExceeded').retryAfterMs! > 0);
  assert.equal(yt('uploadLimitExceeded', 400).class, 'quota');
  assert.equal(yt('forbiddenPrivacySetting').class, 'approval_blocked');
  assert.equal(yt('invalidTitle', 400).class, 'permanent');
  assert.equal(classifyHttp('INSTAGRAM', 400, { error: { code: 190 } }, H()).class, 'auth');
  assert.equal(classifyHttp('INSTAGRAM', 400, { error: { code: 4 } }, H()).class, 'rate_limited');
  assert.equal(classifyHttp('THREADS', 400, { error: { code: 9007 } }, H()).class, 'transient');
  assert.equal(classifyHttp('FACEBOOK', 400, { error: { code: 200 } }, H()).class, 'approval_blocked');
  assert.equal(classifyHttp('TIKTOK', 403, { error: { code: 'unaudited_client_can_only_post_to_private_accounts' } }, H()).class, 'approval_blocked');
  assert.equal(classifyHttp('TIKTOK', 401, { error: { code: 'access_token_invalid' } }, H()).class, 'auth');
  assert.equal(classifyHttp('X', 429, {}, H({ 'x-rate-limit-reset': String(Math.floor(Date.now() / 1000) + 120) })).class, 'rate_limited');
  assert.equal(classifyHttp('X', 402, {}, H()).class, 'quota');
  assert.equal(classifyHttp('LINKEDIN', 422, {}, H()).class, 'permanent');
  assert.equal(classifyHttp('LINKEDIN', 503, {}, H()).class, 'transient');
  assert.equal(classifyHttp('LINKEDIN', 429, {}, H({ 'retry-after': '90' })).retryAfterMs, 90_000);
  assert.equal(parseRetryAfter('120'), 120_000);
  assert.equal(parseRetryAfter(new Date(10_000 + 5000).toUTCString(), 10_000) !== undefined, true);
  assert.equal(parseRetryAfter('garbage'), undefined);
  const m = msUntilPacificMidnight(Date.UTC(2026, 10, 12, 20, 0, 0)); // 12:00 PST -> ~12h
  assert.ok(m > 11.5 * 3_600_000 && m < 12.5 * 3_600_000, String(m));
});
test('backoff grows, is bounded, honours Retry-After; permanent never retries', () => {
  const lo = () => 0, hi = () => 0.999999;
  assert.equal(backoffMs(1, lo), 15_000); assert.ok(backoffMs(1, hi) < 30_000);
  assert.ok(backoffMs(4, lo) > backoffMs(2, hi) - 1);
  assert.equal(backoffMs(60, hi) <= 6 * 3_600_000, true);
  const tf = (cls: any, extra = {}) => ({ platform: 'X' as const, code: 'C', message: 'm', class: cls, ...extra });
  assert.equal(decideRetry(tf('permanent'), 1, 5, 0).retry, false);
  assert.equal(decideRetry(tf('approval_blocked'), 1, 5, 0).retry, false);
  const a = decideRetry(tf('auth'), 1, 5, 0); assert.equal(a.retry, false); assert.equal((a as any).needsReconnect, true);
  assert.equal(decideRetry(tf('transient'), 5, 5, 0).retry, false);
  const r = decideRetry(tf('rate_limited', { retryAfterMs: 600_000 }), 1, 5, 1000, lo) as any;
  assert.equal(r.retry, true); assert.equal(r.atMs, 601_000);
  const q = decideRetry(tf('quota', { retryAfterMs: 3_600_000 }), 1, 5, 0, lo) as any;
  assert.equal(q.blockAccountUntilMs, q.atMs);
});

// ---------- job state machine ----------
test('state machine: legal paths, terminal states, cancellation semantics', () => {
  const path = ['scheduled', 'due', 'leased', 'uploading', 'submitted', 'published'] as const;
  for (let i = 0; i < path.length - 1; i++) assert.ok(canTransition(path[i], path[i + 1]), `${path[i]}->${path[i + 1]}`);
  for (const t of ['published', 'permanent_failure', 'cancelled'] as const) for (const s of JOB_STATUSES) assert.equal(canTransition(t, s), false);
  assert.equal(canTransition('scheduled', 'published'), false);   // clients can never jump to published
  assert.equal(canTransition('submitted', 'cancelled'), false);
  assert.equal(canTransition('retryable_failure', 'due'), true);
  assert.equal(cancelOutcome('scheduled'), 'cancelled'); assert.equal(cancelOutcome('uploading'), 'cancel_requested');
  assert.equal(cancelOutcome('submitted'), 'too_late'); assert.equal(cancelOutcome('published'), 'already_final');
  assert.deepEqual(Object.keys(TRANSITIONS).sort(), [...JOB_STATUSES].sort());
});
test('idempotency key is stable per (owner, post, account) and distinct otherwise', async () => {
  const a = await idempotencyKey('u', 'p', 'a');
  assert.equal(a, await idempotencyKey('u', 'p', 'a'));
  assert.notEqual(a, await idempotencyKey('u', 'p', 'b')); assert.notEqual(a, await idempotencyKey('v', 'p', 'a'));
});

// ---------- entitlements ----------
test('FREE/PREMIUM limits', () => {
  assert.deepEqual(ENTITLEMENTS.free, { maxProfiles: 2, maxDevices: 1, aiTokensMonthly: 5000, autoSync: false, cellularUploads: false, advancedAutomation: false });
  assert.equal(ENTITLEMENTS.pro.maxDevices, 5); assert.equal(ENTITLEMENTS.pro.aiTokensMonthly, 500_000); assert.equal(ENTITLEMENTS.pro.maxProfiles, null);
  assert.equal(canAddProfile('free', 1), true); assert.equal(canAddProfile('free', 2), false); assert.equal(canAddProfile('pro', 999), true);
  assert.equal(canAddDevice('free', 1), false); assert.equal(canAddDevice('pro', 4), true); assert.equal(canAddDevice('pro', 5), false);
});
test('downgrade keeps data: oldest N stay active, rest suspended', () => {
  const items = [{ id: 'c', createdAt: 3 }, { id: 'a', createdAt: 1 }, { id: 'b', createdAt: 2 }];
  const r = activeSet(items, 2);
  assert.deepEqual(r.active.map((i) => i.id), ['a', 'b']); assert.deepEqual(r.suspended.map((i) => i.id), ['c']);
  assert.equal(activeSet(items, null).suspended.length, 0);
});
test('upload policy: Wi-Fi only on FREE, cellular needs PREMIUM + user opt-in', () => {
  assert.equal(uploadAllowed({ plan: 'free', userAllowsCellular: true, network: 'cellular' }).reason, 'wifi_only_plan');
  assert.equal(uploadAllowed({ plan: 'pro', userAllowsCellular: false, network: 'cellular' }).reason, 'cellular_disabled');
  assert.equal(uploadAllowed({ plan: 'pro', userAllowsCellular: true, network: 'cellular' }).allowed, true);
  assert.equal(uploadAllowed({ plan: 'free', userAllowsCellular: false, network: 'wifi' }).allowed, true);
  assert.equal(uploadAllowed({ plan: 'pro', userAllowsCellular: true, network: 'none' }).reason, 'offline');
});

// ---------- sync ----------
const srv = (o: object = {}) => ({ id: 'p1', rev: 3, deleted: false, fields: { text: 'S', name: 'n', status: 'QUEUED' } as any, updatedAtMs: 2000, deviceId: 'dev-a', ...o });
const chg = (o: object = {}) => ({ id: 'p1', baseRev: 3, baseFields: { text: 'S', name: 'n' } as any, fields: { text: 'S' } as any, deleted: false, clientUpdatedAtMs: 3000, deviceId: 'dev-b', opId: 'o1', ...o });
test('sync: fast-forward, noop, server-owned fields ignored', () => {
  const r = mergeChange(srv(), chg({ fields: { text: 'C', status: 'DONE' } }), POST_POLICY) as any;
  assert.equal(r.action, 'apply'); assert.equal(r.rev, 4); assert.equal(r.fields.text, 'C'); assert.equal(r.fields.status, 'QUEUED');
  assert.equal(mergeChange(srv(), chg(), POST_POLICY).action, 'noop');
});
test('sync: non-conflicting concurrent edits merge field-wise', () => {
  const s = srv({ rev: 5, fields: { text: 'S', name: 'server-name', status: 'QUEUED' } });
  const r = mergeChange(s, chg({ baseRev: 3, fields: { text: 'client-text', name: 'n' } }), POST_POLICY) as any;
  assert.equal(r.fields.text, 'client-text'); assert.equal(r.fields.name, 'server-name'); assert.equal(r.conflicts.length, 0);
});
test('sync: same-field conflict is deterministic and visible', () => {
  const s = srv({ rev: 5, fields: { text: 'server-text', name: 'n' }, updatedAtMs: 5000 });
  const older = mergeChange(s, chg({ baseRev: 3, fields: { text: 'client-text' }, clientUpdatedAtMs: 4000 }), POST_POLICY) as any;
  assert.equal(older.fields.text, 'server-text'); assert.equal(older.conflicts[0].resolvedWith, 'server');
  const newer = mergeChange(s, chg({ baseRev: 3, fields: { text: 'client-text' }, clientUpdatedAtMs: 6000 }), POST_POLICY) as any;
  assert.equal(newer.fields.text, 'client-text'); assert.equal(newer.conflicts[0].resolvedWith, 'client');
  const tie = (dev: string) => (mergeChange(srv({ rev: 5, fields: { text: 'S2' }, updatedAtMs: 7000, deviceId: 'dev-m' }), chg({ baseRev: 3, fields: { text: 'C2' }, clientUpdatedAtMs: 7000, deviceId: dev }), POST_POLICY) as any).fields.text;
  assert.equal(tie('dev-z'), 'C2'); assert.equal(tie('dev-a'), 'S2');
});
test('sync: tombstones; delete-vs-edit keeps the record and flags it', () => {
  const del = mergeChange(srv(), chg({ deleted: true }), POST_POLICY) as any;
  assert.equal(del.deleted, true); assert.equal(del.rev, 4);
  assert.equal(mergeChange(srv({ deleted: true }), chg({ deleted: true }), POST_POLICY).action, 'noop');
  const edited = srv({ rev: 5, fields: { text: 'edited elsewhere' } });
  const dve = mergeChange(edited, chg({ baseRev: 3, deleted: true }), POST_POLICY) as any;
  assert.equal(dve.deleted, false); assert.equal(dve.conflicts[0].kind, 'delete_vs_edit');
  const back = mergeChange(srv({ deleted: true, rev: 6 }), chg({ baseRev: 3, fields: { text: 'revived' } }), POST_POLICY) as any;
  assert.equal(back.deleted, false); assert.equal(back.conflicts[0].kind, 'delete_vs_edit');
  assert.equal(mergeChange(null, chg({ deleted: true }), POST_POLICY).action, 'reject');
  const created = mergeChange(null, chg({ fields: { text: 'new', status: 'DONE' } }), POST_POLICY) as any;
  assert.equal(created.rev, 1); assert.equal(created.fields.status, undefined);
  assert.equal((mergeChange(null, chg({ fields: { a: 1 } }), DOC_POLICY) as any).fields.a, 1);
});
test('sync: outbox collapse keeps true base; tombstone purge waits for slowest device', () => {
  const mk = (seq: number, baseRev: number, text: string) => ({ opId: `o${seq}`, id: 'p1', seq, change: { id: 'p1', baseRev, baseFields: { text: `b${baseRev}` }, fields: { text }, deleted: false, clientUpdatedAtMs: seq, deviceId: 'd' } });
  const out = collapseOutbox([mk(2, 4, 'two'), mk(1, 3, 'one')]);
  assert.equal(out.length, 1); assert.equal(out[0].change.fields.text, 'two'); assert.equal(out[0].change.baseRev, 3);
  assert.deepEqual(purgeableTombstones([{ id: 'a', serverSeq: 5 }, { id: 'b', serverSeq: 20 }], [10, 30]), ['a']);
  assert.deepEqual(purgeableTombstones([{ id: 'a', serverSeq: 5 }], []), []);
});
test('notification dedupe keys', () => {
  assert.equal(dedupeKey('publish_success', 'j1'), 'publish_success:j1');
  assert.equal(dedupeKey('token_expiring', 'a1', '2026-11-12'), 'token_expiring:a1:2026-11-12');
  assert.notEqual(dedupeKey('account_error', 'a1', 'X'), dedupeKey('account_error', 'a1', 'Y'));
});
