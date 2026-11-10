#!/usr/bin/env bash
# Fails if an AI provider key, URL or header appears anywhere that ships in the Android app. Run in CI.
set -euo pipefail
cd "$(dirname "$0")/.."
if grep -rnE "api\.anthropic\.com|x-api-key|ANTHROPIC_API_KEY|sk-ant-|OPENAI_API_KEY|api\.openai\.com" app/src/main app/build.gradle.kts .env.example 2>/dev/null; then
  echo "FAIL: AI provider reference found in the Android app. Keys belong only in Supabase secrets (see supabase/README.md)." >&2; exit 1
fi
echo "OK: no AI provider keys or endpoints in the app."
