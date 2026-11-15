# AutoPoster v0.8 audit — 2026-11-13

## Changes made
- Android `versionName` updated to `0.8` and `versionCode` to `8`.
- README identifies the package as v0.8 and describes v0.7 functionality as the preserved baseline.
- PROJECT_STATUS handoff header corrected so it no longer describes this archive as a v0.7-only handoff.
- v0.8 report records the Android package metadata.

## Checks run in this environment
- Edge Function Node tests: **88 passed, 0 failed**.
- `scripts/check_no_ai_keys.sh`: passed.
- `scripts/check_no_web_secrets.sh`: passed.
- `scripts/check_migration_sql.py`: passed (10 SQLite migration statements repeatable and matching the five v0.7 entities).

## Not verified / blocked
- Android Kotlin compilation and unit tests: not run.
- Debug APK: not built.
- PostgreSQL v0.8 migration, smoke and concurrency tests: not run.
- Physical-device tests and live provider publishing: not run.

The build environment has Java 21 but no Gradle executable, no Gradle wrapper in the archive, no Android SDK / `sdkmanager`, and no network DNS access to download build tools. The existing CI workflow specifies Java 17, Gradle 8.13 and Android SDK setup. An installable APK must be produced by that Android build environment; this audit does not claim an APK exists.
