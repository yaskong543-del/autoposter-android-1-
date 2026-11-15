# AutoPoster v0.8 CI verification

- Workflow run: https://github.com/yaskong543-del/autoposter-android-1-/actions/runs/43712683765
- Tested source commit: 8d7548d85b77896c5cf09efa2b917eba2589031d
- Android: 120/120 JVM unit tests passed; debug and unsigned release APKs built.
- Edge Functions: 88/88 Node tests passed; Deno entry points type-checked.
- Security: provider-secret and AI-key scans passed.
- Database: migrations 001-004 applied and migration 004 reapplied; SQL smoke, state-machine sync, and concurrency tests passed.
- Release limitation: release APK is unsigned. Live Supabase deployment and real OAuth-provider publishing still require project credentials and provider approval.
