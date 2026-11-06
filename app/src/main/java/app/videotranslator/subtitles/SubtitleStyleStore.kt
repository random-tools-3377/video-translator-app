package app.videotranslator.subtitles

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The subtitle style, remembered for the next video. One of only two things the app keeps by
 * itself (the other is the language model file); it says nothing about any video. App backup is
 * off, so it stays on the phone.
 */
object SubtitleStyleStore {
    private const val PREFS = "subtitle_style"

    private val _style = MutableStateFlow(SubtitleStyle())
    val style: StateFlow<SubtitleStyle> = _style.asStateFlow()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = SubtitleStyle()
        _style.value = SubtitleStyle(
            sizeFraction = p.getFloat("size", d.sizeFraction).coerceIn(SubtitleStyle.MIN_SIZE, SubtitleStyle.MAX_SIZE),
            bottomFraction = p.getFloat("bottom", d.bottomFraction).coerceIn(SubtitleStyle.MIN_BOTTOM, SubtitleStyle.MAX_BOTTOM),
            textColor = p.getInt("textColor", d.textColor),
            font = enumOr(p.getString("font", null), d.font),
            bold = p.getBoolean("bold", d.bold),
            edge = enumOr(p.getString("edge", null), d.edge),
            edgeColor = p.getInt("edgeColor", d.edgeColor),
            boxColor = p.getInt("boxColor", d.boxColor),
            boxOpacity = p.getFloat("boxOpacity", d.boxOpacity).coerceIn(0f, 1f),
        )
    }

    fun set(context: Context, style: SubtitleStyle) {
        _style.value = style
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("size", style.sizeFraction)
            .putFloat("bottom", style.bottomFraction)
            .putInt("textColor", style.textColor)
            .putString("font", style.font.name)
            .putBoolean("bold", style.bold)
            .putString("edge", style.edge.name)
            .putInt("edgeColor", style.edgeColor)
            .putInt("boxColor", style.boxColor)
            .putFloat("boxOpacity", style.boxOpacity)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
