package app.videotranslator.ui

import android.content.res.Configuration
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.videotranslator.subtitles.SubtitleEdge
import app.videotranslator.subtitles.SubtitleFont
import app.videotranslator.subtitles.SubtitleOverlayView
import app.videotranslator.subtitles.SubtitleStyle
import app.videotranslator.subtitles.toCues
import app.videotranslator.whisper.Segment
import java.util.Locale
import android.graphics.Color as AndroidColor

/** Plays the video the user picked (no copy is made) with the subtitles drawn over it. */
@Composable
fun PlayerScreen(
    videoUri: Uri,
    title: String?,
    lines: List<Segment>,
    offsetMs: Long,
    onOffsetChange: (Long) -> Unit,
    style: SubtitleStyle,
    onStyleChange: (SubtitleStyle) -> Unit,
    onSaveSrt: () -> Unit,
    onSaveText: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val cues = remember(lines) { toCues(lines) }
    var styling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val overlay = remember { SubtitleOverlayView(context) }
    val player = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = overlay.invalidate()
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) = overlay.invalidate()
            override fun onPlaybackStateChanged(playbackState: Int) = overlay.invalidate()
            override fun onPlayerError(e: PlaybackException) {
                error = "This video could not be played here (error ${e.errorCode})."
            }
        }
        player.addListener(listener)
        overlay.player = player
        onDispose {
            player.removeListener(listener)
            overlay.player = null
            player.release()
        }
    }

    // Pause when the app leaves the screen; keep the screen on while this is open.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    BackHandler { if (styling) styling = false else onBack() }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            if (!landscape) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { if (styling) styling = false else onBack() }) { Text("Back") }
                    Text(
                        title ?: "Video",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            // Inside the content frame, which is the size of the picture.
                            val frame = findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame)
                            (overlay.parent as? ViewGroup)?.removeView(overlay)
                            frame.addView(
                                overlay,
                                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                            )
                        }
                    },
                    update = { playerView ->
                        playerView.player = player
                        overlay.cues = cues
                        overlay.offsetMs = offsetMs
                        overlay.style = style
                        overlay.preview = styling
                        overlay.invalidate()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!landscape) {
                error?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (styling) {
                    StylePanel(style, onStyleChange, onDone = { styling = false }, Modifier.weight(1f))
                } else {
                    Controls(
                        lineCount = lines.size,
                        offsetMs = offsetMs,
                        onOffsetChange = onOffsetChange,
                        onStyle = { styling = true },
                        onSaveSrt = onSaveSrt,
                        onSaveText = onSaveText,
                    )
                }
            }
        }
    }
}

@Composable
private fun Controls(
    lineCount: Int,
    offsetMs: Long,
    onOffsetChange: (Long) -> Unit,
    onStyle: () -> Unit,
    onSaveSrt: () -> Unit,
    onSaveText: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Subtitle timing: ${offsetLabel(offsetMs)}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "If every line comes too late, move them all earlier (and the other way round).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { onOffsetChange(offsetMs - 1000) }) { Text("-1 s") }
            OutlinedButton(onClick = { onOffsetChange(offsetMs - 100) }) { Text("-0.1") }
            OutlinedButton(onClick = { onOffsetChange(offsetMs + 100) }) { Text("+0.1") }
            OutlinedButton(onClick = { onOffsetChange(offsetMs + 1000) }) { Text("+1 s") }
        }
        if (offsetMs != 0L) TextButton(onClick = { onOffsetChange(0) }) { Text("Back to Whisper's timing") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onStyle) { Text("Change how the subtitles look") }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onSaveSrt) { Text("Save subtitles (.srt)") }
            TextButton(onClick = onSaveText) { Text("Save text") }
        }
        Text(
            "$lineCount lines. Saved subtitles can be opened again later with the same video, " +
                "without running Whisper again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun offsetLabel(ms: Long): String = when {
    ms == 0L -> "as Whisper placed them"
    ms < 0 -> String.format(Locale.US, "%.1f s earlier", -ms / 1000.0)
    else -> String.format(Locale.US, "%.1f s later", ms / 1000.0)
}

private val TEXT_COLORS = listOf(
    AndroidColor.WHITE, 0xFFFFEB3B.toInt(), 0xFF80DEEA.toInt(), 0xFFA5D6A7.toInt(), 0xFFF8BBD0.toInt(), AndroidColor.BLACK,
)
private val EDGE_COLORS = listOf(AndroidColor.BLACK, 0xFF424242.toInt(), AndroidColor.WHITE)
private val BOX_COLORS = listOf(AndroidColor.BLACK, 0xFF424242.toInt(), AndroidColor.WHITE, 0xFF0D47A1.toInt())

/**
 * The look of the subtitles, changed while the video stays in view above: every change shows on
 * the video at once (the nearest line is shown when nothing is being said).
 */
@Composable
private fun StylePanel(
    style: SubtitleStyle,
    onChange: (SubtitleStyle) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("How the subtitles look", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { onChange(SubtitleStyle()) }) { Text("Reset") }
            Button(onClick = onDone) { Text("Done") }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Label("Size")
            Slider(
                value = style.sizeFraction,
                onValueChange = { onChange(style.copy(sizeFraction = it)) },
                valueRange = SubtitleStyle.MIN_SIZE..SubtitleStyle.MAX_SIZE,
            )
            Label("Height on the picture")
            Slider(
                value = style.bottomFraction,
                onValueChange = { onChange(style.copy(bottomFraction = it)) },
                valueRange = SubtitleStyle.MIN_BOTTOM..SubtitleStyle.MAX_BOTTOM,
            )
            Label("Text colour")
            Swatches(TEXT_COLORS, style.textColor) { onChange(style.copy(textColor = it)) }
            Spacer(Modifier.height(8.dp))
            Label("Font")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SubtitleFont.entries.forEach { f ->
                    FilterChip(selected = style.font == f, onClick = { onChange(style.copy(font = f)) }, label = { Text(f.label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bold", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = style.bold, onCheckedChange = { onChange(style.copy(bold = it)) })
            }
            Label("Edge around the letters")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SubtitleEdge.entries.forEach { e ->
                    FilterChip(selected = style.edge == e, onClick = { onChange(style.copy(edge = e)) }, label = { Text(e.label) })
                }
            }
            if (style.edge != SubtitleEdge.None) {
                Spacer(Modifier.height(4.dp))
                Swatches(EDGE_COLORS, style.edgeColor) { onChange(style.copy(edgeColor = it)) }
            }
            Spacer(Modifier.height(8.dp))
            Label("Box behind the text: " + if (style.boxOpacity <= 0f) "none" else "${(style.boxOpacity * 100).toInt()}% solid")
            Slider(
                value = style.boxOpacity,
                onValueChange = { onChange(style.copy(boxOpacity = if (it < 0.03f) 0f else it)) },
                valueRange = 0f..1f,
            )
            if (style.boxOpacity > 0f) {
                Swatches(BOX_COLORS, style.boxColor) { onChange(style.copy(boxColor = it)) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Swatches(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        colors.forEach { c ->
            val isSelected = (c or 0xFF000000.toInt()) == (selected or 0xFF000000.toInt())
            Box(
                Modifier
                    .size(36.dp)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    )
                    .padding(4.dp)
                    .background(Color(c), CircleShape)
                    .clickable { onPick(c) },
            )
        }
        Spacer(Modifier.width(4.dp))
    }
}
