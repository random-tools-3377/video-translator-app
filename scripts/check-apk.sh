#!/usr/bin/env bash
# Checks a release APK before it can become a Release:
#   1. no network permission (aapt2 dump permissions: libraries add permissions through
#      manifest merging, so the built APK is what counts);
#   2. signed with the app's release key and nothing else;
#   3. arm64-v8a native code only (Whisper's and the language model's libraries, each keeping its
#      ggml private), not debuggable;
#   4. the Whisper model is inside, stored uncompressed, at its exact size.
#
#   scripts/check-apk.sh path/to/app-release.apk
set -euo pipefail
apk="$1"
[[ -f "$apk" ]] || { echo "::error::no APK at $apk" >&2; exit 1; }

# SHA-256 of the release signing certificate (also in CLAUDE.md).
EXPECTED_CERT="12:F6:EC:BC:F4:D1:C0:CE:6D:96:68:D6:A1:B3:BC:8C:08:49:93:8E:FA:0C:43:04:AA:9F:7D:80:28:BE:61:52"
# The bundled Whisper model is whisperModel in app/version.properties.
WHISPER_MODEL="$(sed -n 's/^whisperModel=//p' "$(dirname "$0")/../app/version.properties")"
MODEL="assets/models/ggml-${WHISPER_MODEL}.bin"
MODEL_BYTES="$("$(dirname "$0")/fetch-models.sh" --bytes release)"
VAD_MODEL="assets/models/ggml-silero-v6.2.0.bin"
VAD_MODEL_BYTES=885098

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk" ]] || { echo "::error::ANDROID_HOME is not set" >&2; exit 1; }
bt="$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)"
aapt2="${bt}aapt2"
apksigner="${bt}apksigner"
echo "build-tools: $bt"
fail=0

echo "== 1. Permissions"
"$aapt2" dump permissions "$apk" | "$(dirname "$0")/check-permissions.sh" || fail=1

echo "== 2. Signature"
certs="$("$apksigner" verify --verbose --print-certs "$apk")"
echo "$certs" | grep -Ev '^WARNING' || true
expected="$(tr -d ':' <<<"$EXPECTED_CERT" | tr 'A-F' 'a-f')"
# apksigner prints "Signer #1 certificate SHA-256 digest: ..." (older build-tools) or
# "V2 Signer: certificate SHA-256 digest: ..." (newer); take every certificate digest it lists.
signers="$(sed -nE 's/^Number of signers: ([0-9]+)$/\1/p' <<<"$certs")"
digests="$(sed -nE 's/^(V[0-9.]+ )?Signer( #[0-9]+)?:? certificate SHA-256 digest: ([0-9a-f]+)$/\3/p' <<<"$certs" | sort -u)"
if [[ -n "$signers" && "$signers" != "1" ]]; then
  echo "::error::expected exactly one signer, found $signers" >&2; fail=1
elif [[ -z "$digests" ]]; then
  echo "::error::apksigner listed no certificate digest; cannot check the signature" >&2; fail=1
elif [[ "$digests" != "$expected" ]]; then
  echo "::error::signed with the wrong certificate: $(echo $digests) (expected $expected). A build signed with any other key cannot install over the installed app." >&2; fail=1
else
  echo "signed with the release certificate"
fi
grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' <<<"$certs" \
  || { echo "::error::APK Signature Scheme v2 missing" >&2; fail=1; }

echo "== 3. Native code and debuggable flag"
listing="$(unzip -l "$apk")"
abis="$(grep -oE ' lib/[^/]+/' <<<"$listing" | sort -u | sed -E 's# lib/##; s#/##' | tr '\n' ' ')"
echo "ABIs: $abis"
[[ "$abis" == "arm64-v8a " ]] || { echo "::error::expected arm64-v8a only, found: $abis" >&2; fail=1; }
for lib in libvtwhisper.so libvtllama.so; do
  grep -q "lib/arm64-v8a/$lib" <<<"$listing" || { echo "::error::$lib missing" >&2; fail=1; }
done
# whisper.cpp and llama.cpp each carry their own version of ggml. Each library must keep its copy
# private (only JNI functions exported), or the two could clash in the app's process.
nm_tool="$(ls "$sdk"/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm 2>/dev/null | sort -V | tail -1 || true)"
nm_tool="${nm_tool:-nm}"
libdir="$(mktemp -d)"
unzip -q -o "$apk" 'lib/arm64-v8a/*.so' -d "$libdir"
for so in "$libdir"/lib/arm64-v8a/libvt*.so; do
  exported="$("$nm_tool" -D --defined-only "$so" | awk '{print $3}' | grep -v '^Java_\|^JNI_' || true)"
  if grep -qE '^(ggml|gguf|llama|whisper)_' <<<"$exported"; then
    echo "::error::$(basename "$so") exports ggml/llama/whisper symbols: $(grep -E '^(ggml|gguf|llama|whisper)_' <<<"$exported" | head -5 | tr '\n' ' ')" >&2; fail=1
  else
    echo "$(basename "$so"): $("$nm_tool" -D --defined-only "$so" | grep -c ' Java_') JNI functions exported, ggml kept private"
  fi
done
rm -rf "$libdir"
badging="$("$aapt2" dump badging "$apk")"
grep -E "^(package|sdkVersion|targetSdkVersion|application-label):" <<<"$badging" || true
if grep -q "application-debuggable" <<<"$badging"; then
  echo "::error::release APK is debuggable" >&2; fail=1
fi

echo "== 4. Models"
for entry in "$MODEL:$MODEL_BYTES" "$VAD_MODEL:$VAD_MODEL_BYTES"; do
  name="${entry%%:*}"; bytes="${entry##*:}"
  model_line="$(unzip -v "$apk" "$name" | grep -E " $name\$" || true)"
  echo "$model_line"
  read -r length method _ <<<"$model_line"
  if [[ -z "$model_line" ]]; then
    echo "::error::$name is not in the APK" >&2; fail=1
  elif [[ "$method" != "Stored" ]]; then
    echo "::error::$name is compressed ($method); it must be stored so it can be read in place" >&2; fail=1
  elif [[ "$length" != "$bytes" ]]; then
    echo "::error::$name is $length bytes, expected $bytes" >&2; fail=1
  else
    echo "$name stored uncompressed, $length bytes"
  fi
done

ls -l "$apk"
if [[ $fail != 0 ]]; then
  echo "::error::APK checks failed" >&2
  exit 1
fi
echo "All APK checks passed."
