package app.videotranslator.subtitles

import app.videotranslator.whisper.Segment

/** One subtitle as shown: up to [MAX_LINES] lines of up to about [MAX_LINE_CHARS] characters. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** About 42 characters a line, as real subtitles: a person can read it in the time it shows. */
const val MAX_LINE_CHARS = 42

/** At most two lines on screen at once. */
const val MAX_LINES = 2

/**
 * Whisper's lines (and the language model's English lines, which keep Whisper's times) can run
 * long. Each becomes one or more cues of at most two short lines. A line that needs more than one
 * cue has its time shared out by length, so each part shows for as long as it takes to say; the
 * English has no timing of its own inside a line, so this is an estimate, as Whisper's line times
 * themselves are (usually within about half a second).
 */
fun toCues(lines: List<Segment>): List<Cue> {
    val cues = ArrayList<Cue>()
    for (line in lines) {
        val rows = wrap(line.text)
        if (rows.isEmpty()) continue
        val groups = rows.chunked(MAX_LINES)
        if (groups.size == 1) {
            cues += Cue(line.startMs, maxOf(line.endMs, line.startMs), groups[0].joinToString("\n"))
            continue
        }
        val weights = groups.map { g -> g.sumOf { displayWidth(it) }.coerceAtLeast(1) }
        val total = weights.sum().toDouble()
        val span = (line.endMs - line.startMs).coerceAtLeast(0)
        var start = line.startMs
        var done = 0
        for ((i, g) in groups.withIndex()) {
            done += weights[i]
            val end = if (i == groups.lastIndex) line.endMs else line.startMs + Math.round(span * (done / total))
            cues += Cue(start, maxOf(end, start), g.joinToString("\n"))
            start = end
        }
    }
    cues.sortBy { it.startMs }  // stable: lines that start together keep their order
    return cues
}

/** The cue to show at [positionMs], with every cue moved by [offsetMs] (later when positive). */
fun cueAt(cues: List<Cue>, positionMs: Long, offsetMs: Long = 0): Cue? =
    cueIndexAt(cues, positionMs, offsetMs).let { if (it < 0) null else cues[it] }

/** The index of [cueAt]'s cue in [cues], or -1 when nothing is shown at [positionMs]. */
fun cueIndexAt(cues: List<Cue>, positionMs: Long, offsetMs: Long = 0): Int {
    val t = positionMs - offsetMs
    // Binary search on start times: the last cue that starts at or before t.
    var lo = 0
    var hi = cues.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (cues[mid].startMs <= t) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    if (found < 0) return -1
    return if (t < cues[found].endMs) found else -1
}

/**
 * Breaks [text] into rows of at most [MAX_LINE_CHARS] (counting East Asian characters twice, as
 * they are about twice as wide), at spaces where there are any. A word longer than a row is cut.
 */
internal fun wrap(text: String): List<String> {
    val words = text.replace('\n', ' ').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val rows = ArrayList<String>()
    val row = StringBuilder()
    var rowWidth = 0
    fun flush() {
        if (row.isNotEmpty()) rows += row.toString()
        row.setLength(0)
        rowWidth = 0
    }
    for (word in words) {
        var w = word
        while (displayWidth(w) > MAX_LINE_CHARS) {
            // No space to break at (Japanese kept as spoken, a very long word): cut it.
            flush()
            val cut = cutAt(w, MAX_LINE_CHARS)
            rows += w.substring(0, cut)
            w = w.substring(cut)
        }
        val ww = displayWidth(w)
        if (row.isNotEmpty() && rowWidth + 1 + ww > MAX_LINE_CHARS) flush()
        if (row.isNotEmpty()) {
            row.append(' ')
            rowWidth += 1
        }
        row.append(w)
        rowWidth += ww
    }
    flush()
    return rows
}

internal fun displayWidth(s: String): Int {
    var width = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        width += if (isWide(cp)) 2 else 1
        i += Character.charCount(cp)
    }
    return width
}

/** The longest prefix of [s] (in chars, never splitting a code point) at most [maxWidth] wide. */
private fun cutAt(s: String, maxWidth: Int): Int {
    var width = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val w = if (isWide(cp)) 2 else 1
        if (width + w > maxWidth) break
        width += w
        i += Character.charCount(cp)
    }
    return if (i == 0) Character.charCount(s.codePointAt(0)) else i
}

private fun isWide(cp: Int): Boolean =
    (cp in 0x1100..0x115F) || (cp in 0x2E80..0xA4CF) || (cp in 0xAC00..0xD7A3) ||
        (cp in 0xF900..0xFAFF) || (cp in 0xFE30..0xFE4F) || (cp in 0xFF00..0xFF60) ||
        (cp in 0xFFE0..0xFFE6) || (cp in 0x20000..0x3FFFD)

/** The cue nearest to [positionMs] (moved by [offsetMs]): for a preview while nothing is said. */
fun nearestCue(cues: List<Cue>, positionMs: Long, offsetMs: Long = 0): Cue? {
    val t = positionMs - offsetMs
    return cues.minByOrNull { c ->
        when {
            t < c.startMs -> c.startMs - t
            t >= c.endMs -> t - c.endMs
            else -> 0L
        }
    }
}
