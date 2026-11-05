// The Whisper settings the app uses, in one place. The JNI bridge and tools/vt-transcribe (the
// command-line test driver CI runs on public clips) both include this, so the tests translate
// with exactly the app's settings.
#pragma once

#include <cctype>
#include <string>

#include "whisper.h"

inline whisper_context_params vt_context_params() {
    whisper_context_params p = whisper_context_default_params();
    p.use_gpu = false;      // CPU only
    p.flash_attn = true;
    return p;
}

// 0: greedy decoding (one candidate per step, with whisper.cpp's temperature fallback). N > 0: beam
// search with N candidates, slower but often better wording. The app uses this value:
// accuracy matters more than speed (one run per video), so 5, as in whisper.cpp's own CLI.
constexpr int VT_APP_BEAM_SIZE = 5;

// true: Whisper sees the text of the previous 30 s window while working on the next one, which
// helps consistent wording (names, terms) but can let one mistake repeat. The app uses this value.
constexpr bool VT_APP_USE_CONTEXT = false;

// The speech detector is not set here: it runs first, in vt_speech.h, and Whisper gets only the
// speech (whisper.cpp's own vad option is left off; see vt_speech.h for why).
inline whisper_full_params vt_full_params(int threads, bool translate,
                                          int beam_size = VT_APP_BEAM_SIZE,
                                          bool use_context = VT_APP_USE_CONTEXT) {
    whisper_full_params p = whisper_full_default_params(
        beam_size > 0 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (beam_size > 0) p.beam_search.beam_size = beam_size;
    p.n_threads = threads > 0 ? threads : 4;
    p.language = "auto";    // detect the spoken language (from the first 30 s of speech)
    p.translate = translate;
    p.no_context = !use_context;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.print_special = false;
    return p;
}

// Whisper sometimes gets stuck and repeats one line many times. A line whose letters and digits
// are the same as the line before it is dropped.
inline std::string vt_line_key(const char *text) {
    std::string key;
    for (const char *c = text; c != nullptr && *c != '\0'; ++c) {
        const auto u = static_cast<unsigned char>(*c);
        if (u >= 0x80) key.push_back(static_cast<char>(u));                       // non-ASCII: keep
        else if (std::isalnum(u)) key.push_back(static_cast<char>(std::tolower(u)));
    }
    return key;
}

inline bool vt_is_repeat(const std::string &previous_key, const std::string &key) {
    return !key.empty() && key == previous_key;
}
