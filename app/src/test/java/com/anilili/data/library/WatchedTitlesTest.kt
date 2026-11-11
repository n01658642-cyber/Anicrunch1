package com.anilili.data.library

import com.anilili.data.model.Media
import com.anilili.data.model.WatchedFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedTitlesTest {

    private val statuses = mapOf(
        1 to "COMPLETED",
        2 to "CURRENT",
        3 to "PLANNING",
        4 to "DROPPED",
        5 to "PAUSED",
        6 to "REPEATING",
    )

    private fun row(id: Int, vararg watched: Int) = HistoryEntry(
        anilistId = id,
        title = "t$id",
        cover = null,
        episodeNumber = watched.lastOrNull()?.toDouble() ?: 1.0,
        provider = "p",
        category = "sub",
        watchedEpisodes = watched.map(Int::toDouble).toSet(),
    )

    @Test
    fun `off hides nothing`() {
        assertEquals(emptySet<Int>(), WatchedTitles.excludedIds(WatchedFilter.OFF, statuses, listOf(row(9))))
    }

    @Test
    fun `completed sends only the account's completed titles`() {
        assertEquals(setOf(1), WatchedTitles.excludedIds(WatchedFilter.COMPLETED, statuses, listOf(row(9, 1))))
    }

    @Test
    fun `started sends everything touched except plan to watch`() {
        assertEquals(
            setOf(1, 2, 4, 5, 6, 9),
            WatchedTitles.excludedIds(WatchedFilter.STARTED, statuses, listOf(row(9))),
        )
    }

    @Test
    fun `a finished title played through on this device counts as completed`() {
        val show = Media(id = 20, episodes = 3, status = "FINISHED")
        assertTrue(WatchedTitles.hides(show, WatchedFilter.COMPLETED, emptyMap(), listOf(row(20, 1, 2, 3))))
        assertFalse(WatchedTitles.hides(show, WatchedFilter.COMPLETED, emptyMap(), listOf(row(20, 1, 3))))
    }

    @Test
    fun `a title marked watched without an account counts under both filters`() {
        assertEquals(setOf(1, 30), WatchedTitles.excludedIds(WatchedFilter.COMPLETED, statuses, emptyList(), setOf(30)))
        assertTrue(30 in WatchedTitles.excludedIds(WatchedFilter.STARTED, statuses, emptyList(), setOf(30)))
        assertEquals(emptySet<Int>(), WatchedTitles.excludedIds(WatchedFilter.OFF, statuses, emptyList(), setOf(30)))
    }

    @Test
    fun `an airing title is never completed on this device`() {
        val airing = Media(id = 21, episodes = 3, status = "RELEASING")
        assertFalse(WatchedTitles.hides(airing, WatchedFilter.COMPLETED, emptyMap(), listOf(row(21, 1, 2, 3))))
    }
}
