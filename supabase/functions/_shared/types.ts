// Shared v0.8 types. No runtime dependencies so every module can be unit-tested with plain Node.
export type Platform = 'YOUTUBE' | 'INSTAGRAM' | 'TIKTOK' | 'FACEBOOK' | 'THREADS' | 'X' | 'LINKEDIN';
export const PLATFORMS: readonly Platform[] = ['YOUTUBE', 'INSTAGRAM', 'TIKTOK', 'FACEBOOK', 'THREADS', 'X', 'LINKEDIN'];

/** How a failed provider call must be handled by the scheduler. */
export type FailureClass =
  | 'transient'         // network/5xx/not-ready: retry with backoff
  | 'rate_limited'      // 429 or provider throttle: retry after Retry-After / backoff
  | 'quota'             // daily/channel quota exhausted: block the account until `retryAfterMs`
  | 'auth'              // token invalid/expired/revoked: refresh once, else account needs reconnect
  | 'approval_blocked'  // missing permission/app review/audit/paid tier: never retry, tell the user
  | 'permanent';        // bad request / policy / media rejected: never retry

export interface ProviderFailure {
  platform: Platform;
  code: string;            // stable, normalised code (never contains tokens)
  class: FailureClass;
  message: string;         // sanitised, user-presentable
  httpStatus?: number;
  retryAfterMs?: number;
}

export class ProviderError extends Error {
  failure: ProviderFailure;
  constructor(failure: ProviderFailure) {
    super(failure.message);
    this.name = 'ProviderError';
    this.failure = failure;
  }
}

export function isRetryable(c: FailureClass): boolean {
  return c === 'transient' || c === 'rate_limited' || c === 'quota';
}
