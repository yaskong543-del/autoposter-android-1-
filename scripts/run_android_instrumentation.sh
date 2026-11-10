#!/usr/bin/env bash
set -Eeuo pipefail

mkdir -p app/build/diagnostics
trap 'adb logcat -d -v threadtime > app/build/diagnostics/room-instrumentation-logcat.txt 2>&1 || true' EXIT

# android-emulator-runner starts the emulator and waits for boot before this script.
# Do not restart the ADB server here: doing so can strand the emulator in OFFLINE state.
adb wait-for-device
timeout 30s adb shell 'test "$(getprop sys.boot_completed)" = "1"'
adb shell getprop ro.build.version.sdk
adb logcat -c

gradle --no-daemon :app:connectedDebugAndroidTest --stacktrace
