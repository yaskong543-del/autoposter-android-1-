#!/usr/bin/env bash
# Real concurrency tests against PostgreSQL (parallel psql sessions). Needs PGHOST/PGUSER/PGPASSWORD/PGDATABASE in the environment.
# Verifies that limits and job claims hold when requests race. Test data is committed, then removed at the end.
set -u
P="psql -v ON_ERROR_STOP=1 -X -q -tA"
fail=0
check() { if [ "$2" != "$3" ]; then echo "FAIL: $1 (expected $3, got $2)"; fail=1; else echo "ok: $1 = $2"; fi; }

U=$($P -c "insert into auth.users (id, instance_id, aud, role, email) values (gen_random_uuid(), '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'race-' || gen_random_uuid() || '@example.test') returning id")
echo "test user $U"
cleanup() { $P -c "delete from auth.users where id = '$U'" >/dev/null 2>&1; rm -rf "$TMP"; }
TMP=$(mktemp -d); trap cleanup EXIT

# 1) FREE plan: 8 simultaneous connections of different profiles -> exactly 2 succeed
for i in 1 2 3 4 5 6 7 8; do
  ( $P -c "insert into public.provider_accounts (owner, platform, external_id) values ('$U', 'YOUTUBE', 'race-$i')" >/dev/null 2>&1 && echo ok >"$TMP/prof$i" || echo no >"$TMP/prof$i" ) &
done; wait
check "FREE profile limit under 8 concurrent connects" "$(cat $TMP/prof* | grep -c ok)" 2
check "profiles stored" "$($P -c "select count(*) from public.provider_accounts where owner = '$U'")" 2

# 2) FREE plan: 6 simultaneous device registrations -> exactly 1 succeeds
for i in 1 2 3 4 5 6; do
  ( $P -c "select set_config('request.jwt.claims', '{\"sub\":\"$U\"}', false); set role authenticated; select public.register_device('race-install-$i-aaaa', 'd$i', 'android', '1', '1');" >/dev/null 2>&1 && echo ok >"$TMP/dev$i" || echo no >"$TMP/dev$i" ) &
done; wait
check "FREE device limit under 6 concurrent registrations" "$(cat $TMP/dev* | grep -c ok)" 1
check "active devices stored" "$($P -c "select count(*) from public.devices where owner = '$U' and revoked_at is null")" 1

# 3) PREMIUM: 6 concurrent registrations on top of 1 -> total 5
$P -c "select public.admin_set_plan('$U', 'pro', null, 'race test')" >/dev/null
for i in 11 12 13 14 15 16; do
  ( $P -c "select set_config('request.jwt.claims', '{\"sub\":\"$U\"}', false); set role authenticated; select public.register_device('race-install-$i-bbbb', 'd$i', 'android', '1', '1');" >/dev/null 2>&1 && echo ok >"$TMP/pdev$i" || echo no >"$TMP/pdev$i" ) &
done; wait
check "PREMIUM device cap under concurrency" "$($P -c "select count(*) from public.devices where owner = '$U' and revoked_at is null")" 5

# 4) Job claims: 40 due jobs, 8 workers claim at once -> every job claimed exactly once
A=$($P -c "insert into public.provider_accounts (owner, platform, external_id) values ('$U', 'TIKTOK', 'claim-acct') returning id" 2>/dev/null || $P -c "select id from public.provider_accounts where owner='$U' limit 1")
$P -c "insert into public.publish_jobs (owner, post_id, account_id, platform, status, run_at, idempotency_key, next_attempt_at, payload, media_path)
       select '$U', 'p' || g, '$A', (select platform from public.provider_accounts where id = '$A'), 'due', now() - interval '1 minute', md5('k' || g) || md5('z' || g), now() - interval '1 minute', '{}', '$U/c.mp4' from generate_series(1, 40) g" >/dev/null
for w in 1 2 3 4 5 6 7 8; do
  ( $P -c "select id from public.claim_publish_jobs('worker-$w', 10, 120)" >"$TMP/claim$w" 2>&1 ) &
done; wait
total=$(cat $TMP/claim* | grep -c .)
dups=$(cat $TMP/claim* | sort | uniq -d | wc -l)
check "all 40 jobs claimed" "$total" 40
check "no job claimed twice" "$dups" 0
check "no job left due" "$($P -c "select count(*) from public.publish_jobs where owner = '$U' and status = 'due'")" 0

# 5) Idempotent enqueue under races: same key from 6 sessions -> one job
IA=$($P -c "select id from public.provider_accounts where owner = '$U' limit 1")
for i in 1 2 3 4 5 6; do
  ( $P -c "select set_config('request.jwt.claims', '{\"sub\":\"$U\"}', false); set role authenticated; select public.enqueue_publish_job('same-post', '$IA', now() + interval '1 day', 'UTC', '{}', '$U/c.mp4', repeat('r', 32));" >/dev/null 2>&1 ) &
done; wait
check "one job for one idempotency key" "$($P -c "select count(*) from public.publish_jobs where owner = '$U' and post_id = 'same-post'")" 1

exit $fail
