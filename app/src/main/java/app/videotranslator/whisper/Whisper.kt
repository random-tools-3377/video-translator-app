package app.videotranslator.whisper

import android.content.res.AssetManager
import app.videotranslator.BuildConfig
import java.io.Closeable
import java.io.File

/** The model bundled in the release APK (whisperModel in app/version.properties), read in place. */
const val BUNDLED_MODEL_ASSET = BuildConfig.WHISPER_MODEL_ASSET

/** "large-v3" or "medium", shown on screen so it is clear which build this is. */
const val BUNDLED_MODEL_NAME = BuildConfig.WHISPER_MODEL_NAME

/**
 * About 6 threads suits the S24 Ultra's 1 + 5 big cores. Tune from timings on the phone. Never more than
 * the cores there are: ggml's threads wait for each other by spinning, so extra threads make it
 * many times slower (a small language model went from 25 to 0.5 tokens/s with 4 threads on 2 cores).
 */
val DEFAULT_THREADS: Int = minOf(6, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)

class ModelLoadException(message: String) : Exception(message)

/** A loaded Whisper model. Not thread-safe: run one transcription at a time. */
class WhisperModel private constructor(private var ctx: Long) : Closeable {

    internal val handle: Long
        get() = ctx.also { check(it != 0L) { "model is closed" } }

    val isMultilingual: Boolean get() = WhisperNative.isMultilingual(handle)

    override fun close() {
        if (ctx != 0L) {
            WhisperNative.freeModel(ctx)
            ctx = 0L
        }
    }

    companion object {
        val systemInfo: String get() = WhisperNative.systemInfo()

        fun load(assets: AssetManager, path: String): WhisperModel {
            val ctx = WhisperNative.loadModel(assets, path)
            if (ctx == 0L) throw ModelLoadException("Could not load the translation model ($path).")
            return WhisperModel(ctx)
        }
    }
}

/** 16 kHz mono float audio held in native memory, so an hour-long video does not fill the Java heap. */
class NativeAudio(capacityHint: Long = 0) : Closeable {
    private var handle: Long = WhisperNative.newAudio(capacityHint)

    init {
        if (handle == 0L) throw OutOfMemoryError("no memory for audio")
    }

    internal val pointer: Long
        get() = handle.also { check(it != 0L) { "audio is closed" } }

    val sampleCount: Long get() = WhisperNative.audioSize(pointer)

    fun append(samples: FloatArray, count: Int) {
        if (count <= 0) return
        if (!WhisperNative.appendAudio(pointer, samples, count)) {
            throw OutOfMemoryError("no memory for audio")
        }
    }

    override fun close() {
        if (handle != 0L) {
            WhisperNative.freeAudio(handle)
            handle = 0L
        }
    }
}

/**
 * Cancel button state. Safe to call [cancel] from any thread; it reaches into a running native
 * computation (whisper.cpp or the language model), which stops at its next step.
 */
class Cancellation {
    @Volatile
    var isCancelled: Boolean = false
        private set

    private val lock = Any()
    private val hooks = ArrayList<() -> Unit>()

    fun cancel() {
        synchronized(lock) {
            isCancelled = true
            hooks.forEach { it() }
        }
    }

    /**
     * Runs [block] with [onCancel] registered, so [cancel] calls it while [block] runs (or at once
     * if already cancelled). [onCancel] is never called after this returns, so the native job it
     * touches can be freed afterwards.
     */
    fun <T> whileRunning(onCancel: () -> Unit, block: () -> T): T {
        synchronized(lock) {
            hooks.add(onCancel)
            if (isCancelled) onCancel()
        }
        try {
            return block()
        } finally {
            synchronized(lock) { hooks.remove(onCancel) }
        }
    }

    /** Runs [block] with a whisper.cpp job that [cancel] can stop. */
    internal fun <T> withNativeJob(block: (Long) -> T): T {
        val job = WhisperNative.newJob()
        if (job == 0L) throw OutOfMemoryError("no memory for job")
        try {
            return whileRunning({ WhisperNative.cancelJob(job) }) { block(job) }
        } finally {
            WhisperNative.freeJob(job)
        }
    }
}

class CancelledException : Exception("Cancelled")

/** One subtitle line as Whisper produced it. Times are from the start of the audio track. */
data class Segment(val startMs: Long, val endMs: Long, val text: String)

interface TranscriptionListener {
    fun onLanguage(code: String, name: String) {}
    /** 0..100, as whisper.cpp reports it: once per 30-second window. */
    fun onProgress(percent: Int) {}
    fun onSegment(segment: Segment) {}
}

/**
 * Runs Whisper over [audio], language auto-detected. With [translate] on, the output is
 * English whatever the spoken language. With [speechDetector] (see [SpeechDetector]), only the
 * stretches with speech are heard. Blocking: call it from a background thread.
 */
fun WhisperModel.transcribe(
    audio: NativeAudio,
    listener: TranscriptionListener,
    cancellation: Cancellation,
    threads: Int = DEFAULT_THREADS,
    translate: Boolean = true,
    speechDetector: File? = null,
    /** Carry on from here (ms into the audio) after a stopped run; 0 starts at the beginning. */
    startMs: Long = 0,
    /** The language code found before, when carrying on (not detected again from the middle). */
    language: String? = null,
) {
    if (cancellation.isCancelled) throw CancelledException()
    val bridge = object : WhisperNative.Listener {
        override fun onLanguage(code: String, name: String) = listener.onLanguage(code, name)
        override fun onProgress(percent: Int) = listener.onProgress(percent)
        override fun onSegment(startMs: Long, endMs: Long, utf8: ByteArray) {
            val text = String(utf8, Charsets.UTF_8).trim()
            if (text.isNotEmpty()) listener.onSegment(Segment(startMs, endMs, text))
        }
    }
    val rc = cancellation.withNativeJob { job ->
        WhisperNative.transcribe(handle, audio.pointer, job, threads, translate, speechDetector?.path, startMs, language, bridge)
    }
    when {
        rc == WhisperNative.RESULT_CANCELLED || cancellation.isCancelled -> throw CancelledException()
        rc != WhisperNative.RESULT_DONE -> throw IllegalStateException("Whisper stopped with error $rc.")
    }
}
