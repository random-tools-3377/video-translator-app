package app.videotranslator.subtitles

import app.videotranslator.whisper.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {

    private val long = "When I was in elementary school, I jumped off the second floor of the school " +
        "and ended up bedridden for about a week, and nobody believed me."

    @Test
    fun shortLineIsOneCue() {
        val cues = toCues(listOf(Segment(1000, 3000, "There was no deep reason for it.")))
        assertEquals(listOf(Cue(1000, 3000, "There was no deep reason for it.")), cues)
    }

    @Test
    fun rowsAreShortAndAtMostTwoPerCue() {
        val cues = toCues(listOf(Segment(6000, 20000, long)))
        assertTrue("split into more than one cue", cues.size > 1)
        for (c in cues) {
            val rows = c.text.split('\n')
            assertTrue("at most two rows: ${c.text}", rows.size <= MAX_LINES)
            rows.forEach { assertTrue("row too long: $it", it.length <= MAX_LINE_CHARS) }
        }
        // Nothing lost, nothing added.
        assertEquals(long, cues.joinToString(" ") { it.text.replace('\n', ' ') })
    }

    @Test
    fun aLongLineSharesItsTimeInOrder() {
        val cues = toCues(listOf(Segment(6000, 20000, long)))
        assertEquals(6000, cues.first().startMs)
        assertEquals(20000, cues.last().endMs)
        for ((a, b) in cues.zipWithNext()) {
            assertEquals("cues follow on", a.endMs, b.startMs)
            assertTrue(a.startMs < a.endMs)
        }
    }

    @Test
    fun japaneseWithoutSpacesIsCutByWidth() {
        val text = "親譲りの無鉄砲で子供の時から損ばかりしている。小学校に居る時分学校の二階から飛び降りて一週間ほど腰を抜かした事がある。"
        val rows = wrap(text)
        assertTrue(rows.size > 1)
        rows.forEach { assertTrue("too wide: $it", displayWidth(it) <= MAX_LINE_CHARS) }
        assertEquals(text, rows.joinToString(""))
    }

    @Test
    fun cueAtFindsTheLineBeingSaidWithTheOffset() {
        val cues = listOf(Cue(1000, 2000, "a"), Cue(3000, 4000, "b"))
        assertNull(cueAt(cues, 500))
        assertEquals("a", cueAt(cues, 1500)?.text)
        assertNull(cueAt(cues, 2500))
        assertEquals("b", cueAt(cues, 3999)?.text)
        assertNull(cueAt(cues, 4000))
        // All subtitles 0.5 s later: "a" shows from 1.5 s to 2.5 s.
        assertNull(cueAt(cues, 1200, offsetMs = 500))
        assertEquals("a", cueAt(cues, 2400, offsetMs = 500)?.text)
        assertEquals("b", nearestCue(cues, 2600)?.text)
        assertEquals("a", nearestCue(cues, 2400)?.text)
    }

    @Test
    fun srtRoundTrip() {
        val lines = listOf(
            Segment(1600, 8400, "Anton Pavlovich Chekhov. \"The Fat and the Thin.\" A short story."),
            Segment(11000, 16100, "Two friends met at the station of the Nikolaevskaya Railway."),
            Segment(3_725_000, 3_726_500, "One was fat, the other was thin."),
        )
        val srt = SubtitleFiles.toSrt(lines)
        assertTrue(srt.startsWith("1\r\n00:00:01,600 --> "))
        assertTrue(srt.contains("01:02:05,000 --> 01:02:06,500"))
        val back = SubtitleFiles.parseSrt(srt)!!
        assertEquals(toCues(lines).map { Segment(it.startMs, it.endMs, it.text.replace('\n', ' ')) }, back)
    }

    @Test
    fun srtWithOffsetMovesEveryCueAndNeverBelowZero() {
        val lines = listOf(Segment(200, 1200, "first"), Segment(5000, 6000, "second"))
        val back = SubtitleFiles.parseSrt(SubtitleFiles.toSrt(lines, offsetMs = -500))!!
        assertEquals(Segment(0, 700, "first"), back[0])
        assertEquals(Segment(4500, 5500, "second"), back[1])
    }

    @Test
    fun readsSrtFromOtherTools() {
        val other = "﻿1\n00:00:01.5 --> 00:00:03.250\n<i>Hello</i> there\n{\\an8}friend\n\n" +
            "00:00:04,000 --> 00:00:05,000\nNo number before this one\n2\n00:00:06,000 --> 00:00:07,000\nNo blank line before\n"
        val lines = SubtitleFiles.parseSrt(other)!!
        assertEquals(
            listOf(
                Segment(1500, 3250, "Hello there friend"),
                Segment(4000, 5000, "No number before this one"),
                Segment(6000, 7000, "No blank line before"),
            ),
            lines,
        )
        assertNull(SubtitleFiles.parseSrt("not subtitles at all"))
    }

    @Test
    fun webVttFromMeetingApps() {
        val vtt = """
            WEBVTT
            Kind: captions

            NOTE written by a meeting app

            3f1c-0
            00:00:01.000 --> 00:00:04.500 align:start position:10%
            <v Anna Smith>Good morning &amp; welcome.</v>

            3f1c-1
            00:04.500 --> 00:07.000
            <v Ben>Thanks,</v>
            <c.yellow>everyone</c>.

        """.trimIndent()
        val read = SubtitleFiles.read(vtt.toByteArray())!!
        assertEquals(SubtitleKind.WebVtt, read.kind)
        assertEquals(false, read.canSaveBack)
        assertEquals(
            listOf(Segment(1000, 4500, "Good morning & welcome."), Segment(4500, 7000, "Thanks, everyone.")),
            read.lines,
        )
    }

    @Test
    fun youtubeSbv() {
        val sbv = "0:00:00.599,0:00:04.160\nHello there,\nfriends\n\n0:00:04.160,0:00:07.000\nsecond\n"
        val read = SubtitleFiles.read(sbv.toByteArray())!!
        assertEquals(SubtitleKind.Sbv, read.kind)
        assertEquals(listOf(Segment(599, 4160, "Hello there, friends"), Segment(4160, 7000, "second")), read.lines)
    }

    @Test
    fun ourOwnSrtCanBeSavedBack() {
        val read = SubtitleFiles.read(SubtitleFiles.toSrt(listOf(Segment(0, 1000, "a"))).toByteArray())!!
        assertEquals(SubtitleKind.Srt, read.kind)
        assertTrue(read.canSaveBack)
    }

    @Test
    fun transcriptWithTimesAtTheStartOfRows() {
        val text = """
            Weekly meeting, 3 March
            Present: Anna, Ben

            [00:00:02] Anna: Let's start.
            0:05 - Ben: The numbers
            are in.
            (1:02) we met at 10:30 to go over them
            00:01:00 an earlier time is words
            1:10:05 - 1:10:09 A range.
            1:10:20 back at 9:15 am
        """.trimIndent()
        val lines = SubtitleFiles.parseTranscript(text)!!
        assertEquals(
            listOf(
                "Anna: Let's start.",
                "Ben: The numbers are in.",
                "we met at 10:30 to go over them 00:01:00 an earlier time is words",
                "A range.",
                "back at 9:15 am",
            ),
            lines.map { it.text },
        )
        assertEquals(listOf(2_000L, 5_000L, 62_000L, 4_205_000L, 4_220_000L), lines.map { it.startMs })
        // Until the next one starts when that is soon; a range keeps its end.
        assertEquals(5_000L, lines[0].endMs)
        assertEquals(4_209_000L, lines[3].endMs)
        // A short line with the next one minutes later shows long enough to read, not until then.
        val read = lines[2].endMs - lines[2].startMs
        assertTrue("shown for $read ms", read in 3_000L..10_000L)
    }

    @Test
    fun transcriptWithTimesOnTheirOwnRowsOrAfterSpeakers() {
        val copied = "0:00\nfirst words\n0:04\nsecond words\nstill second\n"
        assertEquals(
            listOf(Segment(0, 4000, "first words"), Segment(4000, 4000 + 700 + 80 * 25, "second words still second")),
            SubtitleFiles.parseTranscript(copied),
        )
        val speakers = "Interview\n\nSpeaker 1  0:00\nHello.\n\nAnna (00:00:03):\nHi, we start at 10:30\n"
        val lines = SubtitleFiles.parseTranscript(speakers)!!
        assertEquals(listOf("Speaker 1: Hello.", "Anna: Hi, we start at 10:30"), lines.map { it.text })
        assertEquals(listOf(0L, 3_000L), lines.map { it.startMs })
        // Two rows with the same time are shown together.
        assertEquals(listOf(Segment(1000, 3000, "a b")), SubtitleFiles.parseTranscript("0:01 a\n0:01 b\n0:03\n"))
        assertNull(SubtitleFiles.parseTranscript("Just words.\nNo times at all, except 10:30 am.\n"))
        assertNull(SubtitleFiles.read("Just words, no times.".toByteArray()))
    }

    @Test
    fun savedTextOpensAgainAtItsTimes() {
        val lines = listOf(Segment(1_500, 3_000, " Hello "), Segment(65_000, 66_000, "日本語の行"))
        val text = SubtitleFiles.toText(lines)
        assertEquals("[00:00:01] Hello\n[00:01:05] 日本語の行\n", text)
        assertEquals("[00:00:03] Hello\n[00:01:07] 日本語の行\n", SubtitleFiles.toText(lines, offsetMs = 2_000))
        val read = SubtitleFiles.read(text.toByteArray())!!
        assertEquals(SubtitleKind.Transcript, read.kind)
        assertEquals(listOf(1_000L, 65_000L), read.lines.map { it.startMs })
        assertEquals(listOf("Hello", "日本語の行"), read.lines.map { it.text })
    }

    @Test
    fun windowsUnicodeTextAndWordDocuments() {
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "00:00:02 Привет\r\n".toByteArray(Charsets.UTF_16LE)
        assertEquals(listOf("Привет"), SubtitleFiles.read(utf16)!!.lines.map { it.text })

        val xml = "<?xml version=\"1.0\"?><w:document><w:body>" +
            "<w:p><w:r><w:t>Minutes</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t xml:space=\"preserve\">00:00:05 </w:t></w:r><w:r><w:t>Tom &amp; Jo</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>agree</w:t><w:br/><w:t>00:00:09</w:t><w:tab/><w:t>Next &#x2014; item</w:t></w:r></w:p>" +
            "</w:body></w:document>"
        val zip = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zip).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("[Content_Types].xml"))
            z.write("<Types/>".toByteArray())
            z.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            z.write(xml.toByteArray())
        }
        val read = SubtitleFiles.read(zip.toByteArray())!!
        assertEquals(SubtitleKind.Transcript, read.kind)
        assertEquals(listOf("Tom & Jo agree", "Next — item"), read.lines.map { it.text })
        assertEquals(listOf(5_000L, 9_000L), read.lines.map { it.startMs })
    }

    @Test
    fun textAndNames() {
        assertEquals("VID_1.en.srt", SubtitleFiles.suggestedName("VID_1.mp4", "srt"))
        assertEquals("subtitles.en.txt", SubtitleFiles.suggestedName(null, "txt"))
        assertEquals("00:00:00,000", SubtitleFiles.srtTime(0))
    }
}
