package app.videotranslator.engine

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import app.videotranslator.audio.AudioDecodeException
import app.videotranslator.llm.LanguageModel
import app.videotranslator.llm.LanguageModelException
import app.videotranslator.llm.LanguageModels
import app.videotranslator.whisper.BUNDLED_MODEL_ASSET
import app.videotranslator.whisper.Cancellation
import app.videotranslator.whisper.CancelledException
import app.videotranslator.whisper.DEFAULT_THREADS
import app.videotranslator.whisper.ModelLoadException
import app.videotranslator.whisper.Segment
import app.videotranslator.whisper.SpeechDetector
import app.videotranslator.whisper.WhisperModel
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase { Idle, Working, Done, Cancelled, Failed }

data class Timings(
    val readAudioMs: Long,
    val loadModelMs: Long,
    val whisperMs: Long,
    val loadLanguageModelMs: Long = 0,
    val languageModelMs: Long = 0,
)

/** Everything the screen shows. Lives in memory only, and is gone when the app closes. */
data class JobState(
    val phase: Phase = Phase.Idle,
    /** The video the user picked or shared, to play it with its subtitles (never copied). */
    val videoUri: Uri? = null,
    val videoName: String? = null,
    val durationMs: Long? = null,
    val stage: Stage? = null,
    val progress: Float = 0f,
    val languageCode: String? = null,
    val languageName: String? = null,
    val lines: List<Segment> = emptyList(),
    /**
     * The language model this job uses for anything but English (Whisper then writes down the
     * spoken language first), or null: Whisper translates by itself.
     */
    val languageModel: String? = null,
    /** True once the lines on screen are the language model's English. */
    val translatedByLanguageModel: Boolean = false,
    val startedAtMs: Long = 0L,
    val finishedAtMs: Long? = null,
    val stopping: Boolean = false,
    val error: String? = null,
    val timings: Timings? = null,
)

/**
 * The one translation that can run at a time, shared by every screen in the process so a
 * second Share while one is running cannot start a second Whisper on the same model.
 */
object TranslationEngine {
    private const val TAG = "VTEngine"

    private val _state = MutableStateFlow(JobState())
    val state: StateFlow<JobState> = _state.asStateFlow()

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "translator") }

    @Volatile
    private var current: Cancellation? = null

    // Only touched on the worker thread. Loaded on first use and kept while the app runs.
    private var model: WhisperModel? = null

    val isBusy: Boolean get() = _state.value.phase == Phase.Working

    /** Starts on [uri]. Returns false if a translation (or a model import) is already running. */
    @Synchronized
    fun start(context: Context, uri: Uri): Boolean {
        if (isBusy || LanguageModels.isImporting) return false
        val app = context.applicationContext
        val cancellation = Cancellation()
        current = cancellation
        _state.value = JobState(
            phase = Phase.Working,
            videoUri = uri,
            stage = Stage.ReadingAudio,
            startedAtMs = SystemClock.elapsedRealtime(),
            languageModel = LanguageModels.installedFile(app)?.first?.name,
        )
        worker.execute { run(app, uri, cancellation) }
        // Keeps it running when the user switches apps or the screen goes off.
        TranslationService.start(app)
        return true
    }

    fun cancel() {
        val c = current ?: return
        c.cancel()
        _state.update { if (it.phase == Phase.Working) it.copy(stopping = true) else it }
    }

    /** Back to the start screen, forgetting the last result. */
    fun clear() {
        if (!isBusy) _state.value = JobState()
    }

    private fun run(app: Context, uri: Uri, cancellation: Cancellation) {
        try {
            val name = displayName(app, uri)
            _state.update { it.copy(videoName = name) }

            val afd = app.contentResolver.openAssetFileDescriptor(uri, "r")
                ?: throw AudioDecodeException("Could not open this video.")
            val step = LanguageModels.installedFile(app)?.let { (known, file) ->
                LanguageModelStep(
                    name = known.name,
                    // The language model needs the memory Whisper holds; Whisper loads again next time.
                    beforeLoad = { releaseModel() },
                    load = { c ->
                        LanguageModel.load(file, DEFAULT_THREADS, c).also { Log.i(TAG, LanguageModel.systemInfo) }
                    },
                )
            }
            val result = afd.use {
                Pipeline(model = { loadModel(app) }, speechDetector = { SpeechDetector.file(app) }, languageModel = step)
                    .run(it, Listener, cancellation)
            }
            _state.update {
                it.copy(
                    phase = Phase.Done,
                    progress = 1f,
                    lines = result.lines,
                    translatedByLanguageModel = result.languageModel != null,
                    finishedAtMs = SystemClock.elapsedRealtime(),
                    stopping = false,
                    timings = Timings(
                        result.readAudioMs, result.loadModelMs, result.whisperMs,
                        result.loadLanguageModelMs, result.languageModelMs,
                    ),
                )
            }
            Log.i(
                TAG,
                "done: ${result.lines.size} lines, audio ${result.readAudioMs} ms, model ${result.loadModelMs} ms, " +
                    "whisper ${result.whisperMs} ms, language model ${result.languageModel} load ${result.loadLanguageModelMs} ms " +
                    "run ${result.languageModelMs} ms ${result.languageModelStats}",
            )
        } catch (e: CancelledException) {
            finish(Phase.Cancelled, null)
        } catch (e: AudioDecodeException) {
            Log.w(TAG, "audio failed", e)
            finish(Phase.Failed, e.message)
        } catch (e: ModelLoadException) {
            Log.e(TAG, "model failed", e)
            finish(Phase.Failed, e.message)
        } catch (e: LanguageModelException) {
            Log.e(TAG, "language model failed", e)
            finish(Phase.Failed, "${e.message} The lines below are as Whisper wrote them, in the spoken language.")
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "open failed", e)
            finish(Phase.Failed, "Could not open this video.")
        } catch (e: SecurityException) {
            Log.w(TAG, "open failed", e)
            finish(Phase.Failed, "The app was not allowed to read this video. Pick it again.")
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory", e)
            finish(Phase.Failed, "The phone ran out of memory. Try closing other apps, or a shorter video.")
        } catch (e: Exception) {
            Log.e(TAG, "failed", e)
            finish(Phase.Failed, "Something went wrong (${e.javaClass.simpleName}: ${e.message}).")
        } finally {
            if (current === cancellation) current = null
        }
    }

    private fun finish(phase: Phase, error: String?) {
        _state.update {
            it.copy(phase = phase, error = error, stopping = false, finishedAtMs = SystemClock.elapsedRealtime())
        }
    }

    private fun releaseModel() {
        model?.close()
        model = null
    }

    private fun loadModel(app: Context): WhisperModel =
        model ?: WhisperModel.load(app.assets, BUNDLED_MODEL_ASSET).also {
            model = it
            Log.i(TAG, WhisperModel.systemInfo)
        }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private object Listener : PipelineListener {
        override fun onStage(stage: Stage) {
            _state.update { it.copy(stage = stage, progress = 0f) }
        }

        override fun onDuration(durationMs: Long) {
            _state.update { it.copy(durationMs = durationMs) }
        }

        override fun onProgress(stage: Stage, fraction: Float) {
            _state.update { if (it.stage == stage) it.copy(progress = fraction) else it }
        }

        override fun onLanguage(code: String, name: String) {
            _state.update { it.copy(languageCode = code, languageName = name) }
        }

        override fun onLine(line: Segment) {
            _state.update { it.copy(lines = it.lines + line) }
        }

        override fun onLinesReplaced(first: Int, lines: List<Segment>) {
            _state.update {
                val updated = it.lines.toMutableList()
                lines.forEachIndexed { i, line -> if (first + i < updated.size) updated[first + i] = line }
                it.copy(lines = updated)
            }
        }
    }
}
