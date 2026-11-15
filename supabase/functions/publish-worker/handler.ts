// Wiring between the pure scheduler core and Supabase (RPC functions from migration 004). No secrets are logged here.
import { type JobRow, type JobStore, type NotificationReq, type TokenProvider, type TransitionReq, type WorkerDeps, runTick } from '../_shared/scheduler_core.ts';
import { type MediaSource } from '../_shared/providers/common.ts';
import { ADAPTERS } from '../_shared/providers/index.ts';
import { type AccountRow, type OAuthDeps, refreshAccount } from '../oauth/handler.ts';
import { OAUTH_SPECS, expiryState } from '../_shared/oauth.ts';
import { dayBucket, dedupeKey } from '../_shared/notify.ts';

export type Rpc = (fn: string, args?: Record<string, unknown>) => Promise<{ data: any; error: { message: string; code?: string } | null }>;

async function call<T>(rpc: Rpc, fn: string, args?: Record<string, unknown>): Promise<T> {
  const { data, error } = await rpc(fn, args);
  if (error) throw new Error(`${fn}: ${error.message}`);
  return data as T;
}
const toRow = (r: any): JobRow => ({
  id: r.id, owner: r.owner, account_id: r.account_id, platform: r.platform, post_id: r.post_id, status: r.status, attempts: r.attempts, max_attempts: r.max_attempts,
  payload: r.payload ?? {}, provider_state: r.provider_state ?? {}, media_path: r.media_path, cancel_requested: !!r.cancel_requested, lease_owner: r.lease_owner,
  account_external_id: r.account_external_id, account_meta: r.account_meta ?? {}, submitted_at_ms: r.submitted_at_ms != null ? Number(r.submitted_at_ms) : null,
});

export function makeJobStore(rpc: Rpc): JobStore {
  return {
    recoverStale: async () => Number(await call(rpc, 'recover_stale_jobs')),
    promote: async () => Number(await call(rpc, 'promote_due_jobs')),
    claim: async (w, limit, leaseMs) => ((await call<any[]>(rpc, 'claim_publish_jobs', { p_worker: w, p_limit: limit, p_lease_seconds: Math.ceil(leaseMs / 1000) })) ?? []).map(toRow),
    claimPolls: async (w, limit, leaseMs) => ((await call<any[]>(rpc, 'claim_poll_jobs', { p_worker: w, p_limit: limit, p_lease_seconds: Math.ceil(leaseMs / 1000) })) ?? []).map(toRow),
    transition: async (t: TransitionReq) => Boolean(await call(rpc, 'apply_job_transition', {
      p_id: t.id, p_worker: t.worker, p_to: t.to,
      p_patch: {
        ...(t.providerState ? { providerState: t.providerState } : {}), ...(t.error !== undefined ? { error: t.error } : {}),
        ...(t.nextAttemptAtMs !== undefined ? { nextAttemptAt: new Date(t.nextAttemptAtMs).toISOString() } : {}),
        ...(t.remoteId ? { remoteId: t.remoteId } : {}), ...(t.url ? { url: t.url } : {}), ...(t.incAttempts ? { incAttempts: true } : {}), ...(t.detail ? { detail: t.detail } : {}),
      },
    })),
    cancelRequested: async (id) => Boolean(await call(rpc, 'job_cancel_requested', { p_job: id })),
    accountAllowed: async (j) => Boolean(await call(rpc, 'job_account_allowed', { p_job: j.id })),
    blockAccount: async (id, until, code) => { await call(rpc, 'block_provider_account', { p_account: id, p_until: new Date(until).toISOString(), p_code: code }); },
    needsReconnect: async (id, code) => { await call(rpc, 'mark_account_needs_reconnect', { p_account: id, p_code: code }); },
    notify: async (n: NotificationReq) => { await call(rpc, 'create_notification', { p_owner: n.owner, p_kind: n.kind, p_dedupe: n.dedupeKey, p_title: n.title, p_body: n.body, p_ref: n.ref }); },
  };
}

export function makeTokenProvider(loadAccount: (id: string) => Promise<AccountRow | null>, oauth: OAuthDeps): TokenProvider {
  const run = async (job: JobRow, force: boolean): Promise<string | null> => {
    const a = await loadAccount(job.account_id);
    if (!a || a.owner !== job.owner || a.status === 'REVOKED') return null;
    return await refreshAccount(oauth, a, { force });
  };
  return {
    get: async (job) => { const t = await run(job, false); if (!t) throw new Error('no usable token'); return t; },
    refresh: (job) => run(job, true),
  };
}

/** Private-storage media: HEAD for size, Range GET for chunks, signed URL for platforms that fetch by URL. */
export async function openMedia(path: string, signUrl: (path: string, ttl: number) => Promise<string>, f: typeof fetch): Promise<MediaSource> {
  const url = await signUrl(path, 600);
  const head = await f(url, { method: 'HEAD' });
  const size = Number(head.headers.get('content-length'));
  if (!head.ok || !Number.isFinite(size) || size <= 0) throw new Error('media not found in storage');
  const contentType = head.headers.get('content-type') ?? 'video/mp4';
  return {
    size, contentType, name: path.split('/').pop() ?? 'video.mp4',
    signedUrl: (ttl) => signUrl(path, ttl),
    async readRange(start, endExclusive) {
      const u = await signUrl(path, 600);
      const r = await f(u, { headers: { range: `bytes=${start}-${endExclusive - 1}` } });
      if (r.status !== 206 && !(r.status === 200 && start === 0)) throw new Error(`range read failed (${r.status})`);
      const buf = new Uint8Array(await r.arrayBuffer());
      return buf.length > endExclusive - start ? buf.subarray(0, endExclusive - start) : buf;
    },
  };
}

export interface MaintDeps {
  rpc: Rpc; oauth: OAuthDeps; now(): number;
  listAccountsForSweep(withinMs: number): Promise<AccountRow[]>;
  listUpcomingJobs(withinMs: number): Promise<Array<{ id: string; owner: string; platform: string; run_at: string }>>;
  setAccountStatus(id: string, status: string): Promise<void>;
}
/** Periodic housekeeping: plan expiry, token refresh / expiry warnings, upcoming-post reminders. */
export async function runMaintenance(m: MaintDeps): Promise<{ plansExpired: number; refreshed: number; warned: number; reminders: number }> {
  const plansExpired = Number(await call(m.rpc, 'expire_plans'));
  let refreshed = 0, warned = 0, reminders = 0;
  for (const a of await m.listAccountsForSweep(7 * 86_400_000)) {
    const spec = OAUTH_SPECS[a.platform];
    const exp = a.token_expires_at ? Date.parse(a.token_expires_at) : null;
    const state = expiryState(exp, m.now());
    if (spec.refresh !== 'none') {
      const t = await refreshAccount(m.oauth, a, { force: true }).catch(() => null);
      if (t) { refreshed++; continue; }
    }
    if (state === 'expired') await m.setAccountStatus(a.id, 'EXPIRED');
    else if (state === 'expiring') await m.setAccountStatus(a.id, 'EXPIRING');
    if (state === 'expiring' || state === 'expired') {
      const bucket = dayBucket(m.now());
      await call(m.rpc, 'create_notification', {
        p_owner: a.owner, p_kind: 'token_expiring', p_dedupe: dedupeKey('token_expiring', a.id, bucket),
        p_title: state === 'expired' ? 'Reconnect your account' : 'Your connection expires soon',
        p_body: `${a.platform} needs to be reconnected${state === 'expiring' ? ' soon' : ''}.`, p_ref: a.id,
      });
      warned++;
    }
  }
  for (const j of await m.listUpcomingJobs(15 * 60_000)) {
    await call(m.rpc, 'create_notification', {
      p_owner: j.owner, p_kind: 'reminder', p_dedupe: dedupeKey('reminder', j.id), p_title: 'Post going out soon',
      p_body: `A ${j.platform} post is scheduled for ${new Date(j.run_at).toISOString().slice(11, 16)} UTC.`, p_ref: j.id,
    });
    reminders++;
  }
  return { plansExpired, refreshed, warned, reminders };
}

export interface TickConfig { workerId: string; limit?: number; budgetMs?: number; linkedinVersion?: string }
export async function tick(
  base: { rpc: Rpc; oauth: OAuthDeps; fetch: typeof fetch; loadAccount: (id: string) => Promise<AccountRow | null>; signUrl: (path: string, ttl: number) => Promise<string>; now(): number; rng?: () => number },
  c: TickConfig,
) {
  const media = new Map<string, MediaSource>();
  const store = makeJobStore(base.rpc);
  const deps: WorkerDeps = {
    store, tokens: makeTokenProvider(base.loadAccount, base.oauth), workerId: c.workerId,
    media: (job) => media.get(job.id)!, adapter: (p) => ADAPTERS[p], fetch: base.fetch, now: base.now, rng: base.rng ?? Math.random,
    budgetBytes: 24 * 1024 * 1024, leaseMs: 150_000, maxStepsPerRun: 6, config: { linkedinVersion: c.linkedinVersion ?? '202605' },
  };
  // Open media lazily per claimed job: wrap claim so media sources exist before processing.
  const origClaim = store.claim.bind(store);
  store.claim = async (...a) => {
    const ok: JobRow[] = [];
    for (const r of await origClaim(...a)) {
      try { media.set(r.id, await openMedia(r.media_path, base.signUrl, base.fetch)); ok.push(r); }
      catch {
        // The video is missing from storage: retrying can not help. Fail the job now instead of stranding the lease.
        await store.transition({ id: r.id, worker: c.workerId, to: 'permanent_failure', error: { code: 'MEDIA_MISSING', message: 'The video file is not available in cloud storage. Upload it again.', cls: 'permanent' }, detail: 'media missing' });
        await store.notify({ owner: r.owner, kind: 'publish_failure', dedupeKey: dedupeKey('publish_failure', r.id), title: 'Post not published', body: 'The video file is not available in cloud storage.', ref: r.id });
      }
    }
    return ok;
  };
  const origPoll = store.claimPolls.bind(store);
  store.claimPolls = async (...a) => { const rows = await origPoll(...a); for (const r of rows) media.set(r.id, { size: 0, contentType: '', name: '', signedUrl: async () => '', readRange: async () => new Uint8Array(0) }); return rows; };
  return await runTick(deps, { limit: c.limit ?? 5, deadlineMs: base.now() + (c.budgetMs ?? 100_000) });
}
