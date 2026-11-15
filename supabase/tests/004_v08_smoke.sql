-- Behavioural tests for migration 004. Run after 001-004 (see .github/workflows/backend-v08.yml). Everything rolls back.
-- Each `assert` raises on failure; the script prints "v0.8 smoke passed" only if all sections pass.
begin;
create schema smoke;
grant usage on schema smoke to anon, authenticated, service_role;

create function smoke.mkuser() returns uuid language plpgsql as $$
declare u uuid := gen_random_uuid();
begin
  insert into auth.users (id, instance_id, aud, role, email) values (u, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'smoke-' || u || '@example.test');
  return u;
end $$;
create function smoke.as_user(u uuid, sess uuid default null) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims', json_build_object('sub', u, 'role', 'authenticated', 'session_id', sess)::text, true);
  execute 'set local role authenticated';
end $$;
create function smoke.as_anon() returns void language plpgsql as $$
begin perform set_config('request.jwt.claims', '{}', true); execute 'set local role anon'; end $$;
create function smoke.acct(u uuid, plat text, ext text, st text default 'CONNECTED') returns uuid language plpgsql as $$
declare id uuid;
begin
  insert into public.provider_accounts (owner, platform, external_id, display_name, status, access_token_enc, refresh_token_enc)
  values (u, plat, ext, ext, st, 'v1.k.iv.ct', 'v1.k.iv.ct') returning provider_accounts.id into id;
  return id;
end $$;
-- returns the SQLSTATE of a statement, or 'ok'
create function smoke.state_of(stmt text) returns text language plpgsql as $$
begin execute stmt; return 'ok'; exception when others then return sqlstate; end $$;

-------------------------------------------------------------------------------------------- 1. profile limits
do $$
declare u uuid := smoke.mkuser(); a1 uuid; a2 uuid; r text;
begin
  a1 := smoke.acct(u, 'YOUTUBE', 'c1'); a2 := smoke.acct(u, 'INSTAGRAM', 'i1');
  r := smoke.state_of(format($q$select smoke.acct(%L, 'TIKTOK', 't1')$q$, u));
  assert r = 'PL001', 'FREE: third profile must be refused (got ' || r || ')';
  -- reconnect of an already connected account never needs a new slot
  insert into public.provider_accounts (owner, platform, external_id, status) values (u, 'YOUTUBE', 'c1', 'CONNECTED')
    on conflict (owner, platform, external_id) do update set status = 'CONNECTED';
  assert public.owner_profile_count(u) = 2, 'count stays 2 after reconnect';
  -- the device-side manual accounts (social_accounts) count against the same limit
  r := smoke.state_of(format($q$insert into public.social_accounts (owner, id, platform, label, external_id) values (%L, 'x', 'TIKTOK', 'l', 'zzz')$q$, u));
  assert r = 'PL001', 'FREE: social_accounts insert refused at the limit (got ' || r || ')';
  -- an already counted external id does not double count
  insert into public.social_accounts (owner, id, platform, label, external_id) values (u, 'dup', 'YOUTUBE', 'dup', 'c1');
  assert public.owner_profile_count(u) = 2, 'matching external id counted once';
  -- v0.7 mirrors account metadata with an upsert: re-pushing an existing row at the limit keeps working
  r := smoke.state_of(format($q$insert into public.social_accounts (owner, id, platform, label, external_id) values (%L, 'dup', 'YOUTUBE', 'renamed', 'c1') on conflict (owner, id) do update set label = excluded.label$q$, u));
  assert r = 'ok', 'social_accounts upsert of an existing row at the limit works (got ' || r || ')';
  perform public.admin_set_plan(u, 'pro', now() + interval '30 days', 'smoke');
  perform smoke.acct(u, 'TIKTOK', 't1'); perform smoke.acct(u, 'FACEBOOK', 'f1'); perform smoke.acct(u, 'THREADS', 'h1'); perform smoke.acct(u, 'X', 'x1'); perform smoke.acct(u, 'LINKEDIN', 'l1');
  assert public.owner_profile_count(u) = 7, 'PREMIUM: unlimited profiles, all seven platforms accepted';
  -- downgrade keeps data, suspends the newest beyond the limit
  perform public.admin_set_plan(u, 'free', null, 'downgrade');
  assert public.owner_profile_count(u) = 7, 'downgrade deletes nothing';
  assert public.account_allowed(a1) and public.account_allowed(a2), 'oldest two stay active';
  assert not public.account_allowed((select id from public.provider_accounts where owner = u and external_id = 'l1')), 'newest are suspended';
  r := smoke.state_of(format($q$select smoke.acct(%L, 'TIKTOK', 'new')$q$, u));
  assert r = 'PL001', 'no new profile while over the limit';
end $$;

-------------------------------------------------------------------------------------------- 2. entitlement snapshot and admin plan path
do $$
declare u uuid := smoke.mkuser(); e jsonb; r text;
begin
  perform smoke.as_user(u); e := public.my_entitlements();
  assert e->>'plan' = 'free' and (e->>'max_profiles')::int = 2 and (e->>'max_devices')::int = 1 and (e->>'ai_tokens_monthly')::int = 5000
     and (e->>'auto_sync')::boolean = false and (e->>'cellular_uploads')::boolean = false, 'FREE snapshot: ' || e::text;
  -- a client can not grant itself premium
  r := smoke.state_of(format($q$update public.profiles set plan = 'pro' where id = %L$q$, u));
  assert r = '42501', 'plan column is not writable by clients (got ' || r || ')';
  r := smoke.state_of(format($q$update public.profiles set plan_expires_at = now() where id = %L$q$, u));
  assert r = '42501', 'plan_expires_at not writable (got ' || r || ')';
  r := smoke.state_of($q$select public.admin_set_plan(gen_random_uuid(), 'pro', null, 'x')$q$);
  assert r = '42501', 'admin_set_plan not callable by clients (got ' || r || ')';
  reset role;
  perform public.admin_set_plan(u, 'pro', now() - interval '1 minute', 'expired grant');
  assert public.expire_plans() >= 1, 'expired plans are downgraded';
  assert public.plan_of(u) = 'free', 'back on free after expiry';
  assert (select count(*) from public.plan_audit where owner = u) = 2, 'both changes audited';
  perform public.admin_set_plan(u, 'agency', null, 'x');
  perform smoke.as_user(u); e := public.my_entitlements();
  assert (e->>'max_devices')::int = 5 and (e->>'ai_tokens_monthly')::int = 500000 and e->'max_profiles' = 'null'::jsonb and (e->>'auto_sync')::boolean, 'PREMIUM snapshot: ' || e::text;
  reset role;
end $$;

-------------------------------------------------------------------------------------------- 3. devices
do $$
declare u uuid := smoke.mkuser(); v uuid := smoke.mkuser(); s1 uuid := gen_random_uuid(); s2 uuid := gen_random_uuid(); d1 uuid; d2 uuid; r text; ok boolean;
begin
  insert into auth.sessions (id, user_id) values (s1, u), (s2, u);
  perform smoke.as_user(u, s1);
  d1 := public.register_device('install-0001-aaaa', 'Phone', 'android', '0.8', '15');
  assert public.register_device('install-0001-aaaa', 'Phone', 'android', '0.8', '15') = d1, 're-registering is idempotent';
  -- the v0.7 app registers with a PostgREST-style upsert: must keep working at the plan limit
  perform smoke.as_user(u, s1);
  r := smoke.state_of($q$insert into public.devices (install_id, name, platform, app_version, os_version, last_seen_at) values ('install-0001-aaaa', 'Phone', 'android', '0.7', '15', now())
    on conflict (owner, install_id) do update set install_id = excluded.install_id, name = excluded.name, platform = excluded.platform, app_version = excluded.app_version, os_version = excluded.os_version, last_seen_at = excluded.last_seen_at$q$);
  assert r = 'ok', 'legacy device upsert at the limit still works (got ' || r || ')';
  perform smoke.as_user(u, s2);
  r := smoke.state_of($q$select public.register_device('install-0002-bbbb', 'Tablet', 'android', '0.8', '15')$q$);
  assert r = 'PL002', 'FREE: second device refused (got ' || r || ')';
  r := smoke.state_of(format($q$update public.devices set revoked_at = null where id = %L$q$, d1));
  assert r = '42501', 'clients can not edit revocation (got ' || r || ')';
  r := smoke.state_of($q$insert into public.devices (owner, install_id, revoked_at) values (auth.uid(), 'sneaky-install-1', now())$q$);
  assert r = '42501', 'clients can not insert revocation columns (got ' || r || ')';
  perform smoke.as_user(u, s1);
  ok := public.revoke_device(d1);
  assert ok, 'revoke succeeds';
  reset role;
  assert not exists (select 1 from auth.sessions where id = s1), 'revocation ends the device session';
  assert exists (select 1 from auth.sessions where id = s2), 'other sessions untouched';
  assert public.device_allowed(d1) = 'revoked', 'device_allowed reports revoked';
  perform smoke.as_user(u, s2);
  r := smoke.state_of($q$select public.register_device('install-0001-aaaa', 'Phone', 'android', '0.8', '15')$q$);
  assert r = 'PL003', 'revoked install can not silently re-register (got ' || r || ')';
  d2 := public.register_device('install-0002-bbbb', 'Tablet', 'android', '0.8', '15');
  assert d2 is not null, 'revoked device freed the slot';
  -- another user can not revoke it
  perform smoke.as_user(v);
  assert public.revoke_device(d2) = false, 'cross-user revoke refused';
  reset role;
  perform public.admin_set_plan(u, 'pro', null, 'x');
  perform smoke.as_user(u);
  for i in 3..6 loop perform public.register_device('install-000' || i || '-xxxx', 'D' || i, 'android', '0.8', '15'); end loop;
  r := smoke.state_of($q$select public.register_device('install-0009-zzzz', 'D9', 'android', '0.8', '15')$q$);
  assert r = 'PL002', 'PREMIUM: sixth active device refused (got ' || r || ')';
  reset role;
end $$;

-------------------------------------------------------------------------------------------- 4. job enqueue authorization
do $$
declare u uuid := smoke.mkuser(); v uuid := smoke.mkuser(); a uuid; av uuid; j1 uuid; j2 uuid; r text; k text := repeat('k', 32);
begin
  a := smoke.acct(u, 'YOUTUBE', 'chan'); av := smoke.acct(v, 'YOUTUBE', 'chanv');
  perform smoke.as_user(u);
  j1 := public.enqueue_publish_job('post-1', a, now() + interval '1 hour', 'Asia/Kolkata', '{"caption":"hi"}', u || '/clip.mp4', k);
  j2 := public.enqueue_publish_job('post-1', a, now() + interval '1 hour', 'Asia/Kolkata', '{"caption":"hi"}', u || '/clip.mp4', k);
  assert j1 = j2, 'same idempotency key -> same job';
  r := smoke.state_of(format($q$select public.enqueue_publish_job('p2', %L, now(), 'UTC', '{}', %L, repeat('z', 32))$q$, av, u || '/x.mp4'));
  assert r = 'PL004', 'cannot schedule on another user''s account (got ' || r || ')';
  r := smoke.state_of(format($q$select public.enqueue_publish_job('p3', %L, now(), 'UTC', '{}', %L, repeat('y', 32))$q$, a, v || '/x.mp4'));
  assert r = 'PL006', 'media path must be inside the caller folder (got ' || r || ')';
  r := smoke.state_of(format($q$select public.enqueue_publish_job('p3', %L, now(), 'UTC', '{}', %L, repeat('y', 32))$q$, a, u || '/../x.mp4'));
  assert r = 'PL006', 'path traversal refused (got ' || r || ')';
  r := smoke.state_of(format($q$select public.enqueue_publish_job('p4', %L, now() + interval '5 years', 'UTC', '{}', %L, repeat('x', 32))$q$, a, u || '/x.mp4'));
  assert r = '22023', 'absurd run_at refused (got ' || r || ')';
  -- clients can never write or read worker columns
  r := smoke.state_of(format($q$update public.publish_jobs set status = 'published' where id = %L$q$, j1));
  assert r = '42501', 'client can not mark a job published (got ' || r || ')';
  r := smoke.state_of(format($q$insert into public.publish_jobs (owner, post_id, account_id, platform, run_at, idempotency_key, next_attempt_at, payload, media_path) values (%L, 'x', %L, 'YOUTUBE', now(), repeat('q', 32), now(), '{}', 'a/b')$q$, u, a));
  assert r = '42501', 'client can not insert jobs directly (got ' || r || ')';
  r := smoke.state_of($q$select provider_state from public.publish_jobs$q$);
  assert r = '42501', 'provider_state is not readable by clients (got ' || r || ')';
  r := smoke.state_of($q$select access_token_enc from public.provider_accounts$q$);
  assert r = '42501', 'token columns are not readable by clients (got ' || r || ')';
  r := smoke.state_of($q$select refresh_token_enc from public.provider_accounts$q$);
  assert r = '42501', 'refresh tokens not readable (got ' || r || ')';
  assert (select count(*) from public.publish_jobs) = 1, 'owner sees own job';
  -- pending cap for FREE (advanced automation is premium)
  for i in 2..10 loop perform public.enqueue_publish_job('pp' || i, a, now() + interval '1 day', 'UTC', '{}', u || '/x.mp4', repeat(i::text, 32)); end loop;
  r := smoke.state_of(format($q$select public.enqueue_publish_job('pp11', %L, now() + interval '1 day', 'UTC', '{}', %L, repeat('b', 32))$q$, a, u || '/x.mp4'));
  assert r = 'PL007', 'FREE pending cloud jobs capped (got ' || r || ')';
  -- anon and other users see nothing
  perform smoke.as_user(v);
  assert (select count(*) from public.publish_jobs) = 0 and (select count(*) from public.provider_accounts where owner = u) = 0 and (select count(*) from public.publish_job_events) = 0, 'cross-user isolation';
  perform smoke.as_anon();
  r := smoke.state_of($q$select count(*) from public.publish_jobs$q$);
  assert r = '42501', 'anon has no access to jobs (got ' || r || ')';
  r := smoke.state_of($q$select public.enqueue_publish_job('p', gen_random_uuid(), now(), 'UTC', '{}', 'a/b', repeat('c', 32))$q$);
  assert r = '42501', 'anon can not call enqueue (got ' || r || ')';
  reset role;
  -- needs-reconnect accounts refuse new jobs
  perform public.mark_account_needs_reconnect(a, 'AUTH_INVALID');
  perform smoke.as_user(u);
  r := smoke.state_of(format($q$select public.enqueue_publish_job('pz', %L, now(), 'UTC', '{}', %L, repeat('d', 32))$q$, a, u || '/x.mp4'));
  assert r = 'PL005', 'reconnect required (got ' || r || ')';
  reset role;
end $$;

-------------------------------------------------------------------------------------------- 5. job lifecycle: claim, lease, transitions, recovery, cancel, retry
do $$
declare u uuid := smoke.mkuser(); a uuid; j uuid; j2 uuid; c record; n integer; ok boolean; r text;
begin
  perform public.admin_set_plan(u, 'pro', null, 'x');
  a := smoke.acct(u, 'YOUTUBE', 'chan');
  perform smoke.as_user(u);
  j := public.enqueue_publish_job('post-1', a, now() - interval '1 minute', 'UTC', '{"caption":"x"}', u || '/c.mp4', repeat('1', 32));
  reset role;
  n := public.promote_due_jobs();
  assert n >= 1 and (select status from public.publish_jobs where id = j) = 'due', 'promoted to due when its time came';
  select count(*) into n from public.claim_publish_jobs('worker-A', 10, 60);
  assert n >= 1 and (select status from public.publish_jobs where id = j) = 'leased', 'claimed -> leased';
  assert (select count(*) from public.claim_publish_jobs('worker-B', 10, 60) where id = j) = 0, 'a leased job can not be claimed twice';
  assert public.apply_job_transition(j, 'worker-B', 'uploading', '{}') = false, 'a worker without the lease can not move the job';
  assert public.apply_job_transition(j, 'worker-A', 'uploading', '{"providerState":{"phase":"upload","offset":10}}'), 'lease holder proceeds';
  assert (select provider_state ->> 'phase' from public.publish_jobs where id = j) = 'upload', 'provider state persisted';
  r := smoke.state_of(format($q$select public.apply_job_transition(%L, 'worker-A', 'scheduled', '{}')$q$, j));
  assert r = 'PL009', 'illegal transition refused (got ' || r || ')';
  r := smoke.state_of(format($q$select public.apply_job_transition(%L, 'worker-A', 'published', '{}')$q$, j));
  assert r = 'ok', 'uploading -> published is legal';   -- (guard against typos in the function; real flow is checked next)
  assert (select status from public.publish_jobs where id = j) = 'published' and (select lease_owner from public.publish_jobs where id = j) is null, 'terminal state clears the lease';
  assert public.apply_job_transition(j, 'worker-A', 'due', '{}') = false, 'no lease after terminal';
  -- stale lease recovery
  perform smoke.as_user(u);
  j2 := public.enqueue_publish_job('post-2', a, now() - interval '1 minute', 'UTC', '{}', u || '/c.mp4', repeat('2', 32));
  reset role;
  perform public.promote_due_jobs(); perform * from public.claim_publish_jobs('crash', 10, 60);
  update public.publish_jobs set lease_expires_at = now() - interval '1 second', max_attempts = 2 where id = j2;
  n := public.recover_stale_jobs();
  assert n = 1 and (select status from public.publish_jobs where id = j2) = 'due' and (select attempts from public.publish_jobs where id = j2) = 1, 'stale lease -> due, attempts + 1';
  perform * from public.claim_publish_jobs('crash2', 10, 60);
  update public.publish_jobs set lease_expires_at = now() - interval '1 second' where id = j2;
  perform public.recover_stale_jobs();
  assert (select status from public.publish_jobs where id = j2) = 'permanent_failure' and (select last_error_code from public.publish_jobs where id = j2) = 'LEASE_EXPIRED', 'exhausted attempts fail permanently';
  -- retry of a failed job: allowed, resets; ambiguous publishes refused
  perform smoke.as_user(u);
  assert public.retry_publish_job(j2, now()), 'user can retry a failed job';
  assert (select status from public.publish_jobs where id = j2) = 'scheduled', 'back to scheduled';
  reset role;
  update public.publish_jobs set status = 'permanent_failure', last_error_code = 'AMBIGUOUS_PUBLISH' where id = j2;
  perform smoke.as_user(u);
  r := smoke.state_of(format($q$select public.retry_publish_job(%L)$q$, j2));
  assert r = 'PL008', 'ambiguous publish can not be blindly retried (got ' || r || ')';
  reset role;
  -- blocked accounts are skipped by the claim
  update public.publish_jobs set status = 'due', next_attempt_at = now() - interval '1 second', lease_owner = null, lease_expires_at = null where id = j2;
  perform public.block_provider_account(a, now() + interval '1 hour', 'YT_QUOTA_EXCEEDED');
  assert (select count(*) from public.claim_publish_jobs('w', 10, 60) where id = j2) = 0, 'quota-blocked account is not claimed';
  update public.provider_accounts set blocked_until = null where id = a;
  assert (select count(*) from public.claim_publish_jobs('w', 10, 60) where id = j2) = 1, 'claimable again after the block ends';
  -- suspended (over plan) accounts: job_account_allowed
  assert public.job_account_allowed(j2), 'premium account allowed';
end $$;

do $$
declare u uuid := smoke.mkuser(); a uuid; j uuid; s text;
begin
  a := smoke.acct(u, 'TIKTOK', 'tt');
  perform smoke.as_user(u);
  j := public.enqueue_publish_job('p', a, now() + interval '1 day', 'UTC', '{}', u || '/c.mp4', repeat('7', 32));
  assert public.cancel_publish_job(j) = 'cancelled', 'scheduled job cancels immediately';
  assert public.cancel_publish_job(j) = 'already_final', 'cancel is idempotent';
  j := public.enqueue_publish_job('p2', a, now() - interval '1 minute', 'UTC', '{}', u || '/c.mp4', repeat('8', 32));
  reset role;
  perform public.promote_due_jobs(); perform * from public.claim_publish_jobs('w', 10, 60);
  perform smoke.as_user(u);
  assert public.cancel_publish_job(j) = 'cancel_requested', 'running job gets a cancel request';
  reset role;
  assert public.job_cancel_requested(j), 'worker can see the flag';
  perform public.apply_job_transition(j, 'w', 'uploading', '{}'); perform public.apply_job_transition(j, 'w', 'submitted', '{"providerState":{"phase":"processing"}}');
  perform smoke.as_user(u);
  assert public.cancel_publish_job(j) = 'too_late', 'accepted-by-platform jobs can not be cancelled';
  reset role;
  assert (select count(*) from public.publish_job_events where job_id = j) >= 4, 'audit trail recorded';
end $$;

-------------------------------------------------------------------------------------------- 6. notifications, sync tables, storage
do $$
declare u uuid := smoke.mkuser(); v uuid := smoke.mkuser(); r text;
begin
  assert public.create_notification(u, 'publish_success', 'publish_success:j1', 'Post published', 'x', 'j1') = true, 'first insert';
  assert public.create_notification(u, 'publish_success', 'publish_success:j1', 'Post published', 'x', 'j1') = false, 'duplicate suppressed';
  insert into public.notification_prefs (owner, prefs) values (u, '{"token_expiring": false}');
  assert public.create_notification(u, 'token_expiring', 'te:a:1', 't', 'b', 'a') = false, 'user preference respected';
  perform smoke.as_user(u);
  update public.notifications set delivered_at = now() where owner = u;
  r := smoke.state_of($q$update public.notifications set title = 'hacked'$q$);
  assert r = '42501', 'clients can only set delivery state (got ' || r || ')';
  perform smoke.as_user(v);
  assert (select count(*) from public.notifications) = 0, 'notifications are private';
  reset role;
  -- sync tables
  insert into public.sync_documents (owner, kind, id, data) values (u, 'draft', 'd1', '{"text":"a"}');
  update public.sync_documents set data = '{"text":"b"}' where owner = u and id = 'd1';
  assert (select rev from public.sync_documents where owner = u and id = 'd1') = 2 and (select server_seq from public.sync_documents where owner = u and id = 'd1') > 0, 'rev and seq advance on update';
  perform smoke.as_user(u);
  r := smoke.state_of(format($q$insert into public.sync_documents (owner, kind, id) values (%L, 'draft', 'sneak')$q$, u));
  assert r = '42501', 'clients write sync data only through the Edge Function (got ' || r || ')';
  assert (select count(*) from public.sync_documents) = 1, 'owner can read own documents';
  perform smoke.as_user(v);
  assert (select count(*) from public.sync_documents) = 0, 'sync documents are private';
  perform smoke.as_anon();
  r := smoke.state_of($q$select count(*) from public.sync_documents$q$);
  assert r = '42501', 'anon denied (got ' || r || ')';
  reset role;
  assert (select count(*) from storage.buckets where id = 'publish-media' and public = false) = 1, 'private publish-media bucket exists';
  -- account deletion cascades through every new table
  insert into public.provider_accounts (owner, platform, external_id) values (u, 'X', 'xx');
  delete from auth.users where id = u;
  assert not exists (select 1 from public.provider_accounts where owner = u) and not exists (select 1 from public.notifications where owner = u)
     and not exists (select 1 from public.sync_documents where owner = u), 'user deletion cascades';
end $$;

-------------------------------------------------------------------------------------------- 7. oauth state table is server-only
do $$
declare u uuid := smoke.mkuser(); r text;
begin
  insert into public.oauth_states (state_hash, owner, platform, redirect_uri, expires_at) values ('h', u, 'YOUTUBE', 'https://x/cb', now() + interval '10 minutes');
  perform smoke.as_user(u);
  r := smoke.state_of($q$select count(*) from public.oauth_states$q$);
  assert r = '42501', 'oauth_states is not accessible to clients (got ' || r || ')';
  reset role;
end $$;

do $$ begin raise notice 'v0.8 smoke passed'; end $$;
rollback;
