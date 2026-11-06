// Whisper's thread count, following the CPU cores Android lets the app use.
//
// Android narrows the cores an app may run on once it is no longer on the screen (a foreground
// service keeps the app alive, not on every core). ggml's worker threads wait for each other by
// spinning, so with more threads than allowed cores each step waits for threads that are not
// running, and Whisper slows to almost nothing until the app is back on screen. whisper.cpp takes
// its thread count once per whisper_full call, so VtWhisperRun stops the call and starts a new one
// from the end of the last line when the count must change:
//   - fewer cores: at once, from the abort callback; the 30-second window in progress is redone;
//   - more cores: when Whisper has just finished a window (a line boundary), so nothing is lost.
// The app (whisper_jni.cpp) and tools/vt-transcribe both run Whisper through this.
#pragma once

#include <sched.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>

#include "whisper.h"

// The cores the calling thread may run on now; 0 if unknown. Android moves all of an app's threads
// together, so this is the whole app's allowance.
inline int vt_allowed_cores() {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof(set), &set) != 0) return 0;
    return CPU_COUNT(&set);
}

class VtWhisperRun {
public:
    // Set by the caller before run().
    int wanted = 4;                                  // threads when every core may be used
    const std::atomic<bool> *cancelled = nullptr;    // the user's Cancel: never restart after it
    int (*cores)() = vt_allowed_cores;               // tests replace this to simulate Android

    // For the caller's callbacks. Segment times from whisper.cpp are relative to the current call:
    // add base_cs() to them, and report each one's end with segment_end().
    int64_t base_cs() const { return base_cs_; }
    int threads() const { return threads_; }
    int restarts() const { return restarts_; }

    // whisper.cpp's progress (0-100, of the current call) as progress through the whole audio.
    int overall_progress(int progress) const {
        if (total_ <= 0) return progress;
        const double done = static_cast<double>(start_sample()) +
                            (total_ - start_sample()) * (progress / 100.0);
        return static_cast<int>(std::clamp(100.0 * done / total_, 0.0, 100.0));
    }

    // Call from the new-segment callback for every segment (also ones the caller drops), with its
    // end time as whisper.cpp gives it. Whisper has just finished a window: if more cores are free
    // now, this is the moment to use them, since nothing is lost.
    void segment_end(int64_t t1_cs) {
        end_cs_ = std::max(end_cs_, base_cs_ + t1_cs);
        any_segment_ = true;
        const int fit = fit_threads();
        if (fit > threads_ && !is_cancelled()) {
            next_ = fit;
            stop_ = true;
        }
    }

    // Call from the abort callback. True when the current call must stop: cancelled, or fewer
    // cores than threads (checked at most twice a second).
    bool should_stop() {
        if (is_cancelled() || stop_) return true;
        const auto now = std::chrono::steady_clock::now();
        if (now - last_check_ < std::chrono::milliseconds(500)) return false;
        last_check_ = now;
        const int fit = fit_threads();
        if (fit < threads_) {
            next_ = fit;
            stop_ = true;
        }
        return stop_;
    }

    // whisper_full over pcm[0, n), restarted with another thread count whenever needed. Returns
    // whisper_full's result for the last call (0 when everything was heard).
    int run(whisper_context *ctx, whisper_full_params params, const float *pcm, int n) {
        total_ = n;
        base_cs_ = 0;
        end_cs_ = 0;
        restarts_ = 0;
        any_segment_ = false;
        threads_ = fit_threads();
        for (;;) {
            stop_ = false;
            last_check_ = std::chrono::steady_clock::now();
            params.n_threads = threads_;
            const int64_t start = start_sample();
            if (start + WHISPER_SAMPLE_RATE / 10 >= n) return 0;  // under 0.1 s left: done
            const int rc = whisper_full(ctx, params, pcm + start, static_cast<int>(n - start));
            if (!stop_ || is_cancelled()) return rc;
            // Stopped only to change the thread count. The language was detected in the first
            // call, if it got as far as a line; do not detect it again from the middle.
            if (any_segment_) {
                const int lang = whisper_full_lang_id(ctx);
                if (lang >= 0) params.language = whisper_lang_str(lang);
            }
            base_cs_ = std::max(base_cs_, end_cs_);
            threads_ = next_ > 0 ? next_ : threads_;
            restarts_++;
        }
    }

private:
    int64_t start_sample() const { return base_cs_ * (WHISPER_SAMPLE_RATE / 100); }
    bool is_cancelled() const { return cancelled != nullptr && cancelled->load(); }
    int fit_threads() const {
        const int allowed = cores != nullptr ? cores() : 0;
        const int want = std::max(1, wanted);
        return allowed > 0 ? std::min(want, allowed) : (threads_ > 0 ? threads_ : want);
    }

    int64_t total_ = 0;
    int64_t base_cs_ = 0;    // where the current call starts, in the audio Whisper hears (10 ms)
    int64_t end_cs_ = 0;     // end of the last segment so far, same units
    int threads_ = 0;
    int next_ = 0;
    int restarts_ = 0;
    bool stop_ = false;
    bool any_segment_ = false;
    std::chrono::steady_clock::time_point last_check_{};
};
