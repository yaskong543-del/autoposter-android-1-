-- AutoPoster 0.6 foundation: profiles, devices, social-account metadata, private storage, indexes, account deletion.
-- Requires 001_post_items.sql (uses public.touch_updated_at). Metadata only: no OAuth tokens, no video files.

---------------------------------------------------------------------------------------------------
-- Profiles (one row per auth user; created by trigger; plan is NOT writable by the user)
---------------------------------------------------------------------------------------------------
create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text check (char_length(display_name) <= 80),
  avatar_path text,
  language text not null default 'system' check (language in ('system', 'en', 'hi')),
  plan text not null default 'free' check (plan in ('free', 'pro', 'agency')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create trigger profiles_touch before update on public.profiles
for each row execute function public.touch_updated_at();

alter table public.profiles enable row level security;
create policy "profile owner can read" on public.profiles for select to authenticated using (id = (select auth.uid()));
create policy "profile owner can update" on public.profiles for update to authenticated
  using (id = (select auth.uid())) with check (id = (select auth.uid()));

-- Column-level privileges: users may edit only these three columns (never `plan`).
revoke all on public.profiles from anon, authenticated;
grant select on public.profiles to authenticated;
grant update (display_name, avatar_path, language) on public.profiles to authenticated;

create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id, display_name)
  values (
    new.id,
    left(coalesce(nullif(new.raw_user_meta_data ->> 'full_name', ''), nullif(new.raw_user_meta_data ->> 'name', ''), split_part(coalesce(new.email, ''), '@', 1)), 80)
  )
  on conflict (id) do nothing;
  return new;
end $$;

revoke execute on function public.handle_new_user() from public, anon, authenticated;

create trigger on_auth_user_created after insert on auth.users
for each row execute function public.handle_new_user();

-- Users who signed up before this migration.
insert into public.profiles (id, display_name)
select id, left(split_part(coalesce(email, ''), '@', 1), 80) from auth.users
on conflict (id) do nothing;

---------------------------------------------------------------------------------------------------
-- Registered devices (one row per app installation)
---------------------------------------------------------------------------------------------------
create table public.devices (
  id uuid primary key default gen_random_uuid(),
  owner uuid not null default auth.uid() references auth.users(id) on delete cascade,
  install_id text not null check (char_length(install_id) between 8 and 64),
  name text check (char_length(name) <= 120),
  platform text not null default 'android' check (platform in ('android', 'ios', 'web')),
  app_version text,
  os_version text,
  last_seen_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  unique (owner, install_id)
);

create index devices_owner_last_seen on public.devices (owner, last_seen_at desc);

alter table public.devices enable row level security;
create policy "owner can read devices"   on public.devices for select to authenticated using (owner = (select auth.uid()));
create policy "owner can insert devices" on public.devices for insert to authenticated with check (owner = (select auth.uid()));
create policy "owner can update devices" on public.devices for update to authenticated
  using (owner = (select auth.uid())) with check (owner = (select auth.uid()));
create policy "owner can delete devices" on public.devices for delete to authenticated using (owner = (select auth.uid()));

---------------------------------------------------------------------------------------------------
-- Connected social accounts: metadata only (the tokens never leave the phone)
---------------------------------------------------------------------------------------------------
create table public.social_accounts (
  owner uuid not null default auth.uid() references auth.users(id) on delete cascade,
  id text not null,
  platform text not null check (platform in ('YOUTUBE', 'INSTAGRAM', 'TIKTOK')),
  label text not null check (char_length(label) <= 120),
  status text not null default 'UNKNOWN' check (status in ('CONNECTED', 'EXPIRING', 'EXPIRED', 'NEEDS_RECONNECT', 'UNKNOWN')),
  expires_at timestamptz,
  external_id text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (owner, id)
);

create index social_accounts_owner_platform on public.social_accounts (owner, platform);

create trigger social_accounts_touch before update on public.social_accounts
for each row execute function public.touch_updated_at();

alter table public.social_accounts enable row level security;
create policy "owner can read accounts"   on public.social_accounts for select to authenticated using (owner = (select auth.uid()));
create policy "owner can insert accounts" on public.social_accounts for insert to authenticated with check (owner = (select auth.uid()));
create policy "owner can update accounts" on public.social_accounts for update to authenticated
  using (owner = (select auth.uid())) with check (owner = (select auth.uid()));
create policy "owner can delete accounts" on public.social_accounts for delete to authenticated using (owner = (select auth.uid()));

---------------------------------------------------------------------------------------------------
-- Post metadata: extra indexes for the queue/dashboard queries (table comes from 001)
---------------------------------------------------------------------------------------------------
create index if not exists post_items_owner_status   on public.post_items (owner, status);
create index if not exists post_items_owner_campaign on public.post_items (owner, campaign_id);
create index if not exists post_items_owner_account  on public.post_items (owner, account_label);

---------------------------------------------------------------------------------------------------
-- Private storage buckets; each user may only touch objects under a folder named after their user id
---------------------------------------------------------------------------------------------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types) values
  ('avatars', 'avatars', false, 5242880,  array['image/jpeg', 'image/png', 'image/webp']),
  ('exports', 'exports', false, 52428800, array['application/json', 'text/csv', 'application/zip'])
on conflict (id) do nothing;

create policy "avatars owner read"   on storage.objects for select to authenticated
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "avatars owner insert" on storage.objects for insert to authenticated
  with check (bucket_id = 'avatars' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "avatars owner update" on storage.objects for update to authenticated
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = (select auth.uid())::text)
  with check (bucket_id = 'avatars' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "avatars owner delete" on storage.objects for delete to authenticated
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = (select auth.uid())::text);

create policy "exports owner read"   on storage.objects for select to authenticated
  using (bucket_id = 'exports' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "exports owner insert" on storage.objects for insert to authenticated
  with check (bucket_id = 'exports' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "exports owner delete" on storage.objects for delete to authenticated
  using (bucket_id = 'exports' and (storage.foldername(name))[1] = (select auth.uid())::text);

---------------------------------------------------------------------------------------------------
-- Account deletion: the signed-in user deletes their own auth row; every table above cascades.
-- Storage files are removed by the app through the Storage API first (direct SQL deletes are blocked).
---------------------------------------------------------------------------------------------------
create or replace function public.delete_my_account() returns void
language plpgsql security definer set search_path = '' as $$
declare uid uuid := (select auth.uid());
begin
  if uid is null then
    raise exception 'not authenticated' using errcode = '28000';
  end if;
  delete from auth.users where id = uid;
end $$;

revoke all on function public.delete_my_account() from public, anon;
grant execute on function public.delete_my_account() to authenticated;
