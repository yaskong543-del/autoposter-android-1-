# AutoPoster v0.7 — Verification Report

Date: 2026-11-10  
Source: `AutoPoster_AI_v0_7.zip` (148 ZIP entries)  
Reviewed package: `AutoPoster_v0_7_audited.zip` (149 ZIP entries)

## Scope

Inspected the complete v0.7 archive inventory and read `PROJECT_STATUS.md` and `ARCHITECTURE.md`. Reviewed Gradle configuration, manifest, Room entities and 1→2 migration, unit and instrumentation test sources, Supabase migrations and quota smoke test, Edge Function source and tests, CI workflow, setup documentation, and the v0.6 source archive for the Room migration baseline. Compared the original v0.7 ZIP with the previously audited ZIP.

## Checks run in this session

| Check | Result | Evidence / command |
|---|---|---|
| AI gateway unit tests | **PASS — 29/29** | `node --experimental-strip-types --test supabase/functions/ai-gateway/core.test.ts supabase/functions/ai-gateway/handler.test.ts` |
| Room migration static check | **PASS** | `python scripts/check_migration_sql.py` — 10 additive statements |
| Provider-secret scan | **PASS** | `bash scripts/check_no_ai_keys.sh` |
| Android string resources | **PASS** | 471 Kotlin `R.string` references; all exist in both EN and HI resources |
| Source archive integrity | **PASS** | Original v0.7 ZIP (148 entries) and audited ZIP (149 entries) extracted successfully |

## Audit changes already present in this package

1. `.github/workflows/build.yml` explicitly runs both TypeScript `.test.ts` files using Node's `--experimental-strip-types` flag. The prior plain `node --test` invocation could report success while discovering zero tests in the Node 22.16 environment.
2. `supabase/README.md` documents the same explicit test command.
3. `PROJECT_STATUS.md` records the static checks and outstanding verification blockers.

## Product limitations retained intentionally

- Images can be imported and included in drafts, but cannot be published because no image uploader is implemented. UI copy should continue to disclose this.
- Publishing behavior remains the v0.5 implementation and is subject to each platform's app/API approval. Do not claim public publishing is enabled without the relevant approvals and live integration tests.
- The Free AI quota is 5,000 tokens/month. At the stated approximate 400–900 tokens per call, that is roughly 6–12 calls/month. Changing this requires a deliberate quota/migration decision.
- Hindi translations for the 201 new strings have not had native-speaker review.
- Signing out clears local brand voices; unsynced edits can be lost.
- `CalendarPage.kt` is unused but intentionally retained; `CalendarV2.kt` is the active replacement.

## Not run / not verified

| Check | Status | Reason |
|---|---|---|
| Android Gradle compile and JVM tests | **NOT RUN** | No Gradle executable or wrapper, Android SDK, or network access to provision them |
| Room KSP schema export | **NOT RUN** | Requires actual Gradle/KSP build; `app/schemas` is absent |
| Room 1→2 instrumentation migration test | **NOT RUN** | Requires genuine Room-generated v1/v2 schema JSON and emulator/device |
| Supabase migration 003 + quota smoke SQL | **NOT RUN** | No authorized Supabase project/database connection supplied |
| AI gateway deployment / authenticated request | **NOT RUN** | No Supabase CLI/Deno, project credentials, or Anthropic API key supplied |
| APK build / install | **NOT RUN** | No Android SDK, Gradle, or adb |
| GitHub push / CI | **NOT RUN** | The connected GitHub integration does not currently expose `yaskong543-del/autoposter-android`; the owner has shown the repository is private and empty |

The available environment has Java 21 and Node 22.16, but the Android project targets JDK 17 / Gradle 8.13 / Android SDK 36. There is no Gradle wrapper or Git history/remote in the supplied archive.

## Required to finish

1. In a network-enabled Android build environment, provision JDK 17, Gradle 8.13, and Android SDK 36. Run `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest`; fix only observed failures while preserving existing behavior.
2. Build the v0.6 source using Room/KSP to export the genuine schema version 1 JSON; commit it. Build v0.7 to export version 2 JSON, then run `connectedDebugAndroidTest` on an emulator/device. Do not hand-author schema JSON.
3. With explicit owner authorization, apply Supabase migrations 001, 002, and 003 in order if not already applied; run `supabase/tests/003_ai_quota_smoke.sql` and confirm its pass output.
4. Set `ANTHROPIC_API_KEY` as a Supabase secret, deploy `ai-gateway`, and verify an authenticated request. Never place provider or service-role secrets in Android source or Git.
5. Build and install the debug APK on a device/emulator. A release-signed APK additionally requires the owner's keystore and release configuration.
6. Obtain the required YouTube, Meta/Instagram/Facebook, and TikTok platform approvals before representing publishing as production-ready.

**No Android build, Room instrumentation, Supabase migration/deployment, authenticated production request, APK build, or device install is claimed as successful.**
