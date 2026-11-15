import { type Platform } from './types.ts';
import { b64uEncode } from './crypto.ts';

export interface OAuthSpec {
  platform: Platform;
  authUrl: string;
  tokenUrl: string;
  revokeUrl?: string;
  scopes: string[];
  scopeSep: ' ' | ',';
  /** PKCE is sent only where the provider documents it for this client type. */
  pkce: boolean;
  clientIdParam: 'client_id' | 'client_key';
  /** How the client secret reaches the token endpoint. */
  clientAuth: 'body' | 'basic';
  extraAuthParams: Record<string, string>;
  refresh: 'oauth2' | 'meta_ig' | 'meta_threads' | 'none';
  /** Documented lifetimes, for the support matrix and expiry warnings (seconds). */
  accessTtlSec?: number;
}

// Endpoints/scopes verified against provider documentation on 2026-11-13 (see docs/API_SUPPORT_MATRIX.md for sources),
// except Google's standard OAuth endpoints and LinkedIn's authorization URL, which are stable public endpoints not re-fetched.
export const OAUTH_SPECS: Record<Platform, OAuthSpec> = {
  YOUTUBE: {
    platform: 'YOUTUBE', authUrl: 'https://accounts.google.com/o/oauth2/v2/auth', tokenUrl: 'https://oauth2.googleapis.com/token',
    revokeUrl: 'https://oauth2.googleapis.com/revoke',
    scopes: ['https://www.googleapis.com/auth/youtube.upload', 'https://www.googleapis.com/auth/youtube.readonly'],
    scopeSep: ' ', pkce: true, clientIdParam: 'client_id', clientAuth: 'body',
    extraAuthParams: { access_type: 'offline', prompt: 'consent', include_granted_scopes: 'true' }, refresh: 'oauth2', accessTtlSec: 3600,
  },
  INSTAGRAM: {
    platform: 'INSTAGRAM', authUrl: 'https://www.instagram.com/oauth/authorize', tokenUrl: 'https://api.instagram.com/oauth/access_token',
    scopes: ['instagram_business_basic', 'instagram_business_content_publish'], scopeSep: ',', pkce: false,
    clientIdParam: 'client_id', clientAuth: 'body', extraAuthParams: {}, refresh: 'meta_ig', accessTtlSec: 60 * 86400,
  },
  THREADS: {
    platform: 'THREADS', authUrl: 'https://threads.net/oauth/authorize', tokenUrl: 'https://graph.threads.net/oauth/access_token',
    scopes: ['threads_basic', 'threads_content_publish'], scopeSep: ',', pkce: false,
    clientIdParam: 'client_id', clientAuth: 'body', extraAuthParams: {}, refresh: 'meta_threads', accessTtlSec: 60 * 86400,
  },
  FACEBOOK: {
    platform: 'FACEBOOK', authUrl: 'https://www.facebook.com/v26.0/dialog/oauth', tokenUrl: 'https://graph.facebook.com/v26.0/oauth/access_token',
    scopes: ['pages_show_list', 'pages_read_engagement', 'pages_manage_posts'], scopeSep: ',', pkce: false,
    clientIdParam: 'client_id', clientAuth: 'body', extraAuthParams: {}, refresh: 'none',
  },
  TIKTOK: {
    platform: 'TIKTOK', authUrl: 'https://www.tiktok.com/v2/auth/authorize/', tokenUrl: 'https://open.tiktokapis.com/v2/oauth/token/',
    revokeUrl: 'https://open.tiktokapis.com/v2/oauth/revoke/', scopes: ['user.info.basic', 'video.publish'], scopeSep: ',',
    pkce: false /* PKCE applies to desktop/iOS/Android, not web (TikTok Login Kit overview) */,
    clientIdParam: 'client_key', clientAuth: 'body', extraAuthParams: {}, refresh: 'oauth2', accessTtlSec: 86400,
  },
  X: {
    platform: 'X', authUrl: 'https://x.com/i/oauth2/authorize', tokenUrl: 'https://api.x.com/2/oauth2/token',
    revokeUrl: 'https://api.x.com/2/oauth2/revoke', scopes: ['tweet.read', 'tweet.write', 'users.read', 'media.write', 'offline.access'],
    scopeSep: ' ', pkce: true, clientIdParam: 'client_id', clientAuth: 'basic', extraAuthParams: {}, refresh: 'oauth2', accessTtlSec: 7200,
  },
  LINKEDIN: {
    platform: 'LINKEDIN', authUrl: 'https://www.linkedin.com/oauth/v2/authorization', tokenUrl: 'https://www.linkedin.com/oauth/v2/accessToken',
    scopes: ['openid', 'profile', 'w_member_social'], scopeSep: ' ', pkce: false,
    clientIdParam: 'client_id', clientAuth: 'body', extraAuthParams: {}, refresh: 'oauth2', accessTtlSec: 60 * 86400,
  },
};

// ---------- PKCE / state ----------
export function randomB64u(bytes: number): string {
  return b64uEncode(crypto.getRandomValues(new Uint8Array(bytes)));
}
/** RFC 7636: 43..128 chars from the unreserved set. 32 random bytes -> 43 chars. */
export function newCodeVerifier(): string { return randomB64u(32); }
export async function codeChallengeS256(verifier: string): Promise<string> {
  return b64uEncode(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))));
}
export function newState(): string { return randomB64u(32); }
export async function sha256Hex(s: string): Promise<string> {
  const h = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)));
  return Array.from(h, (b) => b.toString(16).padStart(2, '0')).join('');
}
/** Constant-time string comparison. */
export function safeEqual(a: string, b: string): boolean {
  const x = new TextEncoder().encode(a), y = new TextEncoder().encode(b);
  let d = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) d |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return d === 0;
}

export interface BuildAuthInput { spec: OAuthSpec; clientId: string; redirectUri: string; state: string; codeChallenge?: string }
export function buildAuthUrl(i: BuildAuthInput): string {
  const u = new URL(i.spec.authUrl);
  u.searchParams.set(i.spec.clientIdParam, i.clientId);
  u.searchParams.set('redirect_uri', i.redirectUri);
  u.searchParams.set('response_type', 'code');
  u.searchParams.set('scope', i.spec.scopes.join(i.spec.scopeSep));
  u.searchParams.set('state', i.state);
  for (const [k, v] of Object.entries(i.spec.extraAuthParams)) u.searchParams.set(k, v);
  if (i.spec.pkce) {
    if (!i.codeChallenge) throw new Error('PKCE required for this provider');
    u.searchParams.set('code_challenge', i.codeChallenge);
    u.searchParams.set('code_challenge_method', 'S256');
  }
  return u.toString();
}

/** A stored, server-side OAuth attempt. The state value itself is never stored, only its SHA-256. */
export interface OAuthStateRecord {
  state_hash: string; owner: string; platform: Platform; redirect_uri: string;
  expires_at: string; used_at: string | null;
}
export type CallbackCheck =
  | { ok: true }
  | { ok: false; reason: 'missing_state' | 'unknown_state' | 'expired' | 'replayed' | 'platform_mismatch' | 'redirect_mismatch' | 'provider_error' | 'missing_code' | 'owner_mismatch' };

/** Pure validation of an OAuth callback. `record` is the row found by hash(state), or null. */
export function validateCallback(p: {
  state: string | null; code: string | null; error: string | null; record: OAuthStateRecord | null;
  platform: Platform; redirectUri: string; nowMs: number; sessionOwner?: string;
}): CallbackCheck {
  if (!p.state) return { ok: false, reason: 'missing_state' };
  if (!p.record) return { ok: false, reason: 'unknown_state' };
  if (p.record.used_at) return { ok: false, reason: 'replayed' };
  if (Date.parse(p.record.expires_at) <= p.nowMs) return { ok: false, reason: 'expired' };
  if (p.record.platform !== p.platform) return { ok: false, reason: 'platform_mismatch' };
  if (!safeEqual(p.record.redirect_uri, p.redirectUri)) return { ok: false, reason: 'redirect_mismatch' };
  if (p.sessionOwner && p.sessionOwner !== p.record.owner) return { ok: false, reason: 'owner_mismatch' };
  if (p.error) return { ok: false, reason: 'provider_error' };
  if (!p.code) return { ok: false, reason: 'missing_code' };
  return { ok: true };
}

// ---------- token exchange / refresh / revoke ----------
export interface Credentials { clientId: string; clientSecret: string }
export interface TokenSet {
  accessToken: string; refreshToken?: string; expiresAtMs?: number; scope?: string; providerUserId?: string;
}
type Fetch = typeof fetch;

function tokenSetFrom(j: any, nowMs: number): TokenSet {
  const data = j?.data && typeof j.data === 'object' ? j.data : j; // TikTok v2 flat; some providers nest under data
  const access = data?.access_token;
  if (typeof access !== 'string' || !access) throw new Error('token response has no access_token');
  const exp = Number(data.expires_in);
  return {
    accessToken: access,
    refreshToken: typeof data.refresh_token === 'string' ? data.refresh_token : undefined,
    expiresAtMs: Number.isFinite(exp) && exp > 0 ? nowMs + exp * 1000 : undefined,
    scope: typeof data.scope === 'string' ? data.scope : undefined,
    providerUserId: data.open_id ?? (data.user_id != null ? String(data.user_id) : undefined),
  };
}

function formBody(o: Record<string, string>): URLSearchParams { return new URLSearchParams(o); }
function clientHeaders(spec: OAuthSpec, c: Credentials): Record<string, string> {
  const h: Record<string, string> = { 'content-type': 'application/x-www-form-urlencoded' };
  if (spec.clientAuth === 'basic') h.authorization = 'Basic ' + btoa(`${c.clientId}:${c.clientSecret}`);
  return h;
}

export async function exchangeCode(
  spec: OAuthSpec, c: Credentials, p: { code: string; redirectUri: string; codeVerifier?: string }, f: Fetch, nowMs = Date.now(),
): Promise<TokenSet> {
  const body: Record<string, string> = { grant_type: 'authorization_code', code: p.code, redirect_uri: p.redirectUri };
  if (spec.clientAuth === 'body') { body[spec.clientIdParam] = c.clientId; body.client_secret = c.clientSecret; }
  else body.client_id = c.clientId;
  if (spec.pkce) { if (!p.codeVerifier) throw new Error('missing code_verifier'); body.code_verifier = p.codeVerifier; }
  const r = await f(spec.tokenUrl, { method: 'POST', headers: clientHeaders(spec, c), body: formBody(body) });
  if (!r.ok) throw new Error(`token exchange failed (${r.status})`);
  let t = tokenSetFrom(await r.json(), nowMs);
  // Meta: the first token is short-lived (1h). Exchange immediately for the 60-day token.
  if (spec.platform === 'INSTAGRAM' || spec.platform === 'THREADS') t = await metaLongLived(spec, c, t, f, nowMs);
  return t;
}

async function metaLongLived(spec: OAuthSpec, c: Credentials, short: TokenSet, f: Fetch, nowMs: number): Promise<TokenSet> {
  const ig = spec.platform === 'INSTAGRAM';
  const u = new URL(ig ? 'https://graph.instagram.com/access_token' : 'https://graph.threads.net/access_token');
  u.searchParams.set('grant_type', ig ? 'ig_exchange_token' : 'th_exchange_token');
  u.searchParams.set('client_secret', c.clientSecret);
  u.searchParams.set('access_token', short.accessToken);
  const r = await f(u.toString());
  if (!r.ok) throw new Error(`long-lived exchange failed (${r.status})`);
  const t = tokenSetFrom(await r.json(), nowMs);
  return { ...t, providerUserId: short.providerUserId };
}

/** Refresh. Returns the new TokenSet; for Meta the access token itself is the refresh credential. */
export async function refreshTokens(
  spec: OAuthSpec, c: Credentials, current: { accessToken: string; refreshToken?: string }, f: Fetch, nowMs = Date.now(),
): Promise<TokenSet> {
  if (spec.refresh === 'none') throw new Error('this provider has no refresh flow');
  if (spec.refresh === 'meta_ig' || spec.refresh === 'meta_threads') {
    const ig = spec.refresh === 'meta_ig';
    const u = new URL(ig ? 'https://graph.instagram.com/refresh_access_token' : 'https://graph.threads.net/refresh_access_token');
    u.searchParams.set('grant_type', ig ? 'ig_refresh_token' : 'th_refresh_token');
    u.searchParams.set('access_token', current.accessToken);
    const r = await f(u.toString());
    if (!r.ok) throw new Error(`refresh failed (${r.status})`);
    return tokenSetFrom(await r.json(), nowMs);
  }
  if (!current.refreshToken) throw new Error('no refresh token stored');
  const body: Record<string, string> = { grant_type: 'refresh_token', refresh_token: current.refreshToken };
  if (spec.clientAuth === 'body') { body[spec.clientIdParam] = c.clientId; body.client_secret = c.clientSecret; } else body.client_id = c.clientId;
  const r = await f(spec.tokenUrl, { method: 'POST', headers: clientHeaders(spec, c), body: formBody(body) });
  if (!r.ok) throw new Error(`refresh failed (${r.status})`);
  const t = tokenSetFrom(await r.json(), nowMs);
  // Providers may omit a new refresh token: keep the old one.
  return { ...t, refreshToken: t.refreshToken ?? current.refreshToken };
}

/** Best-effort revocation. Returns false when the provider has no revoke endpoint or the call failed (caller still deletes locally). */
export async function revokeToken(spec: OAuthSpec, c: Credentials, token: string, f: Fetch): Promise<boolean> {
  if (!spec.revokeUrl) return false;
  try {
    let r: Response;
    if (spec.platform === 'YOUTUBE') {
      r = await f(`${spec.revokeUrl}?token=${encodeURIComponent(token)}`, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' } });
    } else if (spec.platform === 'TIKTOK') {
      r = await f(spec.revokeUrl, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: formBody({ client_key: c.clientId, client_secret: c.clientSecret, token }) });
    } else {
      r = await f(spec.revokeUrl, { method: 'POST', headers: clientHeaders(spec, c), body: formBody({ token, client_id: c.clientId }) });
    }
    return r.ok;
  } catch { return false; }
}

/** Seconds-from-now for a token expiry bucket used by the "token expiring" notification. */
export function expiryState(expiresAtMs: number | null | undefined, nowMs: number): 'ok' | 'expiring' | 'expired' | 'unknown' {
  if (!expiresAtMs) return 'unknown';
  if (expiresAtMs <= nowMs) return 'expired';
  return expiresAtMs - nowMs <= 7 * 86_400_000 ? 'expiring' : 'ok';
}
