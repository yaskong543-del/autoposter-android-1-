# AutoPoster v0.8 report: Automation and Cloud Engine

**Android package metadata:** versionName `0.8`, versionCode `8` (debug build still requires Android SDK/Gradle).

**Read this first: v0.8 is code-complete but is NOT accepted.** The acceptance gate (green CI, passing Android/instrumentation/SQL tests, live
provider evidence) could not be met from the environment this work was done in. Nothing below is claimed beyond what was actually run.

## 1. Phase 0 (v0.7 baseline): what was and was not verified

| Check | Result |
|---|---|
| Repository state | Read through the GitHub connector: `yaskong543-del/autoposter-android-1-`, `main` head `02f9607`. The handoff document in the repo says CI run #43 was green for that commit. **I did not independently re-verify CI** (no connector call returns check runs for a commit). |
| Execution plan file | `CLAUDE_V08_V10_EXECUTION_PLAN.md` exists in the repo and was read. It was not in the uploaded zip. |
| Writing to the repo | `create_branch` for `v0.8-engine` was **not approved**, so nothing was pushed and **no CI has run on any v0.8 change**. The work is delivered as a zip for you to commit. |
| Android debug/unit tests, Room schema export, Room 1->2 migration instrumentation, release build | **Not run** (no Android SDK, no network, no emulator in the authoring environment). |
| Static/secret checks | Run locally and passed: `check_no_ai_keys.sh`, `check_migration_sql.py` (10 statements valid/repeatable), new `check_no_web_secrets.sh`. |
| Supabase migrations 001-003 in the connected project | **Blocked.** The Supabase connector lists one organization and **zero projects**, so no project could be inspected, no SQL run, nothing deployed. I did not create a project. |
| `003_ai_quota_smoke.sql` through the connection | Blocked for the same reason. It is added to the new CI job, where it runs on a vanilla PostgreSQL with a Supabase shim (not the hosted project). |
| `ai-gateway` deployment and authenticated request | Blocked (no project). Its 29 Node tests pass locally. |
| Physical-device testing, signed release | **Not performed.** |

## 2. What was built (all in the zip)

* `supabase/migrations/004_v08_engine.sql`: seven-platform constraints; `plan_entitlements`; `admin_set_plan`/`expire_plans`/audit; encrypted `provider_accounts`; `oauth_states`;
  race-free profile/device limits (advisory locks); `register_device`/`revoke_device` (ends the auth session); durable `publish_jobs` with leases, SQL state machine,
  events, `enqueue/cancel/retry` client RPCs and `promote/recover/claim/apply_job_transition` worker RPCs; `notifications` + prefs; sync tables (`rev`, tombstones, conflicts, op log);
  private `publish-media` bucket; explicit function privileges; realtime publication. Forward-only and re-runnable; existing data untouched.
* Edge Functions: `oauth` (start/callback/disconnect), `publish-worker` (scheduler tick + maintenance), `sync`; shared modules for crypto, OAuth/PKCE, error classification,
  retry/backoff, job state machine, entitlements, sync merge, notification keys, identity lookup and the seven provider adapters.
* Android: `V08Policy.kt`, `CloudApi.kt`, `SyncEngine.kt`, `V08Workers.kt` (`UploadWorker`, `RetryWorker`, `NotificationWorker`, `CloudSyncWorker`, scheduler, notifier), small wiring in `AppContainer.kt`.
  The existing `PostWorker` and `SyncWorker` are unchanged.
* Web dashboard: cloud section (plan/limits, connect/disconnect accounts, schedule a video, cloud queue with cancel/retry, devices with revoke, notifications and preferences, sync conflicts).
* CI: `.github/workflows/backend-v08.yml` (Node tests, secret scan, SQL migrations on PostgreSQL 15, smoke tests, state-machine agreement check, parallel concurrency tests).
* Docs: `docs/API_SUPPORT_MATRIX.md`, updated `PROJECT_STATUS.md`, `ARCHITECTURE.md`, `FEATURE_PRESERVATION_CHECKLIST.md`, `supabase/README.md` (deploy + cron).

## 3. Exact test evidence

**Run and passed (locally, Node 22, `node --experimental-strip-types --test`): 88 of 88.**

| File | Tests |
|---|---|
| `_shared/core.test.ts` (crypto, OAuth/PKCE/callback, error mapping, backoff, state machine, entitlements, sync merge, notification keys) | 19 |
| `_shared/providers.test.ts` (7 adapters with a scripted fake `fetch`) | 10 |
| `_shared/scheduler.test.ts` (leases, retry, quota block, auth refresh, cancel, stale recovery, plan suspension, at-most-once, polling, deadline) | 13 |
| `oauth/handler.test.ts` | 6 |
| `publish-worker/handler.test.ts` | 5 |
| `sync/handler.test.ts` | 6 |
| `ai-gateway/*.test.ts` (existing v0.7) | 29 |

Provider tests use a fake `fetch`; they are labelled as mock tests and **do not prove a provider accepts the requests**.

**Written but NOT run:**

* `supabase/tests/004_v08_smoke.sql` (84 assertions on limits, downgrade, RLS, column privileges, job lifecycle, cancel/retry, notifications, sync tables) and `supabase/tests/concurrency.sh` (parallel sessions: profile limit, device limit, job claim, idempotent enqueue). They run in the `postgres` CI job. Expect to fix small SQL mistakes on the first run; this SQL has never executed.
* Kotlin: 26 new JVM unit tests (`V08PolicyTest` 8, `SyncEngineTest` 9, `CloudApiTest` 9, MockWebServer). The Kotlin has **never been compiled**; expect possible compile errors on the first CI run.
* `supabase/functions/*/index.ts` (Deno entry points) have never run under Deno.
* Web dashboard: the script parses; nothing was exercised in a browser.
* No instrumentation test was added for v0.8 (nothing in Room changed).

## 4. Requirement by requirement

| Requirement | State |
|---|---|
| Official publishing adapters, priority order | Implemented for all seven, mock-tested, **none live-tested**. Details and blockers: `docs/API_SUPPORT_MATRIX.md` |
| OAuth state/PKCE, encrypted tokens, refresh, revoke, ownership | Implemented + Node-tested; hosted flow untested |
| Normalised errors, 429/Retry-After, quota | Implemented + Node-tested |
| Durable cloud scheduler | Implemented; TS side tested, SQL side untested |
| Android WorkManager workers | `UploadWorker`, `RetryWorker`, `NotificationWorker`, `CloudSyncWorker` added; `PostWorker`/`SyncWorker` pre-existing. Not compiled |
| Cloud sync + devices | Implemented; drafts and post results sync; **posts are not created on a second device** (media binary is not transferred) |
| Server-authoritative FREE/PREMIUM | Implemented in SQL; Google Play billing intentionally absent |
| Web dashboard | Cloud section added; AI generation is not on the dashboard |
| Notifications | Server-deduplicated, preferences honoured, delivery state tracked; polling every 15 min (no FCM push: needs a Firebase project) |
| Acceptance gate | **Not met** |

## 5. Decisions you may want to change

1. **"Advanced automation"** was undefined in the brief. I defined it as the number of pending cloud-scheduled posts: FREE up to 10, PREMIUM unlimited (`plan_entitlements.max_pending_jobs`).
2. Entitlements map `free` -> FREE and `pro`/`agency` -> PREMIUM.
3. Over-limit handling after a downgrade: oldest accounts/devices stay active, newer ones are suspended (not deleted) and publishing/syncing for them fails with a clear message.
4. Instagram/Threads/Facebook receive a signed URL to the private video; TikTok/X/LinkedIn/YouTube are streamed in chunks by the worker.
5. The dev premium override is `select public.admin_set_plan(...)` from the SQL editor/service role only; it expires and is audited.

## 6. Known limitations and honest risks

* Scheduling is not exact-time: tick every minute, plus upload and platform processing time.
* Phone-to-cloud upload uses the standard Storage endpoint (no TUS): limited by the project's global file-size limit (50 MB on the free plan).
* If a worker dies after the provider accepted a post, the job fails as `AMBIGUOUS_PUBLISH` instead of retrying (by design, to avoid duplicates); the user must check the platform.
* Revoking a device deletes its `auth.sessions` row; this relies on GoTrue's schema and is verified only against the shim.
* Several provider facts are unverified (marked in the matrix): YouTube quota reset time and consent-screen rules, TikTok daily cap and chunk sizes, Facebook/Threads daily limits, X pricing and OAuth for media upload, LinkedIn userinfo.
* The legacy `CloudSync` (v0.7) still upserts `post_items` directly from the phone, bypassing the new merge logic; both paths coexist.

## 7. Owner checklist (nothing here can be done by the assistant)

1. Commit the zip contents to a branch (e.g. `v0.8-engine`) and open a PR; read the **Android build** and **AutoPoster v0.8 backend** workflows. Send me the failures and I will fix them.
2. Create/connect a Supabase project (the connector currently sees none), run migrations 001-004, `supabase/tests/003_ai_quota_smoke.sql` and `004_v08_smoke.sql`, deploy `ai-gateway`, `oauth`, `publish-worker`, `sync`, set secrets (`TOKEN_ENC_KEY`, `WORKER_SECRET`, `OAUTH_REDIRECT_URI`, `OAUTH_RESULT_URL`, per-provider client id/secret) and schedule the cron jobs from `supabase/README.md`. Never paste secrets into chat.
3. For live provider tests: register developer apps and request the approvals in the matrix (Google consent-screen verification, Meta App Review, TikTok audit, LinkedIn products, X API access), then connect an eligible test account per provider.
4. Run the app on a physical device (Wi-Fi vs cellular upload, reboot, notification permission).

v1.0 (billing, pricing, Play Store) has **not** been started.
