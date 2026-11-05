#!/usr/bin/env bash
# Chooses an Android NDK the GitHub runner already has, so the build does not download one,
# and exports it as VT_NDK for Gradle (-Pvt.ndkVersion).
set -euo pipefail
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
installed="$(ls "$sdk/ndk" 2>/dev/null | sort -V || true)"
echo "NDKs on this runner: $(echo $installed)"
preferred=27.3.13750724
if grep -qx "$preferred" <<<"$installed"; then
  ndk="$preferred"
else
  ndk="$(awk -F. '$1 >= 27' <<<"$installed" | tail -1)"
  [[ -n "$ndk" ]] || ndk="$preferred"   # Gradle installs it
fi
echo "Using NDK $ndk"
echo "VT_NDK=$ndk" >> "${GITHUB_ENV:-/dev/null}"
