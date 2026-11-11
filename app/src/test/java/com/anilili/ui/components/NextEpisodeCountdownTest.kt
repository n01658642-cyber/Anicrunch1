package com.anilili.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

private const val MINUTE = 60L
private const val HOUR = 3_600L
private const val DAY = 86_400L

class AiringCountdownFormatTest {

    @Test
    fun theWholeClockReadsLargestUnitFirst() {
        assertEquals(
            "1 DAY 3 HRS 28 MINS 5 SECS",
            formatAiringCountdown(DAY + 3 * HOUR + 28 * MINUTE + 5),
        )
    }

    /** Naive pluralisation is what makes a countdown read as broken at exactly one of anything. */
    @Test
    fun everyUnitIsSingularAtOne() {
        assertEquals("1 DAY 1 HR 1 MIN 1 SEC", formatAiringCountdown(DAY + HOUR + MINUTE + 1))
    }

    /**
     * Units bigger than the time left are dropped. "0 DAYS 0 HRS 4 MINS 2 SECS" spends most of its
     * width saying nothing, and the part that is moving is at the end where it is read last.
     */
    @Test
    fun unitsLargerThanTheTimeLeftAreNotShown() {
        assertEquals("4 MINS 2 SECS", formatAiringCountdown(4 * MINUTE + 2))
        assertEquals("2 SECS", formatAiringCountdown(2))
    }

    /**
     * A zero *below* a shown unit stays. Dropping it would shorten the line mid-count — "1 HR 0 MINS
     * 30 SECS" becoming "1 HR 30 SECS" reads as half an hour less.
     */
    @Test
    fun zeroesBeneathTheLargestUnitAreKept() {
        assertEquals("1 HR 0 MINS 30 SECS", formatAiringCountdown(HOUR + 30))
        assertEquals("2 DAYS 0 HRS 0 MINS 0 SECS", formatAiringCountdown(2 * DAY))
    }

    /** An episode that aired while the page was open counts to zero rather than going negative. */
    @Test
    fun anElapsedAiringStopsAtZero() {
        assertEquals("0 SECS", formatAiringCountdown(0))
        assertEquals("0 SECS", formatAiringCountdown(-90))
    }

    @Test
    fun daysDoNotRollIntoHours() {
        assertEquals("3 DAYS 0 HRS 0 MINS 1 SEC", formatAiringCountdown(3 * DAY + 1))
    }
}

class SpokenAiringCountdownTest {

    @Test
    fun itIsReadAsASentenceRatherThanAbbreviations() {
        assertEquals(
            "Episode 20 airs in 1 day, 3 hours, 28 minutes",
            spokenAiringCountdown(DAY + 3 * HOUR + 28 * MINUTE + 5, episode = 20),
        )
    }

    /** Seconds are left out on purpose; a polite live region would otherwise talk every second. */
    @Test
    fun secondsAreNotSpoken() {
        assertEquals("Episode 4 airs in 2 minutes", spokenAiringCountdown(2 * MINUTE + 30, episode = 4))
    }

    @Test
    fun theLastMinuteStillSaysSomethingUseful() {
        assertEquals("Episode 4 airs in less than a minute", spokenAiringCountdown(30, episode = 4))
    }

    @Test
    fun anUnknownEpisodeNumberIsNotSpokenAsAGap() {
        assertEquals("The next episode airs in 1 hour", spokenAiringCountdown(HOUR, episode = null))
    }

    @Test
    fun anAiredEpisodeIsAnnouncedAsAvailable() {
        assertEquals("Episode 20 is available now", spokenAiringCountdown(0, episode = 20))
    }

    /** The card-width clock: padded hours, minutes and seconds under a day. */
    @Test
    fun compactClockPadsEveryFieldUnderADay() {
        assertEquals("02:15:08", formatCompactCountdown(2 * HOUR + 15 * MINUTE + 8))
        assertEquals("00:00:59", formatCompactCountdown(59))
    }

    /** Past a day it drops the seconds so the line still fits the card. */
    @Test
    fun compactClockTradesSecondsForDays() {
        assertEquals("2d 04:09", formatCompactCountdown(2 * DAY + 4 * HOUR + 9 * MINUTE + 30))
    }

    @Test
    fun compactClockNeverGoesNegative() {
        assertEquals("00:00:00", formatCompactCountdown(-5))
    }
}
