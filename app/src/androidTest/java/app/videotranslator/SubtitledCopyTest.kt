package app.videotranslator

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.videotranslator.export.SubtitledCopy
import app.videotranslator.subtitles.SubtitleEdge
import app.videotranslator.subtitles.SubtitleStyle
import app.videotranslator.subtitles.toCues
import app.videotranslator.whisper.Segment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The copy with subtitles burned in, made as the app makes it (Media3 Transformer with the
 * subtitle overlay drawn by SubtitleRenderer), from jfk.mp4: a plain dark picture with the speech.
 * Checks the copy has the picture and the sound, the same size and length, and white subtitle
 * pixels at the bottom of the picture where the original has none.
 */
@RunWith(AndroidJUnit4::class)
class SubtitledCopyTest {

    @Test
    fun copyHasTheSubtitlesBurnedInAndKeepsTheSound() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val input = File(target.cacheDir, "copy-test-input.mp4")
        instrumentation.context.assets.open("jfk.mp4").use { i -> input.outputStream().use { i.copyTo(it) } }
        val output = File(target.cacheDir, "copy-test-output.mp4")

        val lines = listOf(Segment(0, 11_000, "And so, my fellow Americans"))
        val style = SubtitleStyle(sizeFraction = 0.09f, textColor = Color.WHITE, edge = SubtitleEdge.None, boxOpacity = 0f)
        val done = CountDownLatch(1)
        var error: Exception? = null
        val started = System.nanoTime()
        instrumentation.runOnMainSync {
            SubtitledCopy(target, Uri.fromFile(input), output, toCues(lines), 0, style) { e ->
                error = e
                done.countDown()
            }.start()
        }
        assertTrue("the copy finished within 10 minutes", done.await(10, TimeUnit.MINUTES))
        Log.i(TAG, "copy took %.1f s, %d bytes".format((System.nanoTime() - started) / 1e9, output.length()))
        assertNull("the copy failed: $error", error)
        assertTrue("the copy has content", output.length() > 10_000)

        val extractor = MediaExtractor().apply { setDataSource(output.absolutePath) }
        val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        extractor.release()
        assertTrue("a picture: $mimes", mimes.any { it.startsWith("video/") })
        assertTrue("the sound is kept: $mimes", mimes.any { it.startsWith("audio/") })

        val original = MediaMetadataRetriever().apply { setDataSource(input.absolutePath) }
        val copy = MediaMetadataRetriever().apply { setDataSource(output.absolutePath) }
        fun MediaMetadataRetriever.num(key: Int) = extractMetadata(key)?.toLongOrNull() ?: -1
        assertEquals(original.num(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), copy.num(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
        assertEquals(original.num(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), copy.num(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
        val lengthDiff = abs(original.num(MediaMetadataRetriever.METADATA_KEY_DURATION) - copy.num(MediaMetadataRetriever.METADATA_KEY_DURATION))
        assertTrue("same length (off by $lengthDiff ms)", lengthDiff < 500)

        val before = original.getFrameAtTime(5_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        val after = copy.getFrameAtTime(5_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        original.release()
        copy.release()
        val brightBefore = brightPixelsInBottom(before)
        val brightAfter = brightPixelsInBottom(after)
        Log.i(TAG, "bright pixels at the bottom: original $brightBefore, copy $brightAfter")
        assertTrue("no white in the original's picture ($brightBefore)", brightBefore < 50)
        assertTrue("the subtitle is drawn into the copy ($brightAfter)", brightAfter > 300)
        val brightTop = brightPixelsInTop(after)
        assertTrue("nothing drawn at the top ($brightTop)", brightTop < 50)

        input.delete()
        output.delete()
    }

    private fun brightPixelsInBottom(b: Bitmap) = countBright(b, b.height * 6 / 10, b.height)

    private fun brightPixelsInTop(b: Bitmap) = countBright(b, 0, b.height * 4 / 10)

    private fun countBright(b: Bitmap, fromY: Int, toY: Int): Int {
        var n = 0
        for (y in fromY until toY) for (x in 0 until b.width) {
            val c = b.getPixel(x, y)
            if (Color.red(c) > 200 && Color.green(c) > 200 && Color.blue(c) > 200) n++
        }
        return n
    }

    private companion object {
        const val TAG = "VTTest"
    }
}
