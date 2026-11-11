package com.anilili.ui.watch

import com.anilili.data.model.EpisodeItem
import com.anilili.data.settings.EpisodeLayout
import com.anilili.ui.components.blockIndexContaining
import com.anilili.ui.components.episodeBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InPlayerEpisodeListTest {
    @Test
    fun tvPlayerControlOrderIncludesEpisodesButtonWhenHasEpisodesIsTrue() {
        val controlsWithoutEpisodes = tvPlayerControlOrder(hasEpisodes = false)
        assertFalse(controlsWithoutEpisodes.contains(TvPlayerControl.EPISODES))

        val controlsWithEpisodes = tvPlayerControlOrder(hasEpisodes = true)
        assertTrue(controlsWithEpisodes.contains(TvPlayerControl.EPISODES))
        assertEquals(TvPlayerControl.EPISODES, controlsWithEpisodes[5])
    }

    @Test
    fun tvPlayerControlOrderIncludesMyListAfterEpisodes() {
        val controls = tvPlayerControlOrder(hasEpisodes = true, hasList = true)

        assertEquals(TvPlayerControl.EPISODES, controls[5])
        assertEquals(TvPlayerControl.MY_LIST, controls[6])
        assertEquals(TvPlayerControl.MUTE, controls[7])
    }

    @Test
    fun episodeBlockIndexingLocatesCorrectBlockForInPlayerList() {
        val episodes = (1..250).map { number ->
            EpisodeItem(
                pipeId = "ep-$number",
                number = number.toDouble(),
                title = "Episode $number",
                image = null,
                filler = false,
            )
        }

        val blocks = episodeBlocks(episodes)
        assertEquals(3, blocks.size) // 1-100, 101-200, 201-250

        val blockIndex150 = blockIndexContaining(blocks, 150.0)
        assertEquals(1, blockIndex150)
        assertEquals("101 – 200", blocks[blockIndex150].label)

        val blockIndex220 = blockIndexContaining(blocks, 220.0)
        assertEquals(2, blockIndex220)
    }
}

/**
 * A long season stops being a list of artwork rows and becomes a grid of numbers: 100 rows with a
 * thumbnail each is a lot of scrolling for someone who already knows they want episode 74.
 */
class EpisodeGridLayoutTest {
    @Test
    fun `a short season keeps its artwork rows`() {
        assertFalse(showsEpisodeGrid(12))
        assertFalse(showsEpisodeGrid(25))
    }

    @Test
    fun `past twenty-five episodes the season switches to the grid`() {
        assertTrue(showsEpisodeGrid(26))
        assertTrue(showsEpisodeGrid(100))
    }

    @Test
    fun `the viewer's choice from the drawer header overrides the automatic rule`() {
        assertTrue(showsEpisodeGrid(12, EpisodeLayout.GRID))
        assertFalse(showsEpisodeGrid(100, EpisodeLayout.LIST))
        // A grid row holds five episodes, so the scroll target follows the chosen layout too.
        assertEquals(7, episodeDrawerItemIndex(7, blockIndex = 0, blockCount = 1, blockSize = 100, layout = EpisodeLayout.LIST))
        assertEquals(1, episodeDrawerItemIndex(7, blockIndex = 0, blockCount = 1, blockSize = 12, layout = EpisodeLayout.GRID))
    }

    @Test
    fun `rows are filled left to right and the last one is allowed to be short`() {
        assertEquals(
            listOf(listOf(1, 2, 3, 4, 5), listOf(6, 7, 8)),
            episodeGridRows((1..8).toList(), columns = 5),
        )
    }

    @Test
    fun `an exact multiple leaves no trailing empty row`() {
        assertEquals(2, episodeGridRows((1..10).toList(), columns = 5).size)
    }

    @Test
    fun `no episodes means no rows to draw`() {
        assertTrue(episodeGridRows(emptyList<Int>(), columns = 5).isEmpty())
    }

    @Test
    fun episodeDrawerItemIndexCountsHeadersAndGridRows() {
        // One short season: rows only, no headers.
        assertEquals(7, episodeDrawerItemIndex(positionInBlock = 7, blockIndex = 0, blockCount = 1, blockSize = 12))
        // Naruto ep 150: 50th of season 2 (101-200), a grid season. Two headers, then row 9.
        assertEquals(2 + 49 / 5, episodeDrawerItemIndex(positionInBlock = 49, blockIndex = 1, blockCount = 3, blockSize = 100))
        // A short third season is rows again, after three headers.
        assertEquals(3 + 4, episodeDrawerItemIndex(positionInBlock = 4, blockIndex = 2, blockCount = 3, blockSize = 20))
    }
}
