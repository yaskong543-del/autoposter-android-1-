// Publish-job state machine. Mirrors public.publish_job_transition_allowed() in migration 004 (kept in sync by a test).
export type JobStatus =
  | 'scheduled' | 'due' | 'leased' | 'uploading' | 'submitted'
  | 'published' | 'retryable_failure' | 'permanent_failure' | 'cancelled';

export const JOB_STATUSES: readonly JobStatus[] = [
  'scheduled', 'due', 'leased', 'uploading', 'submitted', 'published', 'retryable_failure', 'permanent_failure', 'cancelled',
];
export const TERMINAL: readonly JobStatus[] = ['published', 'permanent_failure', 'cancelled'];

export const TRANSITIONS: Record<JobStatus, readonly JobStatus[]> = {
  scheduled: ['due', 'cancelled'],
  due: ['leased', 'cancelled', 'permanent_failure'],
  leased: ['uploading', 'due', 'retryable_failure', 'permanent_failure', 'cancelled'],
  uploading: ['uploading', 'submitted', 'published', 'due', 'retryable_failure', 'permanent_failure', 'cancelled'],
  submitted: ['submitted', 'published', 'permanent_failure'],
  retryable_failure: ['due', 'permanent_failure', 'cancelled'],
  published: [], permanent_failure: [], cancelled: [],
};

export function canTransition(from: JobStatus, to: JobStatus): boolean {
  return TRANSITIONS[from]?.includes(to) ?? false;
}
export function isTerminal(s: JobStatus): boolean { return TERMINAL.includes(s); }

/**
 * Cancellation semantics:
 *  - scheduled/due/retryable_failure: cancelled immediately.
 *  - leased/uploading: `cancel_requested` is set; the worker stops at its next checkpoint if the provider has not accepted the media yet.
 *  - submitted/published: too late (the platform already has it); the UI must say so and offer "delete on the platform" manually.
 */
export type CancelOutcome = 'cancelled' | 'cancel_requested' | 'too_late' | 'already_final';
export function cancelOutcome(s: JobStatus): CancelOutcome {
  if (s === 'scheduled' || s === 'due' || s === 'retryable_failure') return 'cancelled';
  if (s === 'leased' || s === 'uploading') return 'cancel_requested';
  if (s === 'submitted') return 'too_late';
  return 'already_final';
}

/** One intended provider publication = one (owner, post, account). The key is stable across retries and device resyncs. */
export async function idempotencyKey(owner: string, postId: string, accountId: string): Promise<string> {
  const h = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`v1|${owner}|${postId}|${accountId}`)));
  return Array.from(h, (b) => b.toString(16).padStart(2, '0')).join('');
}
