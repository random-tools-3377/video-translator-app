#!/usr/bin/env bash
# Reads `aapt2 dump permissions` output on stdin and fails if the APK asks for any network
# permission, or for anything not on the short allow-list below.
#
#   aapt2 dump permissions app-release.apk | scripts/check-permissions.sh
#   scripts/check-permissions.sh --self-test     # proves the check catches INTERNET
set -euo pipefail

check() {
  local perms package requested fail=0
  perms="$(cat)"
  echo "$perms"
  if ! grep -q '^package: ' <<<"$perms"; then
    echo "::error::no 'package:' line from aapt2; cannot trust the check" >&2
    return 1
  fi
  # "package: com.example" or "package: name='com.example' versionCode=..."
  package="$(sed -nE "s/^package: *(name=')?([^' ]+).*/\2/p" <<<"$perms" | head -1)"
  # Every permission the APK asks for, in either aapt2 output style.
  requested="$(grep -E '^uses-permission(-sdk-23)?:' <<<"$perms" \
    | sed -E "s/^uses-permission(-sdk-23)?: *(name=)?'?([^' ]+)'?.*/\3/" || true)"
  local network_re='^android\.permission\.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE|CHANGE_NETWORK_STATE|CHANGE_WIFI_STATE|CHANGE_WIFI_MULTICAST_STATE|NEARBY_WIFI_DEVICES|BLUETOOTH.*|UWB_RANGING|NFC.*|ACCESS_LOCAL_NETWORK|BIND_VPN_SERVICE)$|c2dm|\.gms\.'
  # Allowed: the not-exported-receiver permission androidx.core declares for the app itself, and
  # what working in the background needs (a foreground service with its notification, and keeping
  # the processor awake while the screen is off). None of these can reach the network.
  local allowed_re="^${package//./\\.}\\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$|^android\\.permission\\.(FOREGROUND_SERVICE|FOREGROUND_SERVICE_MEDIA_PROCESSING|FOREGROUND_SERVICE_DATA_SYNC|POST_NOTIFICATIONS|WAKE_LOCK)$"
  while IFS= read -r p; do
    [[ -z "$p" ]] && continue
    if [[ "$p" =~ $network_re ]]; then
      echo "::error::network permission in the APK: $p" >&2; fail=1
    elif [[ ! "$p" =~ $allowed_re ]]; then
      echo "::error::unexpected permission in the APK: $p (allow it in scripts/check-permissions.sh only if it cannot reach the network)" >&2; fail=1
    else
      echo "ok: $p"
    fi
  done <<<"$requested"
  [[ $fail == 0 ]] && echo "No network permission."
  return $fail
}

if [[ "${1:-}" == "--self-test" ]]; then
  good="package: com.example.app
permission: com.example.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.example.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'"
  for bad_line in \
      "uses-permission: name='android.permission.INTERNET'" \
      "uses-permission: android.permission.INTERNET" \
      "uses-permission: name='android.permission.ACCESS_NETWORK_STATE'" \
      "uses-permission: name='android.permission.ACCESS_WIFI_STATE' maxSdkVersion='32'" \
      "uses-permission-sdk-23: name='android.permission.CHANGE_WIFI_STATE'" \
      "uses-permission: name='com.google.android.c2dm.permission.RECEIVE'" \
      "uses-permission: name='android.permission.READ_MEDIA_VIDEO'"; do
    if printf '%s\n%s\n' "$good" "$bad_line" | check >/dev/null 2>&1; then
      echo "::error::self-test: the check let through: $bad_line" >&2; exit 1
    fi
  done
  printf '%s\n' "$good" | check >/dev/null || { echo "::error::self-test: the check rejected a clean APK" >&2; exit 1; }
  echo "Permission check self-test passed."
  exit 0
fi

check
