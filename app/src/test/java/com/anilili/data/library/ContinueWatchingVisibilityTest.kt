package com.anilili.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Continue Watching rows Home takes off until an episode is out.
 *
 * The rows here are the shapes the app actually writes, not invented ones: a row caught up and
 * resting on the episode before the one airing, a row `markEpisodeWatched` has already advanced onto
 * tomorrow's episode, one left part-way, and one behind the frontier on a rewatch. Most cases put the
 * next episode tomorrow; the later ones put it most of a week out, which is the case the feature is
 * for, and the device clock is the only thing that moves between a row stepping aside and coming
 * back.
 *
 * The instants are 2026-09-24 09:00 UTC (now) and an episode airing 2026-09-25 01:30 UTC.
 */
class ContinueWatchingVisibilityTest {
    /** AniList 21, whose episode 1177 is the one airing tomorrow in these tests. */
    private val title = 21

    /** 2026-09-24 09:00 UTC — a Thursday morning. */
    private val nowSec = 1_790_240_400L

    /** 2026-09-25 01:30 UTC — episode 1177, the next morning. */
    private val airsTomorrowAt = 1_790_299_800L

    private val tomorrow: UpcomingAiring = mapOf(title to mapOf(1177 to airsTomorrowAt))

    /** A row on the last episode out: watched, nothing left in progress, nothing to resume. */
    private fun caughtUp(episode: Double = 1176.0) = HistoryEntry(
        anilistId = title,
        title = "ONE PIECE",
        cover = null,
        episodeNumber = episode,
        provider = AUTO_PROVIDER,
        category = "sub",
        positionMs = 0,
        updatedAt = nowSec * 1000,
        watchedEpisodes = setOf(episode),
    )

    @Test
    fun `a caught-up row steps aside when the next episode is tomorrow`() {
        assertTrue(hideFromContinueUntilAired(caughtUp(), tomorrow, nowSec))
    }

    /**
     * The shape finishing the latest episode actually leaves. `markEpisodeWatched` advances the row
     * to the next episode whether or not it has aired, so the row the viewer sees after the credits
     * points at tomorrow's episode with nothing recorded against it — and there is nothing to press
     * play on, which is exactly the case the feature exists for.
     */
    @Test
    fun `a row advanced onto tomorrow's episode steps aside too`() {
        val advanced = caughtUp(episode = 1177.0).copy(watchedEpisodes = setOf(1176.0))
        assertTrue(hideFromContinueUntilAired(advanced, tomorrow, nowSec))
    }

    /**
     * Nothing of this title is left to watch and nothing says where the viewer got to: the row is on
     * the last episode out with no clock of its own. A viewer who simply never started an episode
     * AniList already counts as watched loses nothing — the row is still one press away on the show's
     * own page.
     */
    @Test
    fun `a caught-up row with nothing recorded steps aside`() {
        assertTrue(hideFromContinueUntilAired(caughtUp().copy(watchedEpisodes = emptySet()), tomorrow, nowSec))
    }

    /**
     * A row seeded from the account is the shape a fresh install has, and the likeliest to be caught
     * up: AniList only counts progress, so it rests on the latest aired episode with nowhere further
     * to point.
     */
    @Test
    fun `a row seeded from the account steps aside`() {
        assertTrue(hideFromContinueUntilAired(caughtUp().copy(watchedEpisodes = emptySet(), fromRemote = true), tomorrow, nowSec))
    }

    /** Past the airing time by the margin the servers need, so the row comes back on its own. */
    @Test
    fun `the row returns once the episode has been carried`() {
        assertFalse(hideFromContinueUntilAired(caughtUp(), tomorrow, airsTomorrowAt + 2 * 60 * 60))
    }

    /**
     * The window the servers need, and no more. A row that returned before the episode was carried
     * would open onto "no playable source on any server" and read as the app being broken.
     */
    @Test
    fun `an episode that has only just aired is not out yet`() {
        assertTrue(hideFromContinueUntilAired(caughtUp(), tomorrow, airsTomorrowAt + 60 * 60))
    }

    /** Part-way through the episode it names: Continue Watching is exactly what the row is for. */
    @Test
    fun `a part-watched episode is never taken off the row`() {
        val midway = caughtUp().copy(positionMs = 5 * 60 * 1000, durationMs = 24 * 60 * 1000)
        assertFalse(hideFromContinueUntilAired(midway, tomorrow, nowSec))
    }

    /** Part-way through *tomorrow's* episode, which is the same statement about the row. */
    @Test
    fun `a part-watched row on tomorrow's episode stays`() {
        val midway = caughtUp(episode = 1177.0).copy(
            watchedEpisodes = setOf(1176.0),
            positionMs = 5 * 60 * 1000,
            durationMs = 24 * 60 * 1000,
        )
        assertFalse(hideFromContinueUntilAired(midway, tomorrow, nowSec))
    }

    /**
     * An episode left part-way on another device. This device's copy of the row carries the account's
     * progress and no clock of its own, so this is the case that only [HistoryEntry.resumeFor] can
     * tell apart from a finished episode — and a place to resume is something to play.
     */
    @Test
    fun `a row with a stored resume point keeps its place`() {
        val seeded = caughtUp().copy(watchedEpisodes = emptySet(), fromRemote = true)
        val resumable = seeded.copy(resumePoints = mapOf(seeded.episodeLabel to EpisodeResume(60_000, 1_440_000)))
        assertFalse(hideFromContinueUntilAired(resumable, tomorrow, nowSec))
    }

    /**
     * A row behind the frontier — a rewatch begun on the phone, or an earlier season. The episode
     * airing tomorrow is not the one after this, so it says nothing about what the row can play.
     */
    @Test
    fun `a row behind the schedule is left alone`() {
        assertFalse(hideFromContinueUntilAired(caughtUp(episode = 1000.0), tomorrow, nowSec))
    }

    /** A rewatch with an episode actually in progress behind the frontier, which is the same thing. */
    @Test
    fun `a rewatch in progress is left alone`() {
        val rewatch = caughtUp(episode = 1000.0).copy(positionMs = 90_000, durationMs = 1_440_000)
        assertFalse(hideFromContinueUntilAired(rewatch, tomorrow, nowSec))
    }

    @Test
    fun `a title with nothing scheduled tomorrow keeps its place`() {
        assertFalse(hideFromContinueUntilAired(caughtUp(), emptyMap(), nowSec))
    }

    @Test
    fun `another title's episode does not take this row off`() {
        assertFalse(hideFromContinueUntilAired(caughtUp(), mapOf(999 to mapOf(1177 to airsTomorrowAt)), nowSec))
    }

    /** Two episodes of one title on the same day: the schedule is read as a whole, not per row. */
    @Test
    fun `a second episode airing tomorrow does not change the answer`() {
        val double = mapOf(title to mapOf(1177 to airsTomorrowAt, 1178 to airsTomorrowAt + 7 * 24 * 60 * 60))
        assertTrue(hideFromContinueUntilAired(caughtUp(), double, nowSec))
    }

    /**
     * The schedule outlives the day it was read for: an app left open, or reopened without a
     * refetch, still holds yesterday's tomorrow. Those episodes have aired by the time it is stale,
     * so the row comes back rather than staying away.
     */
    @Test
    fun `a schedule read yesterday does not hide the row today`() {
        assertFalse(hideFromContinueUntilAired(caughtUp(), tomorrow, airsTomorrowAt + 26 * 60 * 60))
    }

    /** A row behind the frontier on a fractional episode is behind it like any other. */
    @Test
    fun `a special behind the frontier is left alone`() {
        assertFalse(hideFromContinueUntilAired(caughtUp(episode = 1174.5), tomorrow, nowSec))
    }

    @Test
    fun `a first episode is a row to hide`() {
        val first = caughtUp(episode = 1.0)
        assertTrue(hideFromContinueUntilAired(first, mapOf(title to mapOf(2 to airsTomorrowAt)), nowSec))
    }

    /**
     * Finishing 1175 advances the row onto 1176, the last episode out, and 1177 airing tomorrow does
     * not change that 1176 is waiting. A seeded row whose account count is one behind rests in the
     * same place.
     */
    @Test
    fun `an unwatched latest episode keeps its place`() {
        val waiting = caughtUp().copy(watchedEpisodes = (1..1175).map { it.toDouble() }.toSet())
        assertFalse(hideFromContinueUntilAired(waiting, tomorrow, nowSec))
        assertFalse(hideFromContinueUntilAired(waiting.copy(fromRemote = true), tomorrow, nowSec))
    }

    /** 2026-09-30 09:00 UTC — the next weekly episode, six days out. */
    private val airsNextWeekAt = 1_790_758_800L

    /**
     * The point of the feature: a weekly show caught up on Thursday is off the row until next
     * Wednesday's episode is out, not merely on the day before it.
     */
    @Test
    fun `a caught-up row stays aside all week`() {
        val nextWeek: UpcomingAiring = mapOf(title to mapOf(1177 to airsNextWeekAt))
        assertTrue(hideFromContinueUntilAired(caughtUp(), nextWeek, nowSec))
        assertTrue(hideFromContinueUntilAired(caughtUp(), nextWeek, airsNextWeekAt + 60 * 60))
        assertFalse(hideFromContinueUntilAired(caughtUp(), nextWeek, airsNextWeekAt + 2 * 60 * 60))
    }

    /** When Home has to judge again: the broadcast plus the two hours the servers need. */
    @Test
    fun `a hidden row says when it comes back`() {
        assertEquals(airsTomorrowAt + 2 * 60 * 60, returnsToContinueAt(caughtUp(), tomorrow, nowSec))
        assertNull(returnsToContinueAt(caughtUp(episode = 1000.0), tomorrow, nowSec))
    }

    /**
     * The window reaches back to yesterday, so it can hold an episode that is already out. That one
     * is something to play, and the row is judged against the next one after it.
     */
    @Test
    fun `an episode already out in the window is not what the row waits for`() {
        val window: UpcomingAiring = mapOf(title to mapOf(1176 to nowSec - 24 * 60 * 60, 1177 to airsNextWeekAt))
        // Watched yesterday's: waiting on next week's.
        assertTrue(hideFromContinueUntilAired(caughtUp(), window, nowSec))
        // Not watched it yet: it is there to play.
        val behind = caughtUp(episode = 1175.0)
        assertFalse(hideFromContinueUntilAired(behind, window, nowSec))
        val advancedOntoIt = caughtUp().copy(watchedEpisodes = setOf(1175.0))
        assertFalse(hideFromContinueUntilAired(advancedOntoIt, window, nowSec))
    }

    /** An episode that aired late last night is in the window and not carried yet: still waiting. */
    @Test
    fun `an episode from last night that is not out yet keeps the row aside`() {
        val lateLastNight = nowSec - 60 * 60
        val window: UpcomingAiring = mapOf(title to mapOf(1177 to lateLastNight))
        assertTrue(hideFromContinueUntilAired(caughtUp(), window, nowSec))
        assertFalse(hideFromContinueUntilAired(caughtUp(), window, lateLastNight + 2 * 60 * 60))
    }
}
