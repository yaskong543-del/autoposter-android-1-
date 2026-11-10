# AutoPoster AI — Feature-Preservation Checklist (baseline: v0.6)

Built by inspecting `AutoPoster_AI_v0_6.zip` (docs, build config, manifest, migrations, Room entities, file layout).
**Status key:** ✅ implemented per code/docs · ⚠️ partial · ❌ not implemented · 🔒 needs outside credentials/approval
**Important:** the project has never been compiled or run (stated in `PROJECT_STATUS.md`). Every ✅ below means "code exists", not "verified working".

Rule for every future version: before merging, tick every item below as *still present and still working*. If an item is removed or changed on purpose, say so in the release notes.

---

## A. Publishing engine (v0.3–v0.5 core — must not regress)
| # | Feature | Where | Status |
|---|---|---|---|
| A1 | Multi-account (YouTube, Instagram, TikTok), switch active account, rename/edit/remove (removal cancels that account's queue) | `AccountsPage`, `Stores` (AccountStore) | ✅ |
| A2 | Account picker lists only added accounts, grouped by platform | `CreatePage`, `AccountsPage` | ✅ |
| A3 | Bulk modes: one-by-one switching account · random clip then switch · all clips in one account then next · same clip on every account | `CreatePage`, `Draft` | ✅ |
| A4 | Gallery album as clip source; hand-pick up to 100; folder import up to 500 | `GalleryRepository`, `MediaLibraryPage`, `CreatePage` | ✅ |
| A5 | Shared caption/hashtags + per-platform override, live limits | `CreatePage`, `Validators` | ✅ |
| A6 | Caption variants (`one \| two \| three`) rotating across videos | `Draft` | ✅ |
| A7 | Sound credit appended to caption as text (platform APIs cannot attach catalog music) | `Draft`, `Uploaders` | ⚠️ by design |
| A8 | Per-account start time and gap; "Suggest start times" | `Scheduler`, `BestTime` | ✅ |
| A9 | Videos copied to app storage on queue (survive restart / permission loss) | `Media` | ✅ |
| A10 | Duplicate detection (size + SHA-256 of first 1 MB) | `Media`, `Draft` | ✅ approximate |
| A11 | Upload to YouTube (resumable), Instagram (Graph API Reels), TikTok (Content Posting API) | `Uploaders` | ✅ code · 🔒 platform approval |
| A12 | Retry with back-off (3 attempts), retry item / retry all failed, pause/resume, Wi-Fi-only | `PostWorker`, `Scheduler`, `Settings` | ✅ |
| A13 | Live per-video and per-account progress | `QueuePage`, `PostWorker` | ✅ |
| A14 | Quick-publish text/voice command → draft for review (platforms, hashtags, `at 6pm`, `tomorrow at 9`, `in 2 hours`, `every 5 minutes`) | `CommandParser` (regression test exists) | ✅ rule-based |
| A15 | Caption/hashtag generator, 3 tones, platform limits | `CaptionAI` | ✅ rule-based |
| A16 | Result notifications (one summary per run) | `Alerts`, `PostWorker` | ✅ |
| A17 | Insights: per day, success rate, by platform/account, YouTube views/likes/comments | `Insights`, `InsightsPage` | ⚠️ YouTube only |
| A18 | Calendar of scheduled posts, day filter | `CalendarPage` | ✅ |
| A19 | On-device AI model (`LlmEngine`) | — | ❌ placeholder only |
| A20 | Phone-only operation (no data-centre AI) | whole app | ✅ no server AI used |

## B. Cloud, auth and sync (v0.5–v0.6)
| # | Feature | Where | Status |
|---|---|---|---|
| B1 | Optional cloud backup of queue metadata (no tokens, no video) | `CloudSync`, `CloudCard`, migration 001 | ✅ 🔒 Supabase project |
| B2 | Web dashboard (login, stats, chart, calendar, search, live updates) | `web/index.html` | ✅ |
| B3 | n8n failure-alert workflow | `n8n/` | ⚠️ folder is empty in this zip — README referenced but missing |
| B4 | Supabase sign-up, sign-in, e-mail verify (code/link), forgot/reset password, logout, sign-out-everywhere, session restore, account deletion | `AuthRepository`, `AuthViewModel`, `AuthScreens` | ✅ (15 MockWebServer tests written, not run) |
| B5 | Google Sign-In → Supabase `id_token` exchange | `GoogleSignIn` | ✅ 🔒 Google Web client ID |
| B6 | Profile edit, device list/registration, account-metadata sync (Room-first, WorkManager push) | `Repositories`, `AppContainer` (SyncWorker) | ✅ |
| B7 | Redacting logger (tokens, JWTs, passwords, e-mails) | `AppLog` (tested) | ✅ |
| B8 | Deep link `autoposter://auth/callback` | Manifest, `MainActivity` | ✅ |

## C. UI / platform (v0.6)
| # | Feature | Status |
|---|---|---|
| C1 | Material 3, dark / light / system theme (DataStore) | ✅ |
| C2 | 5 tabs (Home, Create, Calendar, Queue, Insights) + drawer (Accounts, Media library, Settings, Subscription) | ✅ |
| C3 | English + Hindi (legacy Create page and Quick-publish card still English; Hindi unreviewed) | ⚠️ |
| C4 | Settings: profile, appearance, language, notifications, security, devices, privacy, help/diagnostics | ✅ |
| C5 | Room (posts, profile, devices, account_meta) + DataStore + WorkManager; manual DI | ✅ |
| C6 | minSdk 26, target/compile SDK 36, R8 + resource shrinking on release | ✅ config only |
| C7 | Subscription screen — informational, no billing | ⚠️ |
| C8 | Avatar / export bucket upload | ❌ buckets exist, app doesn't use them |
| C9 | Biometric / app lock | ❌ |

## D. Known gaps found during inspection (fix, don't hide)
1. **Social OAuth is not implemented.** Accounts are connected by pasting tokens. Instagram/TikTok tokens never refresh. YouTube refresh needs a pasted client ID.
2. **Migration 002 is not repeatable.** Plain `create table`, `create trigger`, `create policy`; 001 also runs `alter publication … add table`. A second run fails. 001/002 were never executed.
3. **No release signing config** in `app/build.gradle.kts`, and **CI only builds the debug APK**. No release APK is produced anywhere.
4. **`app/schemas/` does not exist**, though the Room schema export is configured and instrumented tests read it. Room is at v1, so no `Migration` yet, but export must be committed before the first schema change.
5. Library versions were "chosen from memory" and not resolved by Gradle. No Gradle wrapper is included.
6. `n8n/` directory is empty.

---

## Acceptance rules for the next version (the six requirements)

**1. Inspect first, preserve always.** Before changing code, re-run this inventory against the new zip. Deliver an updated copy of this file with a before/after status for every row. Nothing in A–C may silently disappear.

**2. Real implementations only.** A feature counts as done only if the code path runs end to end (UI → logic → storage/network → visible result) and has a test or a documented manual test. A screen with no working backing logic must be marked ⚠️ or ❌, and its button disabled with an explanation.

**3. OAuth through official platform APIs only.**
- YouTube: Google OAuth 2.0 (Credential Manager / AppAuth, PKCE), scope `youtube.upload`, YouTube Data API v3.
- Instagram: Instagram API / Facebook Login for Business (Graph API), Reels publishing via the content publishing endpoints.
- TikTok: Login Kit + Content Posting API.
- No scraping, no password collection, no unofficial/private endpoints, no browser automation, no pasted tokens in production. Client secrets never ship in the APK (use PKCE or a backend exchange).
- Where platform approval is required (YouTube audit, Meta app review, TikTok approval), mark the feature 🔒 and state what happens without approval (e.g. private-only posts).

**4. Safe, repeatable migrations.**
- Postgres: `create … if not exists`, `drop policy/trigger if exists` before create, guarded `alter publication` (check `pg_publication_tables`), additive changes only, each file wrapped so a re-run is a no-op. Never edit an applied migration; add a new numbered file.
- Room: bump version, add an explicit `Migration`, no destructive fallback, commit exported schema JSON, cover each migration with a `MigrationTestHelper` test.
- Run the migrations twice against a scratch database and report the result.

**5. A release APK, not just a compile.** Deliver a signed `app-release.apk` built with `assembleRelease` (R8 on), after `testDebugUnitTest` passes. State the signing method (a keystore the user owns, kept out of the repo), version code/name, file size and SHA-256. Smoke-test the *release* build: launch, sign-in, queue a post, and confirm R8 didn't break reflection/JSON paths (check `proguard-rules.pro`). If it cannot be built in the working environment, say so plainly and deliver the CI workflow that builds it.

**6. Honest reporting.** Every delivery ends with a status table: Built & tested / Built, untested / Code only / Not done, plus what was actually run (commands and results) versus only reviewed. "Compiles" is never reported as "works". Anything needing user credentials, platform approval or a device test is listed as such.


---
## v0.7 additions (status: code written, not compiled — see PROJECT_STATUS.md)
| Feature | v0.6 → v0.7 |
|---|---|
| Create tab (bulk modes, quick publish, v0.5 rule-based AI dialog) | Preserved; a card to the new composer sits above it |
| Media library page (albums, import folder, Pick for Create) | Preserved as the "Gallery folders" tab |
| Calendar tab | Replaced by month/week/day + filters + reschedule (`CalendarV2`); old file unused |
| Home tab | Preserved; dashboard + composer/campaign tiles added |
| AI composer, brand voice, bulk campaign | New |
| AI gateway + quota (Postgres) | New (SQL unexecuted, gateway tests pass) |
| Auth, accounts, settings, queue, uploaders, scheduler | Unchanged |
