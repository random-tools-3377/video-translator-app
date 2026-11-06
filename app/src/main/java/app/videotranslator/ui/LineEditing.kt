package app.videotranslator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.videotranslator.whisper.Segment
import java.util.Locale

/** The shortest a line may last after nudging its start or end. */
private const val MIN_LINE_MS = 200L

/** 83_400 -> "1:23.4". */
fun formatTenths(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 100
    val tenths = t % 10
    val s = (t / 10) % 60
    val m = (t / 600) % 60
    val h = t / 36000
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d.%d", h, m, s, tenths)
    else String.format(Locale.US, "%d:%02d.%d", m, s, tenths)
}

/** The original words of line [i], when they differ from the English shown (translated lines). */
fun spokenFor(spoken: List<Segment>?, lines: List<Segment>, i: Int): String? {
    val said = spoken?.getOrNull(i)?.text?.trim() ?: return null
    return said.takeIf { it.isNotEmpty() && it != lines.getOrNull(i)?.text?.trim() }
}

/**
 * Every line with its time, to read through and tap one to change it. [current] (the line being
 * said, or -1) is highlighted and kept in view while the video plays.
 */
@Composable
fun LinesPanel(
    lines: List<Segment>,
    spoken: List<Segment>?,
    current: Int,
    onTap: (Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) {
            val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
            if (current !in visible) listState.animateScrollToItem(current)
        }
    }
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("The words", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Tap a line to hear it and change it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onDone) { Text("Done") }
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
                LineRow(
                    line = line,
                    said = spokenFor(spoken, lines, i),
                    highlighted = i == current,
                    onClick = { onTap(i) },
                )
            }
        }
    }
}

/** One line in a list: its time, the English, and what was said when it was translated. */
@Composable
fun LineRow(line: Segment, said: String?, highlighted: Boolean, onClick: (() -> Unit)?) {
    val base = Modifier
        .fillMaxWidth()
        .then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 8.dp, vertical = 6.dp)
    Column(base) {
        Text(
            "${formatTenths(line.startMs)} – ${formatTenths(line.endMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(line.text, style = MaterialTheme.typography.bodyLarge)
        if (said != null) {
            Text(said, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Changes one line: its words, and its start and end in tenths of a second (for the line Whisper
 * placed badly). [onPlay], when there is a video, saves and plays from just before the line.
 */
@Composable
fun LineEditDialog(
    line: Segment,
    said: String?,
    onSave: (Segment) -> Unit,
    onRemove: () -> Unit,
    onPlay: ((Segment) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var text by remember(line) { mutableStateOf(line.text.trim()) }
    var start by remember(line) { mutableLongStateOf(line.startMs) }
    var end by remember(line) { mutableLongStateOf(line.endMs) }
    var confirmRemove by remember(line) { mutableStateOf(false) }
    fun edited() = Segment(start, maxOf(end, start + MIN_LINE_MS), text.trim())

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove this line?") },
            text = { Text("It will not be shown, saved or burned into a copy. This cannot be undone.") },
            confirmButton = { TextButton(onClick = onRemove) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep it") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Line at ${formatTenths(line.startMs)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Words") },
                )
                if (said != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "As said: $said",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Nudge("Starts", start) { delta ->
                    start = (start + delta).coerceIn(0L, end - MIN_LINE_MS)
                }
                Nudge("Ends", end) { delta ->
                    end = (end + delta).coerceAtLeast(start + MIN_LINE_MS)
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { confirmRemove = true }) { Text("Remove this line") }
            }
        },
        confirmButton = {
            Row {
                if (onPlay != null) {
                    TextButton(onClick = { onPlay(edited()) }, enabled = text.isNotBlank()) { Text("Save and play") }
                }
                TextButton(onClick = { onSave(edited()) }, enabled = text.isNotBlank()) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Nudge(label: String, ms: Long, onNudge: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label ${formatTenths(ms)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
        OutlinedButton(onClick = { onNudge(-100) }) { Text("-0.1") }
        OutlinedButton(onClick = { onNudge(100) }) { Text("+0.1") }
    }
}

/** These lines with line [i] replaced. */
fun List<Segment>.withLine(i: Int, line: Segment): List<Segment> =
    if (i !in indices) this else toMutableList().also { it[i] = line }

/** These lines without line [i]. */
fun List<Segment>.withoutLine(i: Int): List<Segment> =
    if (i !in indices) this else toMutableList().also { it.removeAt(i) }
