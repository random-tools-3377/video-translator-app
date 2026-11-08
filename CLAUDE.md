# Video Translator — house rules

An Android app for a Samsung Galaxy S24 Ultra: pick a video from the gallery (or Share one from
Samsung Gallery), and watch it with English subtitles — worked out entirely on the phone. The user
can restyle and reword the subtitles, and save a copy of the video with them burned in. The target
videos are one language each, mainly Russian, Japanese or English.

## This repository is public

Anyone can read the code, the commits, the issues, the Actions logs and the Releases. So none of
them may ever contain the owner's name or username, what the owner said about the app (feedback,
quotes, requests), anything about the owner's videos, or any transcript or subtitle text from them.
Write "the owner" or "the user". Decisions are recorded as decisions, with their technical reasons,
not as who said what. Release notes are written for a non-developer and name nobody. Commits are
authored as `Claude <noreply@anthropic.com>`.

The app was first built in an earlier private repository under another application ID. This
repository starts at 0.1.4 with the ID `app.videotranslator`, so 0.1.4 installs as a separate app
beside the earlier build, which the owner uninstalls once 0.1.4 works.

## Nothing leaves the phone

This is the reason the app exists: other phone apps either do not work on video files or send the
audio somewhere.

- **No network permission, checked on the APK.** The manifest declares
  `<uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>`, and CI fails
  the build if `aapt2 dump permissions` on the release APK lists `INTERNET`,
  `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` or any other network permission. Libraries add
  permissions through manifest merging, which is why the check is on the built APK and not on our
  manifest.
- **Only the one video the user picks.** The Photo Picker (`PickVisualMedia`, video only) and an
  `ACTION_SEND` filter for `video/*` both hand over a single item. No `READ_MEDIA_VIDEO`, no storage
  permission.
- **Nothing is kept unless the user saves it.** Subtitles live in memory. They reach storage only
  when the user saves a subtitle file (system save dialog, `CreateDocument`) or a subtitled copy
  (written to the gallery through `MediaStore`, which needs no permission for the app's own files).
  The only things the app keeps by itself are the subtitle *style*, the language model file the
  user loads (below), and (since 0.5.0, the owner's decision) the one translation in progress or
  just finished: `engine/JobStore` keeps its lines in no-backup private storage so a run Android
  stops can carry on and a result survives the app closing, plus a persisted read permission for
  that one video; both go when the user closes the result or starts another video.
  `android:allowBackup="false"` and data-extraction rules that exclude everything, so a Samsung or
  Google backup has nothing to take from the app. No analytics, no crash reporter, no Play
  Services.
- **A saved copy leaves the app's control.** It is an ordinary gallery video, so Samsung Cloud or
  Google Photos backup uploads it, English text and all. Say so in one line on the save screen. The
  in-app player creates nothing.
- **The user's videos and transcripts never come into a session.** Ask how a run went in words —
  the language, how long the video was, how long it took, whether the English made sense — and
  never ask them to paste output. Test clips are public recordings only. Nothing the owner reports
  goes into this repository (see above).

## How it works (decided)

- Kotlin and Jetpack Compose, with whisper.cpp through JNI, pinned to a release tag (v1.9.5 is the
  latest as of 2026-11-04). Upstream's `examples/whisper.android` (MIT) is a reasonable start if it
  is still there.
- Audio: `MediaExtractor` and `MediaCodec` decode the audio track, which is mixed to mono and
  resampled to 16 kHz float. Low-pass before decimating; do not just drop samples.
- Whisper runs with the language auto-detected and `translate` on, so the output is English. Show
  the detected language. An English video simply comes out as an English transcript.
- **With a language model loaded (since 0.1.3):** Whisper runs with `translate` off and writes
  down the spoken language; for anything but English, the language model (Qwen3.5-9B, or the
  smaller Qwen3.5-4B) then translates those lines to English in groups, one English line per line
  so each keeps its time. Way D of the test below. The user downloads the model once in the
  phone's browser (the app only opens the link; it has no network permission) and picks it with the
  system file picker (`OpenDocument`); the app copies it into no-backup storage, checking size and
  sha256 against `LanguageModels.KNOWN`, so only the tested files are ever used. Whisper's memory
  is released before the language model loads. Without a model the app uses Whisper's own
  translation.
- While it works: a progress bar, Cancel, and the English lines appearing as they are produced.
- Keep the screen on while it works, and keep working in the background (since 0.1.4): long
  videos take a long time, and Android stops an app in the background. `engine/TranslationService`
  is a foreground service, type `mediaProcessing` (Android 15+; `dataSync` before), started with
  each job: a notification with the video's name, progress and Cancel (never subtitle text), a
  partial wake lock while working, a "finished" notification, and `onTimeout` (Android's 6-hour
  limit) cancels cleanly. Permissions added, all allow-listed in `scripts/check-permissions.sh`
  and none network: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`,
  `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` (asked once, at the first translation),
  `WAKE_LOCK`.
- **The thread count follows the cores Android allows (since 0.1.5).** Off the screen, even with
  the foreground service, Android lets the app run on fewer cores. ggml's threads wait for each
  other by spinning, so more threads than allowed cores slows Whisper and the language model
  to almost nothing off the screen (0.1.4 used a fixed count). `app/src/main/cpp/vt_cores.h` (`VtWhisperRun`, used by the app and
  `tools/vt-transcribe`) checks `sched_getaffinity` from Whisper's abort callback at most twice a
  second: with fewer cores than threads it stops `whisper_full` and starts again from the end of
  the last line with fewer threads (the language already detected, times and progress offset);
  with more cores it restarts at the next window boundary, where nothing is lost. The language
  model (`vt_translate.h`) sets `llama_set_n_threads` before every `llama_decode`, decodes the
  prompt one ubatch per call, and redoes a step its abort callback stopped for fewer cores
  (llama.cpp drops an aborted ubatch from its memory); its thread pool has `poll = 0` so idle
  threads sleep. CI's `language-model` job runs both tools with `VT_TEST_CORES_FLIP_S=30` (one
  core during every other 30 s) and checks the lines, their times and the English, and that
  Whisper did restart.

### Subtitles

- **Watch in the app.** When Whisper finishes, the original video plays with the English subtitles
  over it (Media3 ExoPlayer). No copy is made; it plays the file the user picked.
- **Timing like real subtitles.** Whisper's segments can run long. Split them into lines a person
  can read — about 42 characters a line, at most two lines on screen — using whisper.cpp's token
  timestamps to place each split. Segment times are usually within about half a second of the
  speech; do not promise better.
- **The user styles them**, with a live preview over the playing video: height on screen, text
  size, colour, font, outline or shadow, and a background box with its colour and opacity. The
  style is remembered for the next video.
- **The user rewords them.** Tap a line to fix its text. Edits live in memory like everything else.
- **The user retimes them.** Shift *all* subtitles earlier or later in one go — when the whole
  video is two seconds late, that is one adjustment, not forty — in steps of a tenth of a second,
  checked against the playing video. And nudge one line's start and end on its own, for the line
  Whisper placed badly. Not frame-perfect; the aim is that each line shows while it is being said.
- **Save subtitles** as `.srt` (and `.txt`, the transcript with a time before each line), and
  **open saved subtitles or a transcript** for a video later, so reopening a video already done
  does not mean running Whisper again, and a transcript made elsewhere shows at its own times.
  Corrections to an opened `.srt` are saved back into it.
- **Save a copy with the subtitles burned in**, to the gallery under `Movies/Video Translator`. It
  looks exactly like the in-app preview: one style definition draws both, never two renderers that
  drift apart. Media3 Transformer with a text overlay timed per frame is the expected route. Same
  resolution and frame rate as the original, audio passed through untouched, with progress and
  Cancel. The original video is never modified.
- Build it in this order, each step a Release the owner can try: English transcript, then the
  in-app player with styling and editing, then the burned-in copy.
- **Done in 0.2.0 (stage 2, first part):** `ui/PlayerScreen.kt` plays the picked URI with Media3
  ExoPlayer (`PlayerView` in an `AndroidView`); `subtitles/SubtitleOverlayView` sits inside the
  player's `exo_content_frame` (exactly the picture's size, under the controls) and draws the
  current cue every frame while playing with `subtitles/SubtitleRenderer` (StaticLayout on a
  Canvas), the one renderer the burned-in copy must also use. `SubtitleStyle` sizes are fractions
  of the picture (text: of its shorter side), so preview and copy match at any resolution. The
  style panel sits below the video and changes it live; while it is open and nothing is being
  said, the nearest line (else a sample) is shown. `SubtitleStyleStore` keeps the style in
  SharedPreferences. `subtitles/Cues.kt` splits lines into cues of at most two rows of about 42
  characters (East Asian characters count double), sharing a long line's time by length (the
  English has no timing of its own inside a line; Whisper's token timestamps are not used yet).
  `SubtitleFiles` writes `.srt` (cues, with the "all earlier/later" offset applied) and `.txt`
  through `CreateDocument`, and reads `.srt` back (BOM, `.` decimals, missing numbers, tags) for
  "Watch a video with subtitles you saved" (Photo Picker, then `OpenDocument`). The activity
  handles rotation itself (`configChanges`), so playback survives turning the phone. Unit tests:
  `app/src/test/.../subtitles/SubtitlesTest.kt`. Still to do in stage 2: reword a line, nudge one
  line's start and end.
- **Done in 0.3.0 (stage 3, before the rest of stage 2):** `export/SubtitledCopy` runs Media3
  Transformer on the picked URI with an
  `OverlayEffect` whose `SubtitleOverlay` (a `BitmapOverlay`) draws each frame's cue with
  `SubtitleRenderer` onto a transparent bitmap the size of the frame (from `configure()`, with
  MediaMetadataRetriever's rotated size as the fallback); a new bitmap only when the cue changes,
  because Media3 uploads a bitmap again only when it is a different object. Video re-encoded at its
  own size and frame rate, audio passed through. Output goes to `cacheDir/subtitled-copy.mp4`
  (Transformer needs a file path), then `export/ExportEngine` copies it into MediaStore
  (`Movies/Video Translator`, `IS_PENDING` while writing; no permission for the app's own files)
  and deletes the cache file (also at start-up, if a crash left it). `export/ExportService` is a
  foreground service like TranslationService (progress, Cancel, wake lock, "saved"
  notification). The confirmation dialog says the gallery copy may be uploaded by cloud backup.
  `SubtitledCopyTest` (emulator) makes a copy of jfk.mp4 and checks picture and sound tracks, same
  size and length, and white subtitle pixels at the bottom (none at the top, none in the
  original).
- **Done in 0.4.0 (the rest of stage 2):** `ui/LineEditing.kt`: `LinesPanel` under the video
  lists every line with its time (the one being said highlighted and kept in view; the position
  is polled every 200 ms while it is open); tapping a line plays just that line (seek, play, pause
  at its end) and opens `LineEditDialog`: the words, start and end in 0.1 s steps (at least 0.2 s
  long), Save, Save and play (from 1 s before), Remove. The results screen's list opens the same
  dialog (without playing). `JobState.spoken` keeps each line as first written down, index for
  index with `lines`, and the dialog and list show it under the English when they differ (the
  language model's translations). Corrections go to `TranslationEngine.editLine/removeLine` when
  the lines are the job's (`Watch.fromJob`), else to the watched file's lines, so the player,
  `.srt`/`.txt` and the burned-in copy all use them.
- **Done in 0.5.0 (carry on after a stop):** Android's 6-hour daily limit for `mediaProcessing`
  and `dataSync` services (and low-memory kills) can stop a long run, which used to lose
  everything. `engine/JobStore` writes the job (URI, name, duration, language, lines, `spoken`,
  `translated` count, `whisperDone`, timings) as JSON to `noBackupFilesDir/job.json` (written to a
  `.tmp` file and renamed) after every line, every translated group, every edit and at the end;
  `TranslationEngine.restore` reads it at start-up (a run that was working comes back as
  `Cancelled` with `restored`). `TranslationEngine.resume` builds a `Resume` for
  `Pipeline.run`: Whisper carries on from the end of the last kept line (JNI `startMs` in the
  audio's timeline, mapped into the speech by `VtSpeech::to_speech_cs`, and the language found
  before so it is not detected again from the middle; `VtWhisperRun::run(..., start_cs)`), and the
  language model translates only lines from `translated` on; with `whisperDone` the video is not
  even opened. The picker's URI gets `takePersistableUriPermission`, released on Close or a new
  job; when the video can no longer be opened the user picks it again and the duration must
  match within 1.5 s. `CarryOnTest` (emulator) checks the store round trip and a carry-on from
  9 s of ja-udhr.mp4; `vt-transcribe --self-test` checks the inverse time mapping, and
  `--from-ms`/`--language` carry on from the command line.
- **Done in 0.6.0 (open transcripts, save corrections into the file):**
  `SubtitleFiles.read(bytes)` opens `.srt`, WebVTT (`.vtt`: optional hours, cue settings, NOTE
  blocks, `<v Speaker>`/`<c>` tags and inline times stripped, entities decoded), YouTube's `.sbv`,
  and transcripts with times (`parseTranscript`): a time at the start of a row (`01:23`,
  `[00:01:23]`, `(1:23) -`, a range `0:05 - 0:09`, or alone on its row with the words below), or,
  only in files with fewer than two such rows, at the end of a short speaker row followed by
  words (`Speaker 1  0:00`, `Anna (00:00:03):`, kept as an "Anna: " prefix; not if it has `,` or
  sentence punctuation or ends with "at", "by", "until" and the like). Rows before the first time
  are dropped; a time earlier than the one before, or followed by am/pm, is words; rows with the
  same time are joined. A transcript line shows until the next starts, unless that is more than
  3 s after it has been read (700 ms + 80 ms per width unit, at least 1.5 s). Plain text in UTF-8
  or UTF-16 with a BOM, or `.docx` (`word/document.xml` from the zip, paragraphs, tabs and breaks
  by regex; no XML library). If the first time is past the video's end (clock times in meeting
  minutes) the lines are moved to start at 0, with a message. `toText` now writes
  `[00:01:23] words` per line (with the offset), so a saved `.txt` opens again. A `.srt` opened
  this way (or saved with "Save subtitles" while watching a file's lines) is the watch's `file`:
  "Save changes in <name>" writes it back (`openOutputStream(uri, "wt")`, the picker's write
  grant; else the save dialog), and leaving the player with unsaved corrections or timing asks
  first. The copy dialog says the burned-in words cannot be read back, so keep the `.srt`.
  Unit tests: `SubtitlesTest` (VTT, SBV, transcript shapes, `.txt` round trip, UTF-16, `.docx`).

### The model

The bundled model is `whisperModel` in `app/version.properties` (since 0.1.2: `large-v3-q5_0`,
chosen for accuracy over speed, since it is one run per video; 0.1.0 and 0.1.1 bundled
`medium-q5_0`). It is an uncompressed asset (`noCompress`). CI fetches it from
`https://huggingface.co/ggerganov/whisper.cpp/resolve/main/<file>` and checks it against the
sha256 below. It is never committed — GitHub refuses files over 100 MB.

| File | Bytes | sha256 | Use |
|---|---|---|---|
| `ggml-large-v3-q5_0.bin` | 1,081,140,203 | `d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1` | the app (since 0.1.2) |
| `ggml-medium-q5_0.bin` | 539,212,467 | `19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f` | 0.1.0 and 0.1.1; still selectable |
| `ggml-tiny.bin` | 77,691,713 | `be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21` | tests only |

Medium was chosen over small because Japanese suffers most from a small model; large-v3 then
replaced medium at roughly twice the time. The `large-v3-turbo` models are not trained for
*translation*; do not use them with `translate` on. Whether turbo is good enough for the
language-model path, where Whisper only writes down the spoken language, is the open question
under "Where things stand".

### The phone

S24 Ultra: Snapdragon 8 Gen 3, arm64-v8a, 12 GB RAM, cores 1 × X4 + 5 × A720 + 2 × A520. The release
APK ships arm64-v8a only. The native code is compiled optimised (Release, `-O3`,
`armv8.2-a+dotprod+fp16` or newer) in **every** build type: an unoptimised whisper.cpp is many times
slower and reads as "the app is broken". Start at about 6 threads and tune from timings on the
phone. **Never more threads than cores** (`DEFAULT_THREADS` caps at `availableProcessors()`):
ggml's threads wait for each other by spinning, so 4 threads on 2 cores made the small Qwen3.5
write 0.5 tokens/s instead of 25. GitHub's standard runners have 4 vCPUs for a public repository
(2 for a private one), and the emulator has 2 cores; tests and CI use `nproc` /
`availableProcessors()`, never a fixed number.

## Builds and releases

- GitHub Actions builds the APK. Its Ubuntu runners ship the Android SDK and NDK, so nothing heavy
  needs installing. A cloud session may not be able to install the SDK itself; if it cannot, push
  and read the Actions result. Standard runners are free for a public repository.
- **The owner installs from the Releases page in the phone's browser.** Every build meant to be
  tried is a Release with the APK attached. Do not rely on Actions artifacts: they expire, and an
  APK with the large-v3 model inside is over 1 GB.
- **Every release is signed with the same key**, or Android refuses to install it over the previous
  one. The key is in repository secrets, never in the repo: `SIGNING_KEYSTORE_B64` (a base64
  PKCS12 keystore), `SIGNING_STORE_PASSWORD`, and optionally `SIGNING_KEY_ALIAS` and
  `SIGNING_KEY_PASSWORD` (without them CI uses the keystore's only key and the store password). The
  owner keeps a backup offline. Never generate another key for this app. CI checks the release APK
  with `apksigner verify --print-certs` against this certificate, so a build signed with a debug
  key never becomes a Release:
  SHA-256 `12:F6:EC:BC:F4:D1:C0:CE:6D:96:68:D6:A1:B3:BC:8C:08:49:93:8E:FA:0C:43:04:AA:9F:7D:80:28:BE:61:52`.
- CI on every push to `main` (except pushes that change only `*.md` or `docs/`): the
  network-permission check above, and a test on an emulator with the tiny model that takes a real
  video file through decode and Whisper to English text (whisper.cpp's `samples/jfk.wav`, wrapped
  in an `.mp4` with ffmpeg so the video decode path is exercised). Public Russian and Japanese
  clips, each with its source and licence, are in `test-clips/clips.tsv` and
  `test-clips/README.md` (two Russian, two Japanese, jfk). A cloud session cannot reach Wikimedia
  Commons, so the manual "Find test clips" workflow searches it from a GitHub runner and
  fingerprints chosen files.

### How a Release is made (decided)

- `.github/workflows/build.yml` runs on every push to `main` (not a working branch, which carries
  the same commits) and by hand. Every run: `apk` and `emulator`. The long checks (`translation`,
  `language-model`, `arm`) run only for a `[release]` commit or by hand (`full`). Job `apk` builds
  the signed release APK and runs `scripts/check-apk.sh` (permissions via
  `scripts/check-permissions.sh`, whose `--self-test` proves it catches `INTERNET`; signature;
  arm64 only; model stored at its exact size; no exported ggml/llama/whisper symbols) and
  `scripts/check-native-flags.sh` (every file `-O3`, arm64 `-march` from CMake's
  `compile_commands.json`). Job `emulator` runs the unit tests, then the instrumented tests on an
  API 34 x86_64 emulator (`scripts/ci-emulator-test.sh`, plain `adb shell am instrument`). Job
  `translation` translates the public clips with the release's model on PC runners, one runner per
  clip in parallel (as does `language-model`), and checks the detected language, that the output
  is English, and that expected words appear (`scripts/check-translation.py`; it prints the
  English, so read it). Job `language-model` does the same as the app with a model loaded: Whisper
  writes the clips down (`--no-translate`) and `tools/vt-translate` translates them with
  Qwen3.5-9B (`LLM_TOOL`, `LLM_MODEL`), checked the same way. Job `arm` cross-builds the same code
  for arm64 Android with the app's flags and runs it under QEMU with every CPU feature on
  (`QEMU_CPU=max`; QEMU registered through binfmt by `docker/setup-qemu-action`, because apt-get
  can hang on the runners) with the tiny model, quantised to q5_0 like the app's model, requiring
  NEON, FP16_VA, DOTPROD and MATMUL_INT8: the phone's instruction paths, checked for correctness
  (not speed); it does the same for vt-translate with a small Qwen3.5 (0.8B Q4_K_M, 532,517,120
  bytes, sha256 `bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517`) on Whisper's
  transcript of the Japanese UDHR clip (`test-clips/ja-udhr-transcript.txt`,
  `scripts/check-language-model.py`). The emulator job also runs `LanguageModelTest`: a Japanese
  `.mp4` through tiny Whisper and the small Qwen3.5 in the app's process, and Cancel during the
  language model.
- To publish: bump `versionCode` and `versionName` in `app/version.properties`, add
  `docs/releases/<versionName>.md` (plain notes for a non-developer, naming nobody), and push a
  commit whose message contains `[release]`. The `apk` job uploads a draft Release; `publish`
  makes it public only if all five jobs passed, and `discard-draft` deletes it otherwise. GitHub's
  own token creates the `v<version>` tag, because cloud sessions cannot push tags (403) and may
  only be able to push their own branch.
- Models are cached in Actions (`actions/cache`, not artifacts). The test assets — `ggml-tiny.bin`,
  `jfk.mp4`, `ja-udhr.mp4` and `test-llm.gguf` — are put in `app/src/androidTest/assets/` by
  `scripts/fetch-models.sh tiny`, `scripts/make-test-video.sh` and `scripts/hf_fetch.py` (the
  0.8B Qwen3.5, checked against Hugging Face's sha256); the release model goes to
  `app/src/release/assets/models/`. All are git-ignored.

### Code layout and pinned choices

- `applicationId` and namespace `app.videotranslator` (the `:llm` module's namespace is
  `app.videotranslator.llm`): never change it, or Android treats the next build as a different
  app. minSdk 33, target/compile SDK 36. AGP 8.13.0, Kotlin 2.2.21, Gradle 8.14.3 (versions in
  `gradle/libs.versions.toml`). The JNI functions are named after the package
  (`Java_app_videotranslator_...` in `whisper_jni.cpp` and `llm_jni.cpp`).
- llama.cpp is the git submodule `third_party/llama.cpp`, at tag v0.9.0 (commit `cd959bae`), the
  version the language-model test ran. Its ggml is a different version from whisper.cpp's, so it
  is built in its own Gradle module, `:llm` (native only), as `libvtllama.so`, with
  `-Wl,--exclude-libs,ALL` so each library keeps its ggml private; `scripts/check-apk.sh` checks
  that neither exports ggml/llama/whisper symbols. `llm/src/main/cpp/vt_translate.h` is the whole
  step (system and user prompts exactly as the test's way D, the model's own chat template with
  thinking off, the JSON-schema grammar for exactly N strings, temperature 0 (greedy within the
  grammar: `VtGreedyGrammar` checks the likeliest tokens first, the same pick as llama.cpp's common
  sampler, which checks all ~250,000 Qwen3.5 tokens whenever the first choice is not allowed: about
  2 s per token for the 0.8B test model on a PC), llama.cpp's chat parser as the server uses it,
  groups of at most 16 lines split evenly, an unusable answer retried as two halves, a line with no
  usable answer kept as spoken, never throwing). `llm_jni.cpp` and `tools/vt-translate` both use
  it; `llama-build.cmake` holds the build options (`-O3`, the same arm64 `-march`, OpenMP and
  KleidiAI off). Kotlin: `llm/LanguageModel.kt`, `llm/LanguageModels.kt` (import, known files),
  `engine/Pipeline.kt` (stages Whisper, LoadingLanguageModel, LanguageModel).
- whisper.cpp is the git submodule `third_party/whisper.cpp`, at tag v1.9.5 (commit `d1be6fde`).
  `app/src/main/cpp/CMakeLists.txt` builds it and ggml as static libraries inside one JNI library,
  `libvtwhisper.so`: CPU only, OpenMP off, `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16+i8mm` (every
  core of the 8 Gen 3 has these), `-O3` forced after CMake's per-build-type flags.
- `whisper/` is the JNI layer (model read straight from the APK's assets, 16 kHz audio held in
  native memory, a cancel flag the native abort callback checks). `audio/` is MediaExtractor +
  MediaCodec, channel averaging, and a windowed-sinc resampler (Kaiser, cutoff 0.92 × 8 kHz; unit
  tests check level, timing, and that an 11 kHz tone is removed rather than folded to 5 kHz).
  `engine/Pipeline` is the video-to-lines path that both the app and the emulator test run;
  `engine/TranslationEngine` is the one job per process that the screen observes;
  `engine/TranslationService` keeps it running in the background.
- Whisper settings: beam search 5 when Whisper translates (`VT_APP_BEAM_SIZE = 5`, since 0.1.2;
  earlier builds used greedy decoding with whisper.cpp's temperature fallback), greedy when it only
  writes down the spoken language for the language model (`VT_APP_TRANSCRIBE_BEAM_SIZE = 0`, since
  0.1.6: same transcript, about 20% faster on the public clips; `vt_app_beam_size()`), no previous-window context
  (`VT_APP_USE_CONTEXT = false`), `language = "auto"`, `translate` on (off with a language model),
  flash attention on, 6 threads, and whisper.cpp's Silero speech detector
  (`ggml-silero-v6.2.0.bin`, 885,098 bytes, sha256
  `2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987`, matching the hash Hugging Face
  publishes for `ggml-org/whisper-vad`) with pauses under 2 s kept and 400 ms padding. The
  detector runs first, single-threaded, in `app/src/main/cpp/vt_speech.h`; Whisper gets only the
  speech and its times are mapped back to the video there (`vt-transcribe --self-test` checks the
  mapping). whisper.cpp's own `vad` option is not used: it runs the detector with 4 threads per
  32 ms step, which cost about 4 minutes per minute of audio on a 2-core CI runner. A line whose
  letters equal the previous line's is dropped. They live in `app/src/main/cpp/vt_params.h` and
  the build options in `whisper-build.cmake`; the app and `tools/vt-transcribe` (CI's
  command-line test driver) both use those two files, so the tests run the app's exact settings.
  Change them there, nowhere else.
- Why the speech detector: on a video that begins without speech (music, silence), Whisper can
  invent one English line and repeat it many times before the talking starts. With the detector
  Whisper only hears speech, and detects the language from speech. It ships in every build
  (`app/src/main/assets/models/`, fetched by `scripts/fetch-models.sh vad`) and is copied once to
  no-backup storage because whisper.cpp opens it by path.
- Changing the model: set `whisperModel` in `app/version.properties` (the names
  `scripts/fetch-models.sh` knows); the build, `scripts/check-apk.sh` and CI all read it, and each
  model lives in `models/<name>/models/` so only the chosen one is bundled. Going back to a smaller
  model needs a new, higher versionCode (Android refuses downgrades). Changing the app's decoding
  or model is the owner's call, because of the extra time on the phone.
- What a cloud session can and cannot do here: `dl.google.com` (Android SDK and Google Maven) and
  Hugging Face are blocked, so no Android build runs in the session; Maven Central is reachable
  but rate-limits. Non-Compose Kotlin can be type-checked against Robolectric's
  `org.robolectric:android-all` jar, and the JNI code host-built with stub Android headers.

### Test results on the public clips

All on GitHub's PC runners with the clips in `test-clips/`.

- **Whisper models** (manual "Compare models" workflow, Whisper translating): medium greedy gives
  the gist with word-level slips (Chekhov's "sherry" as "cherries", "orange flora"); medium beam 5
  was not clearly better (better on the UDHR's first sentence, worse on Botchan, where "reckless"
  became "iron man"); previous-window context changed nothing on clips this short. large-v3
  (beam 5) was best on the formal UDHR text ("reason and conscience") but worse on Botchan's
  opening and on Chekhov (it read "Fat" and "Thin" as the surnames "Tolstoy and Tonsky", "sherry"
  as "herring"), at roughly twice medium's time.
- **large-v2-q5_0** (1,080,732,091 bytes, sha256
  `3a214837221e4530dbc1fe8d734f302af393eb30bd0ed046042ebf4baf70f6f2`, equal to Hugging Face's listed
  oid; `scripts/fetch-models.sh large2`) with beam 5: best of the Whisper models on the UDHR ("All
  humans are born free and equal in dignity and rights ... endowed with both reason and
  conscience"), right on Chekhov's fat/thin where large-v3 made them surnames, good on Tolstoy
  (kept "samovar"); Botchan's first line wrong, as with large-v3. About twice large-v3's time on
  the CI runner. Not adopted.
- **Language model after Whisper** (llama.cpp v0.9.0 at `cd959bae`, Q4_K_M, temperature 0, answer
  forced to a JSON list of exactly N lines so the timing is kept). Whisper large-v3 beam 5
  produced both its English and the original-language transcript (`--no-translate`) of the four
  public clips. Three ways were tried: B, polish Whisper's English with context; C, translate the
  transcript with Whisper's English as a hint; D, translate the transcript alone.
  - **Whisper's own-language transcript is near-verbatim** (Botchan: 時分 heard as 自分, 小使 as
    小遣い; Chekhov and Tolstoy word-perfect). Its *English* is where the errors are: Botchan's
    first line became "I didn't have a gun to give to my parents", Chekhov "Thin and Thin" and
    "herring", and Chekhov's last English line repeated an earlier one (not adjacent, so not
    dropped).
  - **D is clearly best.** B cannot fix meaning (it keeps "gun", "Thin and Thin", "herring";
    Qwen3-4B-Instruct even invented the title "The Man with the Movie Camera"). C sometimes picks
    a better word but moves text between lines (Botchan's last three lines shifted, leaving "he
    asked." alone), so subtitles would show at the wrong time, and its prompts are twice as long.
  - Qwen3.5-9B (D): UDHR exact ("born free and equal in dignity and rights ... endowed with reason
    and conscience ... spirit of brotherhood"); Chekhov right ("The Fat and the Thin", "sherry",
    "ripe cherries", "suitcases, bundles, and boxes"; only "Florida orange" for флёр-д'оранж);
    Tolstoy right ("drawing room", "samovar spigot", "tears of the imagination"); Botchan right
    from the second-floor joke onwards ("He called me a coward, so I did it"), but "losing money"
    for 損ばかり. Best of those tried. 5,680,522,464 bytes (`unsloth/Qwen3.5-9B-GGUF`,
    `Qwen3.5-9B-Q4_K_M.gguf`, sha256 `03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8`).
  - Qwen3.5-4B (D): nearly as good on Russian ("Fat and Thin", "sherry", "cherries"; "flord
    orange"), UDHR right, Botchan gist right with "lost money" and "allowance pocketed".
    2,740,937,888 bytes (`unsloth/Qwen3.5-4B-GGUF`, `Qwen3.5-4B-Q4_K_M.gguf`, sha256
    `00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4`).
  - Qwen3-4B-Instruct-2507 (D): weakest ("Thick and Thin", "brandy", "ripe plums", "straps",
    "kran-samovar" left untranslated; Botchan's joke turned into a conditional).
  - Gemma 4 E4B (D): best on Botchan ("I've inherited a reckless streak ... unlucky since I was a
    kid", "you wouldn't dare jump from there", "You coward!"), good on Tolstoy, Chekhov mostly
    right ("The Fat Man and the Thin Man", "sherry"; "florid orange", "cardboard"). But it
    replaced the UDHR's first sentence with the US Declaration of Independence ("All men are
    created equal, endowed by their Creator ..."): it swaps in famous text it remembers, so it
    cannot be trusted as a translator. 4,977,171,584 bytes (`unsloth/gemma-4-E4B-it-GGUF`;
    `ggml-org/gemma-4-E4B-it-GGUF` has no Q4_K_M).
  - Time on a 2-vCPU runner, D: Qwen3.5-9B 209 s and Qwen3.5-4B 168 s for the 149 s of audio,
    against 1,278 s for Whisper's own transcription pass, so the language model adds roughly 15%
    to Whisper's time there (prompt ~12 tok/s, output 4-5 tok/s).
  - With D, Whisper transcribes instead of translating (same time), so the app keeps one Whisper
    pass. English videos need no language model.
  - Both Qwen3.5 files are over GitHub's 2 GB per-file Release limit, so the user downloads the
    file in the phone's browser from Hugging Face, then imports it once; the app checks its sha256.
  - Decision: D with Qwen3.5-9B, Qwen3.5-4B as the smaller fallback if 9B is too slow or does not
    fit on the phone (0.1.3).
- **Faster transcription for the language-model path** (manual "Experiment - faster
  transcription", 4-vCPU runners, Whisper `--no-translate` with the speech detector, then
  Qwen3.5-9B; Whisper's seconds, each way on the same runner):

  | Clip | large-v3 beam 5 (app) | large-v3 greedy | turbo beam 5 | turbo greedy |
  |---|---|---|---|---|
  | ja-udhr (20 s) | 103 | 88 (-15%) | 76 (-26%) | 73 (-29%) |
  | ja-botchan (45 s) | 92 | 72 (-22%) | 62 (-32%) | 57 (-38%) |
  | ru-chekhov (40 s) | 169 | 138 (-18%) | 117 (-31%) | 111 (-34%) |
  | ru-tolstoy (45 s) | 149 | 113 (-24%) | 95 (-36%) | 89 (-40%) |

  large-v3 greedy wrote the same transcript as beam 5 on all four clips (so the same English).
  large-v3-turbo (`ggml-large-v3-turbo-q5_0.bin`) was fine on the Japanese clips but heard
  Chekhov's хересом as хирисом ("iris" for "sherry") and added a repeated last line, and on
  Tolstoy wrote 15.7-45.0 s as one 30-second line (useless as a subtitle) and "LibriWalks". Short
  clips are dominated by the encoder, which turbo shares with large-v3 (it has 4 decoder layers
  instead of 32), so the saving on long speech may differ. Adopted in 0.1.6: greedy large-v3 for
  the language-model path only; not turbo.

## Where things stand

- Stage 1 (English transcript) is in use, with the optional language model. 0.1.0–0.1.3 were
  built in the earlier private repository; 0.1.4 (background translation) is the first Release
  from this one, under the new application ID. Stage 2 started in 0.2.0 (player, look settings
  with live preview, timing offset, save and open `.srt`); stage 3 (the burned-in copy) in 0.3.0;
  correcting single lines (words, timing, remove) in 0.4.0; carrying on after a stop in 0.5.0;
  opening transcripts with times and saving corrections back into the `.srt` in 0.6.0. Not
  yet tried on a phone: copies of upright or HDR videos, how long a copy takes, the line editing,
  and a real overnight stop. Proposed next: work through long videos in parts (watch the first
  part sooner), and give the language model the lines around each group.
- Faster transcription for long videos: large-v3 greedy for the language-model path since 0.1.6
  (about 20% faster with the same transcript on the public clips; turbo made mistakes). Speed on
  the phone not yet known.
- Phone memory with Qwen3.5-9B beside Android is not known yet.
- CI's `arm` job confirmed the phone's arm64 code gives the right English under QEMU with the
  q5_0 tiny model, using NEON, FP16_VA, DOTPROD and MATMUL_INT8 (about 4 minutes under emulation;
  the unquantised tiny model was dropped from that job because emulating it took over 10).
- `apt-get install` on GitHub's runners can stall for a quarter of an hour in the man-db trigger,
  or hang on a mirror; `scripts/ci-apt-install.sh` switches man-db off and has timeouts and
  retries. Ubuntu's mirror can also trickle (ffmpeg's packages took over 20 minutes on two runners
  at once), so the clip-fetching steps take a static ffmpeg from the imageio-ffmpeg wheel on PyPI
  (`scripts/ci-ffmpeg.sh`, pinned by sha256, apt-get only as the fallback). Many parallel runners fetching every clip from Wikimedia get throttled, so each
  translation runner fetches and caches only its own clip.
- The emulator test takes about 80 s for jfk's 11 s with the tiny model. That says nothing about
  the phone: the x86 emulator has no AVX or F16C, so ggml runs its slowest paths there. Phone speed
  is only known from the owner's timings (the app shows them when it finishes).

## Talking to the owner

- Short and plain. The owner is not an Android developer. Say what changed, what to try, and what
  is not verified.
- Work lands on `main` without pull requests. If the environment can only push its own branch, say
  so plainly and give the one step that merges it.
- "Done" means a Release the owner can install, not a green check. Say what was tested on the
  emulator, and that speed and quality on the phone are unknown until the owner tries it.
