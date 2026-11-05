package app.videotranslator.whisper

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * whisper.cpp's Silero speech detector (ggml-silero-v6.2.0.bin, 885 KB, bundled in the APK).
 * Whisper only hears the stretches where someone is talking, so it cannot invent lines over
 * music, silence or noise, and it detects the language from speech.
 *
 * whisper.cpp opens it by file path, so it is copied once out of the APK into the app's
 * no-backup storage. It is the same file for every video and says nothing about any of them.
 */
object SpeechDetector {
    const val ASSET = "models/ggml-silero-v6.2.0.bin"
    private const val TAG = "VTSpeech"

    /** The model as a file, or null if this build has none (then Whisper hears everything). */
    @Synchronized
    fun file(context: Context): File? {
        val expected = try {
            context.assets.openFd(ASSET).use { it.length }
        } catch (e: IOException) {
            Log.w(TAG, "no speech detector in this build", e)
            return null
        }
        val out = File(context.noBackupFilesDir, File(ASSET).name)
        if (out.isFile && out.length() == expected) return out
        val tmp = File(out.path + ".part")
        context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(out)) throw IOException("could not save the speech detector")
        return out
    }
}
