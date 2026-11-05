package app.videotranslator.ui

import java.util.Locale

/** 75_300 -> "1:15", 3_725_000 -> "1:02:05". */
fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/** 192_400 -> "3 min 12 s", 8_000 -> "8 s", 3_900_000 -> "1 h 5 min". */
fun formatDuration(ms: Long): String {
    val total = (ms.coerceAtLeast(0) + 500) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> if (m > 0) "$h h $m min" else "$h h"
        m > 0 -> if (s > 0) "$m min $s s" else "$m min"
        else -> "$s s"
    }
}

/** "ru" + "russian" -> "Russian". Uses Android's English name for the code when it has one. */
fun languageDisplayName(code: String?, whisperName: String?): String? {
    if (code.isNullOrBlank()) return whisperName?.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
    val fromLocale = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
    return if (fromLocale.isNotBlank() && !fromLocale.equals(code, ignoreCase = true)) fromLocale
    else whisperName?.replaceFirstChar { it.titlecase(Locale.ENGLISH) } ?: code
}
