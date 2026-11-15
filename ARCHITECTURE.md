# ARCHITECTURE — AutoPoster AI 0.6

Single Gradle module `:app`, flat package `com.example.autoposter` (file names carry the layer).

```
UI (Compose, Material 3)             Theme.kt  Components.kt  Ui.kt
  Auth flow                          AuthScreens.kt  ── AuthViewModel.kt
  Main shell (tabs + drawer + NavHost)  Studio.kt  (+ MainActivity.kt: auth-gated root, deep links)
  Tabs                               HomePage  CreatePage  CalendarPage  QueuePage  InsightsPage
  Secondary                          AccountsPage  MediaLibraryPage  SettingsScreens.kt ── SettingsViewModel.kt
Domain / repositories                AuthRepository  Repositories.kt (Profile, Device, AccountMeta)  AccountHealth.kt
Publishing engine (v0.5, unchanged)  PostWorker  Scheduler  Uploaders  Draft  CommandParser  CaptionAI  Insights  BestTime
Local data                           AppDatabase.kt (Room: posts, profile, devices, account_meta)  UserPrefs.kt (DataStore)
                                     Stores.kt (AccountStore = encrypted tokens; Settings = worker-readable flags; PostStore)
Remote                               SupabaseHttp.kt (REST)  AuthModels.kt  GoogleSignIn.kt  CloudSync.kt (post metadata)
Wiring / background                  AppContainer.kt (manual DI, SyncCoordinator, SyncWorker, SyncScheduler, AutoPosterApp)
Cross-cutting                        AppLog.kt (redacting logger)  Validators.kt  JsonExt.kt
```

## Principles
- **Offline-first:** the in-memory `PostStore` StateFlow is the working copy; Room is the durable copy written behind and flushed by `PostWorker`.
  Profile, device and account-metadata edits are written to Room with a `dirty`/`synced` flag and pushed by `SyncWorker` (unique one-time +
  12-hour periodic, network-constrained). Failures retry; nothing blocks the UI.
- **Two separate identities:** the AutoPoster login (Supabase Auth, session in `EncryptedSharedPreferences` "auth_v1") and social-platform
  credentials (`AccountStore`, "accounts_v2"). Social tokens and video files never leave the device; only metadata is synced.
- **Session restore:** `AuthRepository.restore()` loads the saved session, refreshes if near expiry, signs out only on an auth rejection, and
  stays signed in when offline. Refresh is serialised with a mutex (refresh-token rotation).
- **Navigation:** `MainActivity` → `AppRoot` crossfades between Splash / Auth NavHost / Studio NavHost / Reset-password. Studio keeps the v0.5 campaign
  state and actions and hosts a NavHost with 5 tab routes (single back stack, state saved) and secondary routes with a back arrow.
  Deep link `autoposter://auth/callback#…` handles confirm-sign-up and reset-password e-mail links.
- **Theme:** `AutoPosterTheme(mode)` animates a full M3 `ColorScheme`; `LocalIsDark` lets non-composable-era helpers (`platformColor`, `statusColor`) choose accessible tones.
- **MVVM:** `AuthViewModel` (form actions, error mapping to string resources, one-shot events) and `SettingsViewModel` (prefs, profile, devices, load states).
  Created through `containerFactory`.
- **Security:** only the public URL and anon key are compiled in (`BuildConfig`, from env / `.env` / `local.properties`). RLS on every table, column-level
  grants protect `profiles.plan`, storage policies are folder-per-user, `delete_my_account()` is `SECURITY DEFINER` with `search_path=''` and execute granted to `authenticated` only.
  Logs go through `AppLog.redact`.

## Data model (Supabase, migrations 001–002)
`auth.users` 1─1 `profiles`; 1─* `devices` (unique owner+install_id); 1─* `social_accounts` (owner+id); 1─* `post_items` (owner+id; `account_label` is a soft reference).
All child tables cascade on user deletion. Private buckets `avatars`, `exports` (owner folder policies).

## Room schema v1
`posts` (queue), `profile`, `devices`, `account_meta`. No destructive migration fallback — the queue lives here; any schema change must ship a `Migration`.
Schemas export to `app/schemas/` at build time (commit them).

## Build
compileSdk/targetSdk 36, minSdk 26, JDK 17, AGP 8.10.1, Kotlin 2.1.20 + Compose compiler plugin, KSP for Room. Release builds enable R8/resource shrinking.


---

## v0.8 additions

```
Phone (Android)                         Supabase                                   Platforms
 UploadWorker --video--> Storage (private publish-media/<uid>/...)
 UploadWorker --RPC enqueue_publish_job--> publish_jobs (RLS, no client writes)
 CloudSyncWorker --/sync--> Edge Function sync --> post_items / sync_documents (rev, tombstones, conflicts)
 NotificationWorker <-- notifications (deduped, prefs honoured)
                                         pg_cron every minute --> Edge Function publish-worker
                                           recover_stale_jobs -> promote_due_jobs -> claim_publish_jobs (FOR UPDATE SKIP LOCKED + lease)
                                           provider adapter.advance()/status() -----------------------------> YouTube / Instagram / TikTok /
                                           apply_job_transition (lease + legal transition) + audit event          Facebook / Threads / X / LinkedIn
 Web dashboard --same JWT + RLS--> provider_accounts (metadata only), publish_jobs, devices, notifications, sync_conflicts
 OAuth: Edge Function oauth (start / callback / disconnect); tokens AES-256-GCM encrypted, readable by no client.
```

Key decisions: the database is the source of truth for limits (advisory lock per owner, so concurrent requests cannot exceed a plan); the job state
machine is enforced in SQL and mirrored in TypeScript (a CI step fails if they differ); clients never write job, token, plan, revocation or sync tables;
publishing is at-most-once per provider (checkpoint before the non-repeatable call; ambiguity is reported, never retried blindly).
Realistic latency: the worker starts a job at the first one-minute tick after `run_at` and large uploads/processing can take several ticks. No exact-time delivery is promised.
