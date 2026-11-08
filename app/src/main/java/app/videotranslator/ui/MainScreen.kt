package app.videotranslator.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.videotranslator.llm.KnownModel
import app.videotranslator.llm.LanguageModelState
import app.videotranslator.llm.LanguageModels
import app.videotranslator.engine.JobState
import app.videotranslator.engine.Phase
import app.videotranslator.engine.Stage
import app.videotranslator.whisper.BUNDLED_MODEL_NAME
import app.videotranslator.whisper.Segment
import kotlinx.coroutines.delay

/** What can be done with the subtitles of the video on screen. */
class SubtitleActions(
    val onWatch: () -> Unit,
    val onSaveSrt: () -> Unit,
    val onSaveText: () -> Unit,
    /** Pick a video, then the subtitles saved for it earlier, and watch them together. */
    val onOpenSaved: () -> Unit,
    /** The user corrected or removed a line of the finished result. */
    val onEditLine: (Int, Segment) -> Unit,
    val onRemoveLine: (Int) -> Unit,
)

/** A translation that stopped before the end, and the result itself. */
class JobActions(
    /** Carry on from where it stopped. */
    val onCarryOn: () -> Unit,
    /** Pick the same video again, when the app can no longer open it, and carry on. */
    val onPickAgain: () -> Unit,
    /** Forget this result (and the kept copy of it) and go back to the start screen. */
    val onClose: () -> Unit,
)

/** What the start screen's language-model section can ask for. */
class ModelActions(
    val onDownload: (KnownModel) -> Unit,
    val onLoad: () -> Unit,
    val onCancelLoad: () -> Unit,
    val onRemove: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: JobState,
    modelState: LanguageModelState,
    onPick: () -> Unit,
    onCancel: () -> Unit,
    modelActions: ModelActions,
    subtitleActions: SubtitleActions,
    jobActions: JobActions,
) {
    val working = state.phase == Phase.Working
    val importing = modelState.importing != null

    // Keep the screen on while it works: Samsung slows or stops apps whose screen goes off.
    val view = LocalView.current
    DisposableEffect(working, importing) {
        view.keepScreenOn = working || importing
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Video Translator") }) },
    ) { padding ->
        if (state.phase == Phase.Idle) {
            StartScreen(Modifier.padding(padding), modelState, onPick, modelActions, subtitleActions)
        } else {
            JobScreen(Modifier.padding(padding), state, onPick, onCancel, subtitleActions, jobActions)
        }
    }
}

@Composable
private fun StartScreen(
    modifier: Modifier,
    modelState: LanguageModelState,
    onPick: () -> Unit,
    actions: ModelActions,
    subtitleActions: SubtitleActions,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "English subtitles for your videos, worked out on this phone.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Nothing is sent anywhere: the app has no internet permission.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onPick, enabled = modelState.importing == null) { Text("Pick a video") }
        Spacer(Modifier.height(16.dp))
        Text(
            "Or in Gallery, open a video, tap Share and choose Video Translator.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = subtitleActions.onOpenSaved, enabled = modelState.importing == null) {
            Text("Watch a video with subtitles you saved")
        }
        Spacer(Modifier.height(16.dp))
        LanguageModelCard(modelState, actions)
        Spacer(Modifier.height(16.dp))
        Text(
            "Whisper $BUNDLED_MODEL_NAME" + (modelState.installed?.let { " + ${it.name}" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LanguageModelCard(state: LanguageModelState, actions: ModelActions) {
    val recommended = LanguageModels.RECOMMENDED
    val smaller = LanguageModels.KNOWN.first { it != recommended }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Better English (optional)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            val importing = state.importing
            val installed = state.installed
            when {
                importing != null -> {
                    Text(
                        "Checking and copying the language model… ${(importing * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { importing }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = actions.onCancelLoad) { Text("Stop") }
                }
                installed != null -> {
                    Text(
                        "${installed.name} is loaded. Japanese, Russian and other non-English videos are " +
                            "written down by Whisper first, then translated to English by ${installed.name}, " +
                            "which reads the lines around each one.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = actions.onRemove) { Text("Remove it (frees ${LanguageModels.gb(installed.bytes)})") }
                }
                else -> {
                    Text(
                        "A language model makes Japanese and Russian come out as more natural, accurate " +
                            "English. Download it once in your browser (the app itself never goes online), " +
                            "then load the downloaded file here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { actions.onDownload(recommended) }) {
                        Text("1. Download ${recommended.name} (${LanguageModels.gb(recommended.bytes)})")
                    }
                    TextButton(onClick = { actions.onDownload(smaller) }) {
                        Text("or the smaller, faster ${smaller.name} (${LanguageModels.gb(smaller.bytes)})")
                    }
                    Button(onClick = actions.onLoad) { Text("2. Load the downloaded file") }
                }
            }
            state.message?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun JobScreen(
    modifier: Modifier,
    state: JobState,
    onPick: () -> Unit,
    onCancel: () -> Unit,
    subtitleActions: SubtitleActions,
    jobActions: JobActions,
) {
    val working = state.phase == Phase.Working
    var confirmClose by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    if (working) {
        LaunchedEffect(state.startedAtMs) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(1000)
            }
        }
    }
    val elapsedMs = (state.finishedAtMs ?: now) - state.startedAtMs

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        state.videoName ?: "Video",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.durationMs?.let {
                        Text("Length ${formatClock(it)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    languageDisplayName(state.languageCode, state.languageName)?.let { language ->
                        val note = when {
                            state.languageCode == "en" -> " (an English transcript)"
                            state.languageModel != null -> " (translated to English by ${state.languageModel})"
                            else -> " (translated to English)"
                        }
                        Text("Spoken language: $language$note", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            StatusLine(state, elapsedMs)
            if (working) {
                Spacer(Modifier.height(8.dp))
                if (state.stage == Stage.LoadingModel || state.stage == Stage.LoadingLanguageModel || state.stopping) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (working) {
                    OutlinedButton(onClick = onCancel, enabled = !state.stopping) { Text("Cancel") }
                } else {
                    Button(onClick = onPick) { Text("Pick another video") }
                }
                Spacer(Modifier.width(16.dp))
                if (state.lines.isNotEmpty()) {
                    Text("${state.lines.size} lines", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!working && state.lines.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                if (state.videoUri != null) {
                    Button(onClick = subtitleActions.onWatch) { Text("Watch with subtitles") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = subtitleActions.onSaveSrt) { Text("Save subtitles (.srt)") }
                    TextButton(onClick = subtitleActions.onSaveText) { Text("Save text") }
                }
            }
            if (state.canCarryOn) {
                Spacer(Modifier.height(8.dp))
                if (state.needsVideoAgain) {
                    Button(onClick = jobActions.onPickAgain) { Text("Pick the same video again") }
                } else {
                    Button(onClick = jobActions.onCarryOn) { Text("Carry on from where it stopped") }
                }
            }
            if (!working) {
                // Also here, not only on the first screen: after a translation the app stays on this one.
                TextButton(onClick = subtitleActions.onOpenSaved) { Text("Watch a video with subtitles you saved") }
                TextButton(onClick = { confirmClose = true }) { Text("Close this result") }
            }
        }
        if (confirmClose) {
            AlertDialog(
                onDismissRequest = { confirmClose = false },
                title = { Text("Close this result?") },
                text = {
                    Text(
                        "These lines and any corrections are forgotten, and the app goes back to the start. " +
                            "To keep them, save the subtitles (.srt) or a copy of the video first.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClose = false
                        jobActions.onClose()
                    }) { Text("Close") }
                },
                dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep it") } },
            )
        }
        HorizontalDivider()
        Lines(
            state.lines, state.spoken, working, state.languageModel != null, Modifier.weight(1f),
            onEdit = if (working) null else subtitleActions,
        )
    }
}

@Composable
private fun StatusLine(state: JobState, elapsedMs: Long) {
    val percent = (state.progress * 100).toInt().coerceIn(0, 100)
    val text = when (state.phase) {
        Phase.Working -> {
            val doing = when {
                state.stopping -> "Stopping…"
                state.stage == Stage.ReadingAudio -> "Reading the sound… $percent%"
                state.stage == Stage.LoadingModel -> "Loading Whisper…"
                state.stage == Stage.Whisper && state.languageModel != null -> "Writing down what is said… $percent%"
                state.stage == Stage.Whisper -> "Translating… $percent%"
                state.stage == Stage.LoadingLanguageModel -> "Loading ${state.languageModel}…"
                state.stage == Stage.LanguageModel -> "Translating into English with ${state.languageModel}… $percent%"
                else -> "Working… $percent%"
            }
            "$doing   (${formatDuration(elapsedMs)} so far)"
        }
        Phase.Done -> buildString {
            append("Done in ${formatDuration(elapsedMs)}.")
            state.timings?.let {
                append(" Sound ${formatDuration(it.readAudioMs)}, loading Whisper ${formatDuration(it.loadModelMs)}, ")
                append("Whisper $BUNDLED_MODEL_NAME ${formatDuration(it.whisperMs)}")
                if (state.translatedByLanguageModel) {
                    append(", loading ${state.languageModel} ${formatDuration(it.loadLanguageModelMs)}, ")
                    append("${state.languageModel} ${formatDuration(it.languageModelMs)}")
                }
                append(".")
            }
            if (state.lines.isEmpty()) append(" No speech was found.")
        }
        Phase.Cancelled -> when {
            state.error != null -> state.error
            state.restored -> stoppedAt(state) +
                " The app was closed before it finished (Android allows long work in the background only " +
                "for a while). What was done is kept below; carry on to finish it."
            else -> {
                val more = if (state.lines.isNotEmpty()) " The lines so far are below." else ""
                "Cancelled after ${formatDuration(elapsedMs)}.$more"
            }
        }
        Phase.Failed -> state.error ?: "Something went wrong."
        Phase.Idle -> ""
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.phase == Phase.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

/** "Stopped at 12:34 of 23:58." */
private fun stoppedAt(state: JobState): String {
    val of = state.durationMs?.let { " of ${formatClock(it)}" } ?: ""
    return if (state.whisperDone) "Stopped while translating into English." else "Stopped at ${formatClock(state.reachedMs)}$of."
}

@Composable
private fun Lines(
    lines: List<Segment>,
    spoken: List<Segment>,
    working: Boolean,
    twoSteps: Boolean,
    modifier: Modifier,
    /** When done: tapping a line opens it to be corrected. */
    onEdit: SubtitleActions?,
) {
    val listState = rememberLazyListState()
    var editing by remember { mutableStateOf<Int?>(null) }
    // Follow new lines as they arrive, unless the user has scrolled up to read.
    val atEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && atEnd) listState.animateScrollToItem(lines.size - 1)
    }
    if (lines.isEmpty()) {
        Text(
            when {
                !working -> ""
                twoSteps -> "The lines appear here as they are worked out: first as spoken, then in English."
                else -> "The English lines appear here as they are worked out."
            },
            modifier = modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (onEdit != null) {
        Text(
            "Tap a line to correct its words or its timing.",
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    LazyColumn(modifier = modifier.fillMaxWidth(), state = listState, contentPadding = PaddingValues(8.dp)) {
        itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
            LineRow(
                line = line,
                // While the language model works, a line is shown as said until its English arrives.
                said = if (working) null else spokenFor(spoken, lines, i),
                highlighted = false,
                onClick = if (onEdit != null) ({ editing = i }) else null,
            )
        }
    }
    val i = editing
    val line = i?.let { lines.getOrNull(it) }
    if (onEdit != null && i != null && line != null) {
        LineEditDialog(
            line = line,
            said = spokenFor(spoken, lines, i),
            onSave = {
                onEdit.onEditLine(i, it)
                editing = null
            },
            onRemove = {
                onEdit.onRemoveLine(i)
                editing = null
            },
            onPlay = null,
            onDismiss = { editing = null },
        )
    }
}

@Composable
fun BusyDialog(currentName: String?, onReplace: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Already translating") },
        text = {
            Text("Stop translating ${currentName ?: "the current video"} and start the new one?")
        },
        confirmButton = { TextButton(onClick = onReplace) { Text("Stop and start new") } },
        dismissButton = { TextButton(onClick = onKeep) { Text("Keep going") } },
    )
}
