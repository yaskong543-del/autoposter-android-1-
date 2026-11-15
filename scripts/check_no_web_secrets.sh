#!/usr/bin/env bash
# Browser and Android sources must never contain service-role keys, provider client secrets, token-encryption keys or AI keys.
# (app/src/main only: AppLog.kt legitimately mentions "client_secret" in its log-redaction pattern, and tests mention key names on purpose.)
set -u
bad=0
for pat in 'service_role' 'SUPABASE_SERVICE' 'client_secret' 'TOKEN_ENC_KEY' 'ANTHROPIC_API_KEY' 'sk-ant-' 'WORKER_SECRET'; do
  if grep -rIn -e "$pat" web; then echo "FOUND in web/: $pat"; bad=1; fi
done
for pat in 'service_role' 'SUPABASE_SERVICE' 'TOKEN_ENC_KEY' 'sk-ant-' 'WORKER_SECRET' '_CLIENT_SECRET'; do
  if grep -rIn -e "$pat" app/src/main; then echo "FOUND in app/src/main: $pat"; bad=1; fi
done
[ $bad -eq 0 ] && echo "no secret patterns in web/ or app/src/main"
exit $bad
