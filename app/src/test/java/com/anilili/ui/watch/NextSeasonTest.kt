package com.anilili.ui.watch

import com.anilili.data.model.Category
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextSeasonTest {

    private val season1 = Media(id = 1, format = "TV", status = "FINISHED", episodes = 12)
    private val season2 = Media(id = 2, format = "TV", status = "FINISHED", episodes = 12)
    private val season3 = Media(id = 3, format = "TV", status = "NOT_YET_RELEASED")

    @Test
    fun `a finished season moves on to the one after it`() {
        assertEquals(2, nextSeasonAfter(season1, listOf(season1, season2, season3))?.id)
    }

    @Test
    fun `a season that is still airing does not move on`() {
        val airing = season1.copy(status = "RELEASING")
        assertNull(nextSeasonAfter(airing, listOf(airing, season2)))
    }

    @Test
    fun `a season that has not started airing is not moved on to`() {
        assertNull(nextSeasonAfter(season2, listOf(season1, season2, season3)))
    }

    @Test
    fun `a season airing now is moved on to, since its first episode is out`() {
        val airing = season2.copy(status = "RELEASING")
        assertEquals(2, nextSeasonAfter(season1, listOf(season1, airing))?.id)
    }

    @Test
    fun `the last season and a title outside the chain go nowhere`() {
        assertNull(nextSeasonAfter(season2, listOf(season1, season2)))
        assertNull(nextSeasonAfter(season1, listOf(season2)))
    }

    @Test
    fun `a film does not move on to the season that aired after it`() {
        val film = Media(id = 9, format = "MOVIE", status = "FINISHED")
        assertNull(nextSeasonAfter(film, listOf(season1, film, season2)))
    }

    @Test
    fun `the last listed episode of a finished season continues into the next`() {
        val data = data(episodes = 12, index = 11, next = NextSeason(2, "Season 2", afterEpisode = 12))
        assertTrue(data.continuesIntoNextSeason)
        assertTrue(data.canAdvance)
    }

    @Test
    fun `a list that stops short of the finale does not skip it for the next season`() {
        // The servers carry eleven of twelve: episode 11 is last in the list but not in the season.
        val data = data(episodes = 11, index = 10, next = NextSeason(2, "Season 2", afterEpisode = 12))
        assertFalse(data.continuesIntoNextSeason)
        assertFalse(data.canAdvance)
    }

    @Test
    fun `an episode with more after it is an ordinary next`() {
        val data = data(episodes = 12, index = 5, next = NextSeason(2, "Season 2", afterEpisode = 12))
        assertFalse(data.continuesIntoNextSeason)
        assertTrue(data.canAdvance)
    }

    @Test
    fun `an unknown episode count trusts the list`() {
        val data = data(episodes = 12, index = 11, next = NextSeason(2, "Season 2", afterEpisode = null))
        assertTrue(data.continuesIntoNextSeason)
    }

    @Test
    fun `no next season leaves the last episode with nowhere to go`() {
        val data = data(episodes = 12, index = 11, next = null)
        assertFalse(data.canAdvance)
    }

    private fun data(episodes: Int, index: Int, next: NextSeason?) = WatchData(
        episodes = (1..episodes).map { EpisodeItem(pipeId = "e$it", number = it.toDouble(), title = null, image = null, filler = false) },
        currentIndex = index,
        provider = "bonk",
        category = Category.SUB,
        sourceOptions = emptyList(),
        anilistId = 1,
        sources = SourcesResult(emptyList(), emptyList(), null, null),
        chosenStream = null,
        seriesTitle = "Anime",
        artworkUrl = null,
        nextSeason = next,
    )
}
