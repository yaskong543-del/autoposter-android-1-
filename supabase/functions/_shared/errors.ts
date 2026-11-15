import { type FailureClass, type Platform, type ProviderFailure } from './types.ts';

/** Retry-After: delta-seconds or an HTTP date. Returns milliseconds, or undefined. */
export function parseRetryAfter(value: string | null | undefined, nowMs = Date.now()): number | undefined {
  if (!value) return undefined;
  const v = value.trim();
  if (/^\d+$/.test(v)) return Math.min(Number(v) * 1000, 7 * 86_400_000);
  const t = Date.parse(v);
  if (Number.isNaN(t)) return undefined;
  return Math.max(0, t - nowMs);
}

/** Milliseconds until the next midnight in America/Los_Angeles (YouTube quota resets at midnight Pacific). */
export function msUntilPacificMidnight(nowMs: number): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'America/Los_Angeles', hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).formatToParts(new Date(nowMs));
  const get = (t: string) => Number(parts.find((p) => p.type === t)?.value ?? 0) % 24;
  const elapsed = (get('hour') * 3600 + get('minute') * 60 + get('second')) * 1000;
  // On the two DST-change days the Pacific day is 23 or 25 h long; being off by an hour only delays/advances one retry.
  return Math.max(60_000, 86_400_000 - elapsed);
}

function pick(obj: unknown, ...path: string[]): unknown {
  let cur: any = obj;
  for (const k of path) { if (cur == null || typeof cur !== 'object') return undefined; cur = cur[k]; }
  return cur;
}
const str = (v: unknown): string => (typeof v === 'string' ? v : '');
const num = (v: unknown): number => (typeof v === 'number' ? v : NaN);

function f(platform: Platform, cls: FailureClass, code: string, message: string, status?: number, retryAfterMs?: number): ProviderFailure {
  return { platform, class: cls, code, message, httpStatus: status, retryAfterMs };
}

/**
 * Map an HTTP failure from a provider to a normalised failure. `body` is the parsed JSON (or undefined / text).
 * Rules are intentionally conservative: unknown 4xx are permanent (never hammer a provider), unknown 5xx are transient.
 */
export function classifyHttp(
  platform: Platform, status: number, body: unknown, headers: { get(name: string): string | null }, nowMs = Date.now(),
): ProviderFailure {
  const retryAfter = parseRetryAfter(headers.get('retry-after'), nowMs);
  const generic = (): ProviderFailure => {
    if (status === 429) return f(platform, 'rate_limited', 'RATE_LIMITED', 'The platform is rate limiting requests.', status, retryAfter ?? 60_000);
    if (status === 401) return f(platform, 'auth', 'AUTH_INVALID', 'The connected account needs to be reconnected.', status);
    if (status === 408 || status >= 500) return f(platform, 'transient', 'PROVIDER_UNAVAILABLE', 'The platform had a temporary problem.', status, retryAfter);
    if (status === 403) return f(platform, 'approval_blocked', 'FORBIDDEN', 'The platform refused this action (permission, review or plan restriction).', status);
    return f(platform, 'permanent', 'REJECTED', 'The platform rejected this request.', status);
  };

  switch (platform) {
    case 'YOUTUBE': {
      const reason = str(pick(body, 'error', 'errors', '0', 'reason'));
      if (['quotaExceeded', 'dailyLimitExceeded'].includes(reason))
        return f(platform, 'quota', 'YT_QUOTA_EXCEEDED', 'The YouTube API quota is used up. It resets at midnight Pacific Time.', status, msUntilPacificMidnight(nowMs));
      if (reason === 'rateLimitExceeded' || reason === 'userRateLimitExceeded')
        return f(platform, 'rate_limited', 'YT_RATE_LIMITED', 'YouTube is rate limiting requests.', status, retryAfter ?? 60_000);
      if (reason === 'uploadLimitExceeded')
        return f(platform, 'quota', 'YT_CHANNEL_UPLOAD_LIMIT', 'This YouTube channel reached its upload limit for now.', status, 6 * 3_600_000);
      if (reason === 'authError' || status === 401) return f(platform, 'auth', 'AUTH_INVALID', 'The YouTube connection needs to be reconnected.', status);
      if (reason.startsWith('forbidden') || reason === 'insufficientPermissions')
        return f(platform, 'approval_blocked', 'YT_FORBIDDEN', 'YouTube refused this upload (permission or privacy setting).', status);
      if (status === 400) return f(platform, 'permanent', `YT_${reason || 'BAD_REQUEST'}`.toUpperCase(), 'YouTube rejected the video details.', status);
      return generic();
    }
    case 'INSTAGRAM': case 'FACEBOOK': case 'THREADS': {
      const code = num(pick(body, 'error', 'code'));
      const sub = num(pick(body, 'error', 'error_subcode'));
      if (code === 190 || code === 102) return f(platform, 'auth', 'AUTH_INVALID', 'The connection expired or was revoked. Reconnect the account.', status);
      if ([4, 17, 32, 613].includes(code)) return f(platform, 'rate_limited', 'META_RATE_LIMITED', 'Meta is rate limiting this account or app.', status, retryAfter ?? 15 * 60_000);
      if (code === 9007 || sub === 2207027) return f(platform, 'transient', 'MEDIA_NOT_READY', 'The video is still being processed.', status, 30_000);
      if (code === 1 || code === 2) return f(platform, 'transient', 'META_TEMPORARY', 'Meta had a temporary problem.', status, retryAfter);
      if (code === 10 || (code >= 200 && code <= 299)) return f(platform, 'approval_blocked', 'META_PERMISSION', 'A required Meta permission is missing or not approved.', status);
      return generic();
    }
    case 'TIKTOK': {
      const code = str(pick(body, 'error', 'code'));
      if (code === '' || code === 'ok') return generic();
      if (code === 'access_token_invalid' || code === 'scope_not_authorized') return f(platform, 'auth', 'AUTH_INVALID', 'The TikTok connection needs to be reconnected.', status);
      if (code === 'rate_limit_exceeded') return f(platform, 'rate_limited', 'TT_RATE_LIMITED', 'TikTok is rate limiting requests.', status, retryAfter ?? 60_000);
      if (code === 'spam_risk_too_many_posts' || code === 'reached_active_user_cap')
        return f(platform, 'quota', 'TT_DAILY_CAP', 'TikTok daily posting cap reached.', status, 6 * 3_600_000);
      if (code === 'unaudited_client_can_only_post_to_private_accounts' || code === 'url_ownership_unverified')
        return f(platform, 'approval_blocked', `TT_${code}`.toUpperCase(), 'TikTok has not approved this app for public posting yet.', status);
      return status >= 500 ? generic() : f(platform, 'permanent', `TT_${code}`.toUpperCase(), 'TikTok rejected this request.', status);
    }
    case 'X': {
      if (status === 429) {
        const reset = Number(headers.get('x-rate-limit-reset'));
        const ms = Number.isFinite(reset) && reset > 0 ? Math.max(1000, reset * 1000 - nowMs) : (retryAfter ?? 15 * 60_000);
        return f(platform, 'rate_limited', 'X_RATE_LIMITED', 'X is rate limiting requests.', status, ms);
      }
      if (status === 402) return f(platform, 'quota', 'X_CREDITS_REQUIRED', 'The X API account has no remaining credits or plan.', status, 6 * 3_600_000);
      return generic();
    }
    case 'LINKEDIN': {
      if (status === 422 || status === 400) return f(platform, 'permanent', 'LI_REJECTED', 'LinkedIn rejected the post.', status);
      return generic();
    }
  }
}
