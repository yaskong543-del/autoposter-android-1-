# Supabase setup for AutoPoster 0.7

1. Create a Supabase project. Copy the **Project URL** and the **anon / publishable key** into `.env`
   (`SUPABASE_URL`, `SUPABASE_ANON_KEY`). Never put the service-role key in the app or in this repo.
2. Run the migrations in order in the SQL editor (or `supabase db push`):
   `001_post_items.sql`, then `002_foundation.sql`.
3. **Authentication → Providers → Email:** enable it, keep "Confirm email" on, set minimum password length to 8.
4. **Authentication → URL Configuration → Redirect URLs:** add `autoposter://auth/callback`.
5. **Authentication → Email Templates:** in "Confirm signup" and "Reset password", show the 6-digit code with
   `{{ .Token }}` next to the link. The app accepts either the code or the link.
6. **Google sign-in (optional):** create an OAuth *Web* client and an *Android* client (package
   `com.example.autoposter` + your signing SHA-1) in Google Cloud. In Supabase → Providers → Google, enable it, paste the
   Web client ID/secret and list the Web client ID under "Authorized Client IDs". Put the Web client ID in
   `.env` as `GOOGLE_WEB_CLIENT_ID`. Leave "Skip nonce checks" off (the app sends a nonce).
7. Rebuild the app. Without these values the app builds and runs in local mode and shows sign-in as unavailable.

Notes
- `profiles.plan` cannot be changed by the signed-in user (column-level grants).
- `delete_my_account()` deletes the caller's `auth.users` row; profiles, devices, social-account metadata and post rows cascade.
- The web dashboard (`web/index.html`) signs in with e-mail + password. An account created only with Google has no password;
  use "Forgot password" in the app once to set one.


## AI gateway (v0.7)
1. Run `003_ai_creator_studio.sql` after 001 and 002, then `supabase/tests/003_ai_quota_smoke.sql` (rolls back; must end with "ai quota smoke test passed").
2. Install the Supabase CLI, `supabase link`, then:
   `supabase secrets set ANTHROPIC_API_KEY=sk-ant-... AI_MODEL=claude-haiku-5-5`
   `supabase functions deploy ai-gateway`
   (`SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY` are injected automatically. Keep JWT verification on.)
3. The app calls `POST {SUPABASE_URL}/functions/v1/ai-gateway` with the user's session token. Plans: `profiles.plan = 'free'` → 5,000 tokens/month, `'pro'` or `'agency'` → 500,000. Limits live in `public.ai_plan_limits`.
4. Local checks: `cd supabase/functions/ai-gateway && node --experimental-strip-types --test core.test.ts handler.test.ts` (Node 22.16+).
5. Usage and audit trail: `select * from ai_usage` / `ai_requests` (service role only; users can read their own `ai_usage` row).

## v0.8 backend (Automation and Cloud Engine)

Migration `004_v08_engine.sql` (after 001-003), Edge Functions `oauth`, `publish-worker`, `sync` (plus `ai-gateway` from v0.7).

Deploy order and secrets (never paste secrets into chat; set them with the CLI or the dashboard):

```
supabase db push                                   # or run 004_v08_engine.sql in the SQL editor
supabase secrets set TOKEN_ENC_KEY="k1:$(openssl rand -base64 32)"      # AES-256-GCM key for provider tokens; rotate by adding TOKEN_ENC_KEY_PREVIOUS
supabase secrets set WORKER_SECRET="$(openssl rand -hex 32)"           # shared secret for the scheduler tick
supabase secrets set OAUTH_RESULT_URL="autoposter://oauth"             # where the OAuth callback sends the user afterwards
supabase secrets set YOUTUBE_CLIENT_ID=... YOUTUBE_CLIENT_SECRET=...   # only for providers you have approved apps for
supabase functions deploy oauth --no-verify-jwt                        # callback is public; start/disconnect verify the JWT in code
supabase functions deploy publish-worker --no-verify-jwt               # protected by WORKER_SECRET
supabase functions deploy sync
```

Scheduler tick (every minute) with `pg_cron` + `pg_net` (enable both extensions; store the worker secret in Vault):

```sql
select cron.schedule('autoposter-tick', '* * * * *', $$
  select net.http_post(url := 'https://<project-ref>.supabase.co/functions/v1/publish-worker',
         headers := jsonb_build_object('authorization', 'Bearer ' || (select decrypted_secret from vault.decrypted_secrets where name = 'worker_secret')))
$$);
select cron.schedule('autoposter-maintenance', '*/15 * * * *', $$
  select net.http_post(url := 'https://<project-ref>.supabase.co/functions/v1/publish-worker?maintenance=1',
         headers := jsonb_build_object('authorization', 'Bearer ' || (select decrypted_secret from vault.decrypted_secrets where name = 'worker_secret')))
$$);
```

Development premium override (server-side only, audited, expires): `select public.admin_set_plan('<user uuid>', 'pro', now() + interval '30 days', 'dev test');` — run from the SQL editor / service role. There is no client path to change a plan.
