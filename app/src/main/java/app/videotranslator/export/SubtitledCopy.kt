package app.videotranslator.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import app.videotranslator.subtitles.Cue
import app.videotranslator.subtitles.SubtitleRenderer
import app.videotranslator.subtitles.SubtitleStyle
import app.videotranslator.subtitles.cueIndexAt
import com.google.common.collect.ImmutableList
import java.io.File

/**
 * Draws the subtitle of each video frame with [SubtitleRenderer], the same code the in-app player
 * uses, onto a transparent picture the size of the frame, which Media3 lays over it. A new bitmap
 * is made only when the line changes (Media3 uploads a bitmap again only when it is a new one).
 */
@OptIn(UnstableApi::class)
internal class SubtitleOverlay(
    private val cues: List<Cue>,
    private val offsetMs: Long,
    private val style: SubtitleStyle,
    /** The picture's size as shown, until Media3 tells the exact frame size in [configure]. */
    displaySize: Pair<Int, Int>,
) : BitmapOverlay() {
    private var width = displaySize.first.coerceAtLeast(1)
    private var height = displaySize.second.coerceAtLeast(1)
    private var shown = Int.MIN_VALUE
    private var bitmap: Bitmap? = null

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        width = videoSize.width.coerceAtLeast(1)
        height = videoSize.height.coerceAtLeast(1)
        shown = Int.MIN_VALUE
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val index = cueIndexAt(cues, presentationTimeUs / 1000, offsetMs)
        bitmap?.let { if (index == shown) return it }
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (index >= 0) SubtitleRenderer.draw(Canvas(b), width, height, cues[index].text, style)
        shown = index
        bitmap = b
        return b
    }
}

/**
 * Makes a copy of [input] with the subtitles burned in, at [output] (an MP4): the picture is
 * re-encoded with the subtitles drawn on, at its own resolution and frame rate; the sound is
 * copied as it is. The original is only read. Must be started and used on one thread with a
 * Looper (the main thread); [onFinished] is called there, with null on success.
 */
@OptIn(UnstableApi::class)
class SubtitledCopy(
    context: Context,
    private val input: Uri,
    private val output: File,
    cues: List<Cue>,
    offsetMs: Long,
    style: SubtitleStyle,
    private val onFinished: (Exception?) -> Unit,
) {
    private val overlay = SubtitleOverlay(cues, offsetMs, style, displaySize(context, input))
    private val progressHolder = ProgressHolder()
    private var finished = false

    private val transformer: Transformer = Transformer.Builder(context)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) = finish(null)
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                finish(exportException)
        })
        .build()

    fun start() {
        output.delete()
        val item = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setEffects(Effects(emptyList(), listOf(OverlayEffect(ImmutableList.of<TextureOverlay>(overlay)))))
            .build()
        transformer.start(item, output.absolutePath)
    }

    /** 0-100, or -1 while it is not known yet. */
    fun progress(): Int =
        if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) progressHolder.progress else -1

    fun cancel() {
        if (finished) return
        finished = true
        transformer.cancel()
        output.delete()
    }

    /** Width and height as the picture is shown (rotation applied), or 1 x 1 if unknown. */
    private fun displaySize(context: Context, uri: Uri): Pair<Int, Int> = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) h to w else w to h
        } finally {
            r.release()
        }
    } catch (_: Exception) {
        1 to 1
    }

    private fun finish(error: Exception?) {
        if (finished) return
        finished = true
        if (error != null) output.delete()
        onFinished(error)
    }
}
