# AutoPoster v0.7 — Handoff Notes

**Scope boundary:** This handoff is for completing and stabilizing **v0.7 only**. Do not start v0.8 or v1.0 in this task. The user has explicitly assigned those later versions to Claude separately.

## Source of truth
- Authoritative source supplied by the user: `AutoPoster_AI_v0_7.zip`.
- Preserve the existing v0.7 application and all working v0.6/v0.5 behavior. Do not rebuild from scratch or remove features to make the build pass.
- `FEATURE_PRESERVATION_CHECKLIST.md` is the regression baseline. Re-check it before and after any code change.
- The original uploaded ZIP remains the untouched backup. This handoff package contains the reviewed copy plus the audit and CI test-discovery fix.

## Work already performed in this reviewed copy
1. Inspected the complete v0.7 archive and read `PROJECT_STATUS.md` and `ARCHITECTURE.md`.
2. Compared the v0.7 Room database source against the supplied v0.6 archive.
3. Corrected `.github/workflows/build.yml` so the AI gateway `.test.ts` files are explicitly executed under Node 22 with `--experimental-strip-types`.
4. Corrected `GoogleSignIn.kt`: use `GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL` rather than the invalid `TYPE_GOOGLE_ID_TOKEN`. This was the compile failure from the first Android CI attempt.
5. Fixed Android SDK setup in GitHub Actions and confirmed Gradle 8.13 / Android SDK 36 installation.
6. Added a Room schema artifact upload step to CI so KSP-generated schema JSON can be retrieved and checked.
7. Updated `PROJECT_STATUS.md` with the actual successful CI results and remaining blockers.

## CI verification completed on 2026-11-10
- Kotlin unit-test task: **passed** (`gradle :app:testDebugUnitTest --stacktrace`).
- Android app Kotlin compilation: **passed**.
- Debug APK: **built and uploaded** (`autoposter-v0.7-debug-apk`).
- Instrumentation-test APK: **built and uploaded** (`autoposter-v0.7-android-test-apk`).
- AI gateway tests: **29/29 passed**.
- Migration static check and provider-secret scan: **passed**.
- Successful run: https://github.com/yaskong543-del/autoposter-android-1-/actions/runs/42759348402
- APK artifact retention expiry: 2027-02-08.

## Checks actually passed in the available environment
- AI gateway unit tests: **29/29 passed**.
- Room 1→2 migration static/repeatability check: passed (`python scripts/check_migration_sql.py`; 10 additive statements).
- Provider secret scan: passed (`bash scripts/check_no_ai_keys.sh`).
- Android string-resource reference check: 471 Kotlin references found in both English and Hindi resources.
- Original and reviewed ZIP archives extracted successfully.

These are not Android build or production deployment results.

## Must be completed before calling v0.7 ready
1. **Build and run Android tests in a network-enabled Android environment:** JDK 17, Gradle 8.13, Android SDK 36. Run:
   - `gradle :app:testDebugUnitTest --stacktrace`
   - `gradle :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace`
   - `gradle :app:connectedDebugAndroidTest --stacktrace` on an emulator/device.
   Fix only observed errors, preserving the feature checklist.
2. **Generate real Room schema exports.** Build the v0.6 source with Room/KSP to export schema version 1, then build v0.7 to export version 2. Commit the generated JSON under `app/schemas/`; do not hand-author schema files. Run the actual 1→2 `MigrationTestHelper` instrumentation test.
3. **Verify Supabase only with the owner's authorization.** Confirm whether migrations 001 and 002 are already applied. If not, apply 001, 002, then 003 in order. Run `supabase/tests/003_ai_quota_smoke.sql` and record its actual output.
4. **Deploy and verify the AI gateway only with owner-provided configuration:** set `ANTHROPIC_API_KEY` as a Supabase secret, deploy `ai-gateway`, and make an authenticated request. Never put secrets in source control, Android resources, build logs, or chat.
5. **Device smoke test:** install the debug APK and verify launch, sign-in/local mode, brand profile save, media import, draft save/edit, calendar, campaign review/queue, AI generation (once deployed), and existing publishing queue behavior. Record device/OS and outcomes.
6. Keep platform restrictions honest. Image publishing is not implemented; social OAuth is not implemented (accounts use manually supplied tokens); platform publishing requires approvals and live tests. Do not represent these as production-ready.

## Known blockers / user-provided items
- No Gradle wrapper, Gradle executable, Android SDK, `adb`, emulator, Deno, or Supabase CLI was available in the prior execution environment; outbound network/DNS was unavailable.
- No Supabase project authorization/configuration or Anthropic provider key was supplied. The owner must configure these privately; never ask them to paste secrets into chat.
- No release keystore was supplied. A signed release APK cannot be produced without the owner's signing setup. A debug APK is a separate deliverable.
- The private GitHub repository `yaskong543-del/autoposter-android` was not accessible through the connected GitHub integration (API returned 404). The user stated it is currently empty. Do not claim a push, commit, CI run, or repository upload happened.

## Product limits to preserve and disclose
- Images may be imported and included in drafts, but image publishing is not implemented.
- Existing publishing behavior is inherited from v0.5 and remains subject to platform API approval and live integration tests.
- Free AI quota: 5,000 tokens/month; premium plans: 500,000 tokens/month, subject to successful application of migration 003 and gateway deployment.
- Hindi translations for the 201 new strings have not had native-speaker review.
- Signing out clears local brand voices; unsynced edits may be lost.
- `CalendarPage.kt` is unused but retained; `CalendarV2.kt` is active.

## Reporting rule
For every remaining task, report one of: **built and tested**, **built but untested**, **code only**, or **not done**. Include exact commands and results. Never infer that a feature works merely because source code exists or a workflow is configured.


---

## Latest CI update — 2026-11-10 (supersedes stale verification statements above)

Run [42778490167](https://github.com/yaskong543-del/autoposter-android-1-/actions/runs/42778490167): genuine v0.6 Room schema v1 generation passed; Kotlin unit tests passed; debug and instrumentation APK assembly passed; APK and schema artifacts uploaded; AI gateway tests, SQL migration static check, and provider-secret scan passed. The Room schema artifact was downloaded and inspected: it contains Room-generated `1.json` and `2.json` (11,294 and 24,356 bytes respectively).

The emulator step `:app:connectedDebugAndroidTest` was still running at the last status check. It is **not yet a pass**. The newest debug APK from this run was downloaded and its ZIP integrity checked; it is a debug build, not a signed release.

The CI workflow was further changed to automatically commit the generated Room schema files to `app/schemas/com.example.autoposter.AppDatabase/` when changed. Follow-up run [42779517052](https://github.com/yaskong543-del/autoposter-android-1-/actions/runs/42779517052) is responsible for validating that commit step; verify it before claiming schema files are committed.

Remaining external steps: Supabase migration/deployment/quota smoke/live AI request require authorized Supabase project access and a privately configured Anthropic key; physical device smoke test requires installing on the owner's phone; release signing requires the owner's keystore. Do not start v0.8 or v1.0 as part of v0.7 stabilization.


## Latest CI troubleshooting — 2026-11-10
The genuine Room-generated v1/v2 schema JSON files are now committed under `app/schemas/com.example.autoposter.AppDatabase/`. Kotlin unit tests, both APK assembly tasks, AI gateway tests (29/29), migration static check, and provider-secret scan are green.

Room instrumentation is still not verified: run [42787161149](https://github.com/yaskong543-del/autoposter-android-1-/actions/runs/42787161149) failed because the emulator instrumentation process crashed with 15 tests discovered and 0 completed. Captured logcat contained no app-level fatal exception. The workflow shell script was fixed, and the next attempt was moved to a lighter API 30 Google ATD image. Check run [42809071028](https://github.com/yaskong543-del/autoposter-android-1-/actions/runs/42809071028) before making further changes.

Do not mark Room migration as passed unless the 1→2 MigrationTestHelper test and the database instrumentation tests actually complete successfully. Supabase live deployment, authenticated AI request, physical-device smoke test, and signed release remain external/not done.



## Verified final CI result — 2026-11-10
The latest complete workflow, [run 42827817986](https://github.com/yaskong543-del/autoposter-android-1-/actions/runs/42827817986), completed with **all three jobs successful**: Android unit tests/debug build, AI gateway tests/static checks, and Room migration instrumentation.

Room instrumentation is now genuinely verified: the result XML reports 9 tests, 0 failures, 0 errors, and 0 skipped. In particular, `migrate1To2KeepsPostsAndAddsTables` passed, proving the actual Room 1→2 migration preserves existing posts and creates the expected new tables. Run #42 had these tests pass but its diagnostics publishing step hit a sanitizer false positive on generated `stableIds.txt`; the filter was corrected, and run #43 is fully green.

Remaining owner-side v0.7 verification: apply/check Supabase migration 003 and quota smoke SQL in the live project; deploy and make an authenticated AI gateway request using owner-managed secrets; install and smoke-test on a physical Android phone; configure release signing with the owner's keystore. Never put private credentials in chat. Keep v0.8/v1.0 work out of this v0.7 handoff.



## Supabase access update — 2026-11-10
The user reports that Claude is now connected to the user's Supabase project. Claude may therefore be able to perform the previously pending live-project checks directly, subject to the permissions granted in that connection:
1. Inspect the linked project and confirm migrations 001, 002, and 003 are applied in order; do not reapply already-applied migrations.
2. Run `supabase/tests/003_ai_quota_smoke.sql` using an authorized SQL execution path and capture the exact result.
3. Verify the Edge Function deployment and make an authenticated AI gateway smoke request with a test account. Do not expose or copy private provider secrets into chat, GitHub, or client code.
4. Record evidence and any failures here and in `PROJECT_STATUS.md`. Do not claim live verification based only on a successful local test or deployment command.

This connection is Claude's; it does not automatically grant this ChatGPT session direct Supabase project access. The user still needs to have Claude execute and report the live checks unless a Supabase connector is separately connected here.
