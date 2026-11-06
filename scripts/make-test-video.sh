#!/usr/bin/env bash
# Wraps whisper.cpp's samples/jfk.wav (JFK's 1961 inaugural address, a US government work in
# the public domain) in an .mp4 like a phone video: H.264 picture, AAC sound at 44.1 kHz stereo,
# so the emulator test exercises the real decode, downmix and resample path.
#
#   scripts/make-test-video.sh                       jfk.mp4 for the emulator test
#   scripts/make-test-video.sh SRC OUT.mp4 SECONDS   the same from another public clip
set -euo pipefail
cd "$(dirname "$0")/.."
src="${1:-third_party/whisper.cpp/samples/jfk.wav}"
out="${2:-app/src/androidTest/assets/jfk.mp4}"
seconds="${3:-}"
[[ -f "$src" ]] || { echo "missing $src: run git submodule update --init" >&2; exit 1; }
mkdir -p "$(dirname "$out")"
ffmpeg -nostdin -y -hide_banner -loglevel error \
  -f lavfi -i "color=c=0x203040:s=640x360:r=30" \
  ${seconds:+-t "$seconds"} -i "$src" \
  -map 0:v -map 1:a -shortest \
  -c:v libx264 -preset veryfast -pix_fmt yuv420p \
  -c:a aac -b:a 128k -ar 44100 -ac 2 \
  -movflags +faststart \
  "$out"
# What was made, when ffprobe is there (the static ffmpeg CI may use has none).
if command -v ffprobe >/dev/null; then
  ffprobe -v error -show_entries stream=codec_name,sample_rate,channels,width,height -show_entries format=duration -of compact "$out"
else
  ls -l "$out"
fi
