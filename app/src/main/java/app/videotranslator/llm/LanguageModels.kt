package app.videotranslator.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A language model file the app accepts: exactly the one tested, checked by size and sha256. */
data class KnownModel(
    val fileName: String,
    /** Shown on screen, e.g. "Qwen3.5-9B". */
    val name: String,
    val bytes: Long,
    val sha256: String,
    /** Where the user downloads it, in the phone's browser. The app itself never goes online. */
    val downloadUrl: String,
)

data class LanguageModelState(
    /** The model the app will use, already copied in and checked; null if none. */
    val installed: KnownModel? = null,
    /** 0..1 while a file is being checked and copied in; null otherwise. */
    val importing: Float? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

/**
 * The optional language model that turns Whisper's transcript into natural English.
 *
 * The user downloads one of [KNOWN] in the phone's browser and picks it with the system file picker.
 * The app copies it into its own no-backup storage (read in place, a file in Downloads could be
 * moved or deleted), checking its sha256 on the way, so only the exact file that was tested is
 * ever used. It is the same file for every video and says nothing about any of them; it is never
 * backed up. The user can then delete the download.
 */
object LanguageModels {
    private const val TAG = "VTModels"
    private const val DIR = "language-model"

    val KNOWN = listOf(
        KnownModel(
            fileName = "Qwen3.5-9B-Q4_K_M.gguf",
            name = "Qwen3.5-9B",
            bytes = 5_680_522_464L,
            sha256 = "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8",
            downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/resolve/main/Qwen3.5-9B-Q4_K_M.gguf?download=true",
        ),
        KnownModel(
            fileName = "Qwen3.5-4B-Q4_K_M.gguf",
            name = "Qwen3.5-4B",
            bytes = 2_740_937_888L,
            sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
            downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf?download=true",
        ),
    )

    /** Recommended: the best English in the test. */
    val RECOMMENDED: KnownModel = KNOWN[0]

    private val _state = MutableStateFlow(LanguageModelState())
    val state: StateFlow<LanguageModelState> = _state.asStateFlow()

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "model-import") }

    @Volatile
    private var importCancelled = false

    @Volatile
    private var checked = false

    val isImporting: Boolean get() = _state.value.importing != null

    /** Finds the installed model (once per process; cheap: name and size only). */
    @Synchronized
    fun refresh(context: Context) {
        if (checked) return
        checked = true
        _state.update { it.copy(installed = findInstalled(context)) }
    }

    /** The model file to use, or null. Its sha256 was checked when it was copied in. */
    fun installedFile(context: Context): Pair<KnownModel, File>? {
        refresh(context)
        val model = _state.value.installed ?: return null
        val file = File(dir(context), model.fileName)
        return if (file.isFile && file.length() == model.bytes) model to file else null
    }

    /** Copies [uri] in, if it is one of [KNOWN]. Returns false if an import is already running. */
    @Synchronized
    fun import(context: Context, uri: Uri): Boolean {
        if (isImporting) return false
        val app = context.applicationContext
        importCancelled = false
        _state.update { it.copy(importing = 0f, message = null, messageIsError = false) }
        worker.execute { runImport(app, uri) }
        return true
    }

    fun cancelImport() {
        importCancelled = true
    }

    /** Deletes the copied-in model. */
    @Synchronized
    fun remove(context: Context) {
        if (isImporting) return
        dir(context).listFiles()?.forEach { it.delete() }
        _state.update { LanguageModelState(message = "Language model removed.") }
    }

    private fun dir(context: Context) = File(context.noBackupFilesDir, DIR)

    private fun findInstalled(context: Context): KnownModel? {
        val d = dir(context)
        return KNOWN.firstOrNull { m -> File(d, m.fileName).let { it.isFile && it.length() == m.bytes } }
    }

    private fun runImport(app: Context, uri: Uri) {
        val d = dir(app)
        val part = File(d, "import.part")
        try {
            val (displayName, size) = describe(app, uri)
            val candidates = if (size != null) KNOWN.filter { it.bytes == size } else KNOWN
            if (candidates.isEmpty()) {
                fail(notKnown(displayName))
                return
            }
            val needed = candidates.maxOf { it.bytes }
            d.mkdirs()
            // Room for the copy (an existing model is replaced only once the new one checks out).
            val free = d.usableSpace
            if (free < needed + 256L * 1024 * 1024) {
                fail("Not enough free space: the model needs ${gb(needed)} and the phone has ${gb(free)} free.")
                return
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            var lastReport = -1
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("could not open the file")
            input.use { inp ->
                part.outputStream().use { out ->
                    val buf = ByteArray(4 * 1024 * 1024)
                    while (true) {
                        if (importCancelled) {
                            part.delete()
                            _state.update { it.copy(importing = null, message = "Stopped.", messageIsError = false) }
                            return
                        }
                        val n = inp.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        copied += n
                        if (copied > needed) break
                        val percent = (copied * 100 / needed).toInt()
                        if (percent != lastReport) {
                            lastReport = percent
                            _state.update { it.copy(importing = (copied.toFloat() / needed).coerceIn(0f, 1f)) }
                        }
                    }
                    out.fd.sync()
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val model = KNOWN.firstOrNull { it.bytes == copied && it.sha256 == sha }
            if (model == null) {
                part.delete()
                val sameSize = KNOWN.any { it.bytes == copied }
                fail(
                    if (sameSize) "This file is not the expected one, or the download is damaged (its fingerprint does not match). Download it again."
                    else notKnown(displayName)
                )
                return
            }
            // Only one model is kept.
            d.listFiles()?.filter { it != part }?.forEach { it.delete() }
            if (!part.renameTo(File(d, model.fileName))) throw IOException("could not save the model")
            Log.i(TAG, "language model ${model.name} installed")
            _state.update {
                LanguageModelState(
                    installed = model,
                    message = "${model.name} is ready. You can now delete the downloaded file from Downloads to free ${gb(model.bytes)}.",
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "import failed", e)
            part.delete()
            fail("The app was not allowed to read that file. Pick it again.")
        } catch (e: IOException) {
            Log.w(TAG, "import failed", e)
            part.delete()
            fail("Could not copy the file (${e.message}).")
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(importing = null, message = message, messageIsError = true) }
    }

    private fun notKnown(displayName: String?): String =
        "${displayName ?: "That file"} is not a language model this app knows. Pick " +
            KNOWN.joinToString(" or ") { it.fileName } + "."

    private fun describe(context: Context, uri: Uri): Pair<String?, Long?> = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) return@use null to null
                val name = if (c.isNull(0)) null else c.getString(0)
                val size = if (c.isNull(1)) null else c.getLong(1)
                name to size
            } ?: (null to null)
    } catch (_: Exception) {
        null to null
    }

    fun gb(bytes: Long): String = String.format(Locale.US, "%.1f GB", bytes / 1e9)
}
