package app.videotranslator

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.videotranslator.engine.Pipeline
import app.videotranslator.engine.PipelineListener
import app.videotranslator.engine.Stage
import app.videotranslator.whisper.Cancellation
import app.videotranslator.whisper.CancelledException
import app.videotranslator.whisper.DEFAULT_THREADS
import app.videotranslator.whisper.Segment
import app.videotranslator.whisper.SpeechDetector
import app.videotranslator.whisper.WhisperModel
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real pipeline on a real video file: jfk.mp4 (whisper.cpp's samples/jfk.wav wrapped by
 * scripts/make-test-video.sh as AAC at 44.1 kHz stereo, with an H.264 picture) goes through
 * MediaExtractor/MediaCodec, the resampler and whisper.cpp with the tiny model, translate on.
 *
 * The tiny model and the video are test assets put in place by CI; they are not in the app.
 */
@RunWith(AndroidJUnit4::class)
class VideoToEnglishTest {

    private class Recorder : PipelineListener {
        val stages = mutableListOf<Stage>()
        val lines = mutableListOf<Segment>()
        var language: String? = null
        var durationMs: Long? = null
        var onLanguageDetected: (() -> Unit)? = null
        override fun onStage(stage: Stage) { stages += stage }
        override fun onDuration(durationMs: Long) { this.durationMs = durationMs }
        override fun onLanguage(code: String, name: String) {
            language = code
            onLanguageDetected?.invoke()
        }
        override fun onLine(line: Segment) { lines += line }
    }

    private fun video() = testAssets().openFd(VIDEO)

    // As the app runs it: the tiny model in place of medium, the speech detector from the app's assets.
    private fun pipeline() = Pipeline(model = { model }, speechDetector = { detector }, threads = DEFAULT_THREADS)

    @Test
    fun jfkVideoComesOutAsEnglishText() {
        val rec = Recorder()
        val started = System.nanoTime()
        val result = video().use { pipeline().run(it, rec, Cancellation()) }
        val seconds = (System.nanoTime() - started) / 1e9
        val text = rec.lines.joinToString(" ") { it.text }
        Log.i(TAG, "took %.1f s; language=%s; audio %s %d Hz x%d; %d samples at 16 kHz; text: %s".format(
            seconds, rec.language, result.audio.mime, result.audio.sampleRate, result.audio.channels,
            result.audio.outputSamples, text))

        // The decode path: AAC, resampled 44.1 kHz stereo -> 16 kHz mono, 11 s long.
        assertEquals("audio/mp4a-latm", result.audio.mime)
        assertEquals(44_100, result.audio.sampleRate)
        assertEquals(2, result.audio.channels)
        val seconds16k = result.audio.outputSamples / 16_000.0
        assertTrue("decoded $seconds16k s of audio, expected about 11", seconds16k in 10.5..11.6)
        assertTrue("duration ${rec.durationMs}", rec.durationMs!! in 10_500L..11_600L)

        assertEquals(listOf(Stage.ReadingAudio, Stage.LoadingModel, Stage.Whisper), rec.stages)
        assertEquals("detected language", "en", rec.language)
        val words = text.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex(" +"), " ")
        assertTrue("English text expected, got: $text", "ask not what your country can do for you" in words)
        assertTrue("lines expected", rec.lines.isNotEmpty())
        for (line in rec.lines) {
            assertTrue("line times $line", line.startMs >= 0 && line.endMs >= line.startMs && line.endMs <= 11_600)
        }
    }

    @Test
    fun cancelStopsWhisper() {
        val rec = Recorder()
        val cancellation = Cancellation()
        // Cancel from inside whisper.cpp's run, once it has detected the language: the native
        // side must notice and stop before producing any lines.
        rec.onLanguageDetected = { cancellation.cancel() }
        try {
            video().use { pipeline().run(it, rec, cancellation) }
            fail("expected the run to stop")
        } catch (_: CancelledException) {
            // expected
        }
        assertEquals("whisper had started", "en", rec.language)
        assertTrue("no lines after cancel, got ${rec.lines}", rec.lines.isEmpty())

        // The model is still usable afterwards.
        val again = Recorder()
        video().use { pipeline().run(it, again, Cancellation()) }
        assertTrue(again.lines.isNotEmpty())
    }

    companion object {
        private const val TAG = "VTTest"
        private const val MODEL = "ggml-tiny.bin"
        private const val VIDEO = "jfk.mp4"
        private lateinit var model: WhisperModel
        private var detector: java.io.File? = null

        private fun testAssets() = InstrumentationRegistry.getInstrumentation().context.assets

        @BeforeClass
        @JvmStatic
        fun loadModel() {
            val names = testAssets().list("")?.toSet().orEmpty()
            check(MODEL in names && VIDEO in names) {
                "Test assets missing ($names). Run scripts/fetch-models.sh tiny and scripts/make-test-video.sh."
            }
            Log.i(TAG, WhisperModel.systemInfo)
            model = WhisperModel.load(testAssets(), MODEL)
            detector = SpeechDetector.file(InstrumentationRegistry.getInstrumentation().targetContext)
            assertNotNull("the speech detector model is missing from the app's assets", detector)
        }

        @AfterClass
        @JvmStatic
        fun freeModel() {
            if (::model.isInitialized) model.close()
        }
    }
}
