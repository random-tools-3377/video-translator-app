// The language-model step: Whisper's transcript in the spoken language in, English out, one
// English line for each line in, so every line keeps its time on the video.
//
// Shared by the app (llm_jni.cpp) and CI's command-line driver (tools/vt-translate), so the
// tests run the app's exact prompt and settings. Change them here, nowhere else.
//
// It reproduces what was tested on the public clips (way D in CLAUDE.md: translate the transcript
// alone): llama.cpp's server at the same commit, the model's own chat template with thinking off
// (--reasoning off), temperature 0, and the answer forced by a JSON schema to an array of exactly
// as many strings as there are lines. The chat template, the schema-to-grammar conversion and the
// sampler are llama.cpp's own (common/), exactly as the server uses them.
//
// Nothing here writes to storage or the network, and nothing logs transcript text.

#pragma once

#include <sched.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "chat.h"
#include "ggml-cpu.h"
#include "common.h"
#include "llama.h"
#include "sampling.h"

// Context as in the test (llama-server -c 8192). A request is at most VT_LLM_MAX_LINES lines.
constexpr int VT_LLM_N_CTX = 8192;
constexpr int VT_LLM_N_BATCH = 2048;
constexpr int VT_LLM_N_UBATCH = 512;

// Lines per request. The tested clips had 2 to 10 lines, all in one request; a long video is cut
// into requests of at most this many consecutive lines, split evenly.
constexpr int VT_LLM_MAX_LINES = 16;

inline const char *vt_llm_system_prompt() {
    return "You are an expert subtitle translator. You write natural, idiomatic English that a "
           "native speaker would use in subtitles, while keeping the exact meaning of the original. "
           "You never add information that is not there, never drop information, and never merge "
           "or split lines.";
}

// "japanese" (Whisper's name for the language) -> "Japanese".
inline std::string vt_llm_language_name(const std::string &whisper_name) {
    std::string s = whisper_name;
    if (!s.empty() && s[0] >= 'a' && s[0] <= 'z') s[0] = static_cast<char>(s[0] - 'a' + 'A');
    return s;
}

inline std::string vt_llm_user_prompt(const std::string &language, const std::vector<std::string> &lines) {
    std::string numbered;
    for (size_t i = 0; i < lines.size(); ++i) {
        if (i > 0) numbered += "\n";
        numbered += std::to_string(i + 1) + ". " + lines[i];
    }
    const std::string n = std::to_string(lines.size());
    return "These are consecutive subtitle lines from a " + language + " video, from speech recognition, which "
           "can mishear words. Use the surrounding lines to work out what was really meant, and "
           "translate each line into natural, accurate English. Return a JSON array of exactly " +
           n + " strings, string i for line i.\n\nLines:\n" + numbered;
}

// The response_format schema the test sent: a JSON array of exactly n strings.
inline std::string vt_llm_schema(size_t n) {
    nlohmann::ordered_json schema = {
        {"type", "array"},
        {"items", {{"type", "string"}}},
        {"minItems", n},
        {"maxItems", n},
    };
    return schema.dump();
}

// Splits n lines into requests of at most VT_LLM_MAX_LINES, as even as possible: 20 -> 10 + 10.
inline std::vector<std::pair<size_t, size_t>> vt_llm_chunks(size_t n) {
    std::vector<std::pair<size_t, size_t>> out;
    if (n == 0) return out;
    const size_t parts = (n + VT_LLM_MAX_LINES - 1) / VT_LLM_MAX_LINES;
    const size_t base = n / parts, extra = n % parts;
    size_t first = 0;
    for (size_t p = 0; p < parts; ++p) {
        const size_t count = base + (p < extra ? 1 : 0);
        out.emplace_back(first, count);
        first += count;
    }
    return out;
}

struct VtLlmStats {
    int requests = 0;
    int splits = 0;          // requests whose answer was unusable, retried as two halves
    int kept_original = 0;   // single lines left in the spoken language because no answer was usable
    int64_t prompt_tokens = 0;
    int64_t output_tokens = 0;
    double prompt_ms = 0;
    double output_ms = 0;
    double decode_ms = 0;  // of output_ms: the model's steps (the rest is choosing tokens)
    double sample_ms = 0;  // of output_ms: choosing each token within the grammar
    int thread_changes = 0;  // times the thread count followed the cores allowed (vt_llm_cores)
};

// The CPU cores the calling thread may run on now; 0 if unknown. Android narrows this when the app
// leaves the screen, and ggml's threads wait for each other by spinning, so more threads than
// allowed cores makes every step wait for threads that are not running: the model slows to almost
// nothing until the app is back. So the thread count follows this before every step (and a step
// that finds fewer cores while it runs is stopped and redone with fewer threads). The same check
// as vt_allowed_cores() in app/src/main/cpp/vt_cores.h, for Whisper.
inline int vt_llm_allowed_cores() {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof(set), &set) != 0) return 0;
    return CPU_COUNT(&set);
}

// Tests replace this to simulate Android (VT_TEST_CORES_FLIP_S in tools/vt-translate).
inline int (*vt_llm_cores)() = vt_llm_allowed_cores;

// A JSON array of exactly n strings -> out. False if it is anything else.
inline bool vt_llm_parse_answer(const std::string &content, size_t n, std::vector<std::string> &out) {
    out.clear();
    try {
        const auto parsed = nlohmann::json::parse(content);
        if (!parsed.is_array() || parsed.size() != n) return false;
        for (const auto &item : parsed) {
            if (!item.is_string()) return false;
            out.push_back(item.get<std::string>());
        }
        return true;
    } catch (const std::exception &) {
        out.clear();
        return false;
    }
}

// Greedy decoding within the answer's grammar: at each step, the most likely token the grammar
// allows. That is exactly what llama.cpp's common sampler picks at temperature 0 (the test's
// setting), but the common sampler, whenever the model's first choice is not allowed, checks the
// whole vocabulary against the grammar (about 250,000 tokens for Qwen3.5: about 2 s per token on a
// PC). This checks the most likely tokens first and stops at the first allowed one.
class VtGreedyGrammar {
  public:
    ~VtGreedyGrammar() {
        if (grmr_ != nullptr) llama_sampler_free(grmr_);
    }

    // The grammar the chat session set (from the JSON schema). False if there is none.
    bool init(const llama_vocab *vocab, const common_params_sampling &sp) {
        const std::string &grammar = common_grammar_value(sp.grammar);
        if (grammar.empty() || sp.grammar_lazy) return false;
        grmr_ = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (grmr_ == nullptr) return false;
        n_vocab_ = llama_vocab_n_tokens(vocab);
        // As common_sampler_init: move the grammar past what the chat template already wrote of
        // the answer (e.g. an empty thinking block).
        if (!sp.generation_prompt.empty() && common_grammar_needs_prefill(sp.grammar)) {
            const auto tokens = common_tokenize(vocab, sp.generation_prompt, false, true);
            for (size_t i = 0; i < tokens.size(); i++) {
                const std::string piece = common_token_to_piece(vocab, tokens[i], true);
                if (i == 0 && !piece.empty() && std::isspace(static_cast<unsigned char>(piece[0])) &&
                    !std::isspace(static_cast<unsigned char>(sp.generation_prompt[0]))) {
                    continue;  // a space some tokenizers put before the first special token
                }
                llama_sampler_accept(grmr_, tokens[i]);
            }
        }
        return true;
    }

    // The most likely allowed token, or LLAMA_TOKEN_NULL if the grammar allows none.
    llama_token sample(llama_context *ctx) {
        const float *logits = llama_get_logits_ith(ctx, -1);
        if (logits == nullptr) return LLAMA_TOKEN_NULL;
        llama_token best = 0;
        for (llama_token t = 1; t < n_vocab_; ++t) {
            if (logits[t] > logits[best]) best = t;
        }
        if (allowed(best, logits[best])) return best;

        // The next most likely ones, a few at a time; the whole vocabulary only as a last resort.
        for (int k : {64, 1024, n_vocab_}) {
            k = std::min(k, n_vocab_);
            order_.resize(static_cast<size_t>(n_vocab_));
            for (llama_token t = 0; t < n_vocab_; ++t) order_[t] = t;
            std::partial_sort(order_.begin(), order_.begin() + k, order_.end(),
                              [logits](llama_token a, llama_token b) { return logits[a] > logits[b]; });
            cand_.resize(static_cast<size_t>(k));
            for (int i = 0; i < k; ++i) cand_[i] = {order_[i], logits[order_[i]], 0.0f};
            llama_token_data_array arr = {cand_.data(), cand_.size(), -1, true};
            llama_sampler_apply(grmr_, &arr);
            for (size_t i = 0; i < arr.size; ++i) {
                if (arr.data[i].logit != -INFINITY) return arr.data[i].id;  // in order: the most likely allowed
            }
        }
        return LLAMA_TOKEN_NULL;
    }

    void accept(llama_token id) { llama_sampler_accept(grmr_, id); }

  private:
    bool allowed(llama_token id, float logit) {
        llama_token_data one = {id, logit, 0.0f};
        llama_token_data_array arr = {&one, 1, -1, false};
        llama_sampler_apply(grmr_, &arr);
        return arr.data[0].logit != -INFINITY;
    }

    llama_sampler *grmr_ = nullptr;
    int n_vocab_ = 0;
    std::vector<llama_token> order_;
    std::vector<llama_token_data> cand_;
};

enum VtLlmResult { VT_LLM_OK = 0, VT_LLM_CANCELLED = 1, VT_LLM_BAD_ANSWER = 2, VT_LLM_ERROR = -1 };

class VtLlm {
  public:
    ~VtLlm() {
        tmpls_.reset();
        if (ctx_ != nullptr) {
            llama_detach_threadpool(ctx_);
            llama_free(ctx_);
        }
        if (threadpool_ != nullptr) ggml_threadpool_free(threadpool_);
        if (model_ != nullptr) llama_model_free(model_);
    }

    // Loads a GGUF model. Returns null (and a reason) if it cannot. [cancel], if given, stops the
    // load early.
    static std::unique_ptr<VtLlm> load(const std::string &path, int threads, const std::atomic<bool> *cancel,
                                       std::string *error, int n_ctx = VT_LLM_N_CTX) {
        static std::once_flag backend_once;
        std::call_once(backend_once, [] { llama_backend_init(); });

        auto m = std::unique_ptr<VtLlm>(new VtLlm());
        m->cancel_ = cancel;

        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;  // CPU only, as on the phone (load mode and weight repacking: llama.cpp's defaults)
        mp.progress_callback = [](float, void *data) {
            auto *c = static_cast<const std::atomic<bool> *>(data);
            return c == nullptr || !c->load();
        };
        mp.progress_callback_user_data = const_cast<std::atomic<bool> *>(cancel);
        m->model_ = llama_model_load_from_file(path.c_str(), mp);
        if (m->model_ == nullptr) {
            if (error) *error = (cancel && cancel->load()) ? "cancelled" : "could not load the language model";
            return nullptr;
        }

        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = static_cast<uint32_t>(n_ctx);
        cp.n_batch = VT_LLM_N_BATCH;
        cp.n_ubatch = VT_LLM_N_UBATCH;
        cp.n_seq_max = 1;
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        m->wanted_threads_ = std::max(1, threads);
        m->threads_ = m->wanted_threads_;
        cp.no_perf = false;
        m->ctx_ = llama_init_from_model(m->model_, cp);
        if (m->ctx_ == nullptr) {
            if (error) *error = "could not make a context for the language model";
            return nullptr;
        }
        // One thread pool kept for the model's lifetime, as llama.cpp's server sets up: without it,
        // every step starts and stops its own threads.
        // Idle threads sleep at once instead of spinning for a while first (poll 0): when fewer
        // cores are allowed, the pool's unused threads must not take turns on them.
        ggml_threadpool_params tpp = ggml_threadpool_params_default(threads);
        tpp.poll = 0;
        m->threadpool_ = ggml_threadpool_new(&tpp);
        if (m->threadpool_ != nullptr) llama_attach_threadpool(m->ctx_, m->threadpool_, nullptr);
        m->set_cancel(cancel);

        try {
            m->tmpls_ = common_chat_templates_init(m->model_, "");
        } catch (const std::exception &) {
            m->tmpls_.reset();
        }
        if (!m->tmpls_) {
            if (error) *error = "the language model's chat template could not be read";
            return nullptr;
        }
        return m;
    }

    // The flag that stops a running translation (checked between tokens and inside llama.cpp).
    // [cancel] stops the current step; so do fewer cores than threads (the step is then redone).
    void set_cancel(const std::atomic<bool> *cancel) {
        cancel_ = cancel;
        llama_set_abort_callback(ctx_, [](void *data) {
            auto *self = static_cast<VtLlm *>(data);
            return self->cancelled() || self->fewer_cores_now();
        }, this);
    }

    // Translates [lines] (in [language], e.g. "Japanese") to English. Calls
    // on_chunk(first_index, english_lines) after each request, in order. Returns VT_LLM_OK,
    // VT_LLM_CANCELLED or VT_LLM_ERROR. A request that fails or whose answer is unusable is retried
    // as two halves; a single line with no usable answer keeps its original text. VT_LLM_ERROR
    // only if no line at all could be translated.
    template <class OnChunk>
    int translate(const std::string &language, const std::vector<std::string> &lines, OnChunk on_chunk,
                  VtLlmStats &stats) {
        const int kept_before = stats.kept_original;
        for (const auto &[first, count] : vt_llm_chunks(lines.size())) {
            int rc = translate_range(language, lines, first, count, on_chunk, stats);
            if (rc != VT_LLM_OK) return rc;
        }
        if (!lines.empty() && stats.kept_original - kept_before == static_cast<int>(lines.size())) return VT_LLM_ERROR;
        return VT_LLM_OK;
    }

    // One request: exactly lines.size() English strings out, or VT_LLM_BAD_ANSWER. Never throws:
    // llama.cpp's helpers can, and an exception must not take the app down.
    int request(const std::string &language, const std::vector<std::string> &lines, std::vector<std::string> &out,
                VtLlmStats &stats) {
        try {
            return request_unsafe(language, lines, out, stats);
        } catch (const std::exception &) {
            out.clear();
            return VT_LLM_ERROR;
        } catch (...) {
            out.clear();
            return VT_LLM_ERROR;
        }
    }

  private:
    int request_unsafe(const std::string &language, const std::vector<std::string> &lines,
                       std::vector<std::string> &out, VtLlmStats &stats) {
        out.clear();
        if (cancelled()) return VT_LLM_CANCELLED;
        const llama_vocab *vocab = llama_model_get_vocab(model_);
        stats.requests++;

        common_chat_templates_inputs inputs;
        common_chat_msg system;
        system.role = "system";
        system.content = vt_llm_system_prompt();
        common_chat_msg user;
        user.role = "user";
        user.content = vt_llm_user_prompt(language, lines);
        inputs.messages = {system, user};
        inputs.json_schema = vt_llm_schema(lines.size());
        inputs.use_jinja = true;
        inputs.add_generation_prompt = true;
        inputs.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;  // the server's default
        inputs.enable_thinking = false;                              // --reasoning off

        std::unique_ptr<common_chat_session> session;
        try {
            session = std::make_unique<common_chat_session>(tmpls_.get(), vocab, inputs);
        } catch (const std::exception &) {
            return VT_LLM_ERROR;
        }

        const std::vector<llama_token> prompt = common_tokenize(vocab, session->prompt().str(), true, true);
        const int max_tokens = 120 * static_cast<int>(lines.size()) + 200;  // as in the test
        if (prompt.empty()) return VT_LLM_ERROR;
        if (static_cast<int>(prompt.size()) + max_tokens > static_cast<int>(llama_n_ctx(ctx_))) {
            return VT_LLM_BAD_ANSWER;  // too long for one request: the caller splits it
        }

        common_params_sampling sp;
        sp.temp = 0.0f;  // as the test; greedy, so the sampler chain reduces to VtGreedyGrammar
        session->apply_sampling(sp);
        VtGreedyGrammar sampler;
        if (!sampler.init(vocab, sp)) return VT_LLM_ERROR;

        // Prompt.
        llama_memory_clear(llama_get_memory(ctx_), true);
        const auto t0 = std::chrono::steady_clock::now();
        // One ubatch per call (llama.cpp splits a batch into ubatches anyway), so a step stopped for
        // fewer cores is redone whole: llama.cpp drops an aborted ubatch from its memory.
        for (size_t i = 0; i < prompt.size(); i += VT_LLM_N_UBATCH) {
            const int n = static_cast<int>(std::min<size_t>(VT_LLM_N_UBATCH, prompt.size() - i));
            llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(prompt.data() + i), n);
            const int rc = decode(batch, stats);
            if (cancelled()) return VT_LLM_CANCELLED;
            if (rc != 0) return VT_LLM_ERROR;
        }
        const auto t1 = std::chrono::steady_clock::now();
        stats.prompt_tokens += static_cast<int64_t>(prompt.size());
        stats.prompt_ms += std::chrono::duration<double, std::milli>(t1 - t0).count();

        // Answer: greedy within the grammar, until end of turn or max_tokens.
        common_chat_input answer;
        std::string text;
        int generated = 0;
        bool ended = false;
        for (; generated < max_tokens; ++generated) {
            if (cancelled()) return VT_LLM_CANCELLED;
            const auto ts = std::chrono::steady_clock::now();
            const llama_token id = sampler.sample(ctx_);
            if (id == LLAMA_TOKEN_NULL) return VT_LLM_BAD_ANSWER;
            sampler.accept(id);
            stats.sample_ms += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - ts).count();
            if (llama_vocab_is_eog(vocab, id)) {
                ended = true;
                break;
            }
            const bool special = sp.preserved_tokens.count(id) > 0;
            const std::string piece = common_token_to_piece(vocab, id, special);
            answer.append(piece, id);
            text += piece;
            bool stop = false;
            for (const auto &s : session->additional_stops()) {
                if (!s.empty() && text.size() >= s.size() && text.compare(text.size() - s.size(), s.size(), s) == 0) {
                    stop = true;
                }
            }
            if (stop) {
                ended = true;
                ++generated;
                break;
            }
            llama_token next = id;
            const auto td = std::chrono::steady_clock::now();
            const int rc = decode(llama_batch_get_one(&next, 1), stats);
            stats.decode_ms += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - td).count();
            if (cancelled()) return VT_LLM_CANCELLED;
            if (rc != 0) return VT_LLM_ERROR;
        }
        const auto t2 = std::chrono::steady_clock::now();
        stats.output_tokens += generated;
        stats.output_ms += std::chrono::duration<double, std::milli>(t2 - t1).count();
        if (!ended) return VT_LLM_BAD_ANSWER;

        // The answer's content as the server reports it (the chat format's own parser), else the
        // raw text.
        std::vector<std::string> candidates;
        try {
            candidates.push_back(session->finish(answer).content);
        } catch (const std::exception &) {
        }
        candidates.push_back(text);
        for (const auto &content : candidates) {
            if (vt_llm_parse_answer(content, lines.size(), out)) return VT_LLM_OK;
        }
        out.clear();
        return VT_LLM_BAD_ANSWER;
    }

    VtLlm() = default;

    bool cancelled() const { return cancel_ != nullptr && cancel_->load(); }

    int fit_threads() const {
        const int allowed = vt_llm_cores != nullptr ? vt_llm_cores() : 0;
        return allowed > 0 ? std::min(wanted_threads_, allowed) : threads_;
    }

    // From the abort callback (on the thread that called llama_decode), at most twice a second.
    bool fewer_cores_now() {
        const auto now = std::chrono::steady_clock::now();
        if (now - last_core_check_ < std::chrono::milliseconds(500)) return false;
        last_core_check_ = now;
        if (fit_threads() < threads_) fewer_cores_ = true;
        return fewer_cores_;
    }

    // llama_decode with as many threads as cores allowed (up to the number asked for at load),
    // redone with fewer when the cores allowed drop while it runs.
    int decode(llama_batch batch, VtLlmStats &stats) {
        for (int tries = 0;; ++tries) {
            const int n = std::max(1, fit_threads());
            if (n != threads_) {
                threads_ = n;
                llama_set_n_threads(ctx_, n, n);
                stats.thread_changes++;
            }
            fewer_cores_ = false;
            last_core_check_ = std::chrono::steady_clock::now();
            const int rc = llama_decode(ctx_, batch);
            if (rc == 2 && fewer_cores_ && !cancelled() && tries < 100) continue;
            return rc;
        }
    }

    template <class OnChunk>
    int translate_range(const std::string &language, const std::vector<std::string> &lines, size_t first,
                        size_t count, OnChunk &on_chunk, VtLlmStats &stats) {
        if (count == 0) return VT_LLM_OK;
        std::vector<std::string> in(lines.begin() + first, lines.begin() + first + count);
        std::vector<std::string> out;
        const int rc = request(language, in, out, stats);
        if (rc == VT_LLM_OK) {
            for (size_t i = 0; i < count; ++i) {
                // An empty answer for a line that had words: keep the words rather than show nothing.
                if (out[i].find_first_not_of(" \t\r\n") == std::string::npos) {
                    out[i] = in[i];
                    stats.kept_original++;
                }
            }
            on_chunk(first, out);
            return VT_LLM_OK;
        }
        if (rc == VT_LLM_CANCELLED || cancelled()) return VT_LLM_CANCELLED;
        // VT_LLM_BAD_ANSWER or VT_LLM_ERROR: smaller requests may well work.
        if (count == 1) {
            stats.kept_original++;
            on_chunk(first, in);
            return VT_LLM_OK;
        }
        stats.splits++;
        const size_t half = count / 2;
        const int left = translate_range(language, lines, first, half, on_chunk, stats);
        if (left != VT_LLM_OK) return left;
        return translate_range(language, lines, first + half, count - half, on_chunk, stats);
    }

    llama_model *model_ = nullptr;
    llama_context *ctx_ = nullptr;
    ggml_threadpool *threadpool_ = nullptr;
    common_chat_templates_ptr tmpls_;
    const std::atomic<bool> *cancel_ = nullptr;
    int wanted_threads_ = 4;   // asked for at load: every core the app may use, up to a cap
    int threads_ = 4;          // used now
    bool fewer_cores_ = false;
    std::chrono::steady_clock::time_point last_core_check_{};
};
