package com.anilili.ui.detail

import com.anilili.data.model.EpisodeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonDownloadScopeTest {
    private fun episode(number: Int, filler: Boolean = false) = EpisodeItem(
        pipeId = "episode-$number",
        number = number.toDouble(),
        title = "Title $number",
        image = null,
        filler = filler,
    )

    @Test
    fun `a filtered list says so, because all means only the matches`() {
        // The dangerous confusion this label exists to prevent: "All 6 shown" over a filter is a
        // very different download from "All 100 shown" over a block.
        val label = seasonDownloadScopeLabel(
            seasonLabel = "Season 2",
            blockLabel = "1 – 100",
            filterQuery = " dragon ",
            shownCount = 6,
        )
        assertEquals("Matching “dragon” · 6 episodes", label)
    }

    @Test
    fun `a chunked long-runner names the range, not the season total`() {
        val label = seasonDownloadScopeLabel(
            seasonLabel = "Season 1",
            blockLabel = "101 – 200",
            filterQuery = "",
            shownCount = 100,
        )
        assertEquals("Season 1 · episodes 101 – 200", label)
    }

    @Test
    fun `a single-cour season falls back to a plain count`() {
        // One block means the range picker is hidden, so quoting a range would name a control the
        // viewer cannot see.
        val label = seasonDownloadScopeLabel(
            seasonLabel = null,
            blockLabel = null,
            filterQuery = "",
            shownCount = 12,
        )
        assertEquals("12 episodes", label)
    }

    @Test
    fun `a season with one block still names the season`() {
        val label = seasonDownloadScopeLabel(
            seasonLabel = "Season 3",
            blockLabel = null,
            filterQuery = "",
            shownCount = 13,
        )
        assertEquals("Season 3 · 13 episodes", label)
    }

    @Test
    fun `a whitespace-only filter is not a filter`() {
        val label = seasonDownloadScopeLabel(
            seasonLabel = "Season 1",
            blockLabel = null,
            filterQuery = "   ",
            shownCount = 24,
        )
        assertTrue("blank query must not be quoted back", !label.contains("Matching"))
        assertEquals("Season 1 · 24 episodes", label)
    }

    @Test
    fun `explicit episode picks keep their screen order`() {
        val episodes = listOf(episode(1), episode(2), episode(3))
        val selected = selectedDownloadEpisodes(
            episodes,
            selectedEpisodeIds = setOf("episode-3", "episode-1"),
            skipFillers = false,
        )
        assertEquals(listOf(1.0, 3.0), selected.map(EpisodeItem::number))
    }

    @Test
    fun `skip fillers removes selected filler episodes without removing normal picks`() {
        val episodes = listOf(episode(1), episode(2, filler = true), episode(3))
        val selected = selectedDownloadEpisodes(
            episodes,
            selectedEpisodeIds = episodes.mapTo(mutableSetOf(), EpisodeItem::pipeId),
            skipFillers = true,
        )
        assertEquals(listOf(1.0, 3.0), selected.map(EpisodeItem::number))
    }
}
