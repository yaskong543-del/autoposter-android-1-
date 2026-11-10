# AutoPoster AI — v0.7 (AI Creator Studio)

See `PROJECT_STATUS.md` for what is genuinely functional, what still needs credentials, and what was not verified.
See `ARCHITECTURE.md` for the layers. Backend setup: `supabase/README.md`. Config names: `.env.example`.

**New in 0.7:** AI composer (server-side AI gateway, brand voice, token quotas), media library, month/week/day calendar, bulk campaign builder, dashboard. See PROJECT_STATUS.md for exactly what was and was not verified.

**New in 0.6:** premium Material 3 redesign (dark / light / system), five-tab navigation (Home, Create, Calendar, Queue, Insights) with a drawer for Accounts,
Media library, Settings and Subscription, Supabase sign-up / sign-in / e-mail verification / Google / password reset / logout / session restore / account deletion,
Room + DataStore + WorkManager offline-first core, account health and token-expiry warnings, English + Hindi, target SDK 36, tests and CI.

Build: `gradle :app:testDebugUnitTest :app:assembleDebug` (JDK 17, Gradle 8.13, Android SDK 36). No Gradle wrapper is included.

---

# AutoPoster AI — v0.3

Android (Kotlin + Jetpack Compose) publishing studio: many accounts, bulk campaigns, per-account scheduling, retries, calendar and analytics.

## What's new in 0.3

**Multi-account manager**
- Connect any number of YouTube, Instagram and TikTok accounts (tokens encrypted on device).
- Switch the active account with one tap, rename, edit and remove accounts (removing cancels that account's queued uploads).
- Per-account post visibility (YouTube private/unlisted/public; TikTok privacy levels).
- Choose which accounts receive each campaign.

**Bulk publishing**
- Four ways to spread clips over the selected accounts: one by one switching account, random clip then switch account, all clips in one account then the next, or the same clip on every account. An order preview shows exactly what will post.
- Use a whole gallery album as the clip source, or pick clips by hand.
- Pick up to 100 videos per pass (repeat to add more) or import a gallery folder (up to 500).
- Any video to any set of accounts, including several accounts on one platform.
- Shared caption/hashtags plus a separate override per platform, with live character and hashtag limits.
- Caption variants (`one | two | three`) rotate across videos.
- Each account has its own start time and gap between posts.
- Videos are copied to app storage when queued so scheduled posts survive restarts and picker permission loss.

**Advanced**
- Caption and hashtag generator (on-device, rule-based; three tones; platform-aware limits).
- Duplicate detection (size + SHA-256 of the first 1 MB) inside a campaign and against anything already queued or published to the same account.
- Live per-video progress and per-account progress bars.
- Automatic retry with back-off (3 attempts), manual retry per item or "retry all failed", expired-token refresh for YouTube.
- Calendar view of scheduled posts with day filtering.
- Insights: published per day, success rate, split by platform and account, YouTube views/likes/comments.
- Quick-publish text/voice commands now build a draft for review and understand platforms, hashtags, `at 6pm`, `tomorrow at 9`, `in 2 hours`, `every 5 minutes`.
- Persistent queue (survives restarts), pause/resume, Wi‑Fi-only option.

## Honest limitations — read before testing
1. **This code has not been compiled or run.** It was written without an Android toolchain. Expect to fix a few compile errors on first sync, then test each platform with real accounts.
2. **Platform access is yours to obtain.** Tokens are pasted in (development mode). Production needs OAuth sign-in with refresh tokens and each platform's review: YouTube API audit (unaudited projects upload privately), Meta app review + a professional Instagram account for Reels publishing, TikTok app approval for direct posting (unaudited apps post privately). Only YouTube tokens auto-refresh here.
3. **Sound can't be attached.** Platform APIs don't let apps pick catalog music. The "sound credit" is added to the caption as text.
4. **Scheduling is best effort.** WorkManager wakes the queue near the scheduled time, but Android battery saver/Doze can delay it. Keep the app excluded from battery optimisation for reliable timing.
5. **Duplicate detection is approximate** (first 1 MB + size). Two different videos with identical first megabyte and size would be treated as duplicates; turn off "Skip duplicates" if that happens.
6. **Analytics:** only YouTube stats are fetched (needs a token with read scope). TikTok/Instagram insights aren't wired.
7. Caption/hashtag suggestions and the command parser are rule-based; `LlmEngine` is still a placeholder for an on-device model.
8. API versions (Graph `v21.0`, endpoint shapes, limits) change; verify against each platform's current docs.

## Build
1. Open `AutoPoster` in Android Studio, let Gradle sync, run on a device (min SDK 26).
2. Accounts tab → Add account → paste a token (see scopes in the dialog).
3. Create tab → pick videos → choose accounts → captions → schedule → Queue.

No Gradle wrapper is included; CI uses `gradle/actions/setup-gradle` with Gradle 8.9.

## Layout
`Models` data · `Stores` accounts/queue/settings · `Uploaders` YouTube/Instagram/TikTok · `PostWorker` + `Scheduler` queue engine · `Auth` token refresh · `CaptionAI` · `Insights` · `Media` fingerprint/outbox · `*Page` screens · `Studio` navigation and actions.

## v0.5 additions
- **Cloud backup & web dashboard (optional, off by default):** `Home → Cloud` card. Sends clip names, captions, status and YouTube stats to your own Supabase project (`supabase/migrations/001_post_items.sql`, row-level security, owner-only). Account tokens and video files never leave the phone. `web/index.html` is the dashboard (host it on Vercel): login, stats, 14-day chart, per-account progress, calendar, searchable queue, live updates.
- **Suggest start times:** one tap sets each account's start to a sensible posting hour (your own best YouTube hours once there is enough data, otherwise common starting points) and spaces accounts apart.
- **Result notifications:** one summary notification when an upload run finishes or needs attention.
- **App icon.**
- **GitHub Actions** builds a debug APK on every push: GitHub → Actions → latest run → Artifacts → `AutoPoster-apk`.
- **n8n** failure-alert workflow: see `n8n/README.md`.
