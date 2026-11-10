#!/usr/bin/env bash
set -Eeuo pipefail

mkdir -p app/build/diagnostics
trap 'adb logcat -d -v threadtime > app/build/diagnostics/room-instrumentation-logcat.txt 2>&1 || true' EXIT

# android-emulator-runner starts the emulator and waits for boot before this script.
# Do not restart the ADB server here: doing so can strand the emulator in OFFLINE state.
adb wait-for-device
timeout 30s adb shell 'test "$(getprop sys.boot_completed)" = "1"'
adb shell getprop ro.build.version.sdk | tee app/build/diagnostics/emulator-api.txt
adb logcat -c

set +e
timeout --signal=TERM --kill-after=60s 25m gradle --no-daemon :app:connectedDebugAndroidTest --stacktrace 2>&1 | tee app/build/diagnostics/gradle-instrumentation.log
gradle_status=${PIPESTATUS[0]}
set -e

# Preserve small XML/text test reports for diagnosis.
mkdir -p app/build/diagnostics/test-results
while IFS= read -r -d '' report; do
  cp --parents "$report" app/build/diagnostics/test-results/
done < <(find app/build -type f \( -name '*.xml' -o -name '*.txt' \) -size -2M -print0)

test_count=0
if [[ -d app/build/test-results/connected ]]; then
  test_count=$(grep -Rho '<testcase\\b' app/build/test-results/connected --include='*.xml' 2>/dev/null | wc -l || true)
fi
printf 'gradle_exit_code=%s\\nexecuted_test_cases=%s\\n' "$gradle_status" "$test_count" > app/build/diagnostics/result-summary.txt

if [[ "$gradle_status" -ne 0 ]]; then
  exit "$gradle_status"
fi
if [[ "$test_count" -eq 0 ]]; then
  echo "Instrumentation task returned success but executed zero test cases." >&2
  exit 1
fi
