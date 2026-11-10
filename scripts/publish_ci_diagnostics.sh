#!/usr/bin/env bash
set -Eeuo pipefail

: "${GITHUB_RUN_ID:?GITHUB_RUN_ID is required}"
: "${GITHUB_SHA:?GITHUB_SHA is required}"
: "${GITHUB_RUN_NUMBER:?GITHUB_RUN_NUMBER is required}"

source_dir="app/build/diagnostics"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
mkdir -p "$tmp_dir/diagnostics"
if [[ -d "$source_dir" ]]; then
  cp -a "$source_dir/." "$tmp_dir/diagnostics/"
fi
python3 scripts/sanitize_ci_diagnostics.py "$tmp_dir/diagnostics"

git fetch origin ci-diagnostics:refs/remotes/origin/ci-diagnostics 2>/dev/null || true
if git show-ref --verify --quiet refs/remotes/origin/ci-diagnostics; then
  git checkout -B ci-diagnostics refs/remotes/origin/ci-diagnostics
else
  git checkout --orphan ci-diagnostics
  git rm -rf . >/dev/null 2>&1 || true
fi

run_dir="runs/$GITHUB_RUN_ID"
mkdir -p "$run_dir"
cp -a "$tmp_dir/diagnostics/." "$run_dir/"
{
  printf 'run_id=%s\\n' "$GITHUB_RUN_ID"
  printf 'run_number=%s\\n' "$GITHUB_RUN_NUMBER"
  printf 'commit=%s\\n' "$GITHUB_SHA"
  printf 'run_url=%s\\n' "${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
} > "$run_dir/run-info.txt"

# Keep only the ten most recent run IDs.
while IFS= read -r old_run; do
  [[ -n "$old_run" ]] && rm -rf "runs/$old_run"
done < <(find runs -mindepth 1 -maxdepth 1 -type d -printf '%f\\n' | sort -rn | tail -n +11)

git add runs
if git diff --cached --quiet; then
  echo "No diagnostics to publish."
  exit 0
fi
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git commit -m "Publish sanitized CI diagnostics for run $GITHUB_RUN_ID"
git push origin HEAD:ci-diagnostics
