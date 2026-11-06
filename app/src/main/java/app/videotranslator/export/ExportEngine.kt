package app.videotranslator.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import app.videotranslator.subtitles.SubtitleStyle
import app.videotranslator.subtitles.toCues
import app.videotranslator.whisper.Segment
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ExportPhase { Idle, Working, Saving, Done, Cancelled, Failed }

data class ExportState(
    val phase: ExportPhase = ExportPhase.Idle,
    /** The video being copied (the copy is for this one). */
    val videoUri: Uri? = null,
    val videoName: String? = null,
    /** 0..1 while working. */
    val progress: Float = 0f,
    /** The saved copy in the gallery, when done. */
    val savedUri: Uri? = null,
    val error: String? = null,
)

/**
 * Saving a copy of a video with its subtitles burned in, one at a time. The copy is made in the
 * app's cache, then moved into the gallery under Movies/Video Translator (MediaStore; no
 * permission needed for the app's own files), and the cache file is deleted. The original video
 * is never modified. [ExportService] keeps it going when the user switches apps.
 */
object ExportEngine {
    private const val TAG = "VTExport"
    const val FOLDER = "Video Translator"

    private val _state = MutableStateFlow(ExportState())
    val state: StateFlow<ExportState> = _state.asStateFlow()
    val isBusy: Boolean get() = _state.value.phase == ExportPhase.Working || _state.value.phase == ExportPhase.Saving

    private val main = Handler(Looper.getMainLooper())
    private var copy: SubtitledCopy? = null

    /** On the main thread. */
    fun start(
        context: Context,
        videoUri: Uri,
        videoName: String?,
        lines: List<Segment>,
        offsetMs: Long,
        style: SubtitleStyle,
    ) {
        if (isBusy) return
        val app = context.applicationContext
        val temp = tempFile(app)
        _state.value = ExportState(ExportPhase.Working, videoUri, videoName)
        val c = SubtitledCopy(app, videoUri, temp, toCues(lines), offsetMs, style) { error ->
            copy = null
            if (error != null) {
                Log.w(TAG, "export failed", error)
                _state.update {
                    it.copy(phase = ExportPhase.Failed, error = "The copy could not be made (${error.message}).")
                }
            } else {
                saveToGallery(app, temp, videoName)
            }
        }
        copy = c
        try {
            c.start()
        } catch (e: Exception) {
            copy = null
            temp.delete()
            _state.update { it.copy(phase = ExportPhase.Failed, error = "The copy could not be started (${e.message}).") }
            return
        }
        ExportService.start(app)
        pollProgress()
    }

    fun cancel() {
        val c = copy ?: return
        copy = null
        c.cancel()
        _state.update { if (it.phase == ExportPhase.Working) it.copy(phase = ExportPhase.Cancelled) else it }
    }

    /** Back to nothing shown (after the result has been seen). */
    fun clear() {
        if (!isBusy) _state.value = ExportState()
    }

    /** A copy left half-made by a crash takes space: remove it when the app starts. */
    fun removeLeftovers(context: Context) {
        if (!isBusy) tempFile(context.applicationContext).delete()
    }

    private fun pollProgress() {
        val c = copy ?: return
        val p = c.progress()
        if (p >= 0) _state.update { if (it.phase == ExportPhase.Working) it.copy(progress = p / 100f) else it }
        main.postDelayed({ pollProgress() }, 500)
    }

    private fun saveToGallery(app: Context, temp: File, videoName: String?) {
        _state.update { it.copy(phase = ExportPhase.Saving, progress = 1f) }
        Thread({
            val result = try {
                Result.success(moveIntoGallery(app, temp, copyName(videoName)))
            } catch (e: Exception) {
                Log.w(TAG, "saving to the gallery failed", e)
                Result.failure(e)
            } finally {
                temp.delete()
            }
            main.post {
                result.fold(
                    onSuccess = { uri -> _state.update { it.copy(phase = ExportPhase.Done, savedUri = uri) } },
                    onFailure = { e ->
                        _state.update {
                            it.copy(phase = ExportPhase.Failed, error = "The copy could not be saved to the gallery (${e.message}).")
                        }
                    },
                )
            }
        }, "export-save").start()
    }

    private fun moveIntoGallery(app: Context, temp: File, name: String): Uri {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("the gallery did not accept a new video")
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IllegalStateException("no file in the gallery")
            out.use { o -> temp.inputStream().use { it.copyTo(o, 1 shl 20) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** "VID_2025.mp4" -> "VID_2025 (English subtitles).mp4". */
    internal fun copyName(videoName: String?): String {
        val base = videoName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Video"
        return "$base (English subtitles).mp4"
    }

    private fun tempFile(context: Context) = File(context.cacheDir, "subtitled-copy.mp4")
}
