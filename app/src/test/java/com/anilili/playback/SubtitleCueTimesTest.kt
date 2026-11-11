package com.anilili.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTimestampTest {
    @Test
    fun webVttMilliseconds() {
        assertEquals(3_500L, parseTimestampMs("00:00:03.500"))
    }

    /** SubRip writes the decimal point as a comma. */
    @Test
    fun subRipComma() {
        assertEquals(3_500L, parseTimestampMs("00:00:03,500"))
    }

    /**
     * ASS writes two-digit centiseconds and a single-digit hour. Reading `.50` as 50 milliseconds
     * instead of 500 is a half-second error on every line in the file.
     */
    @Test
    fun assCentisecondsScaleByTheirLength() {
        assertEquals(3_500L, parseTimestampMs("0:00:03.50"))
        assertEquals(90L, parseTimestampMs("0:00:00.09"))
    }

    /** WebVTT allows the hour to be left off entirely. */
    @Test
    fun theHourMayBeMissing() {
        assertEquals(63_000L, parseTimestampMs("01:03.000"))
    }

    @Test
    fun hoursMinutesAndSecondsAllCount() {
        assertEquals((1 * 3600 + 2 * 60 + 3) * 1000L + 250, parseTimestampMs("01:02:03.250"))
    }

    @Test
    fun rubbishIsRejectedRatherThanGuessed() {
        assertEquals(null, parseTimestampMs(""))
        assertEquals(null, parseTimestampMs("later"))
        assertEquals(null, parseTimestampMs("12"))
    }
}

class SubtitleCueParsingTest {

    @Test
    fun webVttCuesAreRead() {
        val cues = parseSubtitleCueTimes(
            """
            WEBVTT

            1
            00:00:01.000 --> 00:00:03.000
            Good morning.

            2
            00:00:05.500 --> 00:00:07.000
            Did you sleep well?
            """.trimIndent(),
        )
        assertEquals(listOf(1_000L, 5_500L), cues.map { it.startMs })
        assertEquals("Did you sleep well?", cues[1].text)
    }

    /** A cue split over two lines is one line of dialogue, not two cues. */
    @Test
    fun aMultiLineCueIsJoined() {
        val cues = parseSubtitleCueTimes(
            """
            00:00:01,000 --> 00:00:03,000
            I told you already,
            it is not going to work.
            """.trimIndent(),
        )
        assertEquals(1, cues.size)
        assertEquals("I told you already, it is not going to work.", cues.single().text)
    }

    @Test
    fun assDialogueLinesAreRead() {
        val cues = parseSubtitleCueTimes(
            """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:02.00,0:00:04.00,Default,,0,0,0,,Hello there
            Dialogue: 0,0:00:06.50,0:00:08.00,Default,,0,0,0,,{\pos(400,570)}A sign
            """.trimIndent(),
        )
        assertEquals(listOf(2_000L, 6_500L), cues.map { it.startMs })
        // The override block is not part of what the line says.
        assertEquals("A sign", cues[1].text)
    }

    /** Dialogue routinely contains commas; splitting on all of them truncates the line. */
    @Test
    fun anAssLineKeepsCommasInItsText() {
        val cues = parseSubtitleCueTimes(
            "Dialogue: 0,0:00:02.00,0:00:04.00,Default,,0,0,0,,Wait, what do you mean, exactly?",
        )
        assertEquals("Wait, what do you mean, exactly?", cues.single().text)
    }

    @Test
    fun cuesComeBackInTimeOrder() {
        val cues = parseSubtitleCueTimes(
            """
            00:00:09.000 --> 00:00:10.000
            Later

            00:00:01.000 --> 00:00:02.000
            Earlier
            """.trimIndent(),
        )
        assertEquals(listOf("Earlier", "Later"), cues.map { it.text })
    }

    /** A broken entry loses itself, not the rest of the file. */
    @Test
    fun aMalformedCueIsSkipped() {
        val cues = parseSubtitleCueTimes(
            """
            not a time --> also not a time
            Ignored

            00:00:04.000 --> 00:00:05.000
            Kept
            """.trimIndent(),
        )
        assertEquals(listOf("Kept"), cues.map { it.text })
    }

    @Test
    fun aByteOrderMarkDoesNotHideTheFirstCue() {
        val cues = parseSubtitleCueTimes("﻿WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nFirst")
        assertEquals(1, cues.size)
    }
}

class SubtitleSyncMathTest {
    private fun cue(startMs: Long, text: String = "line") = SubtitleCueTime(startMs, text)

    /**
     * The viewer heard the line at 12s; the file says it starts at 4s. The file is eight seconds
     * early, so every line has to be held back by eight seconds.
     */
    @Test
    fun anEarlyFileGivesAPositiveDelay() {
        assertEquals(8_000L, subtitleSyncDelayMs(12_000L, cue(4_000L)))
    }

    @Test
    fun aLateFileGivesANegativeDelay() {
        assertEquals(-3_000L, subtitleSyncDelayMs(5_000L, cue(8_000L)))
    }

    @Test
    fun anAlreadySyncedFileNeedsNoShift() {
        assertEquals(0L, subtitleSyncDelayMs(7_000L, cue(7_000L)))
    }

    @Test
    fun theNearestCuesAreOfferedInTimeOrder() {
        val cues = listOf(cue(1_000L, "a"), cue(5_000L, "b"), cue(9_000L, "c"), cue(30_000L, "d"))
        val nearest = nearestSubtitleCues(cues, positionMs = 6_000L, count = 3)
        assertEquals(listOf("a", "b", "c"), nearest.map { it.text })
    }

    /** A blank cue is nothing to recognise a moment by, so it is never offered as a choice. */
    @Test
    fun blankCuesAreNotOffered() {
        val cues = listOf(cue(1_000L, "  "), cue(2_000L, "real"))
        assertEquals(listOf("real"), nearestSubtitleCues(cues, 1_500L).map { it.text })
    }

    @Test
    fun fewerCuesThanAskedForIsFine() {
        assertTrue(nearestSubtitleCues(emptyList(), 1_000L).isEmpty())
    }
}
