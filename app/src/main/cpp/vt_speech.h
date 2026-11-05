// Keeps only the stretches of speech for Whisper, using whisper.cpp's Silero speech detector, and
// maps Whisper's times back onto the video. Shared by the JNI bridge and tools/vt-transcribe.
//
// whisper.cpp can do this itself (whisper_full_params.vad), but it then runs the detector with 4
// threads for every 32 ms step of audio, and waking threads that often costs far more than the
// step: on a 2-core CI runner it added about 4 minutes per minute of audio. Here the detector runs
// on one thread, through whisper.cpp's public detector API.
#pragma once

#include <algorithm>
#include <cstdint>
#include <vector>

#include "whisper.h"

struct VtSpeech {
    // A stretch of speech: where it sits in `samples`, and where it came from in the video.
    struct Block {
        int64_t speech_start;
        int64_t video_start;
        int64_t length;
    };
    std::vector<float> samples;  // the speech, with 100 ms of silence between stretches
    std::vector<Block> blocks;

    // A Whisper time (centiseconds into `samples`) as centiseconds into the video. A time in the
    // silence between two stretches maps to the end of the earlier one.
    int64_t to_video_cs(int64_t cs) const {
        if (blocks.empty()) return cs;
        const int64_t s = cs * WHISPER_SAMPLE_RATE / 100;
        auto it = std::upper_bound(blocks.begin(), blocks.end(), s,
                                   [](int64_t v, const Block &b) { return v < b.speech_start; });
        const Block &b = it == blocks.begin() ? blocks.front() : *(it - 1);
        const int64_t offset = std::min(std::max<int64_t>(s - b.speech_start, 0), b.length);
        return (b.video_start + offset) * 100 / WHISPER_SAMPLE_RATE;
    }
};

// Keep pauses shorter than 2 s (they are part of talking), and pad each stretch by 400 ms so the
// first and last words are not clipped: the same choice faster-whisper makes.
inline whisper_vad_params vt_vad_params() {
    whisper_vad_params p = whisper_vad_default_params();
    p.min_silence_duration_ms = 2000;
    p.speech_pad_ms = 400;
    return p;
}

// Fills `out` with the speech in `samples`. Returns false if the detector could not run; the
// caller then gives Whisper all the audio. An empty `out.samples` means no speech was found.
inline bool vt_keep_speech(const char *vad_model_path, const float *samples, int n_samples, VtSpeech &out) {
    out = VtSpeech();
    whisper_vad_context_params cp = whisper_vad_default_context_params();
    cp.n_threads = 1;
    cp.use_gpu = false;
    whisper_vad_context *vctx = whisper_vad_init_from_file_with_params(vad_model_path, cp);
    if (vctx == nullptr) return false;
    whisper_vad_segments *segs = whisper_vad_segments_from_samples(vctx, vt_vad_params(), samples, n_samples);
    if (segs == nullptr) {
        whisper_vad_free(vctx);
        return false;
    }
    const int64_t gap = WHISPER_SAMPLE_RATE / 10;
    int64_t video_end = 0;
    for (int i = 0; i < whisper_vad_segments_n_segments(segs); ++i) {
        // Segment times are in centiseconds.
        int64_t a = static_cast<int64_t>(whisper_vad_segments_get_segment_t0(segs, i) * (WHISPER_SAMPLE_RATE / 100.0));
        int64_t b = static_cast<int64_t>(whisper_vad_segments_get_segment_t1(segs, i) * (WHISPER_SAMPLE_RATE / 100.0));
        a = std::clamp<int64_t>(std::max(a, video_end), 0, n_samples);
        b = std::clamp<int64_t>(b, a, n_samples);
        if (b <= a) continue;
        if (!out.samples.empty()) out.samples.insert(out.samples.end(), static_cast<size_t>(gap), 0.0f);
        out.blocks.push_back({static_cast<int64_t>(out.samples.size()), a, b - a});
        out.samples.insert(out.samples.end(), samples + a, samples + b);
        video_end = b;
    }
    whisper_vad_free_segments(segs);
    whisper_vad_free(vctx);
    return true;
}
