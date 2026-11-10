-- AutoPoster 0.7 AI Creator Studio: brand profiles, AI token quota (ledger, reservation, rate limit).
-- Safe to run more than once. Additive only. Requires 001 (public.touch_updated_at) and 002 (public.profiles).
-- The quota functions are callable ONLY by the service role (the ai-gateway Edge Function). The app can never move its own counters.

begin;

---------------------------------------------------------------------------------------------------
-- Brand profiles (owner-only CRUD; the app is offline-first and supplies the id)
---------------------------------------------------------------------------------------------------
create table if not exists public.brand_profiles (
  id uuid primary key default gen_random_uuid(),
  owner uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 80),
  description text not null default '' check (char_length(description) <= 1000),
  tone text not null default 'casual' check (tone in ('professional', 'funny', 'casual', 'educational', 'marketing', 'inspirational', 'minimal')),
  target_audience text not null default '' check (char_length(target_audience) <= 300),
  default_hashtags text not null default '' check (char_length(default_hashtags) <= 500),
  language text not null default 'en' check (char_length(language) between 2 and 12),
  default_cta text not null default '' check (char_length(default_cta) <= 200),
  signature text not null default '' check (char_length(signature) <= 300),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists brand_profiles_owner on public.brand_profiles (owner, updated_at desc);

drop trigger if exists brand_profiles_touch on public.brand_profiles;
create trigger brand_profiles_touch before update on public.brand_profiles
for each row execute function public.touch_updated_at();

alter table public.brand_profiles enable row level security;
drop policy if exists "owner can read brands" on public.brand_profiles;
drop policy if exists "owner can insert brands" on public.brand_profiles;
drop policy if exists "owner can update brands" on public.brand_profiles;
drop policy if exists "owner can delete brands" on public.brand_profiles;
create policy "owner can read brands"   on public.brand_profiles for select to authenticated using (owner = (select auth.uid()));
create policy "owner can insert brands" on public.brand_profiles for insert to authenticated with check (owner = (select auth.uid()));
create policy "owner can update brands" on public.brand_profiles for update to authenticated
  using (owner = (select auth.uid())) with check (owner = (select auth.uid()));
create policy "owner can delete brands" on public.brand_profiles for delete to authenticated using (owner = (select auth.uid()));

revoke all on public.brand_profiles from anon;

---------------------------------------------------------------------------------------------------
-- AI plan limits. profiles.plan: 'free' = Free; 'pro' and 'agency' = Premium.
---------------------------------------------------------------------------------------------------
create table if not exists public.ai_plan_limits (
  plan text primary key check (plan in ('free', 'pro', 'agency')),
  monthly_tokens bigint not null check (monthly_tokens >= 0),
  requests_per_minute integer not null check (requests_per_minute > 0),
  max_request_tokens integer not null check (max_request_tokens > 0)
);

insert into public.ai_plan_limits (plan, monthly_tokens, requests_per_minute, max_request_tokens) values
  ('free',   5000,   6,  2500),
  ('pro',    500000, 30, 6000),
  ('agency', 500000, 30, 6000)
on conflict (plan) do nothing;

alter table public.ai_plan_limits enable row level security;
drop policy if exists "limits readable" on public.ai_plan_limits;
create policy "limits readable" on public.ai_plan_limits for select to authenticated using (true);
revoke all on public.ai_plan_limits from anon, authenticated;
grant select on public.ai_plan_limits to authenticated;

---------------------------------------------------------------------------------------------------
-- Usage per owner per calendar month (UTC). A new month simply starts a new row: that is the monthly reset.
---------------------------------------------------------------------------------------------------
create table if not exists public.ai_usage (
  owner uuid not null references auth.users(id) on delete cascade,
  period_start date not null,
  used_tokens bigint not null default 0 check (used_tokens >= 0),
  reserved_tokens bigint not null default 0 check (reserved_tokens >= 0),
  updated_at timestamptz not null default now(),
  primary key (owner, period_start)
);

alter table public.ai_usage enable row level security;
drop policy if exists "owner can read usage" on public.ai_usage;
create policy "owner can read usage" on public.ai_usage for select to authenticated using (owner = (select auth.uid()));
revoke all on public.ai_usage from anon, authenticated;
grant select on public.ai_usage to authenticated;

-- One row per AI call: the audit trail behind every counter change.
create table if not exists public.ai_requests (
  id uuid primary key default gen_random_uuid(),
  owner uuid not null references auth.users(id) on delete cascade,
  period_start date not null,
  task text not null check (char_length(task) <= 40),
  status text not null default 'reserved' check (status in ('reserved', 'settled', 'failed', 'expired')),
  reserved_tokens integer not null check (reserved_tokens > 0),
  actual_tokens integer,
  error text check (char_length(error) <= 200),
  created_at timestamptz not null default now(),
  settled_at timestamptz
);

create index if not exists ai_requests_owner_created on public.ai_requests (owner, created_at desc);
create index if not exists ai_requests_open on public.ai_requests (owner, status) where status = 'reserved';

alter table public.ai_requests enable row level security;
revoke all on public.ai_requests from anon, authenticated;   -- no client access at all; service role only

---------------------------------------------------------------------------------------------------
-- Functions (service role only)
---------------------------------------------------------------------------------------------------
create or replace function public.ai_usage_status(p_owner uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_plan text; v_limit bigint; v_rpm integer; v_max integer; v_period date; v_used bigint := 0; v_reserved bigint := 0;
begin
  select coalesce((select p.plan from public.profiles p where p.id = p_owner), 'free') into v_plan;
  select l.monthly_tokens, l.requests_per_minute, l.max_request_tokens into v_limit, v_rpm, v_max
    from public.ai_plan_limits l where l.plan = v_plan;
  if not found then
    v_plan := 'free';
    select l.monthly_tokens, l.requests_per_minute, l.max_request_tokens into v_limit, v_rpm, v_max
      from public.ai_plan_limits l where l.plan = 'free';
  end if;
  v_period := (date_trunc('month', now() at time zone 'utc'))::date;
  select u.used_tokens, u.reserved_tokens into v_used, v_reserved
    from public.ai_usage u where u.owner = p_owner and u.period_start = v_period;
  v_used := coalesce(v_used, 0); v_reserved := coalesce(v_reserved, 0);
  return jsonb_build_object(
    'plan', v_plan, 'limit', v_limit, 'used', v_used, 'reserved', v_reserved,
    'remaining', greatest(0, v_limit - v_used - v_reserved),
    'period_start', v_period,
    'resets_at', ((v_period + interval '1 month')::timestamp at time zone 'utc'),
    'requests_per_minute', v_rpm, 'max_request_tokens', v_max);
end $$;

-- Reserves tokens before the provider is called. Serialised per owner by locking the usage row.
-- Returns {allowed, reason, request_id, <usage fields>, retry_after}. reason: ok | rate_limited | too_large | quota_exceeded
create or replace function public.ai_reserve(p_owner uuid, p_task text, p_estimate integer) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_plan text; v_limit bigint; v_rpm integer; v_max integer; v_period date;
  v_used bigint; v_reserved bigint; v_recent integer; v_oldest timestamptz; v_id uuid; v_retry integer;
begin
  if p_estimate is null or p_estimate <= 0 then raise exception 'invalid_estimate'; end if;

  select coalesce((select p.plan from public.profiles p where p.id = p_owner), 'free') into v_plan;
  select l.monthly_tokens, l.requests_per_minute, l.max_request_tokens into v_limit, v_rpm, v_max
    from public.ai_plan_limits l where l.plan = v_plan;
  if not found then
    v_plan := 'free';
    select l.monthly_tokens, l.requests_per_minute, l.max_request_tokens into v_limit, v_rpm, v_max
      from public.ai_plan_limits l where l.plan = 'free';
  end if;

  v_period := (date_trunc('month', now() at time zone 'utc'))::date;
  insert into public.ai_usage (owner, period_start) values (p_owner, v_period) on conflict do nothing;
  select u.used_tokens, u.reserved_tokens into v_used, v_reserved
    from public.ai_usage u where u.owner = p_owner and u.period_start = v_period for update;

  -- Self-healing: a reservation nobody settled within 5 minutes is released (uncharged).
  with expired as (
    update public.ai_requests r set status = 'expired', settled_at = now(), error = 'reservation_expired'
    where r.owner = p_owner and r.status = 'reserved' and r.created_at < now() - interval '5 minutes'
    returning r.period_start, r.reserved_tokens
  )
  update public.ai_usage u set reserved_tokens = greatest(0, u.reserved_tokens - e.t), updated_at = now()
  from (select x.period_start, sum(x.reserved_tokens)::bigint as t from expired x group by x.period_start) e
  where u.owner = p_owner and u.period_start = e.period_start;

  select u.used_tokens, u.reserved_tokens into v_used, v_reserved
    from public.ai_usage u where u.owner = p_owner and u.period_start = v_period;

  -- Rate limit: every attempt in the last 60 seconds counts, whatever its outcome.
  select count(*), min(r.created_at) into v_recent, v_oldest
    from public.ai_requests r where r.owner = p_owner and r.created_at > now() - interval '60 seconds';
  if v_recent >= v_rpm then
    v_retry := greatest(1, ceil(extract(epoch from (v_oldest + interval '60 seconds' - now())))::integer);
    return public.ai_usage_status(p_owner) || jsonb_build_object('allowed', false, 'reason', 'rate_limited', 'request_id', null, 'retry_after', v_retry);
  end if;

  if p_estimate > v_max then
    return public.ai_usage_status(p_owner) || jsonb_build_object('allowed', false, 'reason', 'too_large', 'request_id', null, 'retry_after', 0);
  end if;

  if v_used + v_reserved + p_estimate > v_limit then
    return public.ai_usage_status(p_owner) || jsonb_build_object('allowed', false, 'reason', 'quota_exceeded', 'request_id', null, 'retry_after', 0);
  end if;

  insert into public.ai_requests (owner, period_start, task, reserved_tokens)
    values (p_owner, v_period, left(p_task, 40), p_estimate) returning id into v_id;
  update public.ai_usage u set reserved_tokens = u.reserved_tokens + p_estimate, updated_at = now()
    where u.owner = p_owner and u.period_start = v_period;

  return public.ai_usage_status(p_owner) || jsonb_build_object('allowed', true, 'reason', 'ok', 'request_id', v_id, 'retry_after', 0);
end $$;

-- Converts a reservation into real usage. Idempotent. A late settle after expiry still charges (the provider did the work).
create or replace function public.ai_settle(p_request uuid, p_actual integer) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  r public.ai_requests%rowtype; v_actual integer := greatest(coalesce(p_actual, 0), 0);
begin
  select * into r from public.ai_requests where id = p_request for update;
  if not found then return jsonb_build_object('ok', false, 'error', 'not_found'); end if;
  if r.status in ('settled', 'failed') then
    return public.ai_usage_status(r.owner) || jsonb_build_object('ok', true, 'already', r.status);
  end if;

  update public.ai_usage u set
    reserved_tokens = case when r.status = 'reserved' then greatest(0, u.reserved_tokens - r.reserved_tokens) else u.reserved_tokens end,
    used_tokens = u.used_tokens + v_actual, updated_at = now()
  where u.owner = r.owner and u.period_start = r.period_start;

  update public.ai_requests set status = 'settled', actual_tokens = v_actual, settled_at = now() where id = r.id;
  return public.ai_usage_status(r.owner) || jsonb_build_object('ok', true);
end $$;

-- A failed call: frees the reservation and charges only what the provider reports it consumed (usually 0). Idempotent.
create or replace function public.ai_release(p_request uuid, p_reason text, p_charge integer default 0) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  r public.ai_requests%rowtype; v_charge integer := greatest(coalesce(p_charge, 0), 0);
begin
  select * into r from public.ai_requests where id = p_request for update;
  if not found then return jsonb_build_object('ok', false, 'error', 'not_found'); end if;
  if r.status in ('settled', 'failed') then
    return public.ai_usage_status(r.owner) || jsonb_build_object('ok', true, 'already', r.status);
  end if;

  update public.ai_usage u set
    reserved_tokens = case when r.status = 'reserved' then greatest(0, u.reserved_tokens - r.reserved_tokens) else u.reserved_tokens end,
    used_tokens = u.used_tokens + v_charge, updated_at = now()
  where u.owner = r.owner and u.period_start = r.period_start;

  update public.ai_requests set status = 'failed', actual_tokens = v_charge, error = left(coalesce(p_reason, 'failed'), 200), settled_at = now()
    where id = r.id;
  return public.ai_usage_status(r.owner) || jsonb_build_object('ok', true);
end $$;

revoke execute on function public.ai_usage_status(uuid) from public, anon, authenticated;
revoke execute on function public.ai_reserve(uuid, text, integer) from public, anon, authenticated;
revoke execute on function public.ai_settle(uuid, integer) from public, anon, authenticated;
revoke execute on function public.ai_release(uuid, text, integer) from public, anon, authenticated;
grant execute on function public.ai_usage_status(uuid) to service_role;
grant execute on function public.ai_reserve(uuid, text, integer) to service_role;
grant execute on function public.ai_settle(uuid, integer) to service_role;
grant execute on function public.ai_release(uuid, text, integer) to service_role;

commit;
