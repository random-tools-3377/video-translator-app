#!/usr/bin/env bash
# ffmpeg for decoding the test clips on a CI runner. Prints a directory to put first on PATH
# (nothing when the runner already has ffmpeg); everything else goes to stderr.
#
# apt-get from Ubuntu's mirror sometimes trickles: ffmpeg's ~80 packages once took over 20
# minutes on two runners at the same time. So the first choice is the static ffmpeg inside the
# imageio-ffmpeg wheel on PyPI (one download, pinned by sha256), kept only if it has the decoders
# and encoders the tests need (the clips; the H.264/AAC test videos); apt-get is the fallback. It
# has no ffprobe, which scripts/make-test-video.sh only uses to print what it made.
set -euo pipefail

if command -v ffmpeg >/dev/null; then
  ffmpeg -hide_banner -version | head -n 1 >&2
  exit 0
fi

url=https://files.pythonhosted.org/packages/04/96/15fe7ef562f7494c358cbd9b521d2da82d7a331b5513703756166478bd5f/imageio_ffmpeg-0.7.0-py3-none-manylinux2014_x86_64.whl
sha=4e1f36bef7bedd4bc5dc25f60a8345d88f57133fa1e754f09b411ac61b854408
dir="${RUNNER_TEMP:-/tmp}/static-ffmpeg"
rm -rf "$dir"
mkdir -p "$dir"

static_ok() {
  curl -fsSL --retry 3 --max-time 300 -o "$dir/wheel.zip" "$url" || return 1
  echo "$sha  $dir/wheel.zip" | sha256sum -c --quiet - || return 1
  python3 -I -m zipfile -e "$dir/wheel.zip" "$dir/wheel" || return 1
  local exe
  exe=$(ls "$dir"/wheel/imageio_ffmpeg/binaries/ffmpeg-linux-* 2>/dev/null | head -n 1)
  [[ -n "$exe" ]] || return 1
  chmod +x "$exe"
  ln -sf "$exe" "$dir/ffmpeg"
  local decoders
  decoders=$("$dir/ffmpeg" -hide_banner -decoders 2>/dev/null) || return 1
  for d in vorbis mp3float pcm_s16le; do
    grep -q " $d " <<<"$decoders" || { echo "static ffmpeg has no $d decoder" >&2; return 1; }
  done
  local encoders
  encoders=$("$dir/ffmpeg" -hide_banner -encoders 2>/dev/null) || return 1
  for e in libx264 aac; do
    grep -q " $e " <<<"$encoders" || { echo "static ffmpeg has no $e encoder" >&2; return 1; }
  done
  "$dir/ffmpeg" -hide_banner -version | head -n 1 >&2
}

if static_ok; then
  echo "$dir"
  exit 0
fi
echo "::warning::the static ffmpeg did not work; installing ffmpeg with apt-get" >&2
rm -f "$dir/ffmpeg"
"$(dirname "$0")/ci-apt-install.sh" ffmpeg >&2
