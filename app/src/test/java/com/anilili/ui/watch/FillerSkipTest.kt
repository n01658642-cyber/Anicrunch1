package com.anilili.ui.watch

import com.anilili.data.model.Category
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.SourcesResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FillerSkipTest {

    @Test
    fun `without filler skipping the next episode is the next one listed`() {
        val data = data(episodes = 6, fillers = setOf(3, 4), index = 1)
        assertEquals(2, data.nextIndex(skipFiller = false))
    }

    @Test
    fun `skipping filler passes over the whole run to the next canon episode`() {
        // Watching episode 2; 3 and 4 are filler, so moving on opens 5.
        val data = data(episodes = 6, fillers = setOf(3, 4), index = 1)
        assertEquals(4, data.nextIndex(skipFiller = true))
        assertFalse(data.advancesIntoNextSeason(skipFiller = true))
    }

    @Test
    fun `a filler episode offers to skip to the next canon one`() {
        val data = data(episodes = 6, fillers = setOf(3, 4), index = 2)
        assertTrue(data.canSkipFiller)
        assertEquals(4, data.nextIndex(skipFiller = true))
    }

    @Test
    fun `a canon episode offers no filler skip`() {
        assertFalse(data(episodes = 6, fillers = setOf(3, 4), index = 1).canSkipFiller)
    }

    @Test
    fun `filler to the end of a season with another after it skips into that season`() {
        val data = data(
            episodes = 6, fillers = setOf(5, 6), index = 3,
            next = NextSeason(2, "Shippuden", afterEpisode = 6),
        )
        assertTrue(data.advancesIntoNextSeason(skipFiller = true))
        assertNull(data.nextIndex(skipFiller = true))
        // Without skipping it is still an ordinary next.
        assertFalse(data.advancesIntoNextSeason(skipFiller = false))
        assertEquals(4, data.nextIndex(skipFiller = false))
    }

    @Test
    fun `a filler episode at the end of a season skips to the next season`() {
        val data = data(
            episodes = 6, fillers = setOf(5, 6), index = 4,
            next = NextSeason(2, "Shippuden", afterEpisode = 6),
        )
        assertTrue(data.canSkipFiller)
    }

    @Test
    fun `filler to the end with no season after it still moves on`() {
        // Stranding the viewer on the last canon episode with a dead Next would be worse.
        val data = data(episodes = 6, fillers = setOf(5, 6), index = 3)
        assertEquals(4, data.nextIndex(skipFiller = true))
        assertFalse(data.advancesIntoNextSeason(skipFiller = true))
    }

    @Test
    fun `a filler episode with only filler after it and nowhere else offers no skip`() {
        assertFalse(data(episodes = 6, fillers = setOf(5, 6), index = 4).canSkipFiller)
    }

    @Test
    fun `a list cut short of the finale does not skip its filler into the next season`() {
        // Five of six listed, the last two filler: the season's own count says it is not over.
        val data = data(
            episodes = 5, fillers = setOf(4, 5), index = 2,
            next = NextSeason(2, "Season 2", afterEpisode = 6),
        )
        assertFalse(data.advancesIntoNextSeason(skipFiller = true))
        assertEquals(3, data.nextIndex(skipFiller = true))
    }

    @Test
    fun `the last episode behaves as before either way`() {
        val data = data(episodes = 6, fillers = emptySet(), index = 5, next = NextSeason(2, "Season 2", 6))
        assertNull(data.nextIndex(skipFiller = true))
        assertTrue(data.advancesIntoNextSeason(skipFiller = true))
        assertTrue(data.advancesIntoNextSeason(skipFiller = false))
    }

    private fun data(episodes: Int, fillers: Set<Int>, index: Int, next: NextSeason? = null) = WatchData(
        episodes = (1..episodes).map {
            EpisodeItem(pipeId = "e$it", number = it.toDouble(), title = null, image = null, filler = it in fillers)
        },
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
