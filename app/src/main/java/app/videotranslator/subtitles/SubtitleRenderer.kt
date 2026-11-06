package app.videotranslator.subtitles

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.min

enum class SubtitleFont(val label: String) { Sans("Sans"), Serif("Serif"), Narrow("Narrow"), Mono("Mono") }

enum class SubtitleEdge(val label: String) { None("None"), Outline("Outline"), Shadow("Shadow") }

/**
 * How subtitles look. Sizes are fractions of the video picture, not of the screen, so the in-app
 * preview and a saved copy with the subtitles burned in (drawn by the same [SubtitleRenderer])
 * look the same at any resolution. Remembered for the next video ([SubtitleStyleStore]).
 */
data class SubtitleStyle(
    /** Text height as a fraction of the picture's shorter side. */
    val sizeFraction: Float = 0.055f,
    /** Gap between the bottom of the picture and the last line, as a fraction of its height. */
    val bottomFraction: Float = 0.07f,
    val textColor: Int = Color.WHITE,
    val font: SubtitleFont = SubtitleFont.Sans,
    val bold: Boolean = true,
    val edge: SubtitleEdge = SubtitleEdge.Outline,
    val edgeColor: Int = Color.BLACK,
    /** Box behind each line: its colour (alpha ignored) and opacity, 0 for no box. */
    val boxColor: Int = Color.BLACK,
    val boxOpacity: Float = 0f,
) {
    companion object {
        const val MIN_SIZE = 0.03f
        const val MAX_SIZE = 0.10f
        const val MIN_BOTTOM = 0.02f
        const val MAX_BOTTOM = 0.85f
    }
}

/**
 * The one place subtitles are drawn: onto the video in the player now, and onto each frame of a
 * saved copy later. Never a second renderer, so the two cannot drift apart.
 */
object SubtitleRenderer {

    /** Draws [text] (rows separated by '\n') onto a picture [width] x [height] pixels. */
    fun draw(canvas: Canvas, width: Int, height: Int, text: String, style: SubtitleStyle) {
        if (text.isBlank() || width <= 0 || height <= 0) return
        val textSize = (style.sizeFraction * min(width, height)).coerceAtLeast(8f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            typeface = typeface(style)
            color = style.textColor
        }
        val maxWidth = (width * 0.92f).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
        val left = (width - maxWidth) / 2f
        val top = (height * (1f - style.bottomFraction) - layout.height).coerceAtLeast(0f)

        canvas.save()
        canvas.translate(left, top)

        if (style.boxOpacity > 0f) {
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = style.boxColor
                alpha = (style.boxOpacity.coerceIn(0f, 1f) * 255).toInt()
            }
            val padX = textSize * 0.35f
            val padY = textSize * 0.12f
            val rect = RectF()
            for (i in 0 until layout.lineCount) {
                rect.set(
                    layout.getLineLeft(i) - padX, layout.getLineTop(i) - padY,
                    layout.getLineRight(i) + padX, layout.getLineBottom(i) + padY,
                )
                canvas.drawRoundRect(rect, textSize * 0.15f, textSize * 0.15f, box)
            }
        }

        when (style.edge) {
            SubtitleEdge.Outline -> {
                // The layout keeps a reference to its paint: draw it once as an outline, then filled.
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = textSize * 0.14f
                paint.color = style.edgeColor
                layout.draw(canvas)
                paint.style = Paint.Style.FILL
                paint.color = style.textColor
            }
            SubtitleEdge.Shadow -> paint.setShadowLayer(textSize * 0.12f, textSize * 0.05f, textSize * 0.07f, style.edgeColor)
            SubtitleEdge.None -> Unit
        }
        layout.draw(canvas)
        canvas.restore()
    }

    private fun typeface(style: SubtitleStyle): Typeface {
        val family = when (style.font) {
            SubtitleFont.Sans -> "sans-serif"
            SubtitleFont.Serif -> "serif"
            SubtitleFont.Narrow -> "sans-serif-condensed"
            SubtitleFont.Mono -> "monospace"
        }
        return Typeface.create(family, if (style.bold) Typeface.BOLD else Typeface.NORMAL)
    }
}
