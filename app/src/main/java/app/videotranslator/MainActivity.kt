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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.videotranslator.engine.Phase
import app.videotranslator.engine.TranslationEngine
import app.videotranslator.export.ExportEngine
import app.videotranslator.llm.LanguageModels
import app.videotranslator.subtitles.OpenedSubtitles
import app.videotranslator.subtitles.SubtitleFiles
import app.videotranslator.subtitles.SubtitleKind
import app.videotranslator.subtitles.SubtitleStyleStore
import app.videotranslator.ui.AppTheme
import app.videotranslator.ui.BusyDialog
import app.videotranslator.ui.CopyActions
import app.videotranslator.ui.JobActions
import app.videotranslator.ui.MainScreen
import app.videotranslator.ui.ModelActions
import app.videotranslator.ui.PlayerScreen
import app.videotranslator.ui.SubtitleActions
import app.videotranslator.ui.withLine
import app.videotranslator.ui.withoutLine
import app.videotranslator.whisper.Segment

/**
 * A video playing with its subtitles: the lines just worked out ([fromJob]: they live in
 * TranslationEngine, so a correction shows on the results screen too), or opened from a file.
 */
private data class Watch(
    val uri: Uri,
    val title: String?,
    val lines: List<Segment>,
    val fromJob: Boolean = false,
    /** The .srt the lines came from (or were last saved to), which corrections can be saved into. */
    val file: Uri? = null,
    val fileName: String? = null,
    /** Corrections made since the lines were opened or last saved. */
    val changed: Boolean = false,
    /** The "all earlier/later" setting the lines were last saved with. */
    val savedOffsetMs: Long = 0,
)

/** Subtitles read from a file the user picked, opened or shared, and the file itself. */
private data class LoadedSubtitles(val opened: OpenedSubtitles, val uri: Uri, val name: String?)

/** A video shared to the app, waiting for the user to say what to do with it. */
private data class SharedVideo(val uri: Uri, val name: String?)

class MainActivity : ComponentActivity() {

    /** Subtitles waiting for the video they belong to (picked, or shared from Gallery). */
    private var loaded by mutableStateOf<LoadedSubtitles?>(null)

    /** A shared video: translate it, or add subtitles the user already has? */
    private var shared by mutableStateOf<SharedVideo?>(null)

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

    /** The save dialog is for the watched file's lines as a .srt, which can be saved into again. */
    private var pendingIsSrt = false

    /** Leave the player once the save dialog has saved (the user chose Save on the way out). */
    private var leaveAfterSave = false

    /** The user is leaving the player with corrections not yet saved: ask first. */
    private var confirmLeave by mutableStateOf(false)

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
        // A translation kept from before the app was closed: finished, or to carry on.
        TranslationEngine.restore(this)
        ExportEngine.removeLeftovers(this)
        if (savedInstanceState == null) handleIncoming(intent)

        setContent {
            AppTheme {
                val state by TranslationEngine.state.collectAsStateWithLifecycle()
                val modelState by LanguageModels.state.collectAsStateWithLifecycle()
                val style by SubtitleStyleStore.style.collectAsStateWithLifecycle()
                val exportState by ExportEngine.state.collectAsStateWithLifecycle()
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    if (uri != null) {
                        keepAccess(uri)
                        request(uri)
                    }
                }
                // The same video again, when the app can no longer open it to carry on.
                val pickAgain = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    if (uri != null) {
                        keepAccess(uri)
                        askForNotificationsOnce()
                        TranslationEngine.resume(this@MainActivity, uri)
                    }
                }
                // The language model file, picked once from Downloads. Read once, copied in, checked.
                val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) LanguageModels.import(this@MainActivity, uri)
                }

                // Subtitles go only where the user chooses, through the system save dialog.
                val saveSrt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
                    val adopt = pendingIsSrt
                    pendingIsSrt = false
                    val leave = leaveAfterSave
                    leaveAfterSave = false
                    if (uri == null) {
                        pendingSave = null
                    } else if (writePending(uri)) {
                        // Corrections to a file's lines can now be saved into this one.
                        val w = watch
                        if (adopt && w != null && !w.fromJob) {
                            watch = w.copy(file = uri, fileName = displayName(uri), changed = false, savedOffsetMs = offsetMs)
                        }
                        if (leave) watch = null
                    }
                }
                val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                    if (uri != null) writePending(uri) else pendingSave = null
                }
                // Subtitles for a video already chosen (a shared one).
                val openSubtitles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    val video = subtitlesFor
                    subtitlesFor = null
                    if (uri != null && video != null) readSubtitles(uri)?.let { watchWith(video, it) }
                }
                // Subtitles first ("Open saved subtitles"), then the video they belong to.
                val loadSubtitles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) readSubtitles(uri)?.let { loaded = it }
                }
                val videoForLoaded = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    val subs = loaded
                    if (uri != null && subs != null) {
                        loaded = null
                        watchWith(uri, subs)
                    }
                }
                // The lines being watched: the job's own (corrections go back to it) or a file's.
                val watching = watch
                val lines = if (watching == null || watching.fromJob) state.lines else watching.lines
                val name = watch?.title ?: state.videoName
                val subtitleActions = SubtitleActions(
                    onWatch = {
                        state.videoUri?.let { watch = Watch(it, state.videoName, emptyList(), fromJob = true) }
                    },
                    onSaveSrt = {
                        pendingSave = SubtitleFiles.toSrt(lines, offsetMs)
                        pendingIsSrt = watching != null && !watching.fromJob
                        saveSrt.launch(SubtitleFiles.suggestedName(name, "srt"))
                    },
                    onSaveText = {
                        pendingSave = SubtitleFiles.toText(lines, offsetMs)
                        saveText.launch(SubtitleFiles.suggestedName(name, "txt"))
                    },
                    onOpenSaved = { loadSubtitles.launch(arrayOf("*/*")) },
                    onEditLine = { i, line ->
                        val w = watch
                        if (w == null || w.fromJob) {
                            TranslationEngine.editLine(i, line)
                        } else {
                            watch = w.copy(lines = w.lines.withLine(i, line), changed = true)
                        }
                    },
                    onRemoveLine = { i ->
                        val w = watch
                        if (w == null || w.fromJob) {
                            TranslationEngine.removeLine(i)
                        } else {
                            watch = w.copy(lines = w.lines.withoutLine(i), changed = true)
                        }
                    },
                )
                // Corrections to lines opened from a file live only in memory until saved.
                val unsaved = watching != null && !watching.fromJob && (watching.changed || offsetMs != watching.savedOffsetMs)
                val saveAsSrt = {
                    pendingSave = SubtitleFiles.toSrt(lines, offsetMs)
                    pendingIsSrt = true
                    saveSrt.launch(SubtitleFiles.suggestedName(name, "srt"))
                }
                // Saves into the opened .srt; without one (a transcript), or if that fails, asks where.
                fun saveChanges(thenLeave: Boolean) {
                    val w = watch ?: return
                    if (w.file != null && saveInto(w.file, SubtitleFiles.toSrt(w.lines, offsetMs), w.fileName)) {
                        watch = if (thenLeave) null else w.copy(changed = false, savedOffsetMs = offsetMs)
                    } else {
                        leaveAfterSave = thenLeave
                        saveAsSrt()
                    }
                }

                LaunchedEffect(state.phase, queued) {
                    val next = queued
                    if (next != null && state.phase != Phase.Working) {
                        queued = null
                        offsetMs = 0
                        TranslationEngine.start(this@MainActivity, next)
                    }
                }

                if (watching != null) {
                    PlayerScreen(
                        videoUri = watching.uri,
                        title = watching.title,
                        lines = lines,
                        spoken = if (watching.fromJob) state.spoken else null,
                        onEditLine = subtitleActions.onEditLine,
                        onRemoveLine = subtitleActions.onRemoveLine,
                        offsetMs = offsetMs,
                        onOffsetChange = { offsetMs = it },
                        style = style,
                        onStyleChange = { SubtitleStyleStore.set(this@MainActivity, it) },
                        onSaveSrt = subtitleActions.onSaveSrt,
                        onSaveText = subtitleActions.onSaveText,
                        unsaved = unsaved,
                        saveChangesTo = if (unsaved && watching.file != null) watching.fileName ?: "the subtitle file" else null,
                        onSaveChanges = { saveChanges(thenLeave = false) },
                        copy = CopyActions(
                            state = exportState,
                            onSave = {
                                askForNotificationsOnce()
                                ExportEngine.start(this@MainActivity, watching.uri, watching.title, lines, offsetMs, style)
                            },
                            onCancel = { ExportEngine.cancel() },
                            onOpen = { uri -> openVideo(uri) },
                            onDismiss = { ExportEngine.clear() },
                        ),
                        onBack = { if (unsaved) confirmLeave = true else watch = null },
                    )
                    if (confirmLeave) {
                        AlertDialog(
                            onDismissRequest = { confirmLeave = false },
                            title = { Text("Save your corrections?") },
                            text = {
                                Text(
                                    if (watching.file != null) {
                                        "Your corrections are not saved in ${watching.fileName ?: "the subtitle file"} yet."
                                    } else {
                                        "Your corrections are not saved yet. Save them as a subtitle file (.srt) to open " +
                                            "and correct again later."
                                    },
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmLeave = false
                                    saveChanges(thenLeave = true)
                                }) { Text("Save") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    confirmLeave = false
                                    watch = null
                                }) { Text("Don't save") }
                            },
                        )
                    }
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
                        jobActions = JobActions(
                            onCarryOn = {
                                askForNotificationsOnce()
                                TranslationEngine.resume(this@MainActivity)
                            },
                            onPickAgain = {
                                pickAgain.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                            },
                            onClose = {
                                offsetMs = 0
                                TranslationEngine.clear()
                            },
                        ),
                    )
                }

                loaded?.let { subs ->
                    AlertDialog(
                        // Stays until the video is chosen or the user forgets them: the video may be
                        // shared from Gallery meanwhile.
                        onDismissRequest = { },
                        title = { Text("Subtitles ready") },
                        text = {
                            Text(
                                "${subs.name ?: "The file"}: ${subs.opened.lines.size} lines. Now pick the video they " +
                                    "belong to, or share it to Video Translator from Gallery.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                videoForLoaded.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                            }) { Text("Pick the video") }
                        },
                        dismissButton = { TextButton(onClick = { loaded = null }) { Text("Forget them") } },
                    )
                }

                shared?.let { video ->
                    AlertDialog(
                        onDismissRequest = { shared = null },
                        title = { Text(video.name ?: "This video") },
                        text = {
                            Text(
                                "Translate it, or watch it with subtitles you already have (a .srt file or a " +
                                    "transcript with times)?" +
                                    if (state.phase != Phase.Idle && state.phase != Phase.Working) {
                                        "\n\nTranslating it replaces the translation the app has now; save its " +
                                            "subtitles first to keep them."
                                    } else {
                                        ""
                                    },
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                shared = null
                                request(video.uri)
                            }) { Text("Translate it") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                shared = null
                                subtitlesFor = video.uri
                                openSubtitles.launch(arrayOf("*/*"))
                            }) { Text("Add subtitles") }
                        },
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
        handleIncoming(intent)
    }

    /**
     * One item handed to the app: a video shared from Samsung Gallery (or any app), or a subtitle
     * file opened from My Files or shared. Videos are never copied; subtitles are read once into
     * memory.
     */
    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) return
        val uri = if (action == Intent.ACTION_VIEW) {
            intent.data
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        } ?: return
        val type = intent.type ?: (try { contentResolver.getType(uri) } catch (_: Exception) { null }) ?: ""
        if (type.startsWith("video/")) videoArrived(uri) else readSubtitles(uri)?.let { loaded = it }
    }

    /** A shared video: with subtitles waiting, it plays with them; else the user chooses. */
    private fun videoArrived(uri: Uri) {
        val subs = loaded
        if (subs != null) {
            loaded = null
            watchWith(uri, subs)
        } else {
            shared = SharedVideo(uri, displayName(uri))
        }
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

    /**
     * Keeps permission to read this one video after the app is closed, so a translation Android
     * stopped can carry on. Given back when the result is closed or another video is started.
     */
    private fun keepAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Not offered for this video: carrying on will ask for it to be picked again.
        }
    }

    /** Opens a saved copy in the phone's video player or gallery. */
    private fun openVideo(uri: Uri) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app to play it; find it in your gallery under Movies/${ExportEngine.FOLDER}.", Toast.LENGTH_LONG).show()
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
    private fun writePending(uri: Uri): Boolean {
        val text = pendingSave ?: return false
        pendingSave = null
        return saveInto(uri, text, null)
    }

    /** Writes [text] into the file [uri] (replacing what was in it). False if it could not. */
    private fun saveInto(uri: Uri, text: String, name: String?): Boolean = try {
        val out = contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("no file")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        Toast.makeText(this, if (name != null) "Saved in $name." else "Saved.", Toast.LENGTH_SHORT).show()
        true
    } catch (e: Exception) {
        Toast.makeText(this, "Could not save: ${e.message}", Toast.LENGTH_LONG).show()
        false
    }

    /** Reads a subtitle file or a transcript with times once into memory; null (and says why) if it cannot. */
    private fun readSubtitles(uri: Uri): LoadedSubtitles? {
        val opened = try {
            contentResolver.openInputStream(uri)?.use { input -> SubtitleFiles.read(input.readNBytes(MAX_SUBTITLE_BYTES)) }
        } catch (e: Exception) {
            null
        }
        if (opened == null) {
            Toast.makeText(
                this,
                "No times were found in that file, so the app cannot tell when each line is said. It opens subtitles " +
                    "(.srt, .vtt) and transcripts with a time at each line or paragraph, like 01:23 or [00:01:23].",
                Toast.LENGTH_LONG,
            ).show()
            return null
        }
        return LoadedSubtitles(opened, uri, displayName(uri))
    }

    /** Plays [video] with [subs]. Corrections can be saved back into a .srt the app may write to. */
    private fun watchWith(video: Uri, subs: LoadedSubtitles) {
        val opened = subs.opened
        var lines = opened.lines
        // Times past the end of the video are clock times (a meeting's "10:00"): start at 0 instead.
        val duration = TranslationEngine.durationOf(this, video)
        val first = lines.first().startMs
        if (duration != null && duration > 0 && first >= duration) {
            lines = lines.map { it.copy(startMs = it.startMs - first, endMs = it.endMs - first) }
            Toast.makeText(
                this,
                "The times in that file start after the end of the video (${SubtitleFiles.clock(first)}), so they look " +
                    "like clock times: the first line now starts with the video. Use Subtitle timing to move them all.",
                Toast.LENGTH_LONG,
            ).show()
        } else if (opened.kind == SubtitleKind.Transcript) {
            Toast.makeText(this, "${lines.size} lines placed at the times in the transcript.", Toast.LENGTH_SHORT).show()
        }
        offsetMs = 0
        // A file picked in the app can usually be written to; one opened from a file manager may
        // only be readable, and then corrections are saved as a new .srt (Save subtitles).
        val writable = opened.canSaveBack &&
            checkCallingOrSelfUriPermission(subs.uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        watch = Watch(
            video,
            displayName(video),
            lines,
            file = if (writable) subs.uri else null,
            fileName = if (writable) subs.name else null,
        )
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        // A Word document with pictures in it can be large; its text is a small part.
        const val MAX_SUBTITLE_BYTES = 64 shl 20
    }
}
