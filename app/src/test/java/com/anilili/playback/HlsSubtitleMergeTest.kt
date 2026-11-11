package com.anilili.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An in-manifest subtitle rendition is a few hundred WebVTT files, each with its own header and its
 * own idea of where zero is. Getting the rebase wrong does not fail loudly — it produces a sidecar
 * whose cues are minutes out, which reads as "the subtitles are broken" rather than as a bug in a
 * timestamp map.
 */
class HlsSubtitleMergeTest {
    private fun segment(map: String?, vararg body: String): String = buildString {
        append("WEBVTT\n")
        if (map != null) append("X-TIMESTAMP-MAP=$map\n")
        append("\n")
        body.forEach { append(it).append("\n") }
    }

    @Test
    fun `segments without a timestamp map concatenate on their own timeline`() {
        val merged = mergeWebvttSegments(
            listOf(
                segment(null, "00:00:01.000 --> 00:00:03.000", "First line", ""),
                segment(null, "00:00:05.500 --> 00:00:07.250", "Second line", ""),
            ),
        )

        assertEquals(
            """
            WEBVTT

            00:00:01.000 --> 00:00:03.000
            First line

            00:00:05.500 --> 00:00:07.250
            Second line
            """.trimIndent() + "\n",
            merged,
        )
    }

    /**
     * The common case: every segment repeats one map whose MPEGTS is the stream's start PTS, and cue
     * times already run continuously. Rebasing against the first segment must leave them untouched
     * rather than shifting the whole document by the start PTS.
     */
    @Test
    fun `a shared non-zero timestamp map leaves continuous cues where they are`() {
        val map = "MPEGTS:900000,LOCAL:00:00:00.000"
        val merged = mergeWebvttSegments(
            listOf(
                segment(map, "00:00:01.000 --> 00:00:03.000", "First", ""),
                segment(map, "00:00:09.000 --> 00:00:11.000", "Second", ""),
            ),
        )!!

        assertTrue(merged.contains("00:00:01.000 --> 00:00:03.000"))
        assertTrue(merged.contains("00:00:09.000 --> 00:00:11.000"))
    }

    /**
     * The other convention: each segment carries its own increasing map and its cue times restart
     * near zero. The same subtraction has to turn those into one rising timeline.
     */
    @Test
    fun `per-segment timestamp maps shift each segment onto the presentation timeline`() {
        val merged = mergeWebvttSegments(
            listOf(
                // 900000 / 90000 = 10s, so this segment's zero is the document's zero.
                segment("MPEGTS:900000,LOCAL:00:00:00.000", "00:00:01.000 --> 00:00:03.000", "First", ""),
                // 1800000 / 90000 = 20s, ten seconds later than the first.
                segment("MPEGTS:1800000,LOCAL:00:00:00.000", "00:00:00.500 --> 00:00:02.000", "Second", ""),
            ),
        )!!

        assertTrue(merged.contains("00:00:01.000 --> 00:00:03.000"))
        assertTrue(merged.contains("00:00:10.500 --> 00:00:12.000"))
    }

    /** A non-zero LOCAL is subtracted from the MPEGTS offset, not added to it. */
    @Test
    fun `a local offset in the map cancels part of the mpegts offset`() {
        val merged = mergeWebvttSegments(
            listOf(
                segment("MPEGTS:0,LOCAL:00:00:00.000", "00:00:00.000 --> 00:00:01.000", "Zero", ""),
                // 20s of MPEGTS against a LOCAL of 5s is a 15s adjustment.
                segment("MPEGTS:1800000,LOCAL:00:00:05.000", "00:00:00.000 --> 00:00:01.000", "Later", ""),
            ),
        )!!

        assertTrue(merged.contains("00:00:15.000 --> 00:00:16.000"))
    }

    @Test
    fun `cue identifiers and settings survive the rewrite`() {
        val merged = mergeWebvttSegments(
            listOf(
                segment(
                    null,
                    "caption-7",
                    "00:00:01.000 --> 00:00:03.000 line:90% align:center",
                    "<i>Styled</i> text",
                    "",
                ),
            ),
        )!!

        assertTrue(merged.contains("caption-7"))
        assertTrue(merged.contains("00:00:01.000 --> 00:00:03.000 line:90% align:center"))
        assertTrue(merged.contains("<i>Styled</i> text"))
    }

    /** Hours are optional in WebVTT input and always written back in the long form. */
    @Test
    fun `short form timestamps are accepted and normalised`() {
        val merged = mergeWebvttSegments(listOf(segment(null, "01:02.500 --> 01:04.000", "Body", "")))!!

        assertTrue(merged.contains("00:01:02.500 --> 00:01:04.000"))
    }

    @Test
    fun `an empty rendition yields nothing rather than a header-only file`() {
        assertNull(mergeWebvttSegments(listOf(segment(null), segment(null))))
        assertNull(mergeWebvttSegments(emptyList()))
    }

    /**
     * A block that never reaches a timing line is not a cue. Carrying a segment's NOTE or STYLE
     * through would put it in the document once per segment — a few hundred copies.
     */
    @Test
    fun `blocks that are not cues are dropped and the cues around them survive`() {
        val merged = mergeWebvttSegments(
            listOf(
                segment(
                    null,
                    "NOTE this rendition was machine generated",
                    "",
                    "00:00:04.000 --> 00:00:06.000",
                    "Kept",
                    "",
                ),
            ),
        )!!

        assertTrue(merged.contains("00:00:04.000 --> 00:00:06.000"))
        assertTrue(merged.contains("Kept"))
        assertTrue(!merged.contains("machine generated"))
    }

    /**
     * A timing line whose numbers will not parse must cost that one cue, not the rendition: an
     * exception here would be caught per rendition, throwing away every other cue in the episode.
     */
    @Test
    fun `an unparseable timing costs one cue and no more`() {
        val merged = mergeWebvttSegments(
            listOf(
                segment(
                    null,
                    "99999999999999999999:00:00.000 --> 00:00:02.000",
                    "Lost",
                    "",
                    "00:00:04.000 --> 00:00:06.000",
                    "Kept",
                    "",
                ),
            ),
        )!!

        assertTrue(merged.contains("00:00:04.000 --> 00:00:06.000"))
        assertTrue(merged.contains("Kept"))
    }

    @Test
    fun `timestamps parse in both short and long form`() {
        assertEquals(62.5, parseVttTime("01:02.500")!!, 0.0001)
        assertEquals(3662.5, parseVttTime("01:01:02.500")!!, 0.0001)
        assertEquals(3662.5, parseVttTime("1:01:02,500")!!, 0.0001)
        assertNull(parseVttTime("nonsense"))
    }

    @Test
    fun `formatting rounds to milliseconds and never goes negative`() {
        assertEquals("00:00:00.000", formatVttTime(-4.0))
        assertEquals("01:01:02.500", formatVttTime(3662.5))
        assertEquals("00:00:01.001", formatVttTime(1.0005))
    }
}
