// vt-translate MODEL.gguf [THREADS] < INPUT
//
// INPUT (UTF-8), as the app hands Whisper's transcript to the language model:
//   LANG <Whisper's language name, e.g. japanese>
//   LINE <text>          one per transcript line, in order
// Prints, for CI to check:
//   SYSTEM <llama.cpp system info>
//   EN <index> <English line>     one per input line, same order
//   STATS requests=.. splits=.. kept_original=.. prompt_tokens=.. output_tokens=.. thread_changes=..
//   TIME load_ms=.. prompt_ms=.. output_ms=.. prompt_tok_s=.. output_tok_s=..
// Uses the app's prompt and settings (llm/src/main/cpp/vt_translate.h).
// VT_TEST_CORES_FLIP_S=N: pretend Android allows only one core during every other N seconds, so the
// thread count changes as in the app when it leaves the screen; the English must not change.
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

#include "log.h"
#include "vt_translate.h"

static void quiet_log(enum ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) fputs(text, stderr);
}

static double g_flip_s = 0;
static std::chrono::steady_clock::time_point g_started;

static int flipping_cores() {
    const double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - g_started).count();
    return (static_cast<long>(s / g_flip_s) % 2 == 1) ? 1 : vt_llm_allowed_cores();
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: vt-translate MODEL.gguf [THREADS] < INPUT\n");
        return 2;
    }
    const std::string model_path = argv[1];
    const int threads = argc > 2 ? atoi(argv[2]) : 4;

    std::string language;
    std::vector<std::string> lines;
    std::string row;
    while (std::getline(std::cin, row)) {
        if (!row.empty() && row.back() == '\r') row.pop_back();
        if (row.rfind("LANG ", 0) == 0) language = row.substr(5);
        else if (row.rfind("LINE ", 0) == 0) lines.push_back(row.substr(5));
    }
    if (language.empty()) {
        fprintf(stderr, "no LANG line in the input\n");
        return 2;
    }

    llama_log_set(quiet_log, nullptr);
    common_log_set_verbosity_thold(-1);

    const auto t0 = std::chrono::steady_clock::now();
    if (const char *flip = getenv("VT_TEST_CORES_FLIP_S")) {
        g_flip_s = atof(flip);
        if (g_flip_s > 0) {
            g_started = t0;
            vt_llm_cores = flipping_cores;
        }
    }
    std::string error;
    // VT_N_CTX overrides the context size, for speed comparisons only.
    const char *n_ctx_env = getenv("VT_N_CTX");
    auto llm = VtLlm::load(model_path, threads, nullptr, &error, n_ctx_env ? atoi(n_ctx_env) : VT_LLM_N_CTX);
    if (!llm) {
        fprintf(stderr, "%s\n", error.c_str());
        return 1;
    }
    const auto t1 = std::chrono::steady_clock::now();
    printf("SYSTEM %s\n", llama_print_system_info());
    fflush(stdout);

    std::vector<std::string> english(lines.size());
    VtLlmStats stats;
    const int rc = llm->translate(vt_llm_language_name(language), lines,
        [&](size_t first, const std::vector<std::string> &out) {
            for (size_t i = 0; i < out.size(); ++i) english[first + i] = out[i];
        }, stats);
    for (size_t i = 0; rc == VT_LLM_OK && i < english.size(); ++i) {
        std::string s = english[i];
        for (char &c : s) if (c == '\n' || c == '\r') c = ' ';
        printf("EN %zu %s\n", i, s.c_str());
    }
    const double load_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    printf("STATS requests=%d splits=%d kept_original=%d prompt_tokens=%lld output_tokens=%lld thread_changes=%d\n",
           stats.requests, stats.splits, stats.kept_original,
           (long long) stats.prompt_tokens, (long long) stats.output_tokens, stats.thread_changes);
    printf("TIME load_ms=%.0f prompt_ms=%.0f output_ms=%.0f decode_ms=%.0f sample_ms=%.0f prompt_tok_s=%.1f output_tok_s=%.1f\n",
           load_ms, stats.prompt_ms, stats.output_ms, stats.decode_ms, stats.sample_ms,
           stats.prompt_ms > 0 ? stats.prompt_tokens * 1000.0 / stats.prompt_ms : 0.0,
           stats.output_ms > 0 ? stats.output_tokens * 1000.0 / stats.output_ms : 0.0);
    if (rc != VT_LLM_OK) {
        fprintf(stderr, "translation failed: %d\n", rc);
        return 1;
    }
    return 0;
}
