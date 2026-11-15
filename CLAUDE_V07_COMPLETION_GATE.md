# MANDATORY PRE-FLIGHT: Finish and verify AutoPoster v0.7 before v0.8

This file is an addendum to `CLAUDE_HANDOFF_V0_7.md`, `PROJECT_STATUS.md`, `ARCHITECTURE.md`, and `FEATURE_PRESERVATION_CHECKLIST.md`. Read all four before changing anything.

## Scope and source control
- Work on v0.7 only until every feasible gate below is completed. Do not implement v0.8/v1.0 in this pass.
- Do not rebuild the app, remove features, replace working flows with placeholders, or rewrite architecture unnecessarily.
- Preserve the original user ZIP as an untouched backup. Work in a separate branch/copy.
- The audited handoff ZIP already includes the CI TypeScript-test discovery fix. Do not undo it.
- The user is operating from an Android phone. Give only short, exact, phone-friendly steps when user action is unavoidable. Never ask for secrets in chat.

## Known facts — do not misreport
- The 29 AI gateway tests passed with Node 22 using `node --experimental-strip-types --test core.test.ts handler.test.ts`.
- The migration SQL static/repeatability script, provider-secret scan, and 471 English/Hindi string-reference checks passed.
- These do NOT prove Android compilation, Room schema validity, Supabase deployment, or app/device behavior.
- No Gradle wrapper is present. `app/schemas/` is absent. No Android build, Kotlin test, instrumentation test, APK build/install, Supabase migration/deployment, or live AI request has been verified.
- `n8n/` is empty although the feature checklist records the failure-alert workflow as partial/missing. Do not claim it exists; either implement a real workflow with documentation/tests or explicitly defer it and update the checklist/status.
- OAuth is not implemented; platform tokens are manually supplied. Image publishing is not implemented. Platform approvals are still required. Do not claim production-ready publishing.
- Hindi translations for the new strings have not had native-speaker review. Signing out clears local brand voices and may lose unsynced edits. `CalendarPage.kt` is unused; `CalendarV2.kt` is active.
- Supabase project credentials and `ANTHROPIC_API_KEY` were not supplied. Never invent deployment results or request that the user paste secrets into chat.

## Required work, in order

### 1. Establish a reproducible Android build
- Use JDK 17, AGP 8.10.1, Gradle 8.13, Kotlin 2.1.20, compile/target SDK 36, and the project's declared dependency versions.
- Add the official Gradle Wrapper (matching Gradle 8.13) with wrapper JAR and scripts; verify wrapper checksum/distribution configuration. Do not rely on a globally installed Gradle in CI.
- Run `./gradlew :app:testDebugUnitTest --stacktrace` and `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace`.
- Fix only observed errors. Keep a log of each failure, change, and rerun. Do not suppress tests or weaken assertions to get green.

### 2. Room schema and migration proof
- Obtain the actual v0.6 source from `AutoPoster_AI_v0_6.zip`; build it with the same Room/KSP toolchain to export genuine schema version 1.
- Build v0.7 to export genuine schema version 2. Commit both generated JSON files under `app/schemas/`.
- Run the real `MigrationTestHelper` 1→2 migration test on an emulator/device. Verify existing v0.6 data survives and all new tables/columns are present.
- Never hand-write schema JSON or treat the existing SQLite static script as a substitute for Room's schema-hash test.

### 3. Fix checklist defects or explicitly document them
- Re-run every row in `FEATURE_PRESERVATION_CHECKLIST.md` before/after changes.
- Investigate the checklist's known issue that Supabase migrations 001/002 are not repeatable. Do not edit migrations that may already have been applied. If repair is required, add a new forward-only migration with safe guards and test it twice on a scratch database.
- Decide and document the empty `n8n/` failure-alert workflow: implement and test it, or mark it explicitly deferred in README, PROJECT_STATUS, and checklist. No fake workflow files.
- Verify the unused `CalendarPage.kt` is not referenced; remove only if proven safe and update docs, otherwise retain.
- Review local brand voice persistence/sign-out behavior and Hindi review status; do not claim fixes without tests.

### 4. Supabase and AI gateway (only with owner authorization/configuration)
- First inspect the target project's migration history. Apply only missing migrations in order; do not blindly rerun non-idempotent 001/002.
- Run `supabase/tests/003_ai_quota_smoke.sql` and save real output.
- Set `ANTHROPIC_API_KEY` as a Supabase secret (never in source, APK, logs, Git, or chat), deploy `ai-gateway`, then make an authenticated request using a real user JWT. Record sanitized status/result only.
- If credentials are unavailable, leave these items NOT DONE and provide exact private setup steps; do not claim a live test.

### 5. Device/APK verification
- Build debug APK after JVM tests pass; install it on a real Android device or emulator.
- Smoke test launch, sign-in/local mode, brand profile save, media import, draft save/edit, calendar, campaign review/queue, existing queue/publishing behavior, and AI generation if gateway is deployed.
- Build a signed release APK only if the owner supplies/configures a keystore securely. Never commit it. If no keystore, report release APK NOT DONE; debug APK is not a release APK.

## Acceptance and final report
Before beginning v0.8, deliver:
1. Updated `PROJECT_STATUS.md`, `AUDIT_REPORT.md`, and `FEATURE_PRESERVATION_CHECKLIST.md`.
2. Source ZIP and Git commit/branch details (only if actually created/pushed).
3. Exact commands and pass/fail output for every test/build.
4. APK path, SHA-256, size, and device/OS smoke-test results if actually produced.
5. A table using only: **Built and tested / Built but untested / Code only / Not done**.
6. A short list of remaining owner-only actions: Supabase authorization/secrets, platform approvals, device actions, and signing key.

Do not start the v0.8 prompt until all locally executable v0.7 gates pass. If an external gate is blocked by missing owner credentials/approval, state it precisely and ask the owner for the minimum required action; do not pretend v0.7 is fully production-ready.
