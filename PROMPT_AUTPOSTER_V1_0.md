# AUTPOSTER v1.0 — PRODUCTION AND COMMERCIAL RELEASE
## Claude implementation prompt

Upgrade the existing, accepted AutoPoster v0.8 project into a genuine v1.0 release candidate.

**Read first:** `CLAUDE_V08_V10_EXECUTION_PLAN.md`, `PROJECT_STATUS.md`, `ARCHITECTURE.md`, `FEATURE_PRESERVATION_CHECKLIST.md`, `CLAUDE_HANDOFF_V0_7.md`, `AUDIT_REPORT.md`, `docs/API_SUPPORT_MATRIX.md`, and the v0.8 completion report.

## Mandatory start gate

- Verify the v0.8 acceptance report and the exact accepted commit.
- Verify CI and test evidence on that commit.
- Confirm the scheduler, sync, entitlements, dashboard, and provider support matrix reflect tested reality.
- If v0.8 has not passed, **stop**. Fix v0.8 blockers; do not start v1.0.
- Preserve working v0.6/v0.7/v0.8 behavior. Do not rebuild or replace working components.
- v1.0 only: production hardening, monetization, analytics, creator-tool polish, security, performance, and release preparation.

## 1. Google Play Billing

Implement genuine Google Play subscriptions:
- Free plan.
- Premium Monthly.
- Premium Annual.

Implement purchase/query, server-side purchase-token verification using the Google Play Developer API, acknowledgement, restore/query purchases, renewals, cancellation, expiration, grace period, account hold, billing errors, upgrades/downgrades/proration, and entitlement reconciliation.
- Retrieve actual localized product/base-plan/offer prices from Google Play; never hardcode prices.
- Make purchase notifications/reconciliation idempotent.
- Backend entitlements must derive from verified purchase state, never a client Premium boolean.
- Handle pending purchases and interrupted flows.
- Test with Play license testers/internal testing track.
- Document required Play Console, service-account/API, package, product/base-plan, and RTDN configuration. Owner secrets remain in secure configuration, never Git or chat.
- Do not claim a real paid purchase was tested unless it was actually completed and verified.

## 2. Premium upgrade experience

Build a polished, accessible pricing screen showing:

**FREE**
- 2 connected profiles.
- 1 device.
- 5,000 AI tokens monthly.

**PREMIUM**
- Unlimited connected profiles.
- Up to 5 devices.
- 500,000 AI tokens monthly.
- Automatic cloud sync.
- Cellular upload when enabled.
- Advanced AI and automation.
- Advanced analytics.
- Expanded bulk scheduling.

Display actual localized Play prices and billing periods, renewal/cancellation terms, restore purchases, manage-subscription link, and clear pending/success/error states. No misleading discounts, fake scarcity, hidden recurring charges, or dark patterns.

## 3. Advanced analytics

Use only metrics genuinely supplied by authorized platform APIs and granted scopes. Where available, implement views, likes, comments, shares, engagement metrics, account/platform comparisons, best-performing posts, historical trends, and date filtering.

For every metric, document its source, definition, availability, freshness, and applicable platform/account restrictions. Show unavailable data honestly; never fabricate or infer missing metrics as facts. Handle API quota and rate limits.

## 4. Advanced creator tools

Polish and test existing capabilities:
- Bulk campaigns.
- Smart scheduling.
- Brand kit.
- Content templates.
- Content repurposing.
- Media library.
- Team architecture.
- Approval workflow.
- Post duplication.
- Notification centre.

Prioritize complete, reliable workflows over feature count. Team roles and approval flows require real authorization and data model enforcement. Do not present a visual-only or nonfunctional control as implemented.

## 5. Complete security and privacy audit

Review and remediate critical/high findings in:
- Authentication and OAuth state/redirect validation.
- Session management and account recovery.
- Database RLS and cross-tenant access.
- Storage bucket policies and signed URLs.
- API authorization and server-side entitlement checks.
- OAuth token encryption, rotation, refresh, and revocation.
- Secret management and repository history.
- Rate limiting and input validation.
- Account deletion and data export/retention.
- Audit logging and sensitive-data redaction.
- Billing verification and webhook/notification authenticity.
- Dependency vulnerabilities and supply-chain risks.

Run appropriate static, dependency, and database security tests. Record findings, severity, remediation commit, and residual risk. Never claim the app is secure solely because a scanner passed. Do not weaken RLS or suppress findings to obtain a green build.

## 6. Performance and reliability

Test and fix major crashes/performance problems across:
- Low-memory Android devices.
- Slow, intermittent, and offline networks.
- App process termination and device reboot.
- Battery/background restrictions.
- Expired/revoked OAuth tokens.
- Large image/video files and interrupted uploads.
- Provider/API failures and rate limiting.
- Multiple simultaneous scheduled posts.
- Cloud sync conflicts and stale devices.
- Subscription expiry, grace period, and downgrade.
- Notification denial and permission changes.

Use realistic device/API test conditions and report what was actually tested. Do not claim broad device coverage from emulator tests alone.

## 7. Google Play preparation

Verify the current Play policy and technical requirements at the time of release. Prepare:
- Required target Android API (API 36 or the then-current Play requirement).
- Signed Android App Bundle (AAB).
- Release signing configuration and secure owner-controlled keystore.
- App icon and splash screen.
- Phone/tablet screenshots and feature graphic as applicable.
- Privacy policy and terms of service.
- Accurate Data Safety declarations.
- Account deletion process and public deletion URL where required.
- Permission declarations and justifications.
- Billing compliance and subscription disclosures.
- Store listing, content rating, and release checklist.

Never commit signing material. Build an AAB only when signing configuration is valid. Do not call an unsigned APK a release bundle. Do not claim Play approval or public launch before Google actually approves/publishes the app.

## 8. Final verification

Run and preserve evidence for:
- Debug and release builds.
- Unit tests.
- Integration tests.
- UI tests where available.
- Android lint.
- Static analysis.
- Room/database migration and security tests.
- Supabase RLS and backend authorization tests.
- Scheduler and synchronization tests.
- Billing license tests and server verification.
- Provider API live tests where credentials and approvals exist.
- Dependency/security scans.

Fix critical and high-severity issues before declaring release-candidate status. Explain any remaining medium/low findings.

## 9. Final deliverables and truthful release report

Provide:
- Final Android source and exact commit.
- Database migrations and deployment order.
- Supabase Edge Functions and deployment instructions.
- Web dashboard source and deployment instructions.
- Complete documentation and current API support matrix.
- Exact CI/test results and links.
- Signed AAB only if owner signing configuration is available and build succeeds.
- External configuration/credential checklist.
- Privacy, terms, deletion, and store listing assets.
- Known limitations and residual security findings.

The final report must explicitly answer:
1. What works now?
2. What was automated-tested?
3. What was live-tested with real provider credentials/accounts?
4. What still requires platform review, audit, or approval?
5. What requires owner credentials or configuration?
6. Is the AAB built and signed? If not, why?
7. What is ready for Play Console submission?
8. What remains before public launch?

Do not claim Play Store approval before approval is granted. Do not describe untested integrations, billing, analytics, or security controls as production-ready. Distinguish **implemented**, **automated-tested**, **live-tested**, **approval-blocked**, and **not implemented**.

## Work discipline

Work incrementally on the existing branch. At each batch report changed files, commit SHA, tests actually run, exact results, CI run URL, live verification, and blockers with the responsible party. If owner credentials or platform approval are missing, complete all independent work and provide precise secure setup instructions; never ask for secrets in chat. Keep `PROJECT_STATUS.md`, `ARCHITECTURE.md`, `AUDIT_REPORT.md`, release checklist, and API support matrix accurate.
