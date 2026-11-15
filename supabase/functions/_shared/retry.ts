import { type ProviderFailure, isRetryable } from './types.ts';

export interface BackoffOptions { baseMs: number; maxMs: number }
export const DEFAULT_BACKOFF: BackoffOptions = { baseMs: 30_000, maxMs: 6 * 3_600_000 };

/** Exponential backoff with "equal jitter": half the cap is fixed, half random, so retries always progress. */
export function backoffMs(attempt: number, rng: () => number = Math.random, o: BackoffOptions = DEFAULT_BACKOFF): number {
  const n = Math.max(1, Math.floor(attempt));
  const cap = Math.min(o.maxMs, o.baseMs * 2 ** (n - 1));
  return Math.floor(cap / 2 + rng() * (cap / 2));
}

export type RetryDecision =
  | { retry: true; atMs: number; reason: string; blockAccountUntilMs?: number; needsReconnect?: false }
  | { retry: false; reason: string; needsReconnect?: boolean };

/** Decide what happens after a failed attempt. `attempt` is the number of attempts already made (>=1). */
export function decideRetry(
  failure: ProviderFailure, attempt: number, maxAttempts: number, nowMs: number, rng: () => number = Math.random,
): RetryDecision {
  if (failure.class === 'auth') return { retry: false, reason: 'auth', needsReconnect: true };
  if (!isRetryable(failure.class)) return { retry: false, reason: failure.class };
  if (attempt >= maxAttempts) return { retry: false, reason: 'max_attempts' };
  const own = backoffMs(attempt, rng);
  const wait = Math.max(own, failure.retryAfterMs ?? 0);
  const at = nowMs + wait;
  return failure.class === 'quota'
    ? { retry: true, atMs: at, reason: 'quota', blockAccountUntilMs: at }
    : { retry: true, atMs: at, reason: failure.class };
}
