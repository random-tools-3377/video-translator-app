package app.videotranslator.llm

/** Thin JNI layer over the language model (llama.cpp). See llm/src/main/cpp/llm_jni.cpp. */
internal object LlmNative {
    init {
        System.loadLibrary("vtllama")
    }

    /** Called from the native side, on the thread running [translate]. Text is UTF-8. */
    interface Listener {
        fun onChunk(first: Int, english: Array<ByteArray>)
        fun onStats(requests: Int, splits: Int, keptOriginal: Int, promptTokens: Long, promptMs: Long, outputTokens: Long, outputMs: Long)
    }

    const val RESULT_DONE = 0
    const val RESULT_CANCELLED = 1

    @JvmStatic external fun systemInfo(): String

    @JvmStatic external fun newJob(): Long
    @JvmStatic external fun cancelJob(job: Long)
    @JvmStatic external fun freeJob(job: Long)

    /** Returns a model handle, or 0 if the file could not be loaded (or [job] was cancelled). */
    @JvmStatic external fun loadModel(path: String, threads: Int, job: Long): Long
    @JvmStatic external fun freeModel(handle: Long)

    @JvmStatic external fun translate(
        handle: Long,
        job: Long,
        language: ByteArray,
        lines: Array<ByteArray>,
        listener: Listener,
    ): Int
}
