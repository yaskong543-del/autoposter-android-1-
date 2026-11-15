// Orchestration of one publish job. Pure: every side effect goes through injected deps so it is unit-tested with fakes.
import { type JobStatus, canTransition } from './jobs.ts';
import { type Platform, type ProviderFailure, ProviderError } from './types.ts';
import { decideRetry } from './retry.ts';
import { type AdapterCtx, type MediaSource, type ProviderAdapter, type ProviderState, type PublishPayload } from './providers/common.ts';
import { dedupeKey, type NotificationKind } from './notify.ts';

export interface JobRow {
  id: string; owner: string; account_id: string; platform: Platform; post_id: string;
  status: JobStatus; attempts: number; max_attempts: number;
  payload: PublishPayload; provider_state: ProviderState; media_path: string;
  cancel_requested: boolean; lease_owner: string | null; account_external_id: string; account_meta: Record<string, unknown>;
  submitted_at_ms?: number | null;
}

export interface TransitionReq {
  id: string; worker: string; to: JobStatus;
  providerState?: ProviderState; error?: { code: string; message: string; cls: string } | null;
  nextAttemptAtMs?: number; remoteId?: string; url?: string; note?: string; incAttempts?: boolean; detail?: string;
}

export interface NotificationReq { owner: string; kind: NotificationKind; dedupeKey: string; title: string; body: string; ref: string }

export interface JobStore {
  /** Move leased/uploading jobs whose lease expired back to due (attempts+1) or to permanent_failure when exhausted. */
  recoverStale(nowMs: number): Promise<number>;
  /** scheduled/retryable_failure -> due when their time has come. */
  promote(nowMs: number): Promise<number>;
  claim(worker: string, limit: number, leaseMs: number, nowMs: number): Promise<JobRow[]>;
  claimPolls(worker: string, limit: number, leaseMs: number, nowMs: number): Promise<JobRow[]>;
  /** Only succeeds when the worker still owns an unexpired lease and the transition is legal. */
  transition(t: TransitionReq): Promise<boolean>;
  cancelRequested(id: string): Promise<boolean>;
  accountAllowed(job: JobRow): Promise<boolean>;
  blockAccount(accountId: string, untilMs: number, code: string): Promise<void>;
  needsReconnect(accountId: string, code: string): Promise<void>;
  notify(n: NotificationReq): Promise<void>;
}

export interface TokenProvider {
  get(job: JobRow): Promise<string>;
  /** Force a refresh (after a 401). Returns the new access token, or null when the account cannot be refreshed. */
  refresh(job: JobRow): Promise<string | null>;
}

export interface WorkerDeps {
  store: JobStore; tokens: TokenProvider; workerId: string;
  media(job: JobRow): MediaSource; adapter(p: Platform): ProviderAdapter;
  fetch: typeof fetch; now(): number; rng(): number;
  budgetBytes: number; leaseMs: number; maxStepsPerRun: number; config: { linkedinVersion: string };
}

export type Outcome = 'published' | 'submitted' | 'yielded' | 'retry_scheduled' | 'failed' | 'cancelled' | 'lost_lease';

/** After these phases the provider already holds the content: cancelling is no longer possible, re-publishing is forbidden. */
const COMMITTED = new Set(['publishing', 'finishing', 'posting', 'processing', 'done']);

function failureOf(e: unknown, platform: Platform): ProviderFailure {
  if (e instanceof ProviderError) return e.failure;
  return { platform, class: 'transient', code: 'INTERNAL', message: 'Unexpected problem while publishing.' };
}

async function notifyOutcome(d: WorkerDeps, job: JobRow, kind: 'publish_success' | 'publish_failure', title: string, body: string) {
  await d.store.notify({ owner: job.owner, kind, dedupeKey: dedupeKey(kind, job.id), title, body, ref: job.id });
}

export async function processJob(d: WorkerDeps, job: JobRow): Promise<Outcome> {
  const w = d.workerId;
  const t = async (req: Omit<TransitionReq, 'id' | 'worker'>): Promise<boolean> => d.store.transition({ id: job.id, worker: w, ...req });

  if (job.cancel_requested && !COMMITTED.has(String(job.provider_state?.phase ?? ''))) {
    return (await t({ to: 'cancelled', detail: 'cancel requested before start' })) ? 'cancelled' : 'lost_lease';
  }
  if (!(await d.store.accountAllowed(job))) {
    await t({ to: 'permanent_failure', error: { code: 'PLAN_SUSPENDED', message: 'This account is over the limit of your current plan. Remove an account or upgrade, then schedule again.', cls: 'plan' } });
    await notifyOutcome(d, job, 'publish_failure', 'Post not published', 'The account is over your plan limit.');
    return 'failed';
  }
  if (!(await t({ to: 'uploading', providerState: job.provider_state, detail: 'worker started' }))) return 'lost_lease';

  let state: ProviderState = job.provider_state ?? {};
  let token: string;
  try { token = await d.tokens.get(job); }
  catch { return fail(d, job, t, { platform: job.platform, class: 'auth', code: 'AUTH_INVALID', message: 'The connected account needs to be reconnected.' }); }

  let refreshed = false;
  const adapter = d.adapter(job.platform);
  const mkCtx = (): AdapterCtx => ({
    fetch: d.fetch, accessToken: token, accountExternalId: job.account_external_id, accountMeta: job.account_meta,
    payload: job.payload, media: d.media(job), budgetBytes: d.budgetBytes, nowMs: d.now(),
    checkpoint: async (s) => { state = s; await t({ to: 'uploading', providerState: s, detail: 'checkpoint' }); },
    config: d.config,
  });

  for (let step = 0; step < d.maxStepsPerRun; step++) {
    if (!COMMITTED.has(String(state.phase ?? '')) && (await d.store.cancelRequested(job.id))) {
      return (await t({ to: 'cancelled', providerState: state, detail: 'cancelled at checkpoint' })) ? 'cancelled' : 'lost_lease';
    }
    try {
      const r = await adapter.advance(mkCtx(), state);
      if (r.kind === 'published') {
        const ok = await t({ to: 'published', providerState: r.state, remoteId: r.remoteId, url: r.url });
        if (ok) await notifyOutcome(d, job, 'publish_success', 'Post published', `Published to ${job.platform}.`);
        return ok ? 'published' : 'lost_lease';
      }
      if (r.kind === 'submitted') {
        return (await t({ to: 'submitted', providerState: r.state, nextAttemptAtMs: d.now() + 30_000, detail: 'accepted by platform, waiting for processing' })) ? 'submitted' : 'lost_lease';
      }
      state = r.state;
      if (r.waitMs && r.waitMs > 0) {
        return (await t({ to: 'due', providerState: state, nextAttemptAtMs: d.now() + r.waitMs, detail: 'waiting on platform' })) ? 'yielded' : 'lost_lease';
      }
      // progress with no wait: persist and continue in this run
      if (!(await t({ to: 'uploading', providerState: state, detail: 'progress' }))) return 'lost_lease';
    } catch (e) {
      const failure = failureOf(e, job.platform);
      if (failure.class === 'auth' && !refreshed) {
        refreshed = true;
        const fresh = await d.tokens.refresh(job).catch(() => null);
        if (fresh) { token = fresh; continue; }
      }
      return fail(d, job, t, failure, state);
    }
  }
  // Step budget used: yield so the next tick resumes from the persisted state.
  return (await t({ to: 'due', providerState: state, nextAttemptAtMs: d.now(), detail: 'yield (step budget)' })) ? 'yielded' : 'lost_lease';
}

async function fail(
  d: WorkerDeps, job: JobRow, t: (r: Omit<TransitionReq, 'id' | 'worker'>) => Promise<boolean>, failure: ProviderFailure, state?: ProviderState,
): Promise<Outcome> {
  const attempt = job.attempts + 1;
  const dec = decideRetry(failure, attempt, job.max_attempts, d.now(), d.rng);
  const error = { code: failure.code, message: failure.message, cls: failure.class };
  if (dec.retry) {
    if (dec.blockAccountUntilMs) await d.store.blockAccount(job.account_id, dec.blockAccountUntilMs, failure.code);
    const ok = await t({ to: 'retryable_failure', providerState: state, error, nextAttemptAtMs: dec.atMs, incAttempts: true, detail: `retry:${dec.reason}` });
    return ok ? 'retry_scheduled' : 'lost_lease';
  }
  if (dec.needsReconnect) {
    await d.store.needsReconnect(job.account_id, failure.code);
    await d.store.notify({ owner: job.owner, kind: 'account_error', dedupeKey: dedupeKey('account_error', job.account_id, failure.code), title: 'Reconnect your account', body: `${job.platform} needs to be reconnected.`, ref: job.account_id });
  }
  const ok = await t({ to: 'permanent_failure', providerState: state, error, incAttempts: true, detail: `final:${dec.reason}` });
  if (ok) await notifyOutcome(d, job, 'publish_failure', 'Post not published', failure.message);
  return ok ? 'failed' : 'lost_lease';
}

/** Poll a submitted job (asynchronous processing on the platform). */
export async function pollJob(d: WorkerDeps, job: JobRow, maxWaitMs = 6 * 3_600_000): Promise<Outcome> {
  const w = d.workerId;
  const t = (req: Omit<TransitionReq, 'id' | 'worker'>) => d.store.transition({ id: job.id, worker: w, ...req });
  const adapter = d.adapter(job.platform);
  try {
    const token = await d.tokens.get(job);
    const ctx: AdapterCtx = {
      fetch: d.fetch, accessToken: token, accountExternalId: job.account_external_id, accountMeta: job.account_meta, payload: job.payload,
      media: d.media(job), budgetBytes: d.budgetBytes, nowMs: d.now(), checkpoint: async () => {}, config: d.config,
    };
    const s = await adapter.status(ctx, job.provider_state);
    if (s.kind === 'published') {
      const ok = await t({ to: 'published', providerState: s.state, remoteId: s.remoteId, url: s.url, note: s.note });
      if (ok) await notifyOutcome(d, job, 'publish_success', 'Post published', s.note ? `Published to ${job.platform} (${s.note}).` : `Published to ${job.platform}.`);
      return ok ? 'published' : 'lost_lease';
    }
    if (s.kind === 'failed') {
      const ok = await t({ to: 'permanent_failure', error: { code: s.failure.code, message: s.failure.message, cls: s.failure.class }, detail: 'platform reported failure' });
      if (ok) await notifyOutcome(d, job, 'publish_failure', 'Post not published', s.failure.message);
      return ok ? 'failed' : 'lost_lease';
    }
    if (job.submitted_at_ms != null && d.now() - job.submitted_at_ms > maxWaitMs) {
      const ok = await t({ to: 'permanent_failure', error: { code: 'STATUS_TIMEOUT', message: 'The platform did not confirm the post in time. Check the platform directly.', cls: 'permanent' }, detail: 'poll timeout' });
      if (ok) await notifyOutcome(d, job, 'publish_failure', 'Post not confirmed', 'The platform did not confirm the post. Check it directly.');
      return ok ? 'failed' : 'lost_lease';
    }
    return (await t({ to: 'submitted', providerState: s.state, nextAttemptAtMs: d.now() + (s.waitMs ?? 60_000), detail: 'still processing' })) ? 'submitted' : 'lost_lease';
  } catch (e) {
    const f = failureOf(e, job.platform);
    // Polling failures never fail the post: the content is already on the platform. Back off and retry the poll.
    if (f.class === 'permanent' || f.class === 'approval_blocked') {
      const ok = await t({ to: 'permanent_failure', error: { code: f.code, message: f.message, cls: f.class }, detail: 'poll failed permanently' });
      return ok ? 'failed' : 'lost_lease';
    }
    return (await t({ to: 'submitted', nextAttemptAtMs: d.now() + Math.max(60_000, f.retryAfterMs ?? 0), detail: `poll retry:${f.class}` })) ? 'submitted' : 'lost_lease';
  }
}

/** One scheduler tick: recover, promote, claim, run. Stops starting new jobs after `deadlineMs`. */
export async function runTick(d: WorkerDeps, opts: { limit: number; deadlineMs: number }): Promise<{ recovered: number; promoted: number; outcomes: Outcome[] }> {
  const now = d.now();
  const recovered = await d.store.recoverStale(now);
  const promoted = await d.store.promote(now);
  const outcomes: Outcome[] = [];
  const jobs = await d.store.claim(d.workerId, opts.limit, d.leaseMs, now);
  for (const j of jobs) {
    if (d.now() > opts.deadlineMs) { // return the claim unprocessed: due again immediately, lease released
      await d.store.transition({ id: j.id, worker: d.workerId, to: 'due', nextAttemptAtMs: d.now(), detail: 'tick deadline' });
      continue;
    }
    outcomes.push(await processJob(d, j));
  }
  const polls = await d.store.claimPolls(d.workerId, opts.limit, d.leaseMs, d.now());
  for (const j of polls) {
    if (d.now() > opts.deadlineMs) { await d.store.transition({ id: j.id, worker: d.workerId, to: 'submitted', nextAttemptAtMs: d.now(), detail: 'tick deadline' }); continue; }
    outcomes.push(await pollJob(d, j));
  }
  return { recovered, promoted, outcomes };
}

export { canTransition };
