// JNI bridge between the app and the language model (llama.cpp), in its own library,
// libvtllama.so, because llama.cpp's ggml is a different version from whisper.cpp's.
// Kotlin side: app/src/main/java/app/videotranslator/llm/LlmNative.kt.
//
// Nothing here writes to storage or the network. Logging goes to logcat on the phone and never
// includes transcript text.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <new>
#include <string>
#include <vector>

#include "log.h"
#include "vt_translate.h"

#define TAG "VTLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Job {
    std::atomic<bool> cancelled{false};
};

void log_callback(enum ggml_log_level level, const char *text, void * /*user_data*/) {
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default: return;  // debug output can include prompt text
    }
    __android_log_write(prio, TAG, text);
}

std::once_flag log_once;

void setup_logging() {
    std::call_once(log_once, [] {
        llama_log_set(log_callback, nullptr);
        common_log_set_verbosity_thold(-1);
        common_log_pause(common_log_main());  // llama.cpp's common library logs nowhere
    });
}

std::string to_string(JNIEnv *env, jbyteArray bytes) {
    if (bytes == nullptr) return {};
    const jsize n = env->GetArrayLength(bytes);
    std::string s(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    if (out != nullptr && !s.empty()) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    }
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_app_videotranslator_llm_LlmNative_systemInfo(JNIEnv *env, jclass) {
    setup_logging();
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_app_videotranslator_llm_LlmNative_newJob(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new (std::nothrow) Job());
}

JNIEXPORT void JNICALL
Java_app_videotranslator_llm_LlmNative_cancelJob(JNIEnv *, jclass, jlong job) {
    if (job != 0) reinterpret_cast<Job *>(job)->cancelled.store(true);
}

JNIEXPORT void JNICALL
Java_app_videotranslator_llm_LlmNative_freeJob(JNIEnv *, jclass, jlong job) {
    delete reinterpret_cast<Job *>(job);
}

// Returns a model handle, or 0 if the file could not be loaded (or the job was cancelled).
JNIEXPORT jlong JNICALL
Java_app_videotranslator_llm_LlmNative_loadModel(JNIEnv *env, jclass, jstring path, jint threads, jlong job) {
    setup_logging();
    const char *p = env->GetStringUTFChars(path, nullptr);
    if (p == nullptr) return 0;
    const std::string file(p);
    env->ReleaseStringUTFChars(path, p);
    auto *j = reinterpret_cast<Job *>(job);
    std::string error;
    std::unique_ptr<VtLlm> llm;
    try {
        llm = VtLlm::load(file, threads, j != nullptr ? &j->cancelled : nullptr, &error);
    } catch (const std::exception &e) {
        error = e.what();
    }
    if (!llm) {
        LOGW("language model not loaded: %s", error.c_str());
        return 0;
    }
    llm->set_cancel(nullptr);  // the job is freed after loading; translate() sets its own
    LOGI("language model loaded, %d threads", static_cast<int>(threads));
    return reinterpret_cast<jlong>(llm.release());
}

JNIEXPORT void JNICALL
Java_app_videotranslator_llm_LlmNative_freeModel(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<VtLlm *>(handle);
}

// Translates [lines] (UTF-8) to English. Calls listener.onChunk(firstIndex, englishLines) after
// each request and listener.onStats(...) at the end. Returns 0 done, 1 cancelled, -1 error.
JNIEXPORT jint JNICALL
Java_app_videotranslator_llm_LlmNative_translate(JNIEnv *env, jclass, jlong handle, jlong job,
                                                         jbyteArray language, jobjectArray lines, jobject listener) {
    auto *llm = reinterpret_cast<VtLlm *>(handle);
    auto *j = reinterpret_cast<Job *>(job);
    if (llm == nullptr || j == nullptr) return -1;
    llm->set_cancel(&j->cancelled);

    std::vector<std::string> in;
    const jsize n = env->GetArrayLength(lines);
    in.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto item = static_cast<jbyteArray>(env->GetObjectArrayElement(lines, i));
        in.push_back(to_string(env, item));
        env->DeleteLocalRef(item);
    }

    jclass cls = env->GetObjectClass(listener);
    jmethodID on_chunk = env->GetMethodID(cls, "onChunk", "(I[[B)V");
    jmethodID on_stats = env->GetMethodID(cls, "onStats", "(IIIJJJJ)V");
    jclass bytes_cls = env->FindClass("[B");
    if (on_chunk == nullptr || on_stats == nullptr || bytes_cls == nullptr) return -1;

    bool java_failed = false;
    VtLlmStats stats;
    const int rc = llm->translate(vt_llm_language_name(to_string(env, language)), in,
        [&](size_t first, const std::vector<std::string> &out) {
            if (java_failed) return;
            jobjectArray arr = env->NewObjectArray(static_cast<jsize>(out.size()), bytes_cls, nullptr);
            if (arr == nullptr) { java_failed = true; j->cancelled.store(true); return; }
            for (size_t i = 0; i < out.size(); ++i) {
                jbyteArray b = to_bytes(env, out[i]);
                if (b == nullptr) { java_failed = true; j->cancelled.store(true); return; }
                env->SetObjectArrayElement(arr, static_cast<jsize>(i), b);
                env->DeleteLocalRef(b);
            }
            env->CallVoidMethod(listener, on_chunk, static_cast<jint>(first), arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) {
                // Stop; the exception is rethrown in Kotlin when this call returns.
                java_failed = true;
                j->cancelled.store(true);
            }
        }, stats);
    llm->set_cancel(nullptr);

    LOGI("translated %d lines: %d requests, %d split, %d kept, prompt %lld tok in %.0f ms, output %lld tok in %.0f ms, "
         "thread count changed %d times to follow the cores allowed",
         static_cast<int>(n), stats.requests, stats.splits, stats.kept_original,
         static_cast<long long>(stats.prompt_tokens), stats.prompt_ms,
         static_cast<long long>(stats.output_tokens), stats.output_ms, stats.thread_changes);
    if (java_failed) return 1;
    env->CallVoidMethod(listener, on_stats, stats.requests, stats.splits, stats.kept_original,
                        static_cast<jlong>(stats.prompt_tokens), static_cast<jlong>(stats.prompt_ms),
                        static_cast<jlong>(stats.output_tokens), static_cast<jlong>(stats.output_ms));
    if (env->ExceptionCheck()) return 1;
    switch (rc) {
        case VT_LLM_OK: return 0;
        case VT_LLM_CANCELLED: return 1;
        default: return -1;
    }
}

}  // extern "C"
