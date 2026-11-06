// vt-transcribe MODEL AUDIO.f32 [THREADS] [--vad VAD_MODEL] [--beam N] [--context|--no-context] [--no-translate]
// vt-transcribe --self-test        checks the speech-to-video time mapping (vt_speech.h)
//
// AUDIO.f32 is raw little-endian float, 16 kHz mono (ffmpeg -ac 1 -ar 16000 -f f32le).
// Runs with the app's settings (app/src/main/cpp/vt_params.h: beam 5 when translating, greedy with
// --no-translate, as the app with a language model); --beam and --context override them
// for comparisons. The app always uses the speech detector; pass --vad to do the same here.
// Prints, for CI to check:
//   SYSTEM <whisper system info>
//   LANG <code> <name>
//   SEG <start_ms> <end_ms> <text>
//   SPEECH <speech_ms> of <audio_ms> in <n> stretches, <ms> ms   (with --vad)
//   TIME load_ms=.. run_ms=.. audio_ms=.. repeats_dropped=.. restarts=..
// VT_TEST_CORES_FLIP_S=N: pretend Android allows only one core during every other N seconds, so
// Whisper restarts with another thread count as in the app (vt_cores.h), for CI to check the
// lines and times still come out right.
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "vt_cores.h"
#include "vt_params.h"
#include "vt_speech.h"

static void quiet_log(enum ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) fputs(text, stderr);
}

struct Seen {
    std::string last_key;
    int dropped = 0;
    const VtSpeech *speech = nullptr;
    VtWhisperRun *run = nullptr;
};

static double g_flip_s = 0;
static std::chrono::steady_clock::time_point g_started;

// VT_TEST_CORES_FLIP_S: one core in every other period, all of them in between.
static int flipping_cores() {
    const double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - g_started).count();
    return (static_cast<long>(s / g_flip_s) % 2 == 1) ? 1 : vt_allowed_cores();
}

static bool on_abort(void *user_data) {
    return static_cast<Seen *>(user_data)->run->should_stop();
}

static int self_test() {
    VtSpeech sp;
    const int64_t sr = WHISPER_SAMPLE_RATE;
    // Speech at 40.0-49.0 s and 70.0-75.0 s of the video; 100 ms of silence between in `samples`.
    sp.blocks.push_back({0, 40 * sr, 9 * sr});
    sp.blocks.push_back({9 * sr + sr / 10, 70 * sr, 5 * sr});
    struct { int64_t in, want; } cases[] = {
        {0, 4000}, {450, 4450}, {900, 4900}, {905, 4900}, {910, 7000}, {1100, 7190}, {1410, 7500}, {9999, 7500},
    };
    int bad = 0;
    for (auto &c : cases) {
        const int64_t got = sp.to_video_cs(c.in);
        if (got != c.want) { printf("map %lld -> %lld, want %lld\n", (long long) c.in, (long long) got, (long long) c.want); bad++; }
    }
    VtSpeech none;
    if (none.to_video_cs(123) != 123) { printf("empty map changed a time\n"); bad++; }
    printf(bad ? "self-test FAILED\n" : "self-test passed\n");
    return bad ? 1 : 0;
}

static void on_segment(whisper_context *, whisper_state *state, int n_new, void *user_data) {
    auto *seen = static_cast<Seen *>(user_data);
    const int n = whisper_full_n_segments_from_state(state);
    for (int i = n - n_new; i < n; ++i) {
        const char *text = whisper_full_get_segment_text_from_state(state, i);
        int64_t cs0 = whisper_full_get_segment_t0_from_state(state, i);
        int64_t cs1 = whisper_full_get_segment_t1_from_state(state, i);
        seen->run->segment_end(cs1);  // as the app does (whisper_jni.cpp)
        cs0 += seen->run->base_cs();
        cs1 += seen->run->base_cs();
        std::string key = vt_line_key(text);
        if (vt_is_repeat(seen->last_key, key)) { seen->dropped++; continue; }  // as the app does
        if (!key.empty()) seen->last_key = std::move(key);
        if (seen->speech != nullptr) {
            cs0 = seen->speech->to_video_cs(cs0);
            cs1 = seen->speech->to_video_cs(cs1);
        }
        printf("SEG %lld %lld %s\n", (long long) cs0 * 10, (long long) cs1 * 10, text);
        fflush(stdout);
    }
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--self-test") == 0) return self_test();
    if (argc < 3) {
        fprintf(stderr, "usage: %s MODEL AUDIO.f32 [THREADS] [--vad VAD_MODEL] [--beam N] [--no-translate]\n", argv[0]);
        return 2;
    }
    int threads = 4;
    bool translate = true;
    const char *vad = nullptr;
    int beam = -1;  // the app's choice (vt_app_beam_size), unless --beam
    bool context = VT_APP_USE_CONTEXT;
    for (int i = 3; i < argc; ++i) {
        if (strcmp(argv[i], "--vad") == 0 && i + 1 < argc) vad = argv[++i];
        else if (strcmp(argv[i], "--beam") == 0 && i + 1 < argc) beam = atoi(argv[++i]);
        else if (strcmp(argv[i], "--context") == 0) context = true;
        else if (strcmp(argv[i], "--no-context") == 0) context = false;
        else if (strcmp(argv[i], "--no-translate") == 0) translate = false;
        else threads = atoi(argv[i]);
    }
    whisper_log_set(quiet_log, nullptr);
    printf("SYSTEM %s\n", whisper_print_system_info());

    std::ifstream in(argv[2], std::ios::binary | std::ios::ate);
    if (!in) { fprintf(stderr, "cannot read %s\n", argv[2]); return 1; }
    const auto bytes = static_cast<size_t>(in.tellg());
    std::vector<float> pcm(bytes / sizeof(float));
    in.seekg(0);
    in.read(reinterpret_cast<char *>(pcm.data()), static_cast<std::streamsize>(pcm.size() * sizeof(float)));

    using clock = std::chrono::steady_clock;
    const auto t0 = clock::now();
    whisper_context *ctx = whisper_init_from_file_with_params(argv[1], vt_context_params());
    if (ctx == nullptr) { fprintf(stderr, "cannot load model %s\n", argv[1]); return 1; }
    const auto t1 = clock::now();

    Seen seen;
    const float *audio = pcm.data();
    int n = static_cast<int>(pcm.size());
    VtSpeech speech;
    if (vad != nullptr) {
        const auto v0 = clock::now();
        if (!vt_keep_speech(vad, audio, n, speech)) { fprintf(stderr, "speech detector failed\n"); return 1; }
        printf("SPEECH %lld of %lld ms in %zu stretches, %lld ms\n",
               (long long) (speech.samples.size() * 1000 / 16000), (long long) (pcm.size() * 1000 / 16000),
               speech.blocks.size(),
               (long long) std::chrono::duration_cast<std::chrono::milliseconds>(clock::now() - v0).count());
        audio = speech.samples.data();
        n = static_cast<int>(speech.samples.size());
        seen.speech = &speech;
    }
    if (beam < 0) beam = vt_app_beam_size(translate);
    whisper_full_params params = vt_full_params(threads, translate, beam, context);
    params.new_segment_callback = on_segment;
    params.new_segment_callback_user_data = &seen;
    params.abort_callback = on_abort;
    params.abort_callback_user_data = &seen;
    VtWhisperRun run;
    run.wanted = params.n_threads;
    if (const char *flip = getenv("VT_TEST_CORES_FLIP_S")) {
        g_flip_s = atof(flip);
        if (g_flip_s > 0) {
            g_started = clock::now();
            run.cores = flipping_cores;
        }
    }
    seen.run = &run;
    const int rc = n > 0 ? run.run(ctx, params, audio, n) : 0;
    const auto t2 = clock::now();
    if (rc != 0) { fprintf(stderr, "whisper_full failed: %d\n", rc); whisper_free(ctx); return 1; }

    if (whisper_full_n_segments(ctx) > 0) {
        const int lang = whisper_full_lang_id(ctx);
        printf("LANG %s %s\n", whisper_lang_str(lang), whisper_lang_str_full(lang));
    } else {
        printf("LANG none no-speech\n");
    }
    printf("TIME load_ms=%lld run_ms=%lld audio_ms=%lld repeats_dropped=%d restarts=%d beam=%d context=%d vad=%d\n",
           (long long) std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count(),
           (long long) std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count(),
           (long long) (pcm.size() * 1000 / 16000), seen.dropped, run.restarts(), beam, context ? 1 : 0, vad != nullptr ? 1 : 0);
    whisper_free(ctx);
    return 0;
}
