# AutoPoster v0.8 → v1.0 — Claude execution contract

Repository: https://github.com/yaskong543-del/autoposter-android-1-
Default branch: main

This document replaces an unrealistic “finish everything at once” instruction with a gated, evidence-based delivery plan. Work in the existing repository. **Do not rebuild the app, discard working v0.6/v0.7 features, or begin v1.0 before v0.8 has passed its acceptance gate.** The user reports that Claude has been connected to their Supabase project; verify what that connection actually permits before relying on it.

## Non-negotiable working rules

1. Before changing code, read `PROJECT_STATUS.md`, `ARCHITECTURE.md`, `FEATURE_PRESERVATION_CHECKLIST.md`, `CLAUDE_HANDOFF_V0_7.md`, `AUDIT_REPORT.md`, and this plan. Inspect the current code and Git history. Treat repository state—not old chat messages—as authoritative.
2. Work on the existing `main` project and preserve its architecture, package/application ID, Room data, migrations, auth, offline queue, AI Creator Studio, media library, calendar, campaigns, and dashboard. No rewrite or destructive migration.
3. Start with a clean baseline and a small implementation plan. Make incremental commits. After each logical change run the relevant tests; do not accumulate a huge untested patch.
4. Never simulate OAuth, uploads, publishing, subscription purchases, analytics, sync, or successful API responses. A mock may be used only in clearly labelled automated tests.
5. Official APIs only. Respect each platform's permissions, review/audit status, media rules, quota, pricing, and rate limits. Where an operation is unavailable or approval is missing, provide an honest manual handoff and show the limitation in the UI.
6. Never put provider secrets, service-role keys, OAuth client secrets, signing keys, or billing credentials in Android code, web bundles, Git, logs, or chat. Use Supabase secrets/server-side storage where appropriate. Encrypt user OAuth tokens at rest and restrict access by owner/device.
7. Never claim a test passed unless you ran it and inspected its result. Report exact command, test count, result, commit, and CI run. Separate **implemented**, **automated-tested**, **live-tested**, **approval-blocked**, and **not implemented**.
8. Do not silently weaken tests, remove existing features, disable security, bypass RLS, or loosen platform restrictions to make CI green.
9. Ask the user only for genuinely owner-only inputs (OAuth app credentials/approvals, billing account, signing keystore, policy URLs, etc.). Explain exactly what is needed and where it must be entered securely. Do not ask the user to paste secrets into chat.
10. Keep documentation current in the same commits as behavior changes. Maintain `PROJECT_STATUS.md`, `ARCHITECTURE.md`, `AUDIT_REPORT.md`, `CLAUDE_HANDOFF_V0_7.md`, and add `docs/API_SUPPORT_MATRIX.md`.

## Stage 0 — close and freeze the v0.7 baseline first

The repository has a verified green CI run #43 (Android build/unit tests, AI gateway/static checks, and Room instrumentation); the actual Room instrumentation XML showed 9 tests, 0 failures. The Room 1→2 migration test passed.

A further CI change was committed to run `:app:assembleRelease` and upload an **unsigned** minified release APK. Verify the run for commit `02f9607f49347980c0598f92cce7706c13261e4f` before recording that release build as passed. Do not confuse an unsigned APK with a signed release.

Before v0.8 feature work:
- Check the latest GitHub Actions run and resolve any regression introduced by the release-build CI change.
- Check that Room schema v1/v2 files are committed and migrations remain additive.
- Use the available Supabase connection to inspect project identity and migration history. Confirm migrations 001, 002, and 003 are applied in order; never blindly reapply them.
- Run `supabase/tests/003_ai_quota_smoke.sql` using an authorized SQL execution method. Capture exact output and verify rollback/cleanup.
- Verify the `ai-gateway` Edge Function deployment and perform one authenticated test request using a controlled test account. Verify quota reservation, settlement on success, release on failure, and authorization. Do not expose secrets.
- Record Supabase and AI live-test evidence. If the connector cannot perform a required action, state the exact limitation and leave it explicitly pending.
- Physical-device smoke test and production signing may remain owner-side tasks; do not block all engineering on a keystore, but disclose these gaps.

Create a baseline tag or documented commit reference only after the baseline checks are stable. Then freeze v0.7 behavior except bug fixes required by v0.8.

## Stage 1 — v0.8 Automation and Cloud Engine

Deliver in the following sub-phases. Do not attempt all platforms in one unreviewed patch.

### 1A. Platform support audit and API support matrix

Create `docs/API_SUPPORT_MATRIX.md`. For YouTube, Instagram, TikTok, Facebook, Threads, X, and LinkedIn, document:
- Official API/product and current endpoint/version references.
- Supported account types and eligibility.
- OAuth flow, exact scopes/permissions, callback configuration, token lifetime/refresh/revocation behavior.
- Media formats, size/duration limits, upload protocol, publishing capabilities, processing/status endpoints.
- Quota/rate limits, pricing, app review/audit requirements, geographic/account restrictions.
- Operations implemented, automated-tested, live-tested, blocked by credentials/approval, and unsupported.
- Manual publishing handoff behavior for unsupported operations.

Prioritize YouTube, then Instagram, TikTok, Facebook, Threads, X, LinkedIn. Verify current official documentation before coding each platform; do not assume endpoint details or permissions from memory. Implement only what the official API currently supports.

For each provider implement, where officially supported: OAuth/PKCE or provider-required flow; secure token storage; account identity; refresh; media upload; publish; processing/status checks; normalized error handling; 429/Retry-After and quota handling; disconnect and token revocation. Never report “published” until the provider confirms it. Keep provider adapters isolated behind a common interface without pretending all platforms have identical capabilities.

### 1B. Durable server-side scheduler

Use Supabase Postgres as the durable source of truth and Edge Functions for processing. Implement:
- Durable job table and state machine (scheduled, due, leased, uploading, submitted, published, retryable failure, permanent failure, cancelled).
- Unique idempotency key per intended publication and provider-side idempotency where supported.
- Atomic claim/lease with expiry so concurrent workers cannot process the same job.
- Bounded retries with exponential backoff and jitter; classify permanent vs transient errors.
- Scheduled invocation/cron or another supported reliable trigger; document deployment and operational monitoring.
- Provider status reconciliation after asynchronous uploads.
- RLS and server-only privileges. Client must not be able to mark its own job as published or bypass quotas/ownership.
- Time-zone/DST correctness, cancellation semantics, stale lease recovery, and audit trail.

Do not claim exact-time guarantees beyond what Supabase scheduling and provider APIs can deliver. Document expected latency and operational limits.

### 1C. Android background work

Retain WorkManager for appropriate local tasks and offline resilience, not as the sole authority for cloud scheduled publishing. Implement or refactor `PostWorker`, `UploadWorker`, `SyncWorker`, `RetryWorker`, and `NotificationWorker` as appropriate:
- Wi-Fi-only vs cellular policy, matching backend entitlement/preferences.
- Upload progress, cancellation, retry/backoff, network recovery.
- Success/failure/reminder/token-expiry/account-error/sync-problem notifications.
- Unique work and idempotency to prevent duplicate enqueue/upload/publish.
- Respect Android background execution, battery restrictions, and notification permission.
- Persist enough state to recover after process death/reboot.

### 1D. Cloud synchronization and devices

Implement bi-directional sync between Android devices and the existing web dashboard for drafts, posts, schedule, media metadata, account metadata (never raw social tokens), preferences, and publishing results.
- Version/revision numbers, updated timestamps, stable IDs, offline outbox, tombstones, idempotency.
- Explicit conflict policy (field-aware where needed), deterministic tie-breaking, and user-visible conflict handling for irreconcilable edits.
- Device registration, secure device identity, revocation, session invalidation, and last-sync state.
- Prefer push/realtime or suitable background sync; avoid unnecessary permanent polling.
- Keep media binary transfer separate from metadata sync; enforce storage ownership and size limits.

### 1E. Server-authoritative plan gates

Implement backend-enforced entitlements. Required target limits:
- FREE: 2 connected profiles, 1 registered device, 5,000 AI tokens/month, manual/on-open sync, Wi-Fi-only background uploads.
- PREMIUM: unlimited connected profiles, up to 5 devices, 500,000 AI tokens/month, automatic sync, cellular uploads when enabled, advanced automation.

All checks must happen server-side at the point of mutation/job execution, not only in UI. Prevent races (e.g. two simultaneous account connections exceeding a limit). Define what happens on downgrade/expiration, preserve user data, and provide clear upgrade/limit errors. Do not add Google Play purchase handling in v0.8; v1.0 owns commercial billing. For pre-billing development, premium test entitlements must be controlled through a documented secure admin/test path, never a client-editable flag.

### 1F. Web dashboard and notifications

Upgrade the existing dashboard, preserving its current auth and design foundations. Add/complete dashboard, calendar, queue, media library, connected accounts, AI tools, device management, settings. Reuse backend authorization and RLS; web client must never hold service-role or provider secrets.

Notifications: successful/failed publishing, upcoming reminders, expiring tokens, account errors, sync issues, and upload progress. Avoid noisy duplicate notifications; store delivery state and respect user preferences and Android notification permissions.

### 1G. v0.8 tests and acceptance gate

Automated tests must cover:
- Provider adapter OAuth state/PKCE and callback validation; token encryption/refresh/revocation logic.
- Upload state transitions and provider error mapping.
- Scheduler lease concurrency, idempotency, retry classification/backoff, cancellation, stale lease recovery.
- Offline outbox, bidirectional sync, conflict resolution, tombstones, device revocation.
- Free/Premium profile/device/token gates under concurrent requests.
- Wi-Fi/cellular constraints, cancellation, reboot/process-death recovery, notification deduplication.
- RLS/authorization tests for cross-user and anonymous access.
- Dashboard flows and regression coverage for preserved v0.6/v0.7 features.

Live provider tests only when valid app credentials, eligible accounts, and approvals exist. Clearly label sandbox/test-account vs public production publishing. Do not claim all seven providers work because a shared interface compiles.

**v0.8 acceptance gate:** CI green; Android unit/instrumentation tests green; backend tests and RLS checks green; scheduler and sync integration tests green; docs/API matrix current; at least one end-to-end provider path genuinely live-tested if credentials/approval are available; every other provider accurately marked. User reviews the report before v1.0 starts.

## Stage 2 — v1.0 Production and Commercial Release

Do not start until the v0.8 acceptance gate is met and documented. Preserve all accepted v0.8 behavior.

### 2A. Google Play Billing

Implement genuine Play Billing subscriptions for Free, Premium Monthly, Premium Annual:
- Query actual localized product/base-plan/offer prices from Play; never hardcode prices.
- Purchase flow, purchase token handling, server-side Google Play Developer API verification, acknowledgement, restore/query purchases, renewal, cancellation, expiration, grace period, account hold, billing errors, upgrade/downgrade/proration.
- Reconcile entitlements server-side from verified purchase state and lifecycle notifications (RTDN or supported equivalent).
- Handle pending purchases and duplicate/replayed notifications idempotently.
- Client premium flags are display hints only; backend authorization uses verified entitlements.
- Test with Play license testers and internal testing track. Document owner-only Play Console/API setup. Never claim a live paid transaction without evidence.

### 2B. Premium upgrade experience

Create a polished, accessible pricing/upgrade screen showing the actual limits:
FREE: 2 profiles, 1 device, 5,000 AI tokens.
PREMIUM: unlimited profiles, 5 devices, 500,000 AI tokens, automatic cloud sync, cellular upload (when enabled), advanced AI/automation/analytics.

Show localized Play prices, billing period, renewal/cancellation terms, restore purchases, manage subscription link, and clear confirmation/error states. No dark patterns, fake discounts, misleading scarcity, or hidden recurring charges.

### 2C. Analytics and creator tools

Use only real data available from authorized APIs. Show views, likes, comments, shares, engagement, account/platform comparisons, top posts, trends, and date filters only when the provider supplies the metric and the user's scopes allow it. Display “unavailable” rather than fabricate.

Polish existing bulk campaigns, smart scheduling, brand kit, templates, repurposing, media library, team architecture, approval workflow, duplication, and notification centre. Prioritize reliability and usability over feature count. Team/approval features require a documented authorization and data model; do not imply they exist until implemented and tested.

### 2D. Security, privacy, reliability, performance

Audit and fix critical/high issues across authentication, OAuth state/redirect validation, sessions, RLS, storage, API authorization, encrypted tokens, secrets, rate limiting, validation, account deletion, audit logs, billing verification, and dependencies. Run dependency/security scanners and record findings and remediation. Test low-memory devices, slow/offline connections, process death, reboot, battery restrictions, expired tokens, large media, provider failures, concurrent scheduled posts, sync conflicts, and entitlement expiration.

Do not claim “secure” or “production-ready” based solely on a static scan. Document residual risks and external platform dependencies.

### 2E. Google Play release preparation

Verify current Play requirements at implementation time. Prepare target API compliance (target API 36 or the then-current Play requirement), signed Android App Bundle, release configuration, icon/splash, screenshots, feature graphic, privacy policy, terms, Data Safety answers, account deletion URL/flow, permissions declarations, billing compliance, store listing, and release checklist.

Signing must use owner-controlled keystore/secrets, never commit them. Prepare the AAB and checklist; do not claim Play approval or public availability until Google actually approves/publishes it.

### 2F. v1.0 final test gate and deliverables

Run and report: release build, unit tests, integration tests, UI tests where practical, Android lint, static analysis, database migration/security tests, dependency checks, backend/RLS tests, billing license tests, and provider live tests where approved.

Deliver source, migrations, Edge Functions, dashboard, docs, test evidence, signed AAB (only if owner signing material is configured), deployment instructions, external configuration checklist, known limitations, and a clear report:
1. What works.
2. What was automated-tested and live-tested.
3. Which integrations need platform approval.
4. Which items require owner credentials/configuration.
5. Whether an AAB is built/signed and what Play readiness remains.
6. Known limitations and blockers before public launch.

## Required progress reporting

At the start of each session, state the current branch/commit and the next concrete task. At the end of each work batch, report:
- Files changed and commit SHA.
- Tests actually run and exact result.
- CI run URL/status.
- Live integrations actually verified.
- Remaining blockers and who must act (Claude vs owner vs platform approval).

If blocked, do not wait silently or claim completion. Finish the work that can be completed without owner credentials, document the exact missing input, and continue with independent tasks. Never promise that the entire multi-platform commercial release can be completed without external approvals, credentials, quotas, or owner-side Play Console setup.
