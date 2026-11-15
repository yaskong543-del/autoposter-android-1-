-- Minimal stand-in for the Supabase platform objects the migrations rely on, so 001-004 can run on a vanilla PostgreSQL in CI.
-- It emulates: roles, auth.users/sessions/uid()/jwt(), storage.buckets/objects/foldername(), default table privileges
-- (Supabase grants ALL on public tables to anon/authenticated/service_role by default) and an empty supabase_realtime publication.
-- This is a TEST HARNESS, not Supabase: results prove the SQL logic, not the hosted project's configuration.
do $$ begin create role anon nologin; exception when duplicate_object then null; end $$;
do $$ begin create role authenticated nologin; exception when duplicate_object then null; end $$;
do $$ begin create role service_role nologin bypassrls; exception when duplicate_object then null; end $$;
create schema if not exists auth; create schema if not exists storage; create schema if not exists extensions;
create table if not exists auth.users (
  id uuid primary key, instance_id uuid, aud text, role text, email text, raw_user_meta_data jsonb not null default '{}', created_at timestamptz not null default now());
create table if not exists auth.sessions (id uuid primary key default gen_random_uuid(), user_id uuid not null references auth.users(id) on delete cascade, created_at timestamptz not null default now());
create or replace function auth.jwt() returns jsonb language sql stable as $$ select coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb $$;
create or replace function auth.uid() returns uuid language sql stable as $$ select nullif(auth.jwt() ->> 'sub', '')::uuid $$;
create table if not exists storage.buckets (id text primary key, name text, public boolean default false, file_size_limit bigint, allowed_mime_types text[]);
create table if not exists storage.objects (id uuid primary key default gen_random_uuid(), bucket_id text, name text, owner uuid);
alter table storage.objects enable row level security;
create or replace function storage.foldername(name text) returns text[] language sql immutable as $$
  select (string_to_array(name, '/'))[1:greatest(array_length(string_to_array(name, '/'), 1) - 1, 0)] $$;
grant usage on schema auth, storage, public, extensions to anon, authenticated, service_role;
grant select on storage.buckets, storage.objects to authenticated, service_role;
grant execute on function auth.uid(), auth.jwt() to anon, authenticated, service_role;
alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
alter default privileges in schema public grant all on sequences to anon, authenticated, service_role;
alter default privileges in schema public grant execute on functions to anon, authenticated, service_role;   -- Supabase default ACL
do $$ begin create publication supabase_realtime; exception when duplicate_object then null; end $$;
