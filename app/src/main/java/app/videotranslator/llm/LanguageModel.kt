package app.videotranslator.llm

import app.videotranslator.whisper.Cancellation
import app.videotranslator.whisper.CancelledException
import java.io.Closeable
import java.io.File

class LanguageModelException(message: String) : Exception(message)

data class LanguageModelStats(
    val requests: Int = 0,
    val splits: Int = 0,
    /** Lines left in the spoken language because the model gave no usable English for them. */
    val keptOriginal: Int = 0,
    val promptTokens: Long = 0,
    val promptMs: Long = 0,
    val outputTokens: Long = 0,
    val outputMs: Long = 0,
)

/**
 * A loaded language model (Qwen3.5, GGUF) that turns Whisper's transcript, in the spoken language,
 * into English: one English line for each line, so every line keeps its time. The prompt and
 * settings are the ones tested on the public clips (llm/src/main/cpp/vt_translate.h).
 * Not thread-safe: one translation at a time.
 */
class LanguageModel private constructor(private var handle: Long) : Closeable {

    /**
     * Translates [lines], spoken in [language] (Whisper's name for it, e.g. "japanese"), to
     * English. [onChunk] gets (index of the first line, its English lines) as each group of lines
     * is done, in order. Blocking: call it from a background thread.
     */
    fun translate(
        language: String,
        lines: List<String>,
        cancellation: Cancellation,
        onChunk: (first: Int, english: List<String>) -> Unit,
    ): LanguageModelStats {
        check(handle != 0L) { "language model is closed" }
        if (cancellation.isCancelled) throw CancelledException()
        var stats = LanguageModelStats()
        val listener = object : LlmNative.Listener {
            override fun onChunk(first: Int, english: Array<ByteArray>) {
                onChunk(first, english.map { String(it, Charsets.UTF_8).trim() })
            }

            override fun onStats(
                requests: Int, splits: Int, keptOriginal: Int,
                promptTokens: Long, promptMs: Long, outputTokens: Long, outputMs: Long,
            ) {
                stats = LanguageModelStats(requests, splits, keptOriginal, promptTokens, promptMs, outputTokens, outputMs)
            }
        }
        val job = LlmNative.newJob()
        if (job == 0L) throw OutOfMemoryError("no memory for job")
        val rc = try {
            cancellation.whileRunning({ LlmNative.cancelJob(job) }) {
                LlmNative.translate(
                    handle, job, language.toByteArray(Charsets.UTF_8),
                    lines.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray(), listener,
                )
            }
        } finally {
            LlmNative.freeJob(job)
        }
        when {
            rc == LlmNative.RESULT_CANCELLED || cancellation.isCancelled -> throw CancelledException()
            rc != LlmNative.RESULT_DONE -> throw LanguageModelException("The language model stopped with an error.")
        }
        return stats
    }

    override fun close() {
        if (handle != 0L) {
            LlmNative.freeModel(handle)
            handle = 0L
        }
    }

    companion object {
        val systemInfo: String get() = LlmNative.systemInfo()

        /** Loads [file] (memory-mapped, so it is not all read at once). Blocking. */
        fun load(file: File, threads: Int, cancellation: Cancellation): LanguageModel {
            if (cancellation.isCancelled) throw CancelledException()
            val job = LlmNative.newJob()
            if (job == 0L) throw OutOfMemoryError("no memory for job")
            val handle = try {
                cancellation.whileRunning({ LlmNative.cancelJob(job) }) {
                    LlmNative.loadModel(file.path, threads, job)
                }
            } finally {
                LlmNative.freeJob(job)
            }
            if (cancellation.isCancelled) {
                if (handle != 0L) LlmNative.freeModel(handle)
                throw CancelledException()
            }
            if (handle == 0L) throw LanguageModelException("Could not load the language model (${file.name}).")
            return LanguageModel(handle)
        }
    }
}
