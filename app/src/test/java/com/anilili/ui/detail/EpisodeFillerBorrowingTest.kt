package com.anilili.ui.detail

import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.ProviderData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EpisodeFillerBorrowingTest {
    private fun episode(provider: String, number: Int, filler: Boolean = false) = EpisodeItem(
        pipeId = "$provider-$number",
        number = number.toDouble(),
        title = null,
        image = null,
        filler = filler,
    )

    /** Naruto on anikoto: its rows say nothing about filler while bee's, in the same catalog, do. */
    @Test
    fun `a server without filler flags borrows them from one that has them`() {
        val spine = (96..100).map { episode("anikoto", it) }
        val bee = (96..100).map { episode("bee", it, filler = it != 96) }
        val filled = fillEpisodeFillerFromCatalog(
            spine,
            EpisodesResult(
                listOf(
                    ProviderData(name = "anikoto", sub = spine, dub = emptyList()),
                    ProviderData(name = "bee", sub = bee, dub = emptyList()),
                ),
            ),
        )
        assertEquals(listOf(false, true, true, true, true), filled.map { it.filler })
        assertEquals(spine.map { it.pipeId }, filled.map { it.pipeId })
    }

    @Test
    fun `nothing to borrow leaves the list as it was`() {
        val spine = listOf(episode("anikoto", 1), episode("anikoto", 2, filler = true))
        val catalog = EpisodesResult(listOf(ProviderData(name = "anikoto", sub = spine, dub = emptyList())))
        val filled = fillEpisodeFillerFromCatalog(spine, catalog)
        assertEquals(listOf(false, true), filled.map { it.filler })
        assertSame(spine, fillEpisodeFillerFromCatalog(spine, EpisodesResult(emptyList())))
    }
}
