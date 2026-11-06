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
    fun textAndNames() {
        assertEquals("a\nb\n", SubtitleFiles.toText(listOf(Segment(0, 1, " a "), Segment(1, 2, "b"))))
        assertEquals("VID_1.en.srt", SubtitleFiles.suggestedName("VID_1.mp4", "srt"))
        assertEquals("subtitles.en.txt", SubtitleFiles.suggestedName(null, "txt"))
        assertEquals("00:00:00,000", SubtitleFiles.srtTime(0))
    }
}
