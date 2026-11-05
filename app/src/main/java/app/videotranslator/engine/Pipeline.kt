package app.videotranslator.engine

import android.content.res.AssetFileDescriptor
import android.os.SystemClock
import app.videotranslator.audio.AudioDecoder
import app.videotranslator.audio.AudioInfo
import app.videotranslator.audio.WHISPER_SAMPLE_RATE
import app.videotranslator.llm.LanguageModel
import app.videotranslator.llm.LanguageModelStats
import app.videotranslator.whisper.Cancellation
import app.videotranslator.whisper.CancelledException
import app.videotranslator.whisper.DEFAULT_THREADS
import app.videotranslator.whisper.NativeAudio
import app.videotranslator.whisper.Segment
import app.videotranslator.whisper.TranscriptionListener
import app.videotranslator.whisper.WhisperModel
import app.videotranslator.whisper.transcribe
import java.io.File

enum class Stage {
    ReadingAudio,
    LoadingModel,
    /** Whisper. With a language model it writes down the spoken language; without, it translates. */
    Whisper,
    LoadingLanguageModel,
    /** The language model turning Whisper's lines into English. */
    LanguageModel,
}

interface PipelineListener {
    fun onStage(stage: Stage) {}
    fun onDuration(durationMs: Long) {}
    /** Fraction 0..1 of the current stage. */
    fun onProgress(stage: Stage, fraction: Float) {}
    fun onLanguage(code: String, name: String) {}
    /** A new line. Times are in the video's timeline. */
    fun onLine(line: Segment) {}
    /** Lines [first], [first] + 1, ... now read [lines] (the language model's English). */
    fun onLinesReplaced(first: Int, lines: List<Segment>) {}
}

/** The optional language-model step: Whisper writes down the speech, the model translates it. */
class LanguageModelStep(
    /** Shown on screen, e.g. "Qwen3.5-9B". */
    val name: String,
    /** Called before [load], to free Whisper's memory for the language model. */
    val beforeLoad: () -> Unit,
    val load: (Cancellation) -> LanguageModel,
)

data class PipelineResult(
    val languageCode: String?,
    val languageName: String?,
    val lines: List<Segment>,
    val audio: AudioInfo,
    val readAudioMs: Long,
    val loadModelMs: Long,
    val whisperMs: Long,
    /** Set when the language model translated the lines (not for English, or with no model). */
    val languageModel: String? = null,
    val loadLanguageModelMs: Long = 0,
    val languageModelMs: Long = 0,
    val languageModelStats: LanguageModelStats? = null,
)

/**
 * Video in, English lines out: decode the audio track to 16 kHz mono, then Whisper with the
 * language auto-detected. Without a language model, Whisper translates to English itself. With
 * one, Whisper writes down what is said in the spoken language and the language model translates
 * those lines to English, reading them in context (English videos skip that step). Blocking; run
 * it on a background thread.
 */
class Pipeline(
    private val model: () -> WhisperModel,
    private val speechDetector: () -> File? = { null },
    private val threads: Int = DEFAULT_THREADS,
    private val languageModel: LanguageModelStep? = null,
) {
    fun run(source: AssetFileDescriptor, listener: PipelineListener, cancellation: Cancellation): PipelineResult {
        NativeAudio().use { audio ->
            // 1. The sound track, to 16 kHz mono in native memory.
            listener.onStage(Stage.ReadingAudio)
            val t0 = SystemClock.elapsedRealtime()
            val info = AudioDecoder.decode(
                source = source,
                cancellation = cancellation,
                onStart = { durationUs -> if (durationUs != null) listener.onDuration(durationUs / 1000) },
                onProgress = { listener.onProgress(Stage.ReadingAudio, it) },
                sink = { buf, n -> audio.append(buf, n) },
            )
            val readMs = SystemClock.elapsedRealtime() - t0
            if (info.durationUs == null) listener.onDuration(info.outputSamples * 1000 / WHISPER_SAMPLE_RATE)
            if (cancellation.isCancelled) throw CancelledException()

            // 2. The model (loaded once per app run, then kept, unless the language model needs the memory).
            listener.onStage(Stage.LoadingModel)
            val t1 = SystemClock.elapsedRealtime()
            val whisper = model()
            val detector = speechDetector()
            val loadMs = SystemClock.elapsedRealtime() - t1
            if (cancellation.isCancelled) throw CancelledException()

            // 3. Whisper: English straight away, or (with a language model) the spoken language.
            listener.onStage(Stage.Whisper)
            val t2 = SystemClock.elapsedRealtime()
            val offsetMs = info.firstSampleUs / 1000
            val audioMs = (info.outputSamples * 1000 / WHISPER_SAMPLE_RATE).coerceAtLeast(1)
            val lines = ArrayList<Segment>()
            var languageCode: String? = null
            var languageName: String? = null
            var whisperPercent = 0
            var lastEndMs = 0L
            fun report() {
                val fromLines = lastEndMs.toFloat() / audioMs
                listener.onProgress(Stage.Whisper, maxOf(whisperPercent / 100f, fromLines).coerceIn(0f, 1f))
            }
            whisper.transcribe(
                audio = audio,
                cancellation = cancellation,
                threads = threads,
                translate = languageModel == null,
                speechDetector = detector,
                listener = object : TranscriptionListener {
                    override fun onLanguage(code: String, name: String) {
                        languageCode = code
                        languageName = name
                        listener.onLanguage(code, name)
                    }

                    override fun onProgress(percent: Int) {
                        whisperPercent = percent
                        report()
                    }

                    override fun onSegment(segment: Segment) {
                        val line = segment.copy(
                            startMs = segment.startMs + offsetMs,
                            endMs = segment.endMs + offsetMs,
                        )
                        lines.add(line)
                        lastEndMs = segment.endMs
                        listener.onLine(line)
                        report()
                    }
                },
            )
            val whisperMs = SystemClock.elapsedRealtime() - t2
            listener.onProgress(Stage.Whisper, 1f)
            val whisperResult = PipelineResult(languageCode, languageName, lines, info, readMs, loadMs, whisperMs)

            // 4. The language model, for anything but English.
            val step = languageModel ?: return whisperResult
            val spoken = languageName
            if (lines.isEmpty() || languageCode == "en" || spoken == null) return whisperResult
            if (cancellation.isCancelled) throw CancelledException()

            listener.onStage(Stage.LoadingLanguageModel)
            val t3 = SystemClock.elapsedRealtime()
            step.beforeLoad()
            val llm = step.load(cancellation)
            val loadLlmMs = SystemClock.elapsedRealtime() - t3

            listener.onStage(Stage.LanguageModel)
            val t4 = SystemClock.elapsedRealtime()
            val total = lines.size
            var done = 0
            val stats = llm.use {
                it.translate(spoken, lines.map { l -> l.text }, cancellation) { first, english ->
                    val replaced = english.mapIndexed { i, text -> lines[first + i].copy(text = text) }
                    replaced.forEachIndexed { i, line -> lines[first + i] = line }
                    done = maxOf(done, first + english.size)
                    listener.onLinesReplaced(first, replaced)
                    listener.onProgress(Stage.LanguageModel, done.toFloat() / total)
                }
            }
            val llmMs = SystemClock.elapsedRealtime() - t4
            listener.onProgress(Stage.LanguageModel, 1f)
            return whisperResult.copy(
                lines = lines,
                languageModel = step.name,
                loadLanguageModelMs = loadLlmMs,
                languageModelMs = llmMs,
                languageModelStats = stats,
            )
        }
    }
}
