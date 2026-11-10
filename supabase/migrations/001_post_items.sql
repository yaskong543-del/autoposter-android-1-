-- AutoPoster cloud backup: metadata only. No account tokens, no video files.
create table public.post_items (
  owner uuid not null default auth.uid() references auth.users(id) on delete cascade,
  id text not null,
  campaign_id text,
  account_label text,
  platform text not null check (platform in ('YOUTUBE','INSTAGRAM','TIKTOK')),
  name text,
  text text,
  scheduled_at timestamptz,
  status text not null check (status in ('QUEUED','UPLOADING','DONE','FAILED','NEEDS_AUTH','CANCELED')),
  progress real not null default 0,
  attempts int not null default 0,
  remote_id text,
  error text,
  created_at timestamptz,
  finished_at timestamptz,
  size_bytes bigint,
  views bigint not null default -1,
  likes bigint not null default -1,
  comments bigint not null default -1,
  updated_at timestamptz not null default now(),
  primary key (owner, id)
);

create index post_items_owner_sched on public.post_items (owner, scheduled_at desc);

create or replace function public.touch_updated_at() returns trigger
language plpgsql set search_path = '' as $$
begin new.updated_at = now(); return new; end $$;

create trigger post_items_touch before update on public.post_items
for each row execute function public.touch_updated_at();

alter table public.post_items enable row level security;

create policy "owner can read"   on public.post_items for select to authenticated using (owner = (select auth.uid()));
create policy "owner can insert" on public.post_items for insert to authenticated with check (owner = (select auth.uid()));
create policy "owner can update" on public.post_items for update to authenticated using (owner = (select auth.uid())) with check (owner = (select auth.uid()));
create policy "owner can delete" on public.post_items for delete to authenticated using (owner = (select auth.uid()));

alter publication supabase_realtime add table public.post_items;
