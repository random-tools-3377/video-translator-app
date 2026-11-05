#!/usr/bin/env bash
# Downloads Whisper models from Hugging Face and checks each against its sha256 and size.
# Models are never committed (GitHub refuses files over 100 MB); CI caches them instead.
#
#   scripts/fetch-models.sh release  # the model the release APK bundles (whisperModel in app/version.properties)
#   scripts/fetch-models.sh medium   # ggml-medium-q5_0.bin
#   scripts/fetch-models.sh large    # ggml-large-v3-q5_0.bin
#   scripts/fetch-models.sh large2   # ggml-large-v2-q5_0.bin (for comparisons)
#   scripts/fetch-models.sh tiny     # for the emulator and ARM tests only
#   scripts/fetch-models.sh vad      # whisper.cpp's speech detector, bundled in every build
#   scripts/fetch-models.sh llm      # Qwen3.5-9B Q4_K_M, the language model loaded into the app (CI tests it)
#   scripts/fetch-models.sh llm4b    # Qwen3.5-4B Q4_K_M, the smaller one the app also accepts
#   scripts/fetch-models.sh --path release   # print where a model lives, without downloading
#   scripts/fetch-models.sh --bytes release  # print its exact size
#
# Each Whisper model sits in its own folder, models/<name>/models/ggml-<name>.bin, and the
# release build takes its assets from models/<name> only, so exactly one model is bundled.
set -euo pipefail
cd "$(dirname "$0")/.."

HF="https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

# name -> file bytes sha256 folder base-url
info() {
  case "$1" in
    release)
      local m; m="$(sed -n 's/^whisperModel=//p' app/version.properties)"
      [[ -n "$m" ]] || { echo "no whisperModel= in app/version.properties" >&2; exit 2; }
      info "$m" ;;
    medium|medium-q5_0)
      echo "ggml-medium-q5_0.bin 539212467 19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f models/medium-q5_0/models $HF" ;;
    large|large-v3-q5_0)
      echo "ggml-large-v3-q5_0.bin 1081140203 d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1 models/large-v3-q5_0/models $HF" ;;
    large2|large-v2-q5_0)
      # Comparison only (not in CLAUDE.md's table yet); sha256 equals Hugging Face's listed oid.
      echo "ggml-large-v2-q5_0.bin 1080732091 3a214837221e4530dbc1fe8d734f302af393eb30bd0ed046042ebf4baf70f6f2 models/large-v2-q5_0/models $HF" ;;
    tiny)
      echo "ggml-tiny.bin 77691713 be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21 app/src/androidTest/assets $HF" ;;
    vad)
      echo "ggml-silero-v6.2.0.bin 885098 2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987 app/src/main/assets/models https://huggingface.co/ggml-org/whisper-vad/resolve/main" ;;
    # Language models: never bundled; the user downloads one and loads it into the app, which checks
    # the same sha256 (app/src/main/java/app/videotranslator/llm/LanguageModels.kt).
    llm|qwen3.5-9b)
      echo "Qwen3.5-9B-Q4_K_M.gguf 5680522464 03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8 models/llm https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/resolve/main" ;;
    llm4b|qwen3.5-4b)
      echo "Qwen3.5-4B-Q4_K_M.gguf 2740937888 00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4 models/llm https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main" ;;
    *) echo "unknown model '$1' (release, medium, large, large2, tiny, vad, llm or llm4b)" >&2; exit 2 ;;
  esac
}

fetch() {
  local file bytes sha dest_dir base
  read -r file bytes sha dest_dir base <<<"$(info "$1")"
  [[ -n "$file" ]] || exit 2
  local dest="$dest_dir/$file"
  mkdir -p "$dest_dir"
  if [[ -f "$dest" ]] && [[ "$(stat -c %s "$dest")" == "$bytes" ]] \
     && echo "$sha  $dest" | sha256sum -c --quiet - 2>/dev/null; then
    echo "$file: present, sha256 matches"
    return
  fi
  echo "$file: downloading"
  local tmp="$dest.part"
  curl -fL --retry 5 --retry-delay 10 --retry-all-errors -o "$tmp" "$base/$file"
  local got_bytes; got_bytes="$(stat -c %s "$tmp")"
  if [[ "$got_bytes" != "$bytes" ]]; then
    echo "::error::$file is $got_bytes bytes, expected $bytes" >&2
    rm -f "$tmp"; exit 1
  fi
  if ! echo "$sha  $tmp" | sha256sum -c --quiet -; then
    echo "::error::$file sha256 does not match $sha" >&2
    rm -f "$tmp"; exit 1
  fi
  mv "$tmp" "$dest"
  echo "$file: downloaded, sha256 matches"
}

case "${1:-}" in
  --path)  read -r file _ _ dir _ <<<"$(info "$2")"; echo "$dir/$file" ;;
  --bytes) read -r _ bytes _ _ _ <<<"$(info "$2")"; echo "$bytes" ;;
  *)       for which in "$@"; do fetch "$which"; done ;;
esac
