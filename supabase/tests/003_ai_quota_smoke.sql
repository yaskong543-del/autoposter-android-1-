-- Manual smoke test for 003_ai_creator_studio.sql. Run in the Supabase SQL editor (superuser). Everything is rolled back.
-- NOT executed by the author of v0.7 (no Postgres was available). Each `assert` raises if the behaviour is wrong.
begin;
do $$
declare
  u uuid := gen_random_uuid(); r jsonb; r2 jsonb; id1 uuid;
begin
  insert into auth.users (id, instance_id, aud, role, email) values (u, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'smoke-' || u || '@example.test');
  -- free plan: 5,000 tokens a month
  r := public.ai_reserve(u, 'caption', 1000);
  assert (r->>'allowed')::boolean, 'first reserve should be allowed';
  assert (r->>'remaining')::int = 4000, 'remaining should drop by the reservation';
  id1 := (r->>'request_id')::uuid;
  r2 := public.ai_settle(id1, 640);
  assert (r2->>'used')::int = 640 and (r2->>'reserved')::int = 0, 'settle moves reserved -> used with the ACTUAL amount';
  r2 := public.ai_settle(id1, 9999);
  assert (r2->>'used')::int = 640, 'settle is idempotent';
  -- failed call is not charged
  r := public.ai_reserve(u, 'hooks', 800);
  r2 := public.ai_release((r->>'request_id')::uuid, 'provider_error', 0);
  assert (r2->>'used')::int = 640 and (r2->>'reserved')::int = 0, 'failed request releases the reservation uncharged';
  -- quota
  r := public.ai_reserve(u, 'x', 2500);  perform public.ai_settle((r->>'request_id')::uuid, 2500);
  r := public.ai_reserve(u, 'x', 2000);
  assert (r->>'allowed')::boolean = false and r->>'reason' = 'quota_exceeded', 'over-quota is refused: ' || r::text;
  -- too large for the plan
  update public.ai_requests set created_at = now() - interval '2 minutes' where owner = u;  -- keep the rate limiter out of the way
  r := public.ai_reserve(u, 'x', 2600);
  assert r->>'reason' = 'too_large', 'per-request cap enforced: ' || r::text;
  -- premium
  update public.profiles set plan = 'pro' where id = u;
  assert (public.ai_usage_status(u)->>'limit')::int = 500000, 'premium limit is 500,000';
  -- rate limit: free allows 6/min, premium 30/min
  for i in 1..30 loop perform public.ai_reserve(u, 'rl', 10); end loop;
  r := public.ai_reserve(u, 'rl', 10);
  assert r->>'reason' = 'rate_limited' and (r->>'retry_after')::int >= 1, 'rate limit enforced: ' || r::text;
  raise notice 'ai quota smoke test passed';
end $$;
rollback;
