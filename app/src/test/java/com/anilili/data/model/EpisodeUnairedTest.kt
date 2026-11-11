package com.anilili.data.model

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which rows the detail page greys out, from the two sources that disagree about it.
 *
 * The numbers here are the live ones for The Frontier Lord Begins with Zero Subjects (AniList
 * 196218), the title that reported this: it broadcasts Fridays from 3 July 2026, so AniList had
 * `nextAiringEpisode` on 12 the morning after episode 11 aired — while Konoha's TMDB-derived dates
 * run a week late for the whole season and still called episode 11 an 18 September release.
 */
class EpisodeUnairedTest {
    private fun episode(number: Int, airDate: String?) = EpisodeItem(
        pipeId = "ep$number",
        number = number.toDouble(),
        title = null,
        image = null,
        filler = false,
        airDate = airDate,
    )

    /** Konoha's dates for the season: every one a week later than the broadcast. */
    private fun konohaDate(number: Int) = LocalDate.of(2026, 7, 10).plusWeeks((number - 1).toLong()).toString()

    private fun season(next: Int?) = Media(
        id = 196218,
        episodes = 12,
        status = "RELEASING",
        nextAiringEpisode = next?.let { NextAiringEpisode(episode = it) },
        startDate = FuzzyDate(year = 2026, month = 7, day = 3),
    )

    /** The report: aired the night before, dated next Friday, and greyed out because of it. */
    @Test
    fun `a live schedule beats a catalogue date that runs late`() {
        val today = LocalDate.of(2026, 9, 12)
        assertFalse(episodeIsUnaired(episode(11, konohaDate(11)), season(next = 12), today = today))
    }

    /** The row AniList has not reached stays greyed even though its date is only a week out. */
    @Test
    fun `the episode still due is unaired`() {
        val today = LocalDate.of(2026, 9, 12)
        assertTrue(episodeIsUnaired(episode(12, konohaDate(12)), season(next = 12), today = today))
    }

    /**
     * The case the date is here for: a RELEASING title with a known total and no schedule attached,
     * where nothing but the catalogue can tell the aired rows from the rest.
     */
    @Test
    fun `without a schedule the catalogue date decides`() {
        val today = LocalDate.of(2026, 9, 12)
        val unscheduled = season(next = null)
        assertFalse(episodeIsUnaired(episode(9, "2026-09-04"), unscheduled, today = today))
        assertTrue(episodeIsUnaired(episode(11, "2026-09-18"), unscheduled, today = today))
    }

    /** Nothing placed the episode and nothing is airing: not a positive answer, so not greyed. */
    @Test
    fun `an undated row with no schedule is left alone`() {
        assertFalse(episodeIsUnaired(episode(11, null), season(next = null), today = LocalDate.of(2026, 9, 12)))
    }

    /** Konoha writes the current season's dates as full timestamps; both shapes must still read. */
    @Test
    fun `a timestamped catalogue date is read`() {
        val unscheduled = season(next = null)
        val today = LocalDate.of(2026, 9, 12)
        assertTrue(episodeIsUnaired(episode(11, "2026-09-18T00:00:00+00:00"), unscheduled, today = today))
        assertFalse(episodeIsUnaired(episode(10, "2026-09-11T00:00:00+00:00"), unscheduled, today = today))
    }

    /**
     * Mushoku Tensei III (AniList 178789): finished, fourteen episodes, the finale aired 15:00 UTC on
     * 27 September — midnight in Tokyo — and TMDB dates it the 28th. On the 27th in the Americas the
     * device's date is earlier than the catalogue's, yet the episode is out.
     */
    private val mushokuTenseiIii = Media(
        id = 178789,
        episodes = 14,
        status = "FINISHED",
        nextAiringEpisode = null,
        startDate = FuzzyDate(year = 2026, month = 7, day = 4),
    )

    @Test
    fun `a finished season has no unaired episodes, whatever the catalogue dates say`() {
        assertFalse(episodeIsUnaired(episode(14, "2026-09-28"), mushokuTenseiIii, today = LocalDate.of(2026, 9, 27)))
    }

    @Test
    fun `a catalogue date is a day in Japan`() {
        // The season's last episode, still RELEASING on AniList with nothing left scheduled: once it
        // is the 28th in Tokyo it has aired, whatever day it is where the viewer is.
        val lastWeek = mushokuTenseiIii.copy(status = "RELEASING")
        assertFalse(episodeIsUnaired(episode(14, "2026-09-28"), lastWeek, today = LocalDate.of(2026, 9, 28)))
        assertTrue(episodeIsUnaired(episode(14, "2026-09-28"), lastWeek, today = LocalDate.of(2026, 9, 27)))
    }

    /** Episode 14's broadcast: 15:00 UTC on 27 September 2026, midnight in Tokyo. */
    private val finaleAiredAt = 1_790_521_200L

    @Test
    fun `an episode opens two hours after it airs, not on the minute`() {
        val justAired = episode(14, "2026-09-28").copy(airingAt = finaleAiredAt)
        // AniList may already call the season FINISHED; the servers still need their two hours.
        assertTrue(episodeIsUnaired(justAired, mushokuTenseiIii, nowSec = finaleAiredAt + 60 * 60))
        assertFalse(episodeIsUnaired(justAired, mushokuTenseiIii, nowSec = finaleAiredAt + 2 * 60 * 60))
    }

    @Test
    fun `without an exact time a catalogue day opens two hours into that day in Japan`() {
        val lastWeek = mushokuTenseiIii.copy(status = "RELEASING")
        val row = episode(14, "2026-09-28")
        assertTrue(episodeIsUnaired(row, lastWeek, nowSec = finaleAiredAt + 60 * 60))
        assertFalse(episodeIsUnaired(row, lastWeek, nowSec = finaleAiredAt + 2 * 60 * 60))
    }
}
