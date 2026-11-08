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
    /** Whisper has heard the whole video (what is left is the language model's part). */
    fun onWhisperDone() {}
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
    /** The sound track read; null when carrying on needed only the language model. */
    val audio: AudioInfo?,
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
 * Where to carry on from: what an earlier run of the same video had done when it was stopped
 * (the app was closed, Android stopped it, or the user cancelled).
 */
data class Resume(
    /** The lines so far, in the video's timeline: the first [translated] in English, the rest as written down. */
    val lines: List<Segment>,
    /** How many of the first [lines] the language model has already translated. */
    val translated: Int,
    /** Whisper had heard the whole video: only the language model's part is left. */
    val whisperDone: Boolean,
    val languageCode: String?,
    val languageName: String?,
)

/**
 * Video in, English lines out: decode the audio track to 16 kHz mono, then Whisper with the
 * language auto-detected. Without a language model, Whisper translates to English itself. With
 * one, Whisper writes down what is said in the spoken language and the language model translates
 * those lines to English, reading them in context (English videos skip that step). With a
 * [Resume], Whisper carries on from the end of the last line and the language model translates
 * only the lines it has not done yet. Blocking; run it on a background thread.
 */
class Pipeline(
    private val model: () -> WhisperModel,
    private val speechDetector: () -> File? = { null },
    private val threads: Int = DEFAULT_THREADS,
    private val languageModel: LanguageModelStep? = null,
) {
    /** [source] may be null only when [resume] says Whisper had finished. */
    fun run(
        source: AssetFileDescriptor?,
        listener: PipelineListener,
        cancellation: Cancellation,
        resume: Resume? = null,
    ): PipelineResult {
        val lines = ArrayList<Segment>(resume?.lines.orEmpty())
        var languageCode: String? = resume?.languageCode
        var languageName: String? = resume?.languageName
        val whisperResult = if (resume?.whisperDone == true) {
            PipelineResult(languageCode, languageName, lines, null, 0, 0, 0)
        } else {
            requireNotNull(source) { "the video is needed to carry on writing it down" }
            whisper(source, listener, cancellation, lines, resume).also {
                languageCode = it.languageCode
                languageName = it.languageName
            }
        }

        // 4. The language model, for anything but English, on the lines it has not translated yet.
        val step = languageModel ?: return whisperResult
        val spoken = languageName
        val first = (resume?.translated ?: 0).coerceIn(0, lines.size)
        if (lines.size <= first || languageCode == "en" || spoken == null) return whisperResult
        if (cancellation.isCancelled) throw CancelledException()

        listener.onStage(Stage.LoadingLanguageModel)
        val t3 = SystemClock.elapsedRealtime()
        step.beforeLoad()
        val llm = step.load(cancellation)
        val loadLlmMs = SystemClock.elapsedRealtime() - t3

        listener.onStage(Stage.LanguageModel)
        val t4 = SystemClock.elapsedRealtime()
        val total = lines.size
        var done = first
        val todo = lines.subList(first, lines.size).map { it.text }
        val stats = llm.use {
            it.translate(spoken, todo, cancellation) { from, english ->
                val at = first + from
                val replaced = english.mapIndexed { i, text -> lines[at + i].copy(text = text) }
                replaced.forEachIndexed { i, line -> lines[at + i] = line }
                done = maxOf(done, at + english.size)
                listener.onLinesReplaced(at, replaced)
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

    /** Steps 1 to 3: the sound, the model, Whisper. New lines are added to [lines]. */
    private fun whisper(
        source: AssetFileDescriptor,
        listener: PipelineListener,
        cancellation: Cancellation,
        lines: MutableList<Segment>,
        resume: Resume?,
    ): PipelineResult {
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
            // Carrying on: from the end of the last line, with the language found before.
            listener.onStage(Stage.Whisper)
            val t2 = SystemClock.elapsedRealtime()
            val offsetMs = info.firstSampleUs / 1000
            val audioMs = (info.outputSamples * 1000 / WHISPER_SAMPLE_RATE).coerceAtLeast(1)
            val startMs = ((resume?.lines?.lastOrNull()?.endMs ?: 0L) - offsetMs).coerceAtLeast(0)
            var languageCode: String? = resume?.languageCode
            var languageName: String? = resume?.languageName
            var whisperPercent = 0
            var lastEndMs = startMs
            fun report() {
                val fromLines = lastEndMs.toFloat() / audioMs
                listener.onProgress(Stage.Whisper, maxOf(whisperPercent / 100f, fromLines).coerceIn(0f, 1f))
            }
            report()
            whisper.transcribe(
                audio = audio,
                cancellation = cancellation,
                threads = threads,
                translate = languageModel == null,
                speechDetector = detector,
                startMs = startMs,
                language = if (startMs > 0) languageCode else null,
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
            listener.onWhisperDone()
            return PipelineResult(languageCode, languageName, lines, info, readMs, loadMs, whisperMs)
        }
    }
}
