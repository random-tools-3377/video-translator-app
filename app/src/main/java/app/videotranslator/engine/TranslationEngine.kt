package app.videotranslator.engine

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
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
     * The lines as first written down, index for index with [lines]: the spoken language when the
     * language model translates them, otherwise the same as [lines]. Shown under a line while the
     * user corrects its English.
     */
    val spoken: List<Segment> = emptyList(),
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
    /** How many of the first [lines] the language model has translated (to carry on after a stop). */
    val translated: Int = 0,
    /** Whisper heard the whole video (to carry on with only the language model's part). */
    val whisperDone: Boolean = false,
    /** Brought back from [JobStore] after the app was closed, rather than run in this app session. */
    val restored: Boolean = false,
    /** The app can no longer open the video: the user picks it again to carry on. */
    val needsVideoAgain: Boolean = false,
) {
    /** Stopped before the end (cancelled, failed, or the app was closed): it can carry on. */
    val canCarryOn: Boolean
        get() = (phase == Phase.Cancelled || phase == Phase.Failed) && videoUri != null &&
            (lines.isNotEmpty() || restored)

    /** Where it got to in the video, for "stopped at 12:34". */
    val reachedMs: Long get() = lines.lastOrNull()?.endMs ?: 0L
}

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

    /** For keeping the work in progress ([JobStore]); set by the first start or restore. */
    @Volatile
    private var appContext: Context? = null

    private fun persist() {
        appContext?.let { JobStore.save(it, _state.value) }
    }

    /**
     * At app start: the job kept from before the app was closed, finished or not, so a result is
     * not lost and a stopped translation can carry on.
     */
    @Synchronized
    fun restore(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (_state.value.phase != Phase.Idle) return
        JobStore.load(app)?.let { _state.value = it }
    }
    val isBusy: Boolean get() = _state.value.phase == Phase.Working

    /** Starts on [uri]. Returns false if a translation (or a model import) is already running. */
    @Synchronized
    fun start(context: Context, uri: Uri): Boolean {
        if (isBusy || LanguageModels.isImporting) return false
        val app = context.applicationContext
        appContext = app
        forgetVideo(app, _state.value.videoUri, keep = uri)
        JobStore.delete(app)
        val cancellation = Cancellation()
        current = cancellation
        _state.value = JobState(
            phase = Phase.Working,
            videoUri = uri,
            stage = Stage.ReadingAudio,
            startedAtMs = SystemClock.elapsedRealtime(),
            languageModel = LanguageModels.installedFile(app)?.first?.name,
        )
        persist()
        worker.execute { run(app, uri, cancellation, null, useModel = true) }
        // Keeps it running when the user switches apps or the screen goes off.
        TranslationService.start(app)
        return true
    }

    /**
     * Carries on a translation that stopped before the end: Whisper from the end of the last
     * line, then the language model on the lines it has not translated. [newUri]: the same video
     * picked again, when the app can no longer open the one it had. Returns false (with a message
     * on screen) when it cannot start.
     */
    @Synchronized
    fun resume(context: Context, newUri: Uri? = null): Boolean {
        val s = _state.value
        if (isBusy || LanguageModels.isImporting || !s.canCarryOn) return false
        val app = context.applicationContext
        appContext = app
        val uri = newUri ?: s.videoUri ?: return false
        if (!s.whisperDone) {
            val readable = try {
                app.contentResolver.openAssetFileDescriptor(uri, "r")?.close() != null
            } catch (e: Exception) {
                false
            }
            if (!readable) {
                _state.update {
                    it.copy(
                        needsVideoAgain = true,
                        error = "The app can no longer open this video. Pick the same video again to carry on.",
                    )
                }
                return false
            }
            val expected = s.durationMs
            val actual = if (newUri != null) durationOf(app, newUri) else null
            if (expected != null && actual != null && kotlin.math.abs(expected - actual) > 1500) {
                _state.update {
                    it.copy(needsVideoAgain = true, error = "That is a different video. Pick the one these lines are from.")
                }
                return false
            }
        }
        val resume = Resume(
            lines = s.lines,
            translated = s.translated,
            whisperDone = s.whisperDone,
            languageCode = s.languageCode,
            languageName = s.languageName,
        )
        val cancellation = Cancellation()
        current = cancellation
        _state.value = s.copy(
            phase = Phase.Working,
            videoUri = uri,
            stage = if (s.whisperDone) Stage.LoadingLanguageModel else Stage.ReadingAudio,
            progress = 0f,
            startedAtMs = SystemClock.elapsedRealtime(),
            finishedAtMs = null,
            stopping = false,
            error = null,
            timings = null,
            restored = false,
            needsVideoAgain = false,
        )
        persist()
        worker.execute { run(app, uri, cancellation, resume, useModel = s.languageModel != null) }
        TranslationService.start(app)
        return true
    }

    fun cancel() {
        val c = current ?: return
        c.cancel()
        _state.update { if (it.phase == Phase.Working) it.copy(stopping = true) else it }
    }

    /** Back to the start screen, forgetting the last result (and the kept copy of it). */
    fun clear() {
        if (isBusy) return
        appContext?.let {
            forgetVideo(it, _state.value.videoUri, keep = null)
            JobStore.delete(it)
        }
        _state.value = JobState()
    }

    /** Gives back the lasting permission to read [uri] (taken so a stopped run could carry on). */
    private fun forgetVideo(app: Context, uri: Uri?, keep: Uri?) {
        if (uri == null || uri == keep) return
        try {
            app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Never had it (a shared video): nothing to give back.
        }
    }

    private fun durationOf(app: Context, uri: Uri): Long? = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(app, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            r.release()
        }
    } catch (_: Exception) {
        null
    }

    /** The user corrected line [index] (its words or its times). Not while it is still working. */
    fun editLine(index: Int, line: Segment) {
        _state.update {
            if (it.phase == Phase.Working || index !in it.lines.indices) it
            else it.copy(lines = it.lines.toMutableList().also { l -> l[index] = line })
        }
        persist()
    }

    /** The user removed line [index] (a line that should not be there). */
    fun removeLine(index: Int) {
        _state.update {
            if (it.phase == Phase.Working || index !in it.lines.indices) it
            else it.copy(
                lines = it.lines.toMutableList().also { l -> l.removeAt(index) },
                spoken = if (index in it.spoken.indices) it.spoken.toMutableList().also { l -> l.removeAt(index) } else it.spoken,
                translated = if (index < it.translated) it.translated - 1 else it.translated,
            )
        }
        persist()
    }

    private fun run(app: Context, uri: Uri, cancellation: Cancellation, resume: Resume?, useModel: Boolean) {
        try {
            val name = displayName(app, uri) ?: _state.value.videoName
            _state.update { it.copy(videoName = name) }

            // Carrying on with only the language model left needs no video at all.
            val afd = if (resume?.whisperDone == true) null
            else app.contentResolver.openAssetFileDescriptor(uri, "r") ?: throw AudioDecodeException("Could not open this video.")
            val step = if (!useModel) null else LanguageModels.installedFile(app)?.let { (known, file) ->
                LanguageModelStep(
                    name = known.name,
                    // The language model needs the memory Whisper holds; Whisper loads again next time.
                    beforeLoad = { releaseModel() },
                    load = { c ->
                        LanguageModel.load(file, DEFAULT_THREADS, c).also { Log.i(TAG, LanguageModel.systemInfo) }
                    },
                )
            }
            val pipeline = Pipeline(model = { loadModel(app) }, speechDetector = { SpeechDetector.file(app) }, languageModel = step)
            val result = if (afd != null) afd.use { pipeline.run(it, Listener, cancellation, resume) }
            else pipeline.run(null, Listener, cancellation, resume)
            _state.update {
                it.copy(
                    phase = Phase.Done,
                    progress = 1f,
                    lines = result.lines,
                    whisperDone = true,
                    translated = if (result.languageModel != null) result.lines.size else it.translated,
                    translatedByLanguageModel = it.translatedByLanguageModel || result.languageModel != null,
                    finishedAtMs = SystemClock.elapsedRealtime(),
                    stopping = false,
                    timings = Timings(
                        result.readAudioMs, result.loadModelMs, result.whisperMs,
                        result.loadLanguageModelMs, result.languageModelMs,
                    ),
                )
            }
            persist()
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
        persist()
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
            persist()
        }

        override fun onLine(line: Segment) {
            _state.update { it.copy(lines = it.lines + line, spoken = it.spoken + line) }
            persist()
        }

        override fun onWhisperDone() {
            _state.update { it.copy(whisperDone = true) }
            persist()
        }

        override fun onLinesReplaced(first: Int, lines: List<Segment>) {
            _state.update {
                val updated = it.lines.toMutableList()
                lines.forEachIndexed { i, line -> if (first + i < updated.size) updated[first + i] = line }
                it.copy(lines = updated, translated = maxOf(it.translated, first + lines.size))
            }
            persist()
        }
    }
}
