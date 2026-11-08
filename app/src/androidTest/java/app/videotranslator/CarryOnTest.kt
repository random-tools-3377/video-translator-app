package app.videotranslator

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.videotranslator.engine.JobState
import app.videotranslator.engine.JobStore
import app.videotranslator.engine.Phase
import app.videotranslator.engine.Pipeline
import app.videotranslator.engine.PipelineListener
import app.videotranslator.engine.Resume
import app.videotranslator.engine.Timings
import app.videotranslator.whisper.Cancellation
import app.videotranslator.whisper.DEFAULT_THREADS
import app.videotranslator.whisper.Segment
import app.videotranslator.whisper.SpeechDetector
import app.videotranslator.whisper.WhisperModel
import java.io.File
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Carrying on a translation that stopped: the work in progress kept by JobStore comes back as it
 * was, and the pipeline carries on Whisper from the end of the last line kept (ja-udhr.mp4, two
 * sentences: about 0-8.5 s and 9.6-18 s) without hearing the part already done.
 */
@RunWith(AndroidJUnit4::class)
class CarryOnTest {

    @Test
    fun keptWorkComesBackAsItWas() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = JobState(
            phase = Phase.Working,
            videoUri = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1000"),
            videoName = "VID_20250101.mp4",
            durationMs = 1_438_000,
            languageCode = "ja",
            languageName = "japanese",
            lines = listOf(Segment(100, 4200, "I'm reckless by nature."), Segment(6000, 13000, "小学校にいる時分")),
            spoken = listOf(Segment(100, 4200, "親譲りの無鉄砲で"), Segment(6000, 13000, "小学校にいる時分")),
            languageModel = "Qwen3.5-9B",
            translatedByLanguageModel = true,
            translated = 1,
            whisperDone = false,
            startedAtMs = 1000,
        )
        JobStore.save(context, state)
        val back = JobStore.load(context)!!
        assertEquals("a run that was still working comes back as stopped", Phase.Cancelled, back.phase)
        assertTrue(back.restored)
        assertTrue(back.canCarryOn)
        assertEquals(state.videoUri, back.videoUri)
        assertEquals(state.videoName, back.videoName)
        assertEquals(state.durationMs, back.durationMs)
        assertEquals("ja", back.languageCode)
        assertEquals("japanese", back.languageName)
        assertEquals(state.lines, back.lines)
        assertEquals(state.spoken, back.spoken)
        assertEquals("Qwen3.5-9B", back.languageModel)
        assertEquals(1, back.translated)
        assertEquals(false, back.whisperDone)
        assertEquals(13_000L, back.reachedMs)

        // A finished result comes back finished, with its times.
        JobStore.save(context, state.copy(phase = Phase.Done, whisperDone = true, translated = 2, finishedAtMs = 61_000,
            timings = Timings(1, 2, 3, 4, 5)))
        val done = JobStore.load(context)!!
        assertEquals(Phase.Done, done.phase)
        assertEquals(false, done.canCarryOn)
        assertEquals(Timings(1, 2, 3, 4, 5), done.timings)
        assertEquals(60_000L, (done.finishedAtMs ?: 0L) - done.startedAtMs)

        JobStore.delete(context)
        assertNull(JobStore.load(context))
    }

    @Test
    fun whisperCarriesOnFromTheLastLineKept() {
        val kept = Segment(0, 9_000, "The first sentence, kept from before the stop.")
        val added = mutableListOf<Segment>()
        val listener = object : PipelineListener {
            override fun onLine(line: Segment) {
                added += line
            }
        }
        val started = System.nanoTime()
        val result = testAssets().openFd(VIDEO).use {
            pipeline().run(
                it, listener, Cancellation(),
                Resume(lines = listOf(kept), translated = 0, whisperDone = false, languageCode = "ja", languageName = "japanese"),
            )
        }
        Log.i(TAG, "carried on in %.1f s: %s".format((System.nanoTime() - started) / 1e9, added))
        assertEquals("the kept line stays first", kept, result.lines.first())
        assertTrue("Whisper wrote the rest", added.isNotEmpty())
        assertEquals(listOf(kept) + added, result.lines)
        for (line in added) {
            assertTrue("nothing from before the stop again: $line", line.startMs >= kept.endMs - 300)
        }
    }

    @Test
    fun withWhisperDoneTheVideoIsNotNeeded() {
        val lines = listOf(Segment(0, 1000, "a"), Segment(1000, 2000, "b"))
        val result = pipeline().run(
            null, object : PipelineListener {}, Cancellation(),
            Resume(lines = lines, translated = 0, whisperDone = true, languageCode = "ja", languageName = "japanese"),
        )
        assertEquals(lines, result.lines)
        assertNull(result.audio)
    }

    private fun pipeline() = Pipeline(model = { whisper }, speechDetector = { detector }, threads = DEFAULT_THREADS)

    companion object {
        private const val TAG = "VTTest"
        private const val WHISPER = "ggml-tiny.bin"
        private const val VIDEO = "ja-udhr.mp4"
        private lateinit var whisper: WhisperModel
        private var detector: File? = null

        private fun testAssets() = InstrumentationRegistry.getInstrumentation().context.assets

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            whisper = WhisperModel.load(testAssets(), WHISPER)
            detector = SpeechDetector.file(target)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (::whisper.isInitialized) whisper.close()
        }
    }
}
