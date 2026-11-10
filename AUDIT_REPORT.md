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
| Android Kotlin compilation + JVM unit tests | **PASS** | GitHub Actions run 42759348402; `gradle :app:testDebugUnitTest --stacktrace` |
| Debug APK + instrumentation APK compilation | **PASS** | `gradle :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace`; both artifacts uploaded |
| Google Sign-In compile fix | **PASS** | Replaced invalid `TYPE_GOOGLE_ID_TOKEN` with `TYPE_GOOGLE_ID_TOKEN_CREDENTIAL` |
| Source archive integrity | **PASS** | Original v0.7 ZIP (148 entries) and audited ZIP (149 entries) extracted successfully |

## Audit changes already present in this package

1. `.github/workflows/build.yml` explicitly runs both TypeScript `.test.ts` files using Node's `--experimental-strip-types` flag. The prior plain `node --test` invocation could report success while discovering zero tests in the Node 22.16 environment.
2. `supabase/README.md` documents the same explicit test command.
3. `PROJECT_STATUS.md` and `CLAUDE_HANDOFF_V0_7.md` record the actual successful CI results and remaining blockers.
4. Fixed the Google ID token credential constant after the first CI compile identified the invalid reference.
5. Corrected Android SDK setup in CI; Android SDK 36 and Gradle 8.13 now install successfully.
6. Added a Room schema artifact upload step to CI so KSP exports can be retrieved and inspected.

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
| Room schema v1/v2 JSON committed and compared | **NOT DONE** | KSP runs in CI; schema artifact upload has been added for the next run. Genuine v0.6 schema v1 still needs export. |
| Room 1→2 instrumentation migration test | **NOT RUN** | Requires genuine Room-generated v1/v2 schema JSON and an emulator/device |
| Supabase migration 003 + quota smoke SQL | **NOT RUN** | No authorized Supabase project/database connection available |
| AI gateway deployment / authenticated request | **NOT RUN** | Requires Supabase project configuration and Anthropic secret |
| APK installation / device smoke test | **NOT RUN** | Requires a physical Android device or emulator |
| Signed release APK | **NOT BUILT** | No release keystore/configuration available |

Successful Android CI run: https://github.com/yaskong543-del/autoposter-android-1-/actions/runs/42759348402  
Debug APK artifact: `autoposter-v0.7-debug-apk` (GitHub Actions artifact, expires 2027-02-08).  
Instrumentation APK artifact: `autoposter-v0.7-android-test-apk`.

The available environment has Java 21 and Node 22.16, but the Android project targets JDK 17 / Gradle 8.13 / Android SDK 36. There is no Gradle wrapper or Git history/remote in the supplied archive.

## Required to finish

1. In a network-enabled Android build environment, provision JDK 17, Gradle 8.13, and Android SDK 36. Run `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest`; fix only observed failures while preserving existing behavior.
2. Build the v0.6 source using Room/KSP to export the genuine schema version 1 JSON; commit it. Build v0.7 to export version 2 JSON, then run `connectedDebugAndroidTest` on an emulator/device. Do not hand-author schema JSON.
3. With explicit owner authorization, apply Supabase migrations 001, 002, and 003 in order if not already applied; run `supabase/tests/003_ai_quota_smoke.sql` and confirm its pass output.
4. Set `ANTHROPIC_API_KEY` as a Supabase secret, deploy `ai-gateway`, and verify an authenticated request. Never place provider or service-role secrets in Android source or Git.
5. Build and install the debug APK on a device/emulator. A release-signed APK additionally requires the owner's keystore and release configuration.
6. Obtain the required YouTube, Meta/Instagram/Facebook, and TikTok platform approvals before representing publishing as production-ready.

**Android Kotlin unit tests and both APK compilation tasks are now verified successful. Room migration instrumentation, Supabase deployment/live AI, and physical-device installation remain unverified.**


---

## Latest CI evidence — 2026-11-10 (authoritative update)

Run [42778490167](https://github.com/yaskong543-del/autoposter-android-1-/actions/runs/42778490167) passed these steps: v0.6 Room schema v1 generation, Kotlin unit tests, debug APK assembly, instrumentation APK assembly, APK/schema artifact uploads, AI gateway tests, migration static check, and provider-secret scan. The downloaded Room schema artifact was inspected and contains genuine Room-generated version 1 and version 2 JSON files (11,294 bytes and 24,356 bytes). The debug APK artifact was downloaded and passed ZIP integrity validation; size 23,376,442 bytes.

At the last check, the Android emulator instrumentation step `:app:connectedDebugAndroidTest` remained in progress. Therefore the Room migration and Android instrumentation tests remain **PENDING / NOT VERIFIED**, not passed.

A follow-up workflow change adds automatic committing of generated v1/v2 Room schemas to the repository, using the workflow token with contents-write permission. Follow-up run [42779517052](https://github.com/yaskong543-del/autoposter-android-1-/actions/runs/42779517052) must be checked to confirm this commit actually succeeded.

Supabase migrations against a live project, quota SQL smoke test, AI gateway deployment/authenticated request, physical-phone install/smoke test, and release signing remain not done. They require external owner credentials, device access, or keystore material.


## Latest stabilization update — 2026-11-10
- **Room schema exports: PASS.** Genuine Room/KSP-generated schema v1 and v2 JSON files are committed under `app/schemas/com.example.autoposter.AppDatabase/`.
- **Kotlin unit tests and APK assembly: PASS** in CI.
- **AI gateway tests: PASS — 29/29.** Migration SQL static check and provider-secret scan also pass.
- **Room instrumentation: NOT VERIFIED.** Run [42787161149](https://github.com/yaskong543-del/autoposter-android-1-/actions/runs/42787161149) crashed in the emulator instrumentation process before any of 15 tests completed. The downloaded logcat showed no app-level fatal exception. A subsequent workflow run [42809071028](https://github.com/yaskong543-del/autoposter-android-1-/actions/runs/42809071028) uses API 30 Google ATD; its outcome is pending at the time of this update.
- **Supabase live migration/quota smoke test, deployed authenticated AI request, physical-device smoke test, and release signing: NOT DONE** for the external-access/device/keystore reasons already documented.



## Final CI verification — 2026-11-10
- **Full CI workflow: PASS.** [Run 42827817986](https://github.com/yaskong543-del/autoposter-android-1-/actions/runs/42827817986) completed successfully; all three jobs passed (Android unit tests/debug build, AI gateway tests/static checks, Room migration instrumentation).
- **Room instrumentation: PASS — 9/9.** Test XML records 9 tests, 0 failures, 0 errors, 0 skipped. The real `migrate1To2KeepsPostsAndAddsTables` migration test passed, including preservation of existing posts and verification of new tables.
- Run #42's test execution had already succeeded; only diagnostics publishing failed because generated `stableIds.txt` was falsely classified by the sanitizer. The diagnostics filter fix is verified by the fully successful run #43.
- **Not done / requires owner action:** live Supabase migration 003 and quota smoke test; authenticated AI gateway deployment/request with private owner credentials; physical-phone installation/smoke test; signed release build with owner keystore.
- No v0.8 or v1.0 implementation was performed as part of this v0.7 stabilization.
