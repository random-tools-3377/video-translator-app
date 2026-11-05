#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner once the emulator has booted. Installs the
# debug app and its test APK (tiny model + jfk.mp4 inside) and runs the instrumented tests with
# adb directly, so Gradle is not competing with the emulator for the runner's memory.
set -euo pipefail
app=app/build/outputs/apk/debug/app-debug.apk
tests=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb wait-for-device
adb shell getprop ro.build.version.release
adb shell getprop ro.product.cpu.abi
adb logcat -c || true
adb install -r -t "$app"
adb install -r -t "$tests"
set +e
out="$(adb shell am instrument -w app.videotranslator.test/androidx.test.runner.AndroidJUnitRunner 2>&1)"
set -e
echo "$out"
echo "---- logcat: app and test ----"
adb logcat -d -v time -s VTTest:V VTWhisper:V VTLlm:V VTEngine:V TestRunner:V AndroidRuntime:E DEBUG:V | tail -400 || true
if grep -q '^OK (' <<<"$out"; then
  echo "Emulator tests passed."
else
  echo "::error::Emulator tests failed"
  exit 1
fi
