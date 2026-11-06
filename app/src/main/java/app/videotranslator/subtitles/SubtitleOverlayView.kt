package app.videotranslator.subtitles

import android.content.Context
import android.graphics.Canvas
import android.view.View
import androidx.media3.common.Player

/**
 * Draws the current subtitle over the video picture, with [SubtitleRenderer]. It sits inside the
 * player's content frame, which is exactly the size of the picture, so the subtitles are placed
 * on the video as they would be in a saved copy, and under the player's controls.
 */
class SubtitleOverlayView(context: Context) : View(context) {
    var player: Player? = null
    var cues: List<Cue> = emptyList()
    var offsetMs: Long = 0
    var style: SubtitleStyle = SubtitleStyle()

    /** While the look is being changed: show the nearest line when nothing is said right now. */
    var preview: Boolean = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = player ?: return
        val position = p.currentPosition
        val text = cueAt(cues, position, offsetMs)?.text
            ?: if (preview) nearestCue(cues, position, offsetMs)?.text ?: SAMPLE else null
        if (text != null) SubtitleRenderer.draw(canvas, width, height, text, style)
        // Follow the video frame by frame while it plays; a pause, seek or change redraws once.
        if (p.isPlaying) postInvalidateOnAnimation()
    }

    companion object {
        const val SAMPLE = "This is how your subtitles\nwill look on the video."
    }
}
