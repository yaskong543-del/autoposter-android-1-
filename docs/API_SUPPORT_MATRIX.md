# API support matrix (v0.8)

Researched against official documentation on **2026-11-13**. Where only third-party guides were available, that is stated and the item is
marked **unverified**: confirm it in the provider's own console before relying on it.

## Status vocabulary

| Term | Meaning |
|---|---|
| **Implemented** | Code exists in this repository. |
| **Mock-tested** | Automated tests run against a scripted fake `fetch`. This proves request shapes, state machines and error mapping, **not** that the provider accepts them. |
| **Live-tested** | A real call succeeded with real credentials on an eligible account. **No provider is live-tested in v0.8.** |
| **Approval-blocked** | The provider must approve the app (review/audit/paid tier) before other users can use it. |
| **Not implemented** | No code. Use the manual publishing handoff. |

A manual handoff (share sheet / open the platform app) is never reported as an API publication.

## Summary

| Priority | Platform | Implemented | Mock-tested | Live-tested | Blocked by |
|---|---|---|---|---|---|
| 1 | YouTube | video upload (resumable), status | yes | **no** | Google OAuth client; consent-screen verification for public users |
| 2 | Instagram | Reels via container + publish | yes | **no** | Meta App Review (Advanced Access); professional account |
| 3 | TikTok | Direct Post video (FILE_UPLOAD), status | yes | **no** | Content Posting API access; **audit** for public posts |
| 4 | Facebook | Page Reels (start/upload/finish), status | yes | **no** | Meta App Review; Page with Create Content task |
| 5 | Threads | video post via container + publish | yes | **no** | Meta App Review (Advanced Access) |
| 6 | X | media upload + post | yes | **no** | paid/credit API access; OAuth/media-upload ambiguity (below) |
| 7 | LinkedIn | member video post | yes | **no** | "Share on LinkedIn" product; organization posting not implemented |

Not implemented anywhere in v0.8: image/carousel/story/text-only publishing, organization/company pages (LinkedIn), Facebook profile posting
(not offered by Meta), provider-side scheduling (our worker publishes at run time instead), TUS resumable upload from the phone to our storage.

## Cross-platform design

* One adapter per provider behind `ProviderAdapter { advance(), status() }` (`supabase/functions/_shared/providers/`). `advance()` does a bounded
  amount of work and returns `progress | submitted | published`; the scheduler persists the returned state, so uploads resume across invocations.
* "Published" is only reported after the provider confirms (a publish call returned an id, or a status endpoint says so). Platforms with
  asynchronous processing move through `submitted` first.
* **At-most-once publishing**: before each non-repeatable call (Instagram `media_publish`, Threads `threads_publish`, Facebook `finish`, X `POST /2/tweets`,
  LinkedIn `POST /rest/posts`) the adapter checkpoints state. If a worker dies after the checkpoint, the retry does **not** call the provider again;
  it reports `AMBIGUOUS_PUBLISH` ("check the platform") instead of risking a duplicate post.
* Errors are normalised to `transient | rate_limited | quota | auth | approval_blocked | permanent` (`_shared/errors.ts`), with `Retry-After` honoured.
  Quota exhaustion blocks only that account until the reset time.
* OAuth: server-side `state` (stored hashed, single use, 10 min), PKCE where the provider documents it, tokens AES-256-GCM encrypted with the row's
  owner/account bound as AAD. Clients can read account metadata only (column-level grants), never tokens.
* Media: the phone uploads the video to a private bucket (`publish-media/<user id>/...`); the worker reads it by Range requests or hands the provider a
  short-lived signed URL (Instagram, Threads, Facebook). TikTok uses `FILE_UPLOAD` because `PULL_FROM_URL` requires a verified URL domain.
  Supabase's global file-size limit applies (50 MB on the free plan; the bucket is configured for 500 MB).

## YouTube (Data API v3)

| Item | Detail | Source / status |
|---|---|---|
| Endpoint | `videos.insert`, resumable upload (`uploadType=resumable`), `videos.list` for processing status | official reference, updated 2026-11-10 |
| Accounts | Google account with a YouTube channel | official |
| OAuth | Google OAuth 2.0 authorization code + PKCE, `access_type=offline` (refresh token), `prompt=consent` | standard Google flow (endpoints not re-fetched) |
| Scopes | `youtube.upload` (+ `youtube.readonly` to read the channel identity) | official lists upload scopes |
| Review | Sensitive scope: the consent screen must be verified before public users; apps in "Testing" mode have short-lived refresh tokens | **unverified this session** (prior knowledge) |
| Quota | Official page: **100 `videos.insert` calls per day** in the "Video Uploads" quota bucket (1 unit each); `videos.list` costs 1 unit from the general bucket | official (the older "1,600 units per upload" figure still appears in third-party guides; the current reference page does not use it) |
| Quota reset | Midnight Pacific Time | **unverified** (prior knowledge); used for `blocked_until` |
| Per-channel limit | `uploadLimitExceeded` is mapped to a 6 h account block | heuristic |
| Privacy of unverified projects | Official page now says uploads from unverified API projects are **not** forced to private | official, contradicts older guides |
| Max file | 256 GB | official |
| Status | `status.uploadStatus` = `uploaded` / `processed` / `failed` / `rejected` / `deleted` | official |
| Revoke | `https://oauth2.googleapis.com/revoke` | standard Google flow |
| Tested | mock only | |

## Instagram (Instagram API with Instagram Login)

| Item | Detail | Source / status |
|---|---|---|
| Endpoints | `POST /{ig-id}/media` (`REELS`, `video_url`), poll `status_code`, `POST /{ig-id}/media_publish`; Graph version v26.0 | official content-publishing doc (updated 2026-06-30) |
| Accounts | Instagram **professional** accounts (Business/Creator). Third-party guides disagree on Creator + Reels; the app accepts both and surfaces the API's answer | official + unverified |
| OAuth | `https://www.instagram.com/oauth/authorize` -> `api.instagram.com/oauth/access_token` (short-lived, 1 h) -> `graph.instagram.com/access_token?grant_type=ig_exchange_token` (60 days) | official business-login doc |
| Refresh | `graph.instagram.com/refresh_access_token?grant_type=ig_refresh_token` (token must be >= 24 h old and unexpired) | official |
| PKCE | not documented for this flow; only `state` is used | |
| Scopes | `instagram_business_basic`, `instagram_business_content_publish` | official |
| Review | Advanced Access via Meta App Review is required for accounts that are not your own/testers | official model |
| Limits | 100 API-published posts per rolling 24 h per account (the doc is inconsistent between 50 and 100) | official, ambiguous |
| Media | `video_url` must be reachable by Instagram; containers expire after 24 h; resumable `rupload` exists but is not used | official |
| Tested | mock only | |

## TikTok (Content Posting API, Direct Post)

| Item | Detail | Source / status |
|---|---|---|
| Endpoints | `post/publish/creator_info/query/`, `post/publish/video/init/`, chunk `PUT` to `upload_url`, `post/publish/status/fetch/` | official reference |
| OAuth | `https://www.tiktok.com/v2/auth/authorize/` (`client_key`), `https://open.tiktokapis.com/v2/oauth/token/`; access token 24 h, refresh token 365 days; web flow has no PKCE | official login-kit docs |
| Scope | `video.publish` (+ `user.info.basic`) | official |
| Unaudited apps | May post only privately (`SELF_ONLY`) and to private accounts: error `unaudited_client_can_only_post_to_private_accounts`. The adapter reads `creator_info` and falls back to `SELF_ONLY`, and the final notification says the post is private | official |
| Daily cap for unaudited apps | reported as 5 users per 24 h | **unverified** (third-party) |
| Chunk rules | 10 MiB chunks, last chunk takes the remainder | **unverified** (prior knowledge of 5-64 MB rule) |
| Domain verification | `PULL_FROM_URL` needs a verified URL prefix, so it is not used | official (`url_ownership_unverified`) |
| Status | `PROCESSING_*`, `SEND_TO_USER_INBOX`, `PUBLISH_COMPLETE`, `FAILED` | official |
| Revoke | `v2/oauth/revoke/` | official |
| Tested | mock only | |

## Facebook (Pages Reels API)

| Item | Detail | Source / status |
|---|---|---|
| Endpoints | `POST /{page}/video_reels` with `upload_phase=start`, upload to `rupload.facebook.com/video-upload/v26.0/{video_id}` (header `file_url`), `upload_phase=finish&video_state=PUBLISHED`, `GET /{video_id}?fields=status` | official reels-publishing guide |
| Accounts | **Pages only**, using a Page access token from a user with the Create Content task | official |
| OAuth | Facebook Login (`v26.0/dialog/oauth`), `GET /me/accounts` returns Page tokens; Page tokens obtained from a long-lived user token do not expire | official model; token lifetime **unverified** |
| Scopes | `pages_show_list`, `pages_read_engagement`, `pages_manage_posts` | official |
| Review | App Review for Advanced Access | official |
| Limits | 30 Reels per Page per 24 h | **unverified** (third-party) |
| Not supported | scheduling Reels through the API | not implemented |
| Tested | mock only | |

## Threads

| Item | Detail | Source / status |
|---|---|---|
| Endpoints | `POST /{user}/threads` (`media_type=VIDEO`, `video_url`, `text`), poll container `status`, `POST /{user}/threads_publish` | official posts doc |
| OAuth | `threads.net/oauth/authorize` -> `graph.threads.net/oauth/access_token` -> `th_exchange_token` (60 days) -> `th_refresh_token` | official long-lived-token doc |
| Scopes | `threads_basic`, `threads_content_publish` | official |
| Review | Advanced Access via App Review for other users | official model |
| Limits | 250 API posts per 24 h; text 500 chars; container should be given ~30 s before publishing | docs overview + third-party, **partly unverified** |
| Tested | mock only | |

## X

| Item | Detail | Source / status |
|---|---|---|
| Endpoints | `POST /2/media/upload/initialize`, `.../{id}/append` (<= 5 MB per call), `.../{id}/finalize`, `GET /2/media/upload?command=STATUS`, `POST /2/tweets` | docs.x.com + third-party |
| OAuth | OAuth 2.0 authorization code + PKCE (`x.com/i/oauth2/authorize`, `api.x.com/2/oauth2/token`), access token 2 h, refresh with `offline.access`, revoke endpoint | official docs |
| Scopes | `tweet.read tweet.write users.read media.write offline.access` | official + third-party |
| Cost | Third-party guides describe pay-per-use credits since 2026; no verified official price | **unverified**; HTTP 402 is mapped to `quota` |
| Known ambiguity | Sources disagree on whether media upload accepts OAuth 2.0 user tokens or only OAuth 1.0a. A 403 on upload becomes `X_MEDIA_AUTH_UNSUPPORTED` (approval_blocked) | unresolved |
| Tested | mock only | |

## LinkedIn

| Item | Detail | Source / status |
|---|---|---|
| Endpoints | `POST /rest/videos?action=initializeUpload`, `PUT` parts (collect `ETag`s), `?action=finalizeUpload`, `GET /rest/videos/{urn}` until `AVAILABLE`, `POST /rest/posts` (id in `x-restli-id`) | official Videos API + Posts API |
| Headers | `Linkedin-Version: 202605` (configurable with `LINKEDIN_API_VERSION`), `X-Restli-Protocol-Version: 2.0.0` | official (the docs expose a 2026-05 version) |
| Accounts | Member (personal) posting implemented. **Organization pages need `w_organization_social` and the Community Management API product (approval)**; not implemented | official |
| OAuth | `linkedin.com/oauth/v2/authorization` -> `/accessToken`; access token ~60 days; programmatic refresh tokens (365 days) are only for approved partners, so most apps must ask the user to reconnect about every 60 days | official refresh-token doc |
| Identity | `GET /v2/userinfo` (OpenID Connect) | **unverified** |
| Tested | mock only | |
