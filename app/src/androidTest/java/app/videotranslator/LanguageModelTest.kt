package app.videotranslator

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.videotranslator.engine.LanguageModelStep
import app.videotranslator.engine.Pipeline
import app.videotranslator.engine.PipelineListener
import app.videotranslator.engine.Stage
import app.videotranslator.llm.LanguageModel
import app.videotranslator.whisper.Cancellation
import app.videotranslator.whisper.CancelledException
import app.videotranslator.whisper.DEFAULT_THREADS
import app.videotranslator.whisper.Segment
import app.videotranslator.whisper.SpeechDetector
import app.videotranslator.whisper.WhisperModel
import java.io.File
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's language-model path on a real video: ja-udhr.mp4 (the public Japanese reading of the
 * Universal Declaration of Human Rights, Article 1, test-clips/clips.tsv) through the decoder,
 * Whisper (tiny, writing down the Japanese) and a small Qwen3.5 model, in one process, as the app
 * runs it: libvtwhisper.so and libvtllama.so each with their own ggml.
 *
 * The tiny models are far from the phone's in quality, so this checks the plumbing (stages, line
 * count and times kept, English letters out, Cancel), not the translation; CI's language-model
 * job checks the English with the real model on a PC.
 */
@RunWith(AndroidJUnit4::class)
class LanguageModelTest {

    private class Recorder : PipelineListener {
        val stages = mutableListOf<Stage>()
        val spoken = mutableListOf<Segment>()
        val replaced = mutableMapOf<Int, Segment>()
        var language: String? = null
        var onStageStarted: ((Stage) -> Unit)? = null
        override fun onStage(stage: Stage) {
            stages += stage
            onStageStarted?.invoke(stage)
        }
        override fun onLanguage(code: String, name: String) { language = code }
        override fun onLine(line: Segment) { spoken += line }
        override fun onLinesReplaced(first: Int, lines: List<Segment>) {
            lines.forEachIndexed { i, l -> replaced[first + i] = l }
        }
    }

    private var whisperReleased = 0

    private fun pipeline() = Pipeline(
        model = { whisper },
        speechDetector = { detector },
        threads = DEFAULT_THREADS,  // the emulator has 2 cores: more threads would be many times slower
        languageModel = LanguageModelStep(
            name = "test model",
            beforeLoad = { whisperReleased++ },
            load = { c -> LanguageModel.load(llmFile, DEFAULT_THREADS, c) },
        ),
    )

    private fun video() = testAssets().openFd(VIDEO)

    @Test
    fun japaneseVideoIsWrittenDownThenTranslated() {
        val rec = Recorder()
        val started = System.nanoTime()
        val result = video().use { pipeline().run(it, rec, Cancellation()) }
        val seconds = (System.nanoTime() - started) / 1e9
        Log.i(TAG, "took %.1f s (whisper %d ms, model load %d ms, model %d ms); %s".format(
            seconds, result.whisperMs, result.loadLanguageModelMs, result.languageModelMs, result.languageModelStats))
        result.lines.forEachIndexed { i, l -> Log.i(TAG, "[${l.startMs}-${l.endMs}] ${rec.spoken[i].text} -> ${l.text}") }

        assertEquals(
            listOf(Stage.ReadingAudio, Stage.LoadingModel, Stage.Whisper, Stage.LoadingLanguageModel, Stage.LanguageModel),
            rec.stages,
        )
        assertEquals("detected language", "ja", rec.language)
        assertEquals("Whisper's memory is released before the language model loads", 1, whisperReleased)
        assertTrue("Whisper wrote lines", rec.spoken.isNotEmpty())
        assertEquals("test model", result.languageModel)

        // One English line per Japanese line, each keeping its time.
        assertEquals(rec.spoken.size, result.lines.size)
        assertEquals(rec.spoken.indices.toSet(), rec.replaced.keys)
        for (i in rec.spoken.indices) {
            assertEquals(rec.spoken[i].startMs, result.lines[i].startMs)
            assertEquals(rec.spoken[i].endMs, result.lines[i].endMs)
            assertEquals(result.lines[i], rec.replaced[i])
        }
        val japanese = rec.spoken.joinToString(" ") { it.text }
        assertTrue("Whisper wrote Japanese: $japanese", japanese.any { Character.UnicodeScript.of(it.code) in JAPANESE })
        val english = result.lines.joinToString(" ") { it.text }
        val letters = english.filter { it.isLetter() }
        val latin = letters.count { it.code < 128 }.toFloat() / maxOf(1, letters.length)
        assertTrue("English expected, got: $english", letters.isNotEmpty() && latin >= 0.9f)
    }

    @Test
    fun cancelStopsTheLanguageModel() {
        val rec = Recorder()
        val cancellation = Cancellation()
        var cancelledAt = 0L
        // Cancel while the language model is reading the prompt: llama.cpp's abort callback must
        // stop it well before it would have finished.
        rec.onStageStarted = { stage ->
            if (stage == Stage.LanguageModel) {
                Thread {
                    Thread.sleep(1500)
                    cancelledAt = System.nanoTime()
                    cancellation.cancel()
                }.start()
            }
        }
        try {
            video().use { pipeline().run(it, rec, cancellation) }
            fail("expected the run to stop")
        } catch (_: CancelledException) {
            // expected
        }
        val stoppedAfter = (System.nanoTime() - cancelledAt) / 1e9
        Log.i(TAG, "stopped %.1f s after Cancel".format(stoppedAfter))
        assertTrue("cancel was pressed", cancelledAt != 0L)
        assertTrue("stopped ${stoppedAfter}s after Cancel", stoppedAfter < 20)
        assertEquals(Stage.LanguageModel, rec.stages.last())
    }

    companion object {
        private const val TAG = "VTTest"
        private const val WHISPER = "ggml-tiny.bin"
        private const val VIDEO = "ja-udhr.mp4"
        private const val LLM = "test-llm.gguf"
        private val JAPANESE = setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HAN)
        private lateinit var whisper: WhisperModel
        private var detector: File? = null
        private lateinit var llmFile: File

        private fun testAssets() = InstrumentationRegistry.getInstrumentation().context.assets

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val names = testAssets().list("")?.toSet().orEmpty()
            check(WHISPER in names && VIDEO in names && LLM in names) {
                "Test assets missing ($names). See the emulator job in .github/workflows/build.yml."
            }
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            whisper = WhisperModel.load(testAssets(), WHISPER)
            detector = SpeechDetector.file(target)
            // llama.cpp opens the model by path, as the app does with the copied-in file.
            llmFile = File(target.noBackupFilesDir, LLM)
            if (!llmFile.isFile) {
                val part = File(llmFile.path + ".part")
                testAssets().open(LLM).use { input -> part.outputStream().use { input.copyTo(it, 4 shl 20) } }
                check(part.renameTo(llmFile)) { "could not save $llmFile" }
            }
            Log.i(TAG, LanguageModel.systemInfo)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (::whisper.isInitialized) whisper.close()
            if (::llmFile.isInitialized) llmFile.delete()
        }
    }
}
