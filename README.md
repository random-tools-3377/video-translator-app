# Video Translator

An Android app that puts English subtitles on the videos on your phone. Everything happens
on the phone: the app has no internet permission, so Android itself stops it sending anything
anywhere.

Built for a Samsung Galaxy S24 Ultra. Uses [whisper.cpp](https://github.com/ggml-org/whisper.cpp)
with Whisper large-v3, and optionally a language model (Qwen3.5 via [llama.cpp](https://github.com/ggml-org/llama.cpp)) that turns its transcript into natural English.

## Installing

1. On the phone, open this repository's **Releases** page and download the newest `.apk`.
2. Open the downloaded file. The first time, Android asks to allow installs from your browser —
   allow it for that one app.
3. Install. A newer release installs over the old one.

## Using it

- In Samsung Gallery, open a video, tap **Share**, and pick **Video Translator**; or
- open the app and tap **Pick a video**.

It works out the English, then plays your video with English subtitles. You can change how they
look — height on screen, size, colour, font, background — fix the wording of any line, and fix the
timing: shift all of them earlier or later at once, or nudge one line. Then:

- save the subtitles as a file, to open again later without waiting for the translation; or
- save a copy of the video with the subtitles burned in, to your gallery. Your original is never
  changed.

Nothing is kept unless you save it. A saved copy is an ordinary gallery video, so if your gallery
backs up to Samsung Cloud or Google Photos, the copy is uploaded with it.

## For whoever works on the code

The decisions behind the app, and how builds and Releases work, are in [CLAUDE.md](CLAUDE.md).
In short: `git submodule update --init` for whisper.cpp and llama.cpp, `scripts/fetch-models.sh release vad` for the
models (checked against their sha256), then `./gradlew assembleRelease` with the Android SDK and NDK
installed. GitHub Actions does all of this on every push.
