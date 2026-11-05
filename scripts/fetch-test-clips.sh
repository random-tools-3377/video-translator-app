#!/usr/bin/env bash
# Downloads the public test clips listed in test-clips/clips.tsv, checks each file's sha256,
# and cuts the listed stretch to raw 16 kHz mono float for tools/vt-transcribe.
#
#   scripts/fetch-test-clips.sh OUT_DIR          (CLIPS_ONLY=a,b limits it to those clips)
set -euo pipefail
cd "$(dirname "$0")/.."
out="${1:?usage: fetch-test-clips.sh OUT_DIR}"
mkdir -p "$out"
UA="video-translator-ci/1.0 (${GITHUB_REPOSITORY:+https://github.com/$GITHUB_REPOSITORY; }test clips)"
grep -v '^#' test-clips/clips.tsv | tail -n +2 | while IFS=$'\t' read -r name language url sha256 start seconds rest; do
  src="$out/$name.src"
  [[ -n "${CLIPS_ONLY:-}" && ",$CLIPS_ONLY," != *",$name,"* ]] && continue
  if [[ "$url" == concat:* ]]; then
    # Joins clips made earlier in this list (raw float audio concatenates as is).
    : > "$out/$name.f32"
    IFS=',' read -ra parts <<<"${url#concat:}"
    for part in "${parts[@]}"; do cat "$out/$part.f32" >> "$out/$name.f32"; done
    echo "$name: $(( $(stat -c %s "$out/$name.f32") / 64000 )) s, joined from ${url#concat:}"
    continue
  fi
  if [[ "$url" != http* ]]; then
    cp "$url" "$src"   # a file in the repository (whisper.cpp's samples)
  elif [[ ! -f "$src" ]] || ! echo "$sha256  $src" | sha256sum -c --quiet - 2>/dev/null; then
    curl -fsSL --max-time 120 --retry 3 --retry-delay 5 --retry-max-time 300 -A "$UA" -o "$src" "$url"
  fi
  echo "$sha256  $src" | sha256sum -c --quiet - || { echo "::error::$name: sha256 does not match" >&2; exit 1; }
  ffmpeg -nostdin -v error -y -ss "$start" -t "$seconds" -i "$src" -ac 1 -ar 16000 -f f32le "$out/$name.f32"
  echo "$name: $(( $(stat -c %s "$out/$name.f32") / 64000 )) s of $language, sha256 ok"
done
