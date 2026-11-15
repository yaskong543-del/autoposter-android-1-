-- AutoPoster 0.8: Automation and Cloud Engine.
-- Forward-only and re-runnable (IF NOT EXISTS / DROP ... IF EXISTS / CREATE OR REPLACE). Requires 001, 002, 003.
-- Adds: 7-platform support, server-authoritative entitlements, encrypted provider accounts, OAuth state, durable publish jobs
-- (leases, state machine, audit), notifications, device revocation, sync tables. No existing data is modified or deleted.

---------------------------------------------------------------------------------------------------
-- 0. Platform lists: YOUTUBE/INSTAGRAM/TIKTOK -> all seven (existing rows stay valid)
---------------------------------------------------------------------------------------------------
alter table public.social_accounts drop constraint if exists social_accounts_platform_check;
alter table public.social_accounts add constraint social_accounts_platform_check
  check (platform in ('YOUTUBE','INSTAGRAM','TIKTOK','FACEBOOK','THREADS','X','LINKEDIN'));
alter table public.post_items drop constraint if exists post_items_platform_check;
alter table public.post_items add constraint post_items_platform_check
  check (platform in ('YOUTUBE','INSTAGRAM','TIKTOK','FACEBOOK','THREADS','X','LINKEDIN'));

---------------------------------------------------------------------------------------------------
-- 1. Plans and entitlements (server-authoritative). AI token limits stay in public.ai_plan_limits (003).
---------------------------------------------------------------------------------------------------
alter table public.profiles add column if not exists plan_expires_at timestamptz;
alter table public.profiles add column if not exists plan_source text not null default 'none';
alter table public.profiles drop constraint if exists profiles_plan_source_check;
alter table public.profiles add constraint profiles_plan_source_check check (plan_source in ('none','admin','billing'));
-- Users still cannot write plan / plan_expires_at / plan_source: column-level grants from 002 only allow display_name, avatar_path, language.

create table if not exists public.plan_entitlements (
  plan text primary key check (plan in ('free','pro','agency')),
  max_profiles integer check (max_profiles is null or max_profiles >= 0),     -- null = unlimited
  max_devices integer not null check (max_devices >= 1),
  auto_sync boolean not null,
  cellular_uploads boolean not null,
  advanced_automation boolean not null,
  max_pending_jobs integer check (max_pending_jobs is null or max_pending_jobs >= 0)  -- cloud-scheduled jobs waiting; null = unlimited
);
insert into public.plan_entitlements (plan, max_profiles, max_devices, auto_sync, cellular_uploads, advanced_automation, max_pending_jobs) values
  ('free',   2,    1, false, false, false, 10),
  ('pro',    null, 5, true,  true,  true,  null),
  ('agency', null, 5, true,  true,  true,  null)
on conflict (plan) do nothing;
alter table public.plan_entitlements enable row level security;
drop policy if exists "entitlements readable" on public.plan_entitlements;
create policy "entitlements readable" on public.plan_entitlements for select to authenticated using (true);
revoke all on public.plan_entitlements from anon, authenticated;
grant select on public.plan_entitlements to authenticated;

create table if not exists public.plan_audit (
  id bigserial primary key,
  owner uuid not null references auth.users(id) on delete cascade,
  at timestamptz not null default now(),
  old_plan text, new_plan text, source text, reason text, expires_at timestamptz
);
alter table public.plan_audit enable row level security;
drop policy if exists "plan audit owner read" on public.plan_audit;
create policy "plan audit owner read" on public.plan_audit for select to authenticated using (owner = (select auth.uid()));
revoke all on public.plan_audit from anon, authenticated;
grant select on public.plan_audit to authenticated;

create or replace function public.plan_of(p_owner uuid) returns text
language sql stable security definer set search_path = '' as $$
  select coalesce((select p.plan from public.profiles p where p.id = p_owner), 'free')
$$;

-- Development / pre-billing premium override. Service role only (no grant to authenticated). v1.0 billing will use source 'billing'.
create or replace function public.admin_set_plan(p_owner uuid, p_plan text, p_expires timestamptz, p_reason text) returns void
language plpgsql security definer set search_path = '' as $$
declare v_old text;
begin
  if p_plan not in ('free','pro','agency') then raise exception 'invalid plan' using errcode = '22023'; end if;
  select plan into v_old from public.profiles where id = p_owner for update;
  if not found then raise exception 'unknown user' using errcode = '22023'; end if;
  update public.profiles set plan = p_plan, plan_expires_at = case when p_plan = 'free' then null else p_expires end,
         plan_source = case when p_plan = 'free' then 'none' else 'admin' end where id = p_owner;
  insert into public.plan_audit (owner, old_plan, new_plan, source, reason, expires_at) values (p_owner, v_old, p_plan, 'admin', left(p_reason, 200), p_expires);
end $$;

-- Downgrade on expiry. Data is never deleted; entitlements shrink and over-limit items become suspended (see account_allowed/device_allowed).
create or replace function public.expire_plans() returns integer
language plpgsql security definer set search_path = '' as $$
declare n integer := 0; r record;
begin
  for r in select id, plan from public.profiles where plan <> 'free' and plan_expires_at is not null and plan_expires_at <= now() for update skip locked loop
    update public.profiles set plan = 'free', plan_expires_at = null, plan_source = 'none' where id = r.id;
    insert into public.plan_audit (owner, old_plan, new_plan, source, reason) values (r.id, r.plan, 'free', 'expiry', 'plan expired');
    n := n + 1;
  end loop;
  return n;
end $$;

---------------------------------------------------------------------------------------------------
-- 2. Provider accounts (OAuth) with encrypted tokens. Clients can read metadata only.
---------------------------------------------------------------------------------------------------
create table if not exists public.provider_accounts (
  id uuid primary key default gen_random_uuid(),
  owner uuid not null references auth.users(id) on delete cascade,
  platform text not null check (platform in ('YOUTUBE','INSTAGRAM','TIKTOK','FACEBOOK','THREADS','X','LINKEDIN')),
  external_id text not null check (char_length(external_id) between 1 and 200),
  display_name text check (char_length(display_name) <= 200),
  account_type text,
  scopes text[] not null default '{}',
  status text not null default 'CONNECTED' check (status in ('CONNECTED','EXPIRING','EXPIRED','NEEDS_RECONNECT','REVOKED')),
  access_token_enc text,                 -- AES-256-GCM envelope written by the Edge Function (never plaintext)
  refresh_token_enc text,
  token_expires_at timestamptz,
  refresh_expires_at timestamptz,
  blocked_until timestamptz,             -- quota / rate-limit block set by the scheduler
  last_error_code text,
  last_refresh_at timestamptz,
  meta jsonb not null default '{}',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (owner, platform, external_id)
);
create index if not exists provider_accounts_owner on public.provider_accounts (owner, created_at);
create index if not exists provider_accounts_expiry on public.provider_accounts (token_expires_at) where status in ('CONNECTED','EXPIRING');
drop trigger if exists provider_accounts_touch on public.provider_accounts;
create trigger provider_accounts_touch before update on public.provider_accounts for each row execute function public.touch_updated_at();

alter table public.provider_accounts enable row level security;
drop policy if exists "provider accounts owner read" on public.provider_accounts;
create policy "provider accounts owner read" on public.provider_accounts for select to authenticated using (owner = (select auth.uid()));
revoke all on public.provider_accounts from anon, authenticated;
grant select (id, owner, platform, external_id, display_name, account_type, scopes, status, token_expires_at, blocked_until, last_error_code, created_at, updated_at)
  on public.provider_accounts to authenticated;     -- token columns deliberately excluded

alter table public.provider_accounts alter column created_at set default clock_timestamp();
create table if not exists public.oauth_states (
  state_hash text primary key,
  owner uuid not null references auth.users(id) on delete cascade,
  platform text not null,
  redirect_uri text not null,
  code_verifier_enc text,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  used_at timestamptz
);
alter table public.oauth_states enable row level security;       -- no policies, no grants: service role only
revoke all on public.oauth_states from anon, authenticated;

---------------------------------------------------------------------------------------------------
-- 3. Race-free limits: connected profiles and devices. One advisory lock per (kind, owner).
---------------------------------------------------------------------------------------------------
create or replace function public.owner_profile_count(p_owner uuid) returns integer
language sql stable security definer set search_path = '' as $$
  select (
    (select count(*) from public.provider_accounts a where a.owner = p_owner and a.status <> 'REVOKED')
    + (select count(*) from public.social_accounts s where s.owner = p_owner
        and not exists (select 1 from public.provider_accounts a where a.owner = p_owner and a.platform = s.platform and a.external_id = s.external_id and a.status <> 'REVOKED'))
  )::integer
$$;

create or replace function public.enforce_profile_limit() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_max integer; v_plan text;
begin
  perform pg_advisory_xact_lock(hashtextextended('profiles:' || new.owner::text, 0));
  -- Reconnecting an account that is already counted never consumes a new slot.
  if tg_table_name = 'provider_accounts' and exists (select 1 from public.provider_accounts a where a.owner = new.owner and a.platform = new.platform and a.external_id = new.external_id) then
    return new;
  end if;
  if tg_table_name = 'social_accounts' then
    if exists (select 1 from public.social_accounts s where s.owner = new.owner and s.id = new.id) then
      return new;                                 -- upsert of an existing row (v0.7 metadata mirror)
    end if;
  end if;
  if tg_table_name = 'social_accounts' and new.external_id is not null and exists (
       select 1 from public.provider_accounts a where a.owner = new.owner and a.platform = new.platform and a.external_id = new.external_id and a.status <> 'REVOKED') then
    return new;
  end if;
  v_plan := public.plan_of(new.owner);
  select e.max_profiles into v_max from public.plan_entitlements e where e.plan = v_plan;
  if v_max is not null and public.owner_profile_count(new.owner) >= v_max then
    raise exception 'PLAN_LIMIT_PROFILES: your % plan allows % connected profiles', v_plan, v_max using errcode = 'PL001';
  end if;
  return new;
end $$;
revoke execute on function public.enforce_profile_limit() from public, anon, authenticated;
drop trigger if exists provider_accounts_limit on public.provider_accounts;
create trigger provider_accounts_limit before insert on public.provider_accounts for each row execute function public.enforce_profile_limit();
drop trigger if exists social_accounts_limit on public.social_accounts;
create trigger social_accounts_limit before insert on public.social_accounts for each row execute function public.enforce_profile_limit();

-- Devices: extra columns; clients may no longer write revocation/sync columns.
alter table public.devices add column if not exists revoked_at timestamptz;
alter table public.devices add column if not exists auth_session_id uuid;
alter table public.devices add column if not exists last_sync_at timestamptz;
alter table public.devices add column if not exists sync_cursor bigint not null default 0;
revoke insert, update on public.devices from authenticated;
grant insert (owner, install_id, name, platform, app_version, os_version, last_seen_at) on public.devices to authenticated;
-- install_id/platform stay updatable because PostgREST upserts (the v0.7 app's device registration) list every column in DO UPDATE SET.
grant update (install_id, name, platform, app_version, os_version, last_seen_at) on public.devices to authenticated;

create or replace function public.enforce_device_limit() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_max integer; v_plan text;
begin
  perform pg_advisory_xact_lock(hashtextextended('devices:' || new.owner::text, 0));
  -- Re-registering an installation that already has a row (v0.7 upsert path) never needs a new slot.
  if exists (select 1 from public.devices d where d.owner = new.owner and d.install_id = new.install_id) then return new; end if;
  new.revoked_at := null; new.sync_cursor := 0; new.last_sync_at := null;
  v_plan := public.plan_of(new.owner);
  select e.max_devices into v_max from public.plan_entitlements e where e.plan = v_plan;
  if (select count(*) from public.devices d where d.owner = new.owner and d.revoked_at is null) >= v_max then
    raise exception 'PLAN_LIMIT_DEVICES: your % plan allows % registered device(s)', v_plan, v_max using errcode = 'PL002';
  end if;
  return new;
end $$;
revoke execute on function public.enforce_device_limit() from public, anon, authenticated;
drop trigger if exists devices_limit on public.devices;
create trigger devices_limit before insert on public.devices for each row execute function public.enforce_device_limit();

-- Register (or re-register) the calling installation. Binds the device to the current auth session so revocation can end it.
create or replace function public.register_device(p_install_id text, p_name text, p_platform text, p_app_version text, p_os_version text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := (select auth.uid()); v_id uuid; v_rev timestamptz; v_sess uuid;
begin
  if v_uid is null then raise exception 'not authenticated' using errcode = '28000'; end if;
  begin v_sess := nullif((select auth.jwt()) ->> 'session_id', '')::uuid; exception when others then v_sess := null; end;
  select id, revoked_at into v_id, v_rev from public.devices where owner = v_uid and install_id = p_install_id;
  if found then
    if v_rev is not null then raise exception 'DEVICE_REVOKED: this device was removed from your account' using errcode = 'PL003'; end if;
    update public.devices set name = coalesce(left(p_name, 120), name), app_version = p_app_version, os_version = p_os_version,
           last_seen_at = now(), auth_session_id = coalesce(v_sess, auth_session_id) where id = v_id;
    return v_id;
  end if;
  insert into public.devices (owner, install_id, name, platform, app_version, os_version, auth_session_id)
  values (v_uid, p_install_id, left(p_name, 120), coalesce(p_platform, 'android'), p_app_version, p_os_version, v_sess) returning id into v_id;
  return v_id;
end $$;

-- Revoke a device: mark it, then end its auth session so its tokens stop working. (Deleting auth.sessions rows is how GoTrue sessions end.)
create or replace function public.revoke_device(p_device uuid) returns boolean
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := (select auth.uid()); v_sess uuid;
begin
  if v_uid is null then raise exception 'not authenticated' using errcode = '28000'; end if;
  update public.devices set revoked_at = now() where id = p_device and owner = v_uid and revoked_at is null returning auth_session_id into v_sess;
  if not found then return false; end if;
  if v_sess is not null then
    begin delete from auth.sessions where id = v_sess and user_id = v_uid; exception when undefined_table then null; end;
  end if;
  return true;
end $$;

create or replace function public.device_allowed(p_device uuid) returns text    -- 'ok' | 'revoked' | 'suspended' | 'unknown'
language plpgsql stable security definer set search_path = '' as $$
declare d record; v_max integer; v_rank integer;
begin
  select * into d from public.devices where id = p_device;
  if not found then return 'unknown'; end if;
  if d.revoked_at is not null then return 'revoked'; end if;
  select e.max_devices into v_max from public.plan_entitlements e where e.plan = public.plan_of(d.owner);
  select count(*) into v_rank from public.devices x where x.owner = d.owner and x.revoked_at is null and (x.created_at, x.id) <= (d.created_at, d.id);
  return case when v_rank <= v_max then 'ok' else 'suspended' end;
end $$;

create or replace function public.account_allowed(p_account uuid) returns boolean
language plpgsql stable security definer set search_path = '' as $$
declare a record; v_max integer; v_rank integer;
begin
  select * into a from public.provider_accounts where id = p_account;
  if not found or a.status = 'REVOKED' then return false; end if;
  select e.max_profiles into v_max from public.plan_entitlements e where e.plan = public.plan_of(a.owner);
  if v_max is null then return true; end if;
  select count(*) into v_rank from public.provider_accounts x where x.owner = a.owner and x.status <> 'REVOKED' and (x.created_at, x.id) <= (a.created_at, a.id);
  return v_rank <= v_max;
end $$;

-- Account entitlement snapshot for the app and dashboard (own data only).
create or replace function public.my_entitlements() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare v_uid uuid := (select auth.uid()); v_plan text; e record; l record;
begin
  if v_uid is null then raise exception 'not authenticated' using errcode = '28000'; end if;
  v_plan := public.plan_of(v_uid);
  select * into e from public.plan_entitlements where plan = v_plan;
  select * into l from public.ai_plan_limits where plan = v_plan;
  return jsonb_build_object(
    'plan', v_plan, 'expires_at', (select plan_expires_at from public.profiles where id = v_uid),
    'max_profiles', e.max_profiles, 'profiles_used', public.owner_profile_count(v_uid),
    'max_devices', e.max_devices, 'devices_used', (select count(*) from public.devices where owner = v_uid and revoked_at is null),
    'ai_tokens_monthly', l.monthly_tokens, 'auto_sync', e.auto_sync, 'cellular_uploads', e.cellular_uploads,
    'advanced_automation', e.advanced_automation, 'max_pending_jobs', e.max_pending_jobs);
end $$;

---------------------------------------------------------------------------------------------------
-- 4. Durable publishing jobs
---------------------------------------------------------------------------------------------------
create or replace function public.publish_job_transition_allowed(p_from text, p_to text) returns boolean
language sql immutable set search_path = '' as $$
  select exists (select 1 from (values
    ('scheduled','due'),('scheduled','cancelled'),
    ('due','leased'),('due','cancelled'),('due','permanent_failure'),
    ('leased','uploading'),('leased','due'),('leased','retryable_failure'),('leased','permanent_failure'),('leased','cancelled'),
    ('uploading','uploading'),('uploading','submitted'),('uploading','published'),('uploading','due'),('uploading','retryable_failure'),('uploading','permanent_failure'),('uploading','cancelled'),
    ('submitted','submitted'),('submitted','published'),('submitted','permanent_failure'),
    ('retryable_failure','due'),('retryable_failure','permanent_failure'),('retryable_failure','cancelled')
  ) t(f, to_) where t.f = p_from and t.to_ = p_to)
$$;

create table if not exists public.publish_jobs (
  id uuid primary key default gen_random_uuid(),
  owner uuid not null references auth.users(id) on delete cascade,
  post_id text not null check (char_length(post_id) between 1 and 100),
  account_id uuid not null references public.provider_accounts(id) on delete cascade,
  platform text not null check (platform in ('YOUTUBE','INSTAGRAM','TIKTOK','FACEBOOK','THREADS','X','LINKEDIN')),
  status text not null default 'scheduled' check (status in ('scheduled','due','leased','uploading','submitted','published','retryable_failure','permanent_failure','cancelled')),
  run_at timestamptz not null,                      -- exact UTC instant (the app converts local time + zone, DST-safe)
  tz text,                                          -- IANA zone the user scheduled in (display only)
  idempotency_key text not null check (char_length(idempotency_key) between 16 and 128),
  attempts integer not null default 0,
  max_attempts integer not null default 5 check (max_attempts between 1 and 10),
  next_attempt_at timestamptz not null,
  lease_owner text,
  lease_expires_at timestamptz,
  cancel_requested boolean not null default false,
  payload jsonb not null check (pg_column_size(payload) <= 16384),
  media_path text not null,
  provider_state jsonb not null default '{}' check (pg_column_size(provider_state) <= 65536),
  remote_id text, remote_url text,
  last_error_code text, last_error_class text, last_error_message text,
  submitted_at timestamptz, finished_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version integer not null default 1,
  unique (owner, idempotency_key)
);
create index if not exists publish_jobs_due on public.publish_jobs (next_attempt_at) where status in ('scheduled','due','retryable_failure','submitted');
create index if not exists publish_jobs_owner on public.publish_jobs (owner, run_at desc);
create index if not exists publish_jobs_lease on public.publish_jobs (lease_expires_at) where status in ('leased','uploading');
drop trigger if exists publish_jobs_touch on public.publish_jobs;
create trigger publish_jobs_touch before update on public.publish_jobs for each row execute function public.touch_updated_at();

alter table public.publish_jobs enable row level security;
drop policy if exists "jobs owner read" on public.publish_jobs;
create policy "jobs owner read" on public.publish_jobs for select to authenticated using (owner = (select auth.uid()));
revoke all on public.publish_jobs from anon, authenticated;
grant select (id, owner, post_id, account_id, platform, status, run_at, tz, attempts, max_attempts, next_attempt_at, cancel_requested, remote_id, remote_url,
              last_error_code, last_error_class, last_error_message, submitted_at, finished_at, created_at, updated_at, version)
  on public.publish_jobs to authenticated;          -- provider_state, payload, lease columns and media_path stay server-side

create table if not exists public.publish_job_events (
  id bigserial primary key,
  job_id uuid not null references public.publish_jobs(id) on delete cascade,
  owner uuid not null references auth.users(id) on delete cascade,
  at timestamptz not null default now(),
  from_status text, to_status text not null,
  worker text, detail text check (char_length(detail) <= 300)
);
create index if not exists publish_job_events_job on public.publish_job_events (job_id, id);
alter table public.publish_job_events enable row level security;
drop policy if exists "job events owner read" on public.publish_job_events;
create policy "job events owner read" on public.publish_job_events for select to authenticated using (owner = (select auth.uid()));
revoke all on public.publish_job_events from anon, authenticated;
grant select on public.publish_job_events to authenticated;

-- Client entry point: validates ownership, plan and idempotency; the client can never set status/lease/result fields.
create or replace function public.enqueue_publish_job(p_post_id text, p_account_id uuid, p_run_at timestamptz, p_tz text, p_payload jsonb, p_media_path text, p_idem text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := (select auth.uid()); a record; v_id uuid; v_pending integer; v_max integer; v_plan text;
begin
  if v_uid is null then raise exception 'not authenticated' using errcode = '28000'; end if;
  perform pg_advisory_xact_lock(hashtextextended('jobs:' || v_uid::text, 0));
  select * into a from public.provider_accounts where id = p_account_id and owner = v_uid;
  if not found then raise exception 'ACCOUNT_NOT_FOUND' using errcode = 'PL004'; end if;
  if a.status in ('REVOKED','NEEDS_RECONNECT','EXPIRED') then raise exception 'ACCOUNT_NEEDS_RECONNECT' using errcode = 'PL005'; end if;
  if not public.account_allowed(p_account_id) then raise exception 'PLAN_LIMIT_PROFILES: this account is over your plan limit' using errcode = 'PL001'; end if;
  if p_media_path is null or split_part(p_media_path, '/', 1) <> v_uid::text or p_media_path like '%..%' then raise exception 'INVALID_MEDIA_PATH' using errcode = 'PL006'; end if;
  if p_run_at < now() - interval '1 day' or p_run_at > now() + interval '366 days' then raise exception 'INVALID_RUN_AT' using errcode = '22023'; end if;
  select id into v_id from public.publish_jobs where owner = v_uid and idempotency_key = p_idem;
  if found then return v_id; end if;                -- idempotent: same intent returns the same job
  v_plan := public.plan_of(v_uid);
  select e.max_pending_jobs into v_max from public.plan_entitlements e where e.plan = v_plan;
  select count(*) into v_pending from public.publish_jobs where owner = v_uid and status in ('scheduled','due','leased','uploading','retryable_failure');
  if v_max is not null and v_pending >= v_max then raise exception 'PLAN_LIMIT_AUTOMATION: your % plan allows % pending cloud-scheduled posts', v_plan, v_max using errcode = 'PL007'; end if;
  insert into public.publish_jobs (owner, post_id, account_id, platform, run_at, tz, idempotency_key, next_attempt_at, payload, media_path)
  values (v_uid, p_post_id, p_account_id, a.platform, p_run_at, left(p_tz, 64), p_idem, p_run_at, coalesce(p_payload, '{}'::jsonb), p_media_path)
  returning id into v_id;
  insert into public.publish_job_events (job_id, owner, to_status, detail) values (v_id, v_uid, 'scheduled', 'enqueued');
  return v_id;
end $$;

create or replace function public.cancel_publish_job(p_job uuid) returns text
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := (select auth.uid()); j record;
begin
  if v_uid is null then raise exception 'not authenticated' using errcode = '28000'; end if;
  select * into j from public.publish_jobs where id = p_job and owner = v_uid for update;
  if not found then raise exception 'JOB_NOT_FOUND' using errcode = 'PL004'; end if;
  if j.status in ('scheduled','due','retryable_failure') then
    update public.publish_jobs set status = 'cancelled', finished_at = now(), lease_owner = null, lease_expires_at = null, version = version + 1 where id = p_job;
    insert into public.publish_job_events (job_id, owner, from_status, to_status, detail) values (p_job, v_uid, j.status, 'cancelled', 'cancelled by user');
    return 'cancelled';
  elsif j.status in ('leased','uploading') then
    update public.publish_jobs set cancel_requested = true, version = version + 1 where id = p_job;
    insert into public.publish_job_events (job_id, owner, from_status, to_status, detail) values (p_job, v_uid, j.status, j.status, 'cancel requested');
    return 'cancel_requested';
  elsif j.status = 'submitted' then return 'too_late';
  else return 'already_final'; end if;
end $$;

-- Re-run a failed/cancelled job (same idempotency key => still exactly one intended publication). Ambiguous publishes are refused.
create or replace function public.retry_publish_job(p_job uuid, p_run_at timestamptz default now()) returns boolean
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := (select auth.uid()); j record;
begin
  if v_uid is null then raise exception 'not authenticated' using errcode = '28000'; end if;
  select * into j from public.publish_jobs where id = p_job and owner = v_uid for update;
  if not found then raise exception 'JOB_NOT_FOUND' using errcode = 'PL004'; end if;
  if j.status not in ('permanent_failure','cancelled') then return false; end if;
  if j.last_error_code = 'AMBIGUOUS_PUBLISH' then raise exception 'AMBIGUOUS_PUBLISH: check the platform first, it may already be live' using errcode = 'PL008'; end if;
  update public.publish_jobs set status = 'scheduled', attempts = 0, run_at = p_run_at, next_attempt_at = p_run_at, cancel_requested = false, finished_at = null,
         provider_state = '{}', last_error_code = null, last_error_class = null, last_error_message = null, lease_owner = null, lease_expires_at = null, version = version + 1 where id = p_job;
  insert into public.publish_job_events (job_id, owner, from_status, to_status, detail) values (p_job, v_uid, j.status, 'scheduled', 'retried by user');
  return true;
end $$;

-- ---- worker functions (service role only) ----
create or replace function public.promote_due_jobs() returns integer
language plpgsql security definer set search_path = '' as $$
declare n integer;
begin
  with p as (
    update public.publish_jobs j set status = 'due', version = version + 1
     where j.id in (select id from public.publish_jobs where (status = 'scheduled' and run_at <= now()) or (status = 'retryable_failure' and next_attempt_at <= now()) for update skip locked)
    returning j.id, j.owner, j.status
  ) insert into public.publish_job_events (job_id, owner, to_status, detail) select id, owner, 'due', 'time reached' from p;
  get diagnostics n = row_count;
  return n;
end $$;

create or replace function public.recover_stale_jobs() returns integer
language plpgsql security definer set search_path = '' as $$
declare n integer := 0; r record;
begin
  for r in select id, owner, status, attempts, max_attempts from public.publish_jobs
            where status in ('leased','uploading') and lease_expires_at <= now() for update skip locked loop
    update public.publish_jobs set attempts = r.attempts + 1, lease_owner = null, lease_expires_at = null, version = version + 1,
           status = case when r.attempts + 1 >= r.max_attempts then 'permanent_failure' else 'due' end,
           finished_at = case when r.attempts + 1 >= r.max_attempts then now() else null end,
           last_error_code = case when r.attempts + 1 >= r.max_attempts then 'LEASE_EXPIRED' else last_error_code end,
           last_error_class = case when r.attempts + 1 >= r.max_attempts then 'permanent' else last_error_class end,
           last_error_message = case when r.attempts + 1 >= r.max_attempts then 'The worker stopped repeatedly while processing this post.' else last_error_message end
     where id = r.id;
    insert into public.publish_job_events (job_id, owner, from_status, to_status, detail)
      values (r.id, r.owner, r.status, case when r.attempts + 1 >= r.max_attempts then 'permanent_failure' else 'due' end, 'stale lease recovered');
    n := n + 1;
  end loop;
  return n;
end $$;

create or replace function public.claim_publish_jobs(p_worker text, p_limit integer, p_lease_seconds integer) returns table (
  id uuid, owner uuid, account_id uuid, platform text, post_id text, status text, attempts integer, max_attempts integer, payload jsonb, provider_state jsonb,
  media_path text, cancel_requested boolean, lease_owner text, account_external_id text, account_meta jsonb)
language plpgsql security definer set search_path = '' as $$
begin
  return query
  with c as (
    select j.id as jid from public.publish_jobs j join public.provider_accounts a on a.id = j.account_id
     where j.status = 'due' and j.next_attempt_at <= now() and a.status in ('CONNECTED','EXPIRING') and (a.blocked_until is null or a.blocked_until <= now())
     order by j.run_at, j.id limit greatest(1, least(p_limit, 50)) for update of j skip locked
  ), u as (
    update public.publish_jobs j set status = 'leased', lease_owner = p_worker, lease_expires_at = now() + make_interval(secs => greatest(10, p_lease_seconds)), version = j.version + 1
     from c where j.id = c.jid returning j.*
  ), ev as (
    insert into public.publish_job_events (job_id, owner, from_status, to_status, worker, detail) select u.id, u.owner, 'due', 'leased', p_worker, 'claimed' from u returning 1
  )
  select u.id, u.owner, u.account_id, u.platform, u.post_id, u.status, u.attempts, u.max_attempts, u.payload, u.provider_state, u.media_path, u.cancel_requested, u.lease_owner,
         a.external_id, a.meta
    from u join public.provider_accounts a on a.id = u.account_id;
end $$;

create or replace function public.claim_poll_jobs(p_worker text, p_limit integer, p_lease_seconds integer) returns table (
  id uuid, owner uuid, account_id uuid, platform text, post_id text, status text, attempts integer, max_attempts integer, payload jsonb, provider_state jsonb,
  media_path text, cancel_requested boolean, lease_owner text, account_external_id text, account_meta jsonb, submitted_at_ms bigint)
language plpgsql security definer set search_path = '' as $$
begin
  return query
  with c as (
    select j.id as jid from public.publish_jobs j
     where j.status = 'submitted' and j.next_attempt_at <= now() and (j.lease_owner is null or j.lease_expires_at <= now())
     order by j.next_attempt_at, j.id limit greatest(1, least(p_limit, 50)) for update skip locked
  ), u as (
    update public.publish_jobs j set lease_owner = p_worker, lease_expires_at = now() + make_interval(secs => greatest(10, p_lease_seconds))
     from c where j.id = c.jid returning j.*
  )
  select u.id, u.owner, u.account_id, u.platform, u.post_id, u.status, u.attempts, u.max_attempts, u.payload, u.provider_state, u.media_path, u.cancel_requested, u.lease_owner,
         a.external_id, a.meta, (extract(epoch from coalesce(u.submitted_at, u.updated_at)) * 1000)::bigint
    from u join public.provider_accounts a on a.id = u.account_id;
end $$;

-- The only way a worker changes a job: lease must be held and unexpired, transition must be legal. p_patch keys:
-- providerState, error{code,message,cls}, nextAttemptAt (ISO), remoteId, url, incAttempts, detail
create or replace function public.apply_job_transition(p_id uuid, p_worker text, p_to text, p_patch jsonb) returns boolean
language plpgsql security definer set search_path = '' as $$
declare j record; v_final boolean; v_keep_lease boolean; v_next timestamptz; v_post_status text;
begin
  select * into j from public.publish_jobs where id = p_id for update;
  if not found then return false; end if;
  if j.lease_owner is distinct from p_worker or j.lease_expires_at is null or j.lease_expires_at <= now() then return false; end if;
  if not public.publish_job_transition_allowed(j.status, p_to) then
    raise exception 'illegal job transition % -> %', j.status, p_to using errcode = 'PL009';
  end if;
  v_final := p_to in ('published','permanent_failure','cancelled');
  v_keep_lease := p_to in ('leased','uploading');
  v_next := coalesce(nullif(p_patch ->> 'nextAttemptAt', '')::timestamptz, j.next_attempt_at);
  update public.publish_jobs set
    status = p_to,
    provider_state = coalesce(p_patch -> 'providerState', provider_state),
    attempts = attempts + case when coalesce((p_patch ->> 'incAttempts')::boolean, false) then 1 else 0 end,
    next_attempt_at = v_next,
    remote_id = coalesce(p_patch ->> 'remoteId', remote_id),
    remote_url = coalesce(p_patch ->> 'url', remote_url),
    last_error_code = case when p_patch ? 'error' then left(p_patch #>> '{error,code}', 80) else last_error_code end,
    last_error_class = case when p_patch ? 'error' then left(p_patch #>> '{error,cls}', 40) else last_error_class end,
    last_error_message = case when p_patch ? 'error' then left(p_patch #>> '{error,message}', 300) else last_error_message end,
    submitted_at = case when p_to = 'submitted' and submitted_at is null then now() else submitted_at end,
    finished_at = case when v_final then now() else finished_at end,
    lease_owner = case when v_keep_lease then lease_owner else null end,
    lease_expires_at = case when v_keep_lease then now() + interval '2 minutes' else null end,
    version = version + 1
  where id = p_id;
  -- Mirror the outcome onto the synced post row so every device and the dashboard see it (status/remote id/error are server-owned fields).
  v_post_status := case when p_to = 'published' then 'DONE' when p_to = 'cancelled' then 'CANCELED'
                        when p_to = 'permanent_failure' then case when p_patch #>> '{error,cls}' = 'auth' then 'NEEDS_AUTH' else 'FAILED' end
                        when p_to in ('uploading','submitted') then 'UPLOADING' else 'QUEUED' end;
  update public.post_items set
    status = v_post_status,
    remote_id = coalesce(p_patch ->> 'remoteId', remote_id),
    error = case when p_to in ('permanent_failure','retryable_failure') then left(p_patch #>> '{error,message}', 300) when p_to = 'published' then null else error end,
    finished_at = case when v_final then now() else finished_at end,
    progress = case when p_to = 'published' then 1 else progress end
  where owner = j.owner and id = j.post_id and (status is distinct from v_post_status or v_final or p_to = 'retryable_failure');
  if j.status is distinct from p_to or (p_patch ->> 'detail') is distinct from 'progress' then
    insert into public.publish_job_events (job_id, owner, from_status, to_status, worker, detail) values (p_id, j.owner, j.status, p_to, p_worker, left(p_patch ->> 'detail', 300));
  end if;
  return true;
end $$;

create or replace function public.job_account_allowed(p_job uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select coalesce((select public.account_allowed(j.account_id) from public.publish_jobs j where j.id = p_job), false)
$$;
create or replace function public.job_cancel_requested(p_job uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select coalesce((select cancel_requested from public.publish_jobs where id = p_job), false)
$$;
create or replace function public.block_provider_account(p_account uuid, p_until timestamptz, p_code text) returns void
language sql security definer set search_path = '' as $$
  update public.provider_accounts set blocked_until = greatest(coalesce(blocked_until, p_until), p_until), last_error_code = left(p_code, 80) where id = p_account
$$;
create or replace function public.mark_account_needs_reconnect(p_account uuid, p_code text) returns void
language sql security definer set search_path = '' as $$
  update public.provider_accounts set status = 'NEEDS_RECONNECT', last_error_code = left(p_code, 80) where id = p_account and status <> 'REVOKED'
$$;

---------------------------------------------------------------------------------------------------
-- 5. Notifications (deduplicated, delivery state tracked) + user preferences
---------------------------------------------------------------------------------------------------
create table if not exists public.notification_prefs (
  owner uuid primary key references auth.users(id) on delete cascade,
  prefs jsonb not null default '{}' check (pg_column_size(prefs) <= 4096),
  updated_at timestamptz not null default now()
);
alter table public.notification_prefs enable row level security;
drop policy if exists "prefs owner all" on public.notification_prefs;
create policy "prefs owner all" on public.notification_prefs for all to authenticated using (owner = (select auth.uid())) with check (owner = (select auth.uid()));
revoke all on public.notification_prefs from anon, authenticated;
grant select, insert, update on public.notification_prefs to authenticated;

create table if not exists public.notifications (
  id uuid primary key default gen_random_uuid(),
  owner uuid not null references auth.users(id) on delete cascade,
  kind text not null check (kind in ('publish_success','publish_failure','reminder','token_expiring','account_error','sync_problem','upload_progress')),
  dedupe_key text not null check (char_length(dedupe_key) <= 200),
  title text not null check (char_length(title) <= 120),
  body text not null check (char_length(body) <= 400),
  ref text,
  created_at timestamptz not null default now(),
  delivered_at timestamptz,          -- set by the device when it showed the system notification
  read_at timestamptz,
  unique (owner, dedupe_key)
);
create index if not exists notifications_owner on public.notifications (owner, created_at desc);
alter table public.notifications enable row level security;
drop policy if exists "notifications owner read" on public.notifications;
drop policy if exists "notifications owner update" on public.notifications;
create policy "notifications owner read" on public.notifications for select to authenticated using (owner = (select auth.uid()));
create policy "notifications owner update" on public.notifications for update to authenticated using (owner = (select auth.uid())) with check (owner = (select auth.uid()));
revoke all on public.notifications from anon, authenticated;
grant select on public.notifications to authenticated;
grant update (delivered_at, read_at) on public.notifications to authenticated;

create or replace function public.create_notification(p_owner uuid, p_kind text, p_dedupe text, p_title text, p_body text, p_ref text) returns boolean
language plpgsql security definer set search_path = '' as $$
declare v_prefs jsonb; n integer;
begin
  select prefs into v_prefs from public.notification_prefs where owner = p_owner;
  if coalesce(v_prefs ->> p_kind, 'true') = 'false' then return false; end if;       -- the user switched this kind off
  insert into public.notifications (owner, kind, dedupe_key, title, body, ref) values (p_owner, p_kind, left(p_dedupe, 200), left(p_title, 120), left(p_body, 400), left(p_ref, 200))
  on conflict (owner, dedupe_key) do nothing;
  get diagnostics n = row_count;
  return n = 1;
end $$;

---------------------------------------------------------------------------------------------------
-- 6. Sync: revisions, tombstones, device cursors, conflicts. Writes go through the `sync` Edge Function (service role).
---------------------------------------------------------------------------------------------------
create sequence if not exists public.sync_seq;

alter table public.post_items add column if not exists rev bigint not null default 1;
alter table public.post_items add column if not exists deleted_at timestamptz;
alter table public.post_items add column if not exists server_seq bigint not null default 0;
alter table public.post_items add column if not exists updated_by_device uuid;
-- Backfill sequence numbers for existing rows without touching updated_at/rev (user triggers are disabled for this one atomic statement).
do $$ begin
  if exists (select 1 from public.post_items where server_seq = 0) then
    alter table public.post_items disable trigger user;
    update public.post_items set server_seq = nextval('public.sync_seq') where server_seq = 0;
    alter table public.post_items enable trigger user;
  end if;
end $$;
create index if not exists post_items_sync on public.post_items (owner, server_seq);

create or replace function public.bump_sync_columns() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  new.server_seq := nextval('public.sync_seq');
  if tg_op = 'UPDATE' then new.rev := coalesce(old.rev, 0) + 1; end if;
  return new;
end $$;
drop trigger if exists post_items_sync_bump on public.post_items;
create trigger post_items_sync_bump before insert or update on public.post_items for each row execute function public.bump_sync_columns();

create table if not exists public.sync_documents (
  owner uuid not null references auth.users(id) on delete cascade,
  kind text not null check (kind in ('draft','media_meta','preferences')),
  id text not null check (char_length(id) between 1 and 100),
  rev bigint not null default 1,
  deleted_at timestamptz,
  data jsonb not null default '{}' check (pg_column_size(data) <= 65536),
  client_updated_at timestamptz,
  updated_by_device uuid,
  server_seq bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (owner, kind, id)
);
create index if not exists sync_documents_seq on public.sync_documents (owner, server_seq);
drop trigger if exists sync_documents_bump on public.sync_documents;
create trigger sync_documents_bump before insert or update on public.sync_documents for each row execute function public.bump_sync_columns();
alter table public.sync_documents enable row level security;
drop policy if exists "sync docs owner read" on public.sync_documents;
create policy "sync docs owner read" on public.sync_documents for select to authenticated using (owner = (select auth.uid()));
revoke all on public.sync_documents from anon, authenticated;
grant select on public.sync_documents to authenticated;

create table if not exists public.sync_ops (
  device_id uuid not null references public.devices(id) on delete cascade,
  op_id text not null check (char_length(op_id) <= 100),
  owner uuid not null references auth.users(id) on delete cascade,
  result jsonb not null,
  at timestamptz not null default now(),
  primary key (device_id, op_id)
);
alter table public.sync_ops enable row level security;
revoke all on public.sync_ops from anon, authenticated;

create table if not exists public.sync_conflicts (
  id uuid primary key default gen_random_uuid(),
  owner uuid not null references auth.users(id) on delete cascade,
  entity text not null, entity_id text not null,
  kind text not null check (kind in ('field','delete_vs_edit')),
  field text, server_value jsonb, client_value jsonb,
  resolved_with text not null check (resolved_with in ('server','client','kept_record')),
  device_id uuid references public.devices(id) on delete set null,
  created_at timestamptz not null default now(),
  dismissed_at timestamptz
);
create index if not exists sync_conflicts_owner on public.sync_conflicts (owner, created_at desc) where dismissed_at is null;
alter table public.sync_conflicts enable row level security;
drop policy if exists "conflicts owner read" on public.sync_conflicts;
drop policy if exists "conflicts owner dismiss" on public.sync_conflicts;
create policy "conflicts owner read" on public.sync_conflicts for select to authenticated using (owner = (select auth.uid()));
create policy "conflicts owner dismiss" on public.sync_conflicts for update to authenticated using (owner = (select auth.uid())) with check (owner = (select auth.uid()));
revoke all on public.sync_conflicts from anon, authenticated;
grant select on public.sync_conflicts to authenticated;
grant update (dismissed_at) on public.sync_conflicts to authenticated;

---------------------------------------------------------------------------------------------------
-- 7. Private media bucket for server-side publishing (binary transfer is separate from metadata sync)
---------------------------------------------------------------------------------------------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types) values
  ('publish-media', 'publish-media', false, 524288000, array['video/mp4','video/quicktime','video/webm'])
on conflict (id) do nothing;
drop policy if exists "publish media owner read" on storage.objects;
drop policy if exists "publish media owner insert" on storage.objects;
drop policy if exists "publish media owner delete" on storage.objects;
create policy "publish media owner read"   on storage.objects for select to authenticated using (bucket_id = 'publish-media' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "publish media owner insert" on storage.objects for insert to authenticated with check (bucket_id = 'publish-media' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "publish media owner delete" on storage.objects for delete to authenticated using (bucket_id = 'publish-media' and (storage.foldername(name))[1] = (select auth.uid())::text);

---------------------------------------------------------------------------------------------------
-- 8. Function privileges: client RPCs for authenticated, everything else service role only
---------------------------------------------------------------------------------------------------
-- Postgres grants EXECUTE to PUBLIC by default and Supabase's default ACL also grants it to anon/authenticated/service_role:
-- close both for every function this migration adds, then grant back exactly what each caller needs.
revoke execute on function
  public.plan_of(uuid), public.admin_set_plan(uuid, text, timestamptz, text), public.expire_plans(), public.owner_profile_count(uuid),
  public.enforce_profile_limit(), public.enforce_device_limit(), public.register_device(text, text, text, text, text), public.revoke_device(uuid),
  public.device_allowed(uuid), public.account_allowed(uuid), public.my_entitlements(), public.publish_job_transition_allowed(text, text),
  public.enqueue_publish_job(text, uuid, timestamptz, text, jsonb, text, text), public.cancel_publish_job(uuid), public.retry_publish_job(uuid, timestamptz),
  public.promote_due_jobs(), public.recover_stale_jobs(), public.claim_publish_jobs(text, integer, integer), public.claim_poll_jobs(text, integer, integer),
  public.apply_job_transition(uuid, text, text, jsonb), public.job_account_allowed(uuid), public.job_cancel_requested(uuid),
  public.block_provider_account(uuid, timestamptz, text), public.mark_account_needs_reconnect(uuid, text),
  public.create_notification(uuid, text, text, text, text, text), public.bump_sync_columns()
  from public, anon, authenticated;
do $$ begin
  grant execute on function public.register_device(text, text, text, text, text) to authenticated;
  grant execute on function public.revoke_device(uuid) to authenticated;
  grant execute on function public.my_entitlements() to authenticated;
  grant execute on function public.enqueue_publish_job(text, uuid, timestamptz, text, jsonb, text, text) to authenticated;
  grant execute on function public.cancel_publish_job(uuid) to authenticated;
  grant execute on function public.retry_publish_job(uuid, timestamptz) to authenticated;
  grant execute on function public.admin_set_plan(uuid, text, timestamptz, text) to service_role;
  grant execute on function public.expire_plans() to service_role;
  grant execute on function public.plan_of(uuid) to service_role;
  grant execute on function public.device_allowed(uuid) to service_role;
  grant execute on function public.account_allowed(uuid) to service_role;
  grant execute on function public.owner_profile_count(uuid) to service_role;
  grant execute on function public.promote_due_jobs() to service_role;
  grant execute on function public.recover_stale_jobs() to service_role;
  grant execute on function public.claim_publish_jobs(text, integer, integer) to service_role;
  grant execute on function public.claim_poll_jobs(text, integer, integer) to service_role;
  grant execute on function public.apply_job_transition(uuid, text, text, jsonb) to service_role;
  grant execute on function public.job_account_allowed(uuid) to service_role;
  grant execute on function public.job_cancel_requested(uuid) to service_role;
  grant execute on function public.block_provider_account(uuid, timestamptz, text) to service_role;
  grant execute on function public.mark_account_needs_reconnect(uuid, text) to service_role;
  grant execute on function public.create_notification(uuid, text, text, text, text, text) to service_role;
  grant execute on function public.publish_job_transition_allowed(text, text) to service_role, authenticated;
end $$;
grant all on public.provider_accounts, public.oauth_states, public.publish_jobs, public.publish_job_events, public.notifications, public.notification_prefs,
      public.sync_documents, public.sync_ops, public.sync_conflicts, public.plan_audit, public.plan_entitlements to service_role;
grant usage, select on all sequences in schema public to service_role;
grant select, update on public.profiles, public.devices, public.post_items to service_role;

---------------------------------------------------------------------------------------------------
-- 9. Realtime (push instead of polling) where the Supabase publication exists
---------------------------------------------------------------------------------------------------
do $$ begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    begin alter publication supabase_realtime add table public.notifications; exception when duplicate_object then null; end;
    begin alter publication supabase_realtime add table public.publish_jobs; exception when duplicate_object then null; end;
    begin alter publication supabase_realtime add table public.sync_documents; exception when duplicate_object then null; end;
  end if;
end $$;
