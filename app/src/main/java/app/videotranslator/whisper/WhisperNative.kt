package app.videotranslator.whisper

import android.content.res.AssetManager

/** Thin JNI layer over whisper.cpp. See app/src/main/cpp/whisper_jni.cpp. */
internal object WhisperNative {
    init {
        System.loadLibrary("vtwhisper")
    }

    /** Called from the native side, on the thread running [transcribe]. */
    interface Listener {
        fun onLanguage(code: String, name: String)
        fun onProgress(percent: Int)
        fun onSegment(startMs: Long, endMs: Long, utf8: ByteArray)
    }

    const val RESULT_DONE = 0
    const val RESULT_CANCELLED = 1

    @JvmStatic external fun systemInfo(): String

    /** Returns a whisper_context pointer, or 0 if the model could not be loaded. */
    @JvmStatic external fun loadModel(assets: AssetManager, path: String): Long
    @JvmStatic external fun isMultilingual(ctx: Long): Boolean
    @JvmStatic external fun freeModel(ctx: Long)

    @JvmStatic external fun newAudio(capacitySamples: Long): Long
    @JvmStatic external fun appendAudio(audio: Long, samples: FloatArray, count: Int): Boolean
    @JvmStatic external fun audioSize(audio: Long): Long
    @JvmStatic external fun freeAudio(audio: Long)

    @JvmStatic external fun newJob(): Long
    @JvmStatic external fun cancelJob(job: Long)
    @JvmStatic external fun freeJob(job: Long)

    @JvmStatic external fun transcribe(
        ctx: Long,
        audio: Long,
        job: Long,
        threads: Int,
        translate: Boolean,
        vadModelPath: String?,
        listener: Listener,
    ): Int
}
