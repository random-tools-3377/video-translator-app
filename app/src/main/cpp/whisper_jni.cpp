// JNI bridge between the app and whisper.cpp.
//
// Nothing here writes to storage or the network. Logging goes to logcat on the phone and never
// includes transcript text.

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <new>
#include <string>
#include <vector>

#include "whisper.h"
#include "vt_cores.h"
#include "vt_params.h"
#include "vt_speech.h"

#define TAG "VTWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

void log_callback(enum ggml_log_level level, const char *text, void * /*user_data*/) {
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default: break;
    }
    if (prio == ANDROID_LOG_DEBUG) return;
    __android_log_write(prio, TAG, text);
}

// ---- model loading straight out of the APK (the asset is stored uncompressed) ----

size_t asset_read(void *ctx, void *output, size_t read_size) {
    auto *asset = static_cast<AAsset *>(ctx);
    auto *out = static_cast<uint8_t *>(output);
    size_t done = 0;
    while (done < read_size) {
        size_t chunk = read_size - done;
        if (chunk > (1u << 30)) chunk = (1u << 30);
        int n = AAsset_read(asset, out + done, chunk);
        if (n <= 0) break;
        done += static_cast<size_t>(n);
    }
    return done;
}

bool asset_eof(void *ctx) {
    return AAsset_getRemainingLength64(static_cast<AAsset *>(ctx)) <= 0;
}

void asset_close(void *ctx) {
    AAsset_close(static_cast<AAsset *>(ctx));
}

// ---- 16 kHz mono audio, kept in native memory so a long video does not fill the Java heap ----

struct Audio {
    std::vector<float> samples;
};

// ---- one transcription run ----

struct Job {
    std::atomic<bool> cancelled{false};
};

struct Callbacks {
    JNIEnv *env = nullptr;
    jobject listener = nullptr;
    jmethodID on_language = nullptr;
    jmethodID on_progress = nullptr;
    jmethodID on_segment = nullptr;
    Job *job = nullptr;
    bool language_sent = false;
    bool java_failed = false;
    const VtSpeech *speech = nullptr;  // set when Whisper hears only the speech: maps times back
    VtWhisperRun *run = nullptr;       // the thread count, following the cores Android allows
    std::string last_key;   // the previous line, to drop a line Whisper repeats (vt_is_repeat)
    int dropped_repeats = 0;
};

// A Java exception inside a callback stops the run; it is rethrown when whisper_full returns.
bool check_java(Callbacks *cb) {
    if (cb->env->ExceptionCheck()) {
        cb->java_failed = true;
        cb->job->cancelled.store(true);
        return false;
    }
    return true;
}

jstring utf_string(JNIEnv *env, const char *s) {
    // Language names are plain ASCII, so NewStringUTF is safe here.
    return env->NewStringUTF(s != nullptr ? s : "");
}

void send_language(Callbacks *cb, struct whisper_state *state) {
    if (cb->language_sent || cb->java_failed) return;
    int id = whisper_full_lang_id_from_state(state);
    if (id < 0) return;
    cb->language_sent = true;
    JNIEnv *env = cb->env;
    jstring code = utf_string(env, whisper_lang_str(id));
    jstring name = utf_string(env, whisper_lang_str_full(id));
    if (code == nullptr || name == nullptr) { check_java(cb); return; }
    env->CallVoidMethod(cb->listener, cb->on_language, code, name);
    env->DeleteLocalRef(code);
    env->DeleteLocalRef(name);
    check_java(cb);
}

void on_progress(struct whisper_context * /*ctx*/, struct whisper_state *state, int progress, void *user_data) {
    auto *cb = static_cast<Callbacks *>(user_data);
    if (cb->java_failed) return;
    send_language(cb, state);
    if (cb->java_failed) return;
    cb->env->CallVoidMethod(cb->listener, cb->on_progress, static_cast<jint>(cb->run->overall_progress(progress)));
    check_java(cb);
}

void on_new_segment(struct whisper_context * /*ctx*/, struct whisper_state *state, int n_new, void *user_data) {
    auto *cb = static_cast<Callbacks *>(user_data);
    if (cb->java_failed) return;
    send_language(cb, state);
    JNIEnv *env = cb->env;
    const int n = whisper_full_n_segments_from_state(state);
    for (int i = n - n_new; i < n && !cb->java_failed; ++i) {
        const char *text = whisper_full_get_segment_text_from_state(state, i);
        if (text == nullptr) text = "";
        // Segment times are in units of 10 ms, in the audio Whisper heard, from where this
        // whisper_full call started (later than 0 after a restart for another thread count).
        int64_t cs0 = whisper_full_get_segment_t0_from_state(state, i);
        int64_t cs1 = whisper_full_get_segment_t1_from_state(state, i);
        cb->run->segment_end(cs1);
        cs0 += cb->run->base_cs();
        cs1 += cb->run->base_cs();
        std::string key = vt_line_key(text);
        if (vt_is_repeat(cb->last_key, key)) {
            cb->dropped_repeats++;
            continue;
        }
        if (!key.empty()) cb->last_key = std::move(key);
        if (cb->speech != nullptr) {
            cs0 = cb->speech->to_video_cs(cs0);
            cs1 = cb->speech->to_video_cs(cs1);
        }
        const jlong t0 = static_cast<jlong>(cs0) * 10;
        const jlong t1 = static_cast<jlong>(cs1) * 10;
        // Pass raw bytes: Whisper can emit text that is not valid "modified UTF-8", which
        // NewStringUTF does not accept. Kotlin decodes it, replacing anything malformed.
        const jsize len = static_cast<jsize>(strlen(text));
        jbyteArray bytes = env->NewByteArray(len);
        if (bytes == nullptr) { check_java(cb); return; }
        env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(text));
        env->CallVoidMethod(cb->listener, cb->on_segment, t0, t1, bytes);
        env->DeleteLocalRef(bytes);
        check_java(cb);
    }
}

// Also stops the call when the thread count must change (VtWhisperRun restarts it).
bool on_abort(void *user_data) {
    return static_cast<Callbacks *>(user_data)->run->should_stop();
}

bool on_encoder_begin(struct whisper_context * /*ctx*/, struct whisper_state * /*state*/, void *user_data) {
    return !static_cast<Callbacks *>(user_data)->job->cancelled.load();
}

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM * /*vm*/, void * /*reserved*/) {
    whisper_log_set(log_callback, nullptr);
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_app_videotranslator_whisper_WhisperNative_systemInfo(JNIEnv *env, jclass) {
    std::string info = std::string("whisper.cpp ") + whisper_version() + " | " + whisper_print_system_info();
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jlong JNICALL
Java_app_videotranslator_whisper_WhisperNative_loadModel(
        JNIEnv *env, jclass, jobject asset_manager, jstring path) {
    AAssetManager *mgr = AAssetManager_fromJava(env, asset_manager);
    if (mgr == nullptr) return 0;
    const char *path_chars = env->GetStringUTFChars(path, nullptr);
    if (path_chars == nullptr) return 0;
    AAsset *asset = AAssetManager_open(mgr, path_chars, AASSET_MODE_STREAMING);
    if (asset == nullptr) {
        LOGE("model asset not found: %s", path_chars);
        env->ReleaseStringUTFChars(path, path_chars);
        return 0;
    }
    LOGI("loading model %s (%lld bytes)", path_chars, static_cast<long long>(AAsset_getLength64(asset)));
    env->ReleaseStringUTFChars(path, path_chars);

    whisper_model_loader loader = {};
    loader.context = asset;
    loader.read = asset_read;
    loader.eof = asset_eof;
    loader.close = asset_close;  // whisper.cpp calls this itself, on success and on failure

    whisper_context *ctx = whisper_init_with_params(&loader, vt_context_params());
    if (ctx == nullptr) LOGE("whisper could not load the model");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jboolean JNICALL
Java_app_videotranslator_whisper_WhisperNative_isMultilingual(JNIEnv *, jclass, jlong ctx) {
    return whisper_is_multilingual(reinterpret_cast<whisper_context *>(ctx)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_videotranslator_whisper_WhisperNative_freeModel(JNIEnv *, jclass, jlong ctx) {
    if (ctx != 0) whisper_free(reinterpret_cast<whisper_context *>(ctx));
}

JNIEXPORT jlong JNICALL
Java_app_videotranslator_whisper_WhisperNative_newAudio(JNIEnv *, jclass, jlong capacity) {
    auto *audio = new (std::nothrow) Audio();
    if (audio == nullptr) return 0;
    if (capacity > 0) {
        try {
            audio->samples.reserve(static_cast<size_t>(capacity));
        } catch (...) {
            // Only a hint; the vector grows as needed.
        }
    }
    return reinterpret_cast<jlong>(audio);
}

JNIEXPORT jboolean JNICALL
Java_app_videotranslator_whisper_WhisperNative_appendAudio(
        JNIEnv *env, jclass, jlong handle, jfloatArray samples, jint count) {
    auto *audio = reinterpret_cast<Audio *>(handle);
    if (audio == nullptr || count <= 0) return JNI_TRUE;
    const size_t old_size = audio->samples.size();
    try {
        audio->samples.resize(old_size + static_cast<size_t>(count));
    } catch (...) {
        return JNI_FALSE;
    }
    env->GetFloatArrayRegion(samples, 0, count, audio->samples.data() + old_size);
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_app_videotranslator_whisper_WhisperNative_audioSize(JNIEnv *, jclass, jlong handle) {
    auto *audio = reinterpret_cast<Audio *>(handle);
    return audio == nullptr ? 0 : static_cast<jlong>(audio->samples.size());
}

JNIEXPORT void JNICALL
Java_app_videotranslator_whisper_WhisperNative_freeAudio(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Audio *>(handle);
}

JNIEXPORT jlong JNICALL
Java_app_videotranslator_whisper_WhisperNative_newJob(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new (std::nothrow) Job());
}

JNIEXPORT void JNICALL
Java_app_videotranslator_whisper_WhisperNative_cancelJob(JNIEnv *, jclass, jlong handle) {
    auto *job = reinterpret_cast<Job *>(handle);
    if (job != nullptr) job->cancelled.store(true);
}

JNIEXPORT void JNICALL
Java_app_videotranslator_whisper_WhisperNative_freeJob(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Job *>(handle);
}

// Returns 0 when finished, 1 when cancelled, a negative whisper.cpp error code otherwise.
JNIEXPORT jint JNICALL
Java_app_videotranslator_whisper_WhisperNative_transcribe(
        JNIEnv *env, jclass, jlong ctx_handle, jlong audio_handle, jlong job_handle,
        jint threads, jboolean translate, jstring vad_model_path, jlong start_ms, jstring language,
        jobject listener) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_handle);
    auto *audio = reinterpret_cast<Audio *>(audio_handle);
    auto *job = reinterpret_cast<Job *>(job_handle);
    if (ctx == nullptr || audio == nullptr || job == nullptr || listener == nullptr) return -100;

    jclass cls = env->GetObjectClass(listener);
    Callbacks cb;
    cb.env = env;
    cb.listener = listener;
    cb.job = job;
    cb.on_language = env->GetMethodID(cls, "onLanguage", "(Ljava/lang/String;Ljava/lang/String;)V");
    cb.on_progress = env->GetMethodID(cls, "onProgress", "(I)V");
    cb.on_segment = env->GetMethodID(cls, "onSegment", "(JJ[B)V");
    env->DeleteLocalRef(cls);
    if (cb.on_language == nullptr || cb.on_progress == nullptr || cb.on_segment == nullptr) return -101;

    // The speech detector's model file (copied out of the APK by the Kotlin side), or null.
    std::string vad_path;
    if (vad_model_path != nullptr) {
        const char *chars = env->GetStringUTFChars(vad_model_path, nullptr);
        if (chars == nullptr) return -103;
        vad_path = chars;
        env->ReleaseStringUTFChars(vad_model_path, chars);
    }
    // Carrying on a stopped run: the language found then (not detected again from the middle).
    std::string lang;
    if (language != nullptr) {
        const char *chars = env->GetStringUTFChars(language, nullptr);
        if (chars == nullptr) return -103;
        lang = chars;
        env->ReleaseStringUTFChars(language, chars);
    }
    whisper_full_params params = vt_full_params(threads, translate == JNI_TRUE);
    if (!lang.empty() && whisper_lang_id(lang.c_str()) >= 0) params.language = lang.c_str();
    params.new_segment_callback = on_new_segment;
    params.new_segment_callback_user_data = &cb;
    params.progress_callback = on_progress;
    params.progress_callback_user_data = &cb;
    params.encoder_begin_callback = on_encoder_begin;
    params.encoder_begin_callback_user_data = &cb;
    params.abort_callback = on_abort;
    params.abort_callback_user_data = &cb;
    VtWhisperRun run;
    run.wanted = params.n_threads;
    run.cancelled = &job->cancelled;
    cb.run = &run;

    if (job->cancelled.load()) return 1;

    // Only the speech goes to Whisper, when the detector is available.
    const float *pcm = audio->samples.data();
    int n = static_cast<int>(audio->samples.size());
    VtSpeech speech;
    if (!vad_path.empty()) {
        const auto started = std::chrono::steady_clock::now();
        if (vt_keep_speech(vad_path.c_str(), pcm, n, speech)) {
            const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - started).count();
            LOGI("speech detector: %d of %d samples are speech, in %zu stretches (%lld ms)",
                 static_cast<int>(speech.samples.size()), n, speech.blocks.size(), static_cast<long long>(ms));
            if (speech.samples.empty()) return 0;  // nothing was said
            pcm = speech.samples.data();
            n = static_cast<int>(speech.samples.size());
            cb.speech = &speech;
        } else {
            LOGW("speech detector failed to run; Whisper hears all the audio");
        }
    }
    if (job->cancelled.load()) return 1;
    LOGI("transcribe: %d samples, up to %d threads (%d cores allowed now), translate=%d, beam=%d",
         n, params.n_threads, vt_allowed_cores(), params.translate ? 1 : 0,
         params.strategy == WHISPER_SAMPLING_BEAM_SEARCH ? params.beam_search.beam_size : 0);
    // start_ms is in the audio's timeline; Whisper hears only the speech, so map it there.
    const int64_t start_cs = start_ms > 0 ? (cb.speech != nullptr ? cb.speech->to_speech_cs(start_ms / 10) : start_ms / 10) : 0;
    if (start_cs > 0) LOGI("carrying on from %lld ms of the audio", static_cast<long long>(start_ms));
    const int rc = run.run(ctx, params, pcm, n, start_cs);
    if (run.restarts() > 0) LOGI("Whisper restarted %d times to follow the cores allowed; ended with %d threads",
                                 run.restarts(), run.threads());
    if (cb.java_failed) return -102;  // the pending Java exception is thrown on return
    if (job->cancelled.load()) return 1;
    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        return rc < 0 ? rc : -rc;
    }
    if (cb.dropped_repeats > 0) LOGI("dropped %d repeated lines", cb.dropped_repeats);
    // A very short clip can finish without a progress callback; report the language anyway.
    // Not when nothing was said: with the speech detector on and no speech found, Whisper never
    // ran and has no language to report.
    if (!cb.language_sent && whisper_full_n_segments(ctx) > 0) {
        int id = whisper_full_lang_id(ctx);
        if (id >= 0) {
            jstring code = utf_string(env, whisper_lang_str(id));
            jstring name = utf_string(env, whisper_lang_str_full(id));
            if (code != nullptr && name != nullptr) {
                env->CallVoidMethod(listener, cb.on_language, code, name);
            }
            if (env->ExceptionCheck()) return -102;
        }
    }
    return 0;
}

}  // extern "C"
