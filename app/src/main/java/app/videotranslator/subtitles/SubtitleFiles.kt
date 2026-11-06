package app.videotranslator.subtitles

import app.videotranslator.whisper.Segment
import java.util.Locale

/**
 * Subtitle files the user saves (through the system save dialog) and opens again later, so a video
 * already done does not need Whisper again. Only ever written where the user chooses.
 */
object SubtitleFiles {

    /**
     * SubRip (.srt), the format every player and editor reads: the readable cues ([toCues]), each
     * moved by [offsetMs] (the "all subtitles earlier/later" setting), numbered from 1.
     */
    fun toSrt(lines: List<Segment>, offsetMs: Long = 0): String = buildString {
        var n = 0
        for (cue in toCues(lines)) {
            val start = (cue.startMs + offsetMs).coerceAtLeast(0)
            val end = (cue.endMs + offsetMs).coerceAtLeast(start)
            n++
            append(n).append("\r\n")
            append(srtTime(start)).append(" --> ").append(srtTime(end)).append("\r\n")
            append(cue.text.replace("\n", "\r\n")).append("\r\n\r\n")
        }
    }

    /** The plain text, one line of speech per line, without times. */
    fun toText(lines: List<Segment>): String =
        lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n") { it.text.trim() }

    /**
     * Reads a .srt (also with "." before the milliseconds, a byte-order mark, missing numbers or
     * stray blank lines, as other tools write them). Each cue becomes one line, its rows joined by
     * a space. Returns null if no cue could be read.
     */
    fun parseSrt(content: String): List<Segment>? {
        val text = content.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val timing = Regex("""^\s*(\d+):(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(\d+):(\d{1,2}):(\d{1,2})[,.](\d{1,3})""")
        val out = ArrayList<Segment>()
        val rows = text.split('\n')
        var i = 0
        while (i < rows.size) {
            val m = timing.find(rows[i])
            if (m == null) {
                i++
                continue
            }
            val g = m.groupValues
            val start = ms(g[1], g[2], g[3], g[4])
            val end = ms(g[5], g[6], g[7], g[8])
            i++
            val body = ArrayList<String>()
            while (i < rows.size && rows[i].isNotBlank() && timing.find(rows[i]) == null) {
                body += rows[i].trim()
                i++
            }
            // A number line just before the next timing line belongs to the next cue.
            if (body.size > 1 && body.last().all { it.isDigit() } && i < rows.size && timing.find(rows[i]) != null) {
                body.removeAt(body.lastIndex)
            }
            val joined = body.joinToString(" ") { stripTags(it) }.trim()
            if (joined.isNotEmpty()) out += Segment(start, maxOf(end, start), joined)
        }
        return out.sortedBy { it.startMs }.takeIf { it.isNotEmpty() }
    }

    /** "VID_20250101.mp4" -> "VID_20250101.en.srt". */
    fun suggestedName(videoName: String?, extension: String): String {
        val base = videoName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "subtitles"
        return "$base.en.$extension"
    }

    internal fun srtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60
        val milli = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }

    private fun ms(h: String, m: String, s: String, frac: String): Long {
        // "5" after the comma means 500 ms, as in "00:00:01,5".
        val milli = frac.padEnd(3, '0').take(3).toLong()
        return h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + milli
    }

    /** <i>, <b>, <font ...> and {\an8}-style tags some tools add. */
    private fun stripTags(s: String): String =
        s.replace(Regex("</?[a-zA-Z][^>]*>"), "").replace(Regex("""\{\\[^}]*\}"""), "")
}
