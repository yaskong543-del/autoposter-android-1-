// Notification de-duplication keys. The database enforces uniqueness on (owner, dedupe_key); the keys are built here.
export type NotificationKind =
  | 'publish_success' | 'publish_failure' | 'reminder' | 'token_expiring' | 'account_error' | 'sync_problem' | 'upload_progress';

export function dedupeKey(kind: NotificationKind, ref: string, bucket?: string): string {
  switch (kind) {
    case 'publish_success': case 'publish_failure': return `${kind}:${ref}`;               // once per job outcome
    case 'reminder': return `reminder:${ref}`;                                              // once per job
    case 'token_expiring': return `token_expiring:${ref}:${bucket ?? ''}`;                  // once per account per expiry day-bucket
    case 'account_error': return `account_error:${ref}:${bucket ?? ''}`;                    // once per account per error code
    case 'sync_problem': return `sync_problem:${ref}:${bucket ?? ''}`;                      // once per device per day
    case 'upload_progress': return `upload_progress:${ref}`;                                // single updating notification
  }
}
export const dayBucket = (ms: number): string => new Date(ms).toISOString().slice(0, 10);
