# AUTPOSTER v0.8 — AUTOMATION AND CLOUD ENGINE
## Claude implementation prompt

Upgrade the existing AutoPoster v0.7 repository in place.

**Read first:** `CLAUDE_V08_V10_EXECUTION_PLAN.md`, `PROJECT_STATUS.md`, `ARCHITECTURE.md`, `FEATURE_PRESERVATION_CHECKLIST.md`, `CLAUDE_HANDOFF_V0_7.md`, and `AUDIT_REPORT.md`. The execution contract is mandatory and defines evidence, security, and acceptance rules.

## Mission and boundaries

- Upgrade the existing application; do not rebuild, rewrite, or replace its architecture.
- Preserve all working v0.6/v0.7 functionality, data, package ID, Room schema/migrations, authentication, AI Creator Studio, media library, calendar, campaigns, offline behavior, and dashboard.
- Fix regressions before adding features. Make incremental commits and run tests after each logical change.
- This task is **v0.8 only**. Do not implement v1.0 billing, pricing, commercial analytics, or Play Store release work.
- Use official platform APIs only. Never simulate successful OAuth, uploads, publishing, or status responses.
- Clearly distinguish implemented, automated-tested, live-tested, approval-blocked, and not implemented.
- Never commit secrets or request that the owner paste secrets into chat.

## Phase 0 — verify v0.7 baseline

1. Inspect current branch, commit, working tree, and latest CI.
2. Confirm Android debug/unit tests, Room schema exports, Room 1→2 migration instrumentation test, static/secret checks, and release build status.
3. Verify Supabase migrations 001–003 in the connected project without blindly reapplying them.
4. Run `supabase/tests/003_ai_quota_smoke.sql` through the authorized Supabase connection.
5. Verify the `ai-gateway` deployment and make a real authenticated test request. Verify authorization, quota reservation, settlement, and failure release.
6. Record exact evidence and unresolved owner-side tasks. Do not claim physical-device testing or signed release readiness unless performed.

Do not proceed by assuming old chat claims are current; inspect repository and CI.

## 1. Official social publishing integrations

Create and maintain `docs/API_SUPPORT_MATRIX.md`.

Implement in this priority order:
1. YouTube
2. Instagram
3. TikTok
4. Facebook
5. Threads
6. X
7. LinkedIn

For each platform, research current official documentation and record API/version, eligible account types, OAuth flow and scopes, review/audit requirements, media restrictions, quota/rate limits, pricing constraints, upload and publishing capabilities, status endpoints, refresh/revocation behavior, and what has actually been tested.

Where officially supported, implement:
- OAuth with state/PKCE or the provider-required secure flow.
- Account identity and eligible-account validation.
- Encrypted server-side token storage; refresh and revocation/disconnect.
- Platform-specific media upload and publishing.
- Processing/publishing status checks.
- Normalized errors, retry classification, 429/Retry-After and quota handling.
- Secure callback validation and ownership checks.

Platform constraints must be respected:
- Instagram publishing is limited to eligible professional accounts and supported content types.
- TikTok public posting requires Content Posting API access and applicable audit approval; unaudited restrictions must be explicit.
- YouTube uploads and API calls consume quota; handle quota exhaustion honestly.
- Facebook/Threads use their applicable Meta permissions and app review.
- LinkedIn requires appropriate member/organization permissions.
- X availability, endpoints, and costs depend on the current API access tier.

Do not assume one platform's upload/publish lifecycle matches another. Use provider-specific adapters behind a shared interface. For unsupported or unapproved operations, provide a clear manual publishing handoff. Never label a handoff as an API publication.

## 2. Durable cloud publishing scheduler

Use Supabase Postgres as durable job storage and Supabase Edge Functions for server-side processing. Implement:
- Durable job state machine and scheduled execution.
- Atomic job claiming/leases with expiry and stale-lease recovery.
- Idempotency keys per intended provider publication.
- Bounded retries with exponential backoff/jitter; distinguish transient and permanent failures.
- Cancellation, status reconciliation, audit history, timezone/DST correctness.
- Server-side ownership, RLS, and least-privilege service access.
- Monitoring/logging that never exposes tokens or secrets.

Do not rely exclusively on Android WorkManager for scheduled publishing. Document realistic execution latency and provider limitations; do not promise exact-time delivery.

## 3. Android WorkManager

Implement/refine:
- `PostWorker`
- `UploadWorker`
- `SyncWorker`
- `RetryWorker`
- `NotificationWorker`

Support Wi-Fi/cellular policies, upload progress, cancellation, retry backoff, network recovery, process-death/reboot recovery, and success/failure/reminder/token-expiry/account-error/sync-problem notifications. Use unique work and idempotency to prevent duplicate operations. Respect Android background and notification permissions.

## 4. Cloud synchronization and device management

Implement bi-directional sync between Android and the existing web dashboard for:
- Drafts and posts.
- Scheduling and publishing results.
- Media metadata (binary transfer separately).
- Connected-account metadata (never expose raw provider tokens).
- Preferences and device metadata.

Use stable IDs, version/revision numbers, updated timestamps, offline outbox, tombstones, idempotency, deterministic conflict resolution, and visible handling for irreconcilable conflicts. Add device registration, revocation, session invalidation, and last-sync state. Prefer push/realtime or suitable background sync over permanent polling.

## 5. Server-authoritative FREE/PREMIUM entitlements

Enforce limits on the backend at every relevant operation, including concurrent requests:

**FREE**
- 2 connected social profiles.
- 1 registered device.
- 5,000 AI tokens monthly.
- Manual/on-open synchronization.
- Wi-Fi-only background uploads.

**PREMIUM**
- Unlimited connected profiles.
- Up to 5 devices.
- 500,000 AI tokens monthly.
- Automatic synchronization.
- Cellular uploads when enabled.
- Advanced automation.

Define downgrade/expiry behavior without deleting user data. Client-side flags are not authoritative. Do not implement Google Play billing in v0.8; that belongs to v1.0. Any development premium override must be secure, server-controlled, and documented.

## 6. Web dashboard

Upgrade the existing dashboard without replacing its foundations. Complete:
- Secure authentication and dashboard.
- Calendar and publishing queue.
- Media library.
- Connected accounts.
- AI tools.
- Device management.
- Settings.

Use the same backend authorization and RLS. Never expose service-role keys or provider secrets in browser code.

## 7. Notifications

Implement deduplicated notifications for successful/failed publishing, upcoming reminders, token expiry, account errors, sync issues, and upload progress. Respect user preferences and Android notification permissions. Track delivery state.

## 8. Testing and acceptance gate

Automated tests must cover:
- OAuth callback/state/PKCE validation, token lifecycle and encryption logic.
- Upload/publish state transitions and provider error mapping.
- Scheduler concurrency, leases, idempotency, retry/backoff, cancellation, stale recovery.
- Offline outbox, two-way sync, conflicts, tombstones, device revocation.
- Concurrent FREE/PREMIUM profile/device/token enforcement.
- Wi-Fi/cellular constraints, cancellation, reboot/process recovery, notification deduplication.
- RLS and cross-user/anonymous access denial.
- Dashboard flows and regressions of existing v0.6/v0.7 features.

Run live provider tests only where valid credentials, eligible accounts, and platform approvals exist. Clearly identify test/sandbox versus public production publishing. A compiling adapter is not proof of a working integration.

## v0.8 acceptance gate — STOP HERE

Do not start v1.0 until:
- CI is green.
- Android unit and instrumentation tests pass.
- Backend, scheduler, sync, and RLS tests pass.
- Existing v0.6/v0.7 regression coverage passes.
- `docs/API_SUPPORT_MATRIX.md` and all project documentation reflect actual state.
- Live provider tests are completed where credentials/approvals allow; all remaining providers are accurately marked blocked/unsupported/not tested.
- A final v0.8 report lists commit SHAs, CI links, exact test counts, live evidence, owner-required credentials/approvals, and known limitations.

If a credential or approval is missing, complete independent work and clearly report the exact blocker. Do not fabricate success or wait silently.
