import { type Platform, PLATFORMS } from '../_shared/types.ts';
import { OAUTH_SPECS, buildAuthUrl, codeChallengeS256, exchangeCode, newCodeVerifier, newState, refreshTokens, revokeToken, sha256Hex, validateCallback, type Credentials, type OAuthStateRecord, type TokenSet } from '../_shared/oauth.ts';
import { aadFor, decryptToken, encryptToken, type KeyRing } from '../_shared/crypto.ts';
import { fetchIdentities } from '../_shared/identity.ts';

export interface AccountRow {
  id: string; owner: string; platform: Platform; external_id: string; status: string;
  access_token_enc: string | null; refresh_token_enc: string | null; token_expires_at: string | null;
}
export interface OAuthDb {
  insertState(r: OAuthStateRecord & { code_verifier_enc: string | null }): Promise<void>;
  /** Atomically mark the state used and return it; null when missing or already used. */
  takeState(hash: string, nowIso: string): Promise<(OAuthStateRecord & { code_verifier_enc: string | null }) | null>;
  findAccount(owner: string, platform: Platform, externalId: string): Promise<AccountRow | null>;
  getAccount(owner: string, id: string): Promise<AccountRow | null>;
  /** Insert or reconnect. Throws an Error with .code === 'PL001' when the plan's profile limit is reached. */
  upsertAccount(a: { id: string; owner: string; platform: Platform; external_id: string; display_name: string; account_type: string; scopes: string[];
    access_token_enc: string; refresh_token_enc: string | null; token_expires_at: string | null; meta: Record<string, unknown> }): Promise<void>;
  saveTokens(id: string, t: { access_token_enc: string; refresh_token_enc: string | null; token_expires_at: string | null }): Promise<void>;
  setStatus(id: string, status: string, code: string | null): Promise<void>;
  clearTokens(id: string): Promise<void>;
}
export interface OAuthDeps {
  db: OAuthDb; ring: KeyRing; fetch: typeof fetch; now(): number; uuid(): string;
  credentials(p: Platform): Credentials | null;
  redirectUri: string;                         // https://<project>.functions.supabase.co/oauth/callback
  resultUrl(r: { platform: Platform; status: string; detail?: string }): string;   // deep link / dashboard URL for the final redirect
}

export type StartResult = { ok: true; url: string } | { ok: false; code: 'unsupported_platform' | 'not_configured' };
export async function startOAuth(d: OAuthDeps, p: { owner: string; platform: string }): Promise<StartResult> {
  if (!(PLATFORMS as readonly string[]).includes(p.platform)) return { ok: false, code: 'unsupported_platform' };
  const platform = p.platform as Platform;
  const creds = d.credentials(platform);
  if (!creds) return { ok: false, code: 'not_configured' };   // no client id/secret configured: honest, nothing is simulated
  const spec = OAUTH_SPECS[platform];
  const state = newState();
  let verifier: string | undefined, challenge: string | undefined;
  if (spec.pkce) { verifier = newCodeVerifier(); challenge = await codeChallengeS256(verifier); }
  const hash = await sha256Hex(state);
  await d.db.insertState({
    state_hash: hash, owner: p.owner, platform, redirect_uri: d.redirectUri, expires_at: new Date(d.now() + 10 * 60_000).toISOString(), used_at: null,
    code_verifier_enc: verifier ? await encryptToken(verifier, d.ring, `autoposter:v1:oauth_state:${hash}`) : null,
  });
  return { ok: true, url: buildAuthUrl({ spec, clientId: creds.clientId, redirectUri: d.redirectUri, state, codeChallenge: challenge }) };
}

export interface FinishResult { redirect: string; connected: number; skipped: number }
export async function finishOAuth(d: OAuthDeps, q: { platform: string; state: string | null; code: string | null; error: string | null }): Promise<FinishResult> {
  const out = (platform: Platform, status: string, detail?: string, connected = 0, skipped = 0): FinishResult =>
    ({ redirect: d.resultUrl({ platform, status, detail }), connected, skipped });
  if (!(PLATFORMS as readonly string[]).includes(q.platform)) return out('YOUTUBE', 'error', 'unsupported_platform');
  const platform = q.platform as Platform;
  const spec = OAUTH_SPECS[platform];
  const creds = d.credentials(platform);
  if (!creds) return out(platform, 'error', 'not_configured');
  const hash = q.state ? await sha256Hex(q.state) : '';
  const rec = q.state ? await d.db.takeState(hash, new Date(d.now()).toISOString()) : null;   // single use, even when validation then fails
  const check = validateCallback({ state: q.state, code: q.code, error: q.error, record: rec ? { ...rec, used_at: null } : null, platform, redirectUri: d.redirectUri, nowMs: d.now() });
  if (!check.ok || !rec) return out(platform, 'error', check.ok ? 'unknown_state' : check.reason);
  try {
    const verifier = rec.code_verifier_enc ? await decryptToken(rec.code_verifier_enc, d.ring, `autoposter:v1:oauth_state:${hash}`) : undefined;
    const tokens = await exchangeCode(spec, creds, { code: q.code!, redirectUri: d.redirectUri, codeVerifier: verifier }, d.fetch, d.now());
    const identities = await fetchIdentities(platform, tokens.accessToken, d.fetch, { providerUserId: tokens.providerUserId });
    let connected = 0, skipped = 0, firstProblem: string | undefined;
    for (const ident of identities) {
      if (!ident.eligible) { skipped++; firstProblem ??= 'ineligible'; continue; }
      const existing = await d.db.findAccount(rec.owner, platform, ident.externalId);
      const id = existing?.id ?? d.uuid();
      const access = ident.token ?? tokens.accessToken;
      try {
        await d.db.upsertAccount({
          id, owner: rec.owner, platform, external_id: ident.externalId, display_name: ident.displayName.slice(0, 200), account_type: ident.accountType,
          scopes: (tokens.scope ?? spec.scopes.join(' ')).split(/[ ,]+/).filter(Boolean), meta: ident.meta,
          access_token_enc: await encryptToken(access, d.ring, aadFor(rec.owner, id, 'access')),
          refresh_token_enc: tokens.refreshToken ? await encryptToken(tokens.refreshToken, d.ring, aadFor(rec.owner, id, 'refresh')) : null,
          token_expires_at: tokens.expiresAtMs ? new Date(tokens.expiresAtMs).toISOString() : null,
        });
        connected++;
      } catch (e) {
        if ((e as { code?: string }).code === 'PL001') { skipped++; firstProblem ??= 'plan_limit'; } else throw e;
      }
    }
    if (connected === 0) return out(platform, 'error', firstProblem ?? 'no_accounts', 0, skipped);
    return out(platform, 'ok', skipped ? 'partial_plan_limit' : undefined, connected, skipped);
  } catch {
    return out(platform, 'error', 'exchange_failed');     // never include provider error bodies, they can echo tokens
  }
}

/** Disconnect: revoke at the provider (best effort), then wipe the stored tokens and mark REVOKED. History stays. */
export async function disconnectAccount(d: OAuthDeps, p: { owner: string; accountId: string }): Promise<{ ok: boolean; revokedAtProvider: boolean }> {
  const a = await d.db.getAccount(p.owner, p.accountId);
  if (!a) return { ok: false, revokedAtProvider: false };
  let revoked = false;
  const creds = d.credentials(a.platform);
  if (creds && a.access_token_enc) {
    try {
      const tok = await decryptToken(a.refresh_token_enc ?? a.access_token_enc, d.ring, aadFor(a.owner, a.id, a.refresh_token_enc ? 'refresh' : 'access'));
      revoked = await revokeToken(OAUTH_SPECS[a.platform], creds, tok, d.fetch);
    } catch { /* undecryptable: still wipe */ }
  }
  await d.db.clearTokens(a.id);
  await d.db.setStatus(a.id, 'REVOKED', null);
  return { ok: true, revokedAtProvider: revoked };
}

/** Refresh one account's tokens in place. Returns the fresh access token, or null (account marked NEEDS_RECONNECT / left as is on transient errors). */
export async function refreshAccount(d: OAuthDeps, a: AccountRow, opts: { force?: boolean } = {}): Promise<string | null> {
  const spec = OAUTH_SPECS[a.platform];
  const creds = d.credentials(a.platform);
  if (!creds || !a.access_token_enc) return null;
  const access = await decryptToken(a.access_token_enc, d.ring, aadFor(a.owner, a.id, 'access'));
  const expMs = a.token_expires_at ? Date.parse(a.token_expires_at) : null;
  const soon = expMs !== null && expMs - d.now() < 5 * 60_000;
  if (!opts.force && !soon) return access;
  if (spec.refresh === 'none') return opts.force ? null : access;
  const refresh = a.refresh_token_enc ? await decryptToken(a.refresh_token_enc, d.ring, aadFor(a.owner, a.id, 'refresh')) : undefined;
  let t: TokenSet;
  try { t = await refreshTokens(spec, creds, { accessToken: access, refreshToken: refresh }, d.fetch, d.now()); }
  catch (e) {
    const msg = String((e as Error).message);
    // 4xx from the token endpoint = refresh token invalid/revoked/expired -> user must reconnect. 5xx/network = try again later.
    if (/\((400|401|403)\)/.test(msg) || /no refresh token/.test(msg)) { await d.db.setStatus(a.id, 'NEEDS_RECONNECT', 'REFRESH_FAILED'); }
    return null;
  }
  await d.db.saveTokens(a.id, {
    access_token_enc: await encryptToken(t.accessToken, d.ring, aadFor(a.owner, a.id, 'access')),
    refresh_token_enc: t.refreshToken ? await encryptToken(t.refreshToken, d.ring, aadFor(a.owner, a.id, 'refresh')) : a.refresh_token_enc,
    token_expires_at: t.expiresAtMs ? new Date(t.expiresAtMs).toISOString() : a.token_expires_at,
  });
  return t.accessToken;
}
