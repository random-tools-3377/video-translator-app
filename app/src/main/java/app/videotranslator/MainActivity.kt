package app.videotranslator

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.videotranslator.engine.Phase
import app.videotranslator.engine.TranslationEngine
import app.videotranslator.llm.LanguageModels
import app.videotranslator.subtitles.SubtitleFiles
import app.videotranslator.subtitles.SubtitleStyleStore
import app.videotranslator.ui.AppTheme
import app.videotranslator.ui.BusyDialog
import app.videotranslator.ui.MainScreen
import app.videotranslator.ui.ModelActions
import app.videotranslator.ui.PlayerScreen
import app.videotranslator.ui.SubtitleActions
import app.videotranslator.whisper.Segment

/** A video playing with its subtitles: the lines just worked out, or opened from a saved file. */
private data class Watch(val uri: Uri, val title: String?, val lines: List<Segment>)

class MainActivity : ComponentActivity() {

    /** A video that arrived while another was being translated, waiting for the user's answer. */
    private var waiting by mutableStateOf<Uri?>(null)

    /** The user said to replace the running one: start this as soon as the old one has stopped. */
    private var queued by mutableStateOf<Uri?>(null)

    /** The player screen, when open. */
    private var watch by mutableStateOf<Watch?>(null)

    /** "All subtitles earlier/later", in ms; applies to watching and saving. */
    private var offsetMs by mutableLongStateOf(0L)

    /** What the system save dialog will write once the user has chosen where. */
    private var pendingSave: String? = null

    /** The video picked for "watch with saved subtitles", while its subtitle file is picked. */
    private var subtitlesFor: Uri? = null

    /**
     * The progress notification needs Android's notification permission to be seen in the
     * notification shade (the translation runs in the background either way). Asked once.
     */
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var askedNotifications = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        LanguageModels.refresh(this)
        SubtitleStyleStore.load(this)
        if (savedInstanceState == null) handleShare(intent)

        setContent {
            AppTheme {
                val state by TranslationEngine.state.collectAsStateWithLifecycle()
                val modelState by LanguageModels.state.collectAsStateWithLifecycle()
                val style by SubtitleStyleStore.style.collectAsStateWithLifecycle()
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    if (uri != null) request(uri)
                }
                // The language model file, picked once from Downloads. Read once, copied in, checked.
                val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) LanguageModels.import(this@MainActivity, uri)
                }

                // Subtitles go only where the user chooses, through the system save dialog.
                val saveSrt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
                    if (uri != null) writePending(uri) else pendingSave = null
                }
                val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                    if (uri != null) writePending(uri) else pendingSave = null
                }
                val openSubtitles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    val video = subtitlesFor
                    subtitlesFor = null
                    if (uri != null && video != null) openSaved(video, uri)
                }
                val pickForSubtitles = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    if (uri != null) {
                        subtitlesFor = uri
                        Toast.makeText(this@MainActivity, "Now pick the subtitle file (.srt) you saved for it.", Toast.LENGTH_LONG).show()
                        openSubtitles.launch(arrayOf("*/*"))
                    }
                }
                val lines = watch?.lines ?: state.lines
                val name = watch?.title ?: state.videoName
                val subtitleActions = SubtitleActions(
                    onWatch = {
                        state.videoUri?.let { watch = Watch(it, state.videoName, state.lines) }
                    },
                    onSaveSrt = {
                        pendingSave = SubtitleFiles.toSrt(lines, offsetMs)
                        saveSrt.launch(SubtitleFiles.suggestedName(name, "srt"))
                    },
                    onSaveText = {
                        pendingSave = SubtitleFiles.toText(lines)
                        saveText.launch(SubtitleFiles.suggestedName(name, "txt"))
                    },
                    onOpenSaved = {
                        pickForSubtitles.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    },
                )

                LaunchedEffect(state.phase, queued) {
                    val next = queued
                    if (next != null && state.phase != Phase.Working) {
                        queued = null
                        offsetMs = 0
                        TranslationEngine.start(this@MainActivity, next)
                    }
                }

                val watching = watch
                if (watching != null) {
                    PlayerScreen(
                        videoUri = watching.uri,
                        title = watching.title,
                        lines = watching.lines,
                        offsetMs = offsetMs,
                        onOffsetChange = { offsetMs = it },
                        style = style,
                        onStyleChange = { SubtitleStyleStore.set(this@MainActivity, it) },
                        onSaveSrt = subtitleActions.onSaveSrt,
                        onSaveText = subtitleActions.onSaveText,
                        onBack = { watch = null },
                    )
                } else {
                    MainScreen(
                        state = state,
                        modelState = modelState,
                        onPick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                        },
                        onCancel = { TranslationEngine.cancel() },
                        modelActions = ModelActions(
                            onDownload = { model -> openInBrowser(model.downloadUrl) },
                            onLoad = { modelPicker.launch(arrayOf("*/*")) },
                            onCancelLoad = { LanguageModels.cancelImport() },
                            onRemove = { LanguageModels.remove(this@MainActivity) },
                        ),
                        subtitleActions = subtitleActions,
                    )
                }

                waiting?.let { uri ->
                    BusyDialog(
                        currentName = state.videoName,
                        onReplace = {
                            waiting = null
                            queued = uri
                            TranslationEngine.cancel()
                        },
                        onKeep = { waiting = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** A video shared from Samsung Gallery (or any app): one item, read once, never copied. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type ?: return
        if (!type.startsWith("video/")) return
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            ?: return
        request(uri)
    }

    /**
     * Opens a download link in the phone's browser. The browser does the download; this app has no
     * network permission and never sees the connection.
     */
    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            // No browser: nothing to do.
        }
    }

    private fun askForNotificationsOnce() {
        if (askedNotifications) return
        askedNotifications = true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun request(uri: Uri) {
        askForNotificationsOnce()
        watch = null
        if (TranslationEngine.isBusy) {
            waiting = uri
        } else {
            offsetMs = 0
            TranslationEngine.start(this, uri)
        }
    }

    /** Writes what the save dialog was opened for to the place the user chose. */
    private fun writePending(uri: Uri) {
        val text = pendingSave ?: return
        pendingSave = null
        try {
            val out = contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("no file")
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not save: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Plays [video] with the subtitles in the file [subtitles] (a .srt), read once into memory. */
    private fun openSaved(video: Uri, subtitles: Uri) {
        val lines = try {
            contentResolver.openInputStream(subtitles)?.use { input ->
                SubtitleFiles.parseSrt(String(input.readNBytes(MAX_SUBTITLE_BYTES), Charsets.UTF_8))
            }
        } catch (e: Exception) {
            null
        }
        if (lines == null) {
            Toast.makeText(
                this,
                "No subtitles could be read from that file. Pick a .srt file, such as one saved by this app.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        offsetMs = 0
        watch = Watch(video, displayName(video), lines)
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MAX_SUBTITLE_BYTES = 8 shl 20
    }
}
