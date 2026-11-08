package app.videotranslator.subtitles

import app.videotranslator.whisper.Segment
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** What a file the user opened turned out to be. */
enum class SubtitleKind { Srt, WebVtt, Sbv, Transcript }

/** The lines read from a file the user opened, and what kind of file it was. */
data class OpenedSubtitles(val lines: List<Segment>, val kind: SubtitleKind) {
    /** Corrections can be written back into the same file only when it is a .srt. */
    val canSaveBack: Boolean get() = kind == SubtitleKind.Srt
}

/**
 * Subtitle files the user saves (through the system save dialog) and opens again later, so a video
 * already done does not need Whisper again; and subtitles or transcripts made elsewhere, placed at
 * the times written in them. Only ever written where the user chooses.
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

    /**
     * The transcript: one line of speech per line, each after the time it starts, moved by
     * [offsetMs] like the .srt ("[00:01:23] words"). Easy to read, and it opens again with the video
     * like a .srt (each line then shows until the next starts, or for as long as it takes to read).
     */
    fun toText(lines: List<Segment>, offsetMs: Long = 0): String = buildString {
        for (line in lines) {
            val text = line.text.replace('\n', ' ').trim()
            if (text.isEmpty()) continue
            append('[').append(clock((line.startMs + offsetMs).coerceAtLeast(0))).append("] ").append(text).append('\n')
        }
    }

    /**
     * Reads a file the user picked: subtitles (.srt, WebVTT .vtt as Zoom and Teams write them,
     * YouTube's .sbv) or a transcript with times in it (a time at the start of each line or
     * paragraph, or on a line of its own, perhaps after a speaker's name), as plain text in UTF-8 or
     * UTF-16 or as a Word document (.docx). Null when no times were found, since then there is no
     * way to tell when each line is said.
     */
    fun read(bytes: ByteArray): OpenedSubtitles? {
        val document = isZip(bytes)
        val text = if (document) docxText(bytes) ?: return null else decode(bytes)
        val trimmed = text.trimStart()
        parseTimed(text, CUE_TIMING)?.let {
            val kind = when {
                document -> SubtitleKind.Transcript
                trimmed.startsWith("WEBVTT") -> SubtitleKind.WebVtt
                else -> SubtitleKind.Srt
            }
            return OpenedSubtitles(it, kind)
        }
        parseTimed(text, SBV_TIMING)?.let { return OpenedSubtitles(it, if (document) SubtitleKind.Transcript else SubtitleKind.Sbv) }
        parseTranscript(text)?.let { return OpenedSubtitles(it, SubtitleKind.Transcript) }
        return null
    }

    /**
     * A transcript with times: each time (at the start of a line, alone on a line, or at the end of
     * a short line such as a speaker's name) starts a new line of subtitles, which takes the words
     * after it up to the next time. Anything before the first time (a title, a date, who was
     * there) is left out. A time earlier than the one before it is taken as part of the words, and
     * a clock time ("10:30 AM") is never taken as a time in the video. A line shows until the next
     * one starts, or for as long as it takes to read if the next is much later. Null when no times
     * were found.
     */
    fun parseTranscript(content: String): List<Segment>? {
        val rows = content.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .map { it.trim() }.filter { it.isNotEmpty() }
        class Block(val startMs: Long, val endMs: Long?, val label: String, val words: StringBuilder)
        val blocks = ArrayList<Block>()
        // A file that puts its times at the start of rows does not also put them at the end, so
        // then a row ending with a time ("we met at 10:30") is words, not a speaker and a time.
        val trailing = rows.count { leadingMarker(it) != null } < 2
        fun speaker(i: Int): Marker? {
            if (!trailing) return null
            val m = trailingMarker(rows[i]) ?: return null
            // A speaker's row is followed by what they said.
            val next = rows.getOrNull(i + 1) ?: return null
            return m.takeIf { leadingMarker(next) == null && trailingMarker(next) == null }
        }
        var last = -1L
        for ((i, row) in rows.withIndex()) {
            val marker = leadingMarker(row) ?: speaker(i)
            if (marker != null && marker.startMs >= last) {
                last = marker.startMs
                blocks += Block(marker.startMs, marker.endMs, marker.label, StringBuilder(marker.rest))
            } else if (blocks.isNotEmpty()) {
                val words = blocks.last().words
                if (words.isNotEmpty()) words.append(' ')
                words.append(row)
            }
        }
        if (blocks.isEmpty()) return null
        val out = ArrayList<Segment>()
        for ((i, b) in blocks.withIndex()) {
            val words = cleanWords(b.words.toString())
            if (words.isEmpty()) continue
            val text = if (b.label.isNotEmpty()) "${b.label}: $words" else words
            // Shown until the next time, or for as long as it takes to read if that is much later.
            val next = blocks.subList(i + 1, blocks.size).firstOrNull { it.startMs > b.startMs }?.startMs
            val readMs = (READ_BASE_MS + READ_PER_WIDTH_MS * displayWidth(text)).coerceAtLeast(MIN_SHOW_MS)
            val end = b.endMs?.takeIf { it > b.startMs }
                ?: if (next != null && next - b.startMs <= readMs + LINGER_MS) next else b.startMs + readMs
            // Rows given the same time are shown together.
            val previous = out.lastOrNull()
            if (previous != null && previous.startMs == b.startMs) {
                out[out.lastIndex] = Segment(previous.startMs, maxOf(previous.endMs, end), previous.text + " " + text)
            } else {
                out += Segment(b.startMs, end, text)
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * Reads a .srt (also with "." before the milliseconds, a byte-order mark, missing numbers or
     * stray blank lines, as other tools write them). Each cue becomes one line, its rows joined by
     * a space. Returns null if no cue could be read.
     */
    fun parseSrt(content: String): List<Segment>? = parseTimed(content, CUE_TIMING)

    /**
     * Cues, each a timing line ([timing]: groups 1-4 the start, 5-8 the end, hours optional) and
     * the rows under it up to a blank line. Used for .srt, .vtt (whose header, notes and cue
     * settings are skipped) and .sbv.
     */
    private fun parseTimed(content: String, timing: Regex): List<Segment>? {
        val text = content.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
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
            val joined = cleanWords(body.joinToString(" ") { stripTags(it) })
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

    /** "0:01:23", "1:02:03": the time in a transcript. */
    internal fun clock(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun ms(h: String, m: String, s: String, frac: String): Long {
        // "5" after the comma means 500 ms, as in "00:00:01,5". No hours (WebVTT may leave them out): 0.
        val milli = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').take(3).toLong()
        return (h.toLongOrNull() ?: 0) * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + milli
    }

    /** A time that starts a line of a transcript, and what else the row says. */
    private class Marker(val startMs: Long, val endMs: Long?, val label: String, val rest: String)

    /**
     * The time this row of a transcript starts with ("01:23 words", "[0:01:23] words",
     * "(1:23) - words", "00:01:23 - 00:01:30 words", a time alone); null for other rows.
     */
    private fun leadingMarker(row: String): Marker? {
        val m = LEADING.find(row) ?: return null
        val rest = m.groupValues[9].trim()
        if (CLOCK_WORD.containsMatchIn(rest)) return null
        val start = time(m, 1) ?: return null
        val end = if (m.groups[6] != null) time(m, 5) else null
        return Marker(start, end, "", rest)
    }

    /**
     * The time a short row that is not a sentence ends with: a speaker's name and the time they
     * start ("Speaker 1  01:23", "Anna (00:12:01):"); null for other rows.
     */
    private fun trailingMarker(row: String): Marker? {
        val m = TRAILING.find(row) ?: return null
        val label = m.groupValues[1].trim().trimEnd(':', '-', '|', '–', '—').trim()
        if (label.length > MAX_LABEL_CHARS || label.any { it in ".?!,;。？！、，" }) return null
        // "... starts at 10:30" is a sentence with a clock time, not a speaker.
        if (label.substringAfterLast(' ').lowercase() in NOT_A_NAME_END) return null
        val start = time(m, 2) ?: return null
        return Marker(start, null, label, "")
    }

    /** Groups [i] to [i]+3 of [m]: hours (optional), minutes, seconds, fraction (optional). */
    private fun time(m: MatchResult, i: Int): Long? {
        val h = m.groups[i]?.value ?: ""
        val min = m.groups[i + 1]?.value ?: return null
        val sec = m.groups[i + 2]?.value ?: return null
        if (sec.toInt() > 59 || (h.isNotEmpty() && min.toInt() > 59)) return null
        val t = ms(h, min, sec, m.groups[i + 3]?.value ?: "")
        return t.takeIf { it < 24L * 3_600_000 }
    }

    /** Joins rows, strips WebVTT's &amp;-style entities and inline times, and tidies spaces. */
    private fun cleanWords(s: String): String =
        s.replace(Regex("<\\d[^>]*>"), "")
            .replace("&nbsp;", " ").replace("&lrm;", "").replace("&rlm;", "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
            .replace(Regex("\\s+"), " ").trim()

    /** <i>, <b>, <font ...>, WebVTT's <v Speaker> and <c.colour>, and {\an8}-style tags. */
    private fun stripTags(s: String): String =
        s.replace(Regex("</?[a-zA-Z][^>]*>"), "").replace(Regex("<\\d[^>]*>"), "").replace(Regex("""\{\\[^}]*\}"""), "")

    /** Text in UTF-8 (with or without a byte-order mark) or UTF-16 (Windows "Unicode", with one). */
    private fun decode(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    /** The text of a Word document (.docx), one paragraph per line; null if it is not one. */
    internal fun docxText(bytes: ByteArray): String? = try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var xml: String? = null
            while (xml == null) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") xml = zip.readBytes().toString(Charsets.UTF_8)
            }
            xml?.let { wordParagraphs(it) }
        }
    } catch (_: Exception) {
        null
    }

    private fun wordParagraphs(xml: String): String {
        val out = StringBuilder()
        for (m in WORD_TOKEN.findAll(xml)) {
            val v = m.value
            when {
                v == "</w:p>" -> out.append('\n')
                v.startsWith("<w:tab") -> out.append('\t')
                v.startsWith("<w:br") || v.startsWith("<w:cr") -> out.append('\n')
                else -> out.append(unescapeXml(m.groupValues[1]))
            }
        }
        return out.toString()
    }

    private fun unescapeXml(s: String): String =
        s.replace(Regex("&#(x[0-9a-fA-F]+|\\d+);")) { m ->
            val n = m.groupValues[1]
            val cp = if (n.startsWith("x")) n.substring(1).toInt(16) else n.toInt()
            String(Character.toChars(cp))
        }.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** h:mm:ss or m:ss (hours optional), with an optional fraction: four groups. */
    private const val TIME = """(?:(\d{1,2}):)?(\d{1,3}):(\d{2})(?:[.,](\d{1,3}))?"""

    /** .srt and WebVTT: "00:01:02,500 --> 00:01:04,000", hours optional in WebVTT. */
    private val CUE_TIMING = Regex("""^\s*(?:(\d+):)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})""")

    /** YouTube's .sbv: "0:01:02.500,0:01:04.000" alone on its row. */
    private val SBV_TIMING = Regex("""^\s*(\d+):(\d{1,2}):(\d{1,2})\.(\d{1,3}),(\d+):(\d{1,2}):(\d{1,2})\.(\d{1,3})\s*$""")

    /** A time (or a range) at the start of a row, perhaps in brackets, then the words (group 9). */
    private val LEADING = Regex(
        """^[\[(]?\s*$TIME\s*(?:(?:-->|-|–|—|to)\s*$TIME\s*)?[\])]?(?![\d:])\s*[-–—:|>]*\s*(.*)$""",
    )

    /** A short row ending with a time, perhaps in brackets and with a colon: a speaker's name. */
    private val TRAILING = Regex("""^(\S.*?)\s+[\[(]?$TIME[\])]?\s*:?\s*$""")

    /** "10:30 AM", "10:30 pm", "10:30 a.m.": a clock time, not a time in the video. */
    private val CLOCK_WORD = Regex("""^(?i)(am|pm|a\.m\.|p\.m\.)(\b|$)""")

    /** Word's text runs, tabs, line breaks and paragraph ends. */
    private val WORD_TOKEN = Regex("""<w:t(?:\s[^>]*)?>([^<]*)</w:t>|<w:tab\s*/>|<w:br(?:\s[^>]*)?/>|<w:cr\s*/>|</w:p>""")

    /** A speaker's name before a time is at most this long; a longer row is a sentence. */
    private const val MAX_LABEL_CHARS = 40

    /** Words that end a sentence before a clock time ("we meet at 10:30"), never a name. */
    private val NOT_A_NAME_END = setOf(
        "at", "by", "until", "till", "from", "to", "before", "after", "around", "about", "since", "and", "or",
        "is", "was", "are", "were", "be", "of", "for", "in", "on", "@", "в", "до", "с", "к", "около",
    )

    // How long a transcript line shows: until the next one starts, unless that is more than
    // LINGER_MS after it has been read (a pause), then just long enough to read.
    private const val LINGER_MS = 3000L
    private const val MIN_SHOW_MS = 1500L
    private const val READ_BASE_MS = 700L
    private const val READ_PER_WIDTH_MS = 80L
}
