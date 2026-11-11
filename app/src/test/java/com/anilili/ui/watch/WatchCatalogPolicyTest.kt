package com.anilili.ui.watch

import com.anilili.data.model.EpisodeItem
import com.anilili.ui.components.blockIndexContaining
import com.anilili.ui.components.episodeBlocks
import com.anilili.ui.components.filterEpisodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchCatalogPolicyTest {
    @Test
    fun `long TV series defer supplemental providers while ordinary series still load eagerly`() {
        assertTrue(deferWatchSupplementalCatalog(isTv = true, episodeCount = 1176))
        assertTrue(deferWatchSupplementalCatalog(isTv = true, episodeCount = 101))
        assertFalse(deferWatchSupplementalCatalog(isTv = true, episodeCount = 100))
        assertFalse(deferWatchSupplementalCatalog(isTv = true, episodeCount = 12))
        assertFalse(deferWatchSupplementalCatalog(isTv = false, episodeCount = 1176))
    }

    @Test
    fun `partial provider loading leaves episode 852 and the final episode reachable`() {
        val episodes = (1..1176).map { number ->
            EpisodeItem("ep-$number", number.toDouble(), "Episode $number", null, false)
        }
        val blocks = episodeBlocks(episodes)
        val resumedBlock = blocks[blockIndexContaining(blocks, 852.0)]
        assertEquals(100, resumedBlock.episodes.size)
        assertTrue(resumedBlock.episodes.any { it.number == 852.0 })
        assertEquals(1176.0, blocks.last().episodes.last().number, 0.0)
        assertEquals(1176.0, filterEpisodes(episodes, "1176").single().number, 0.0)
    }
}
