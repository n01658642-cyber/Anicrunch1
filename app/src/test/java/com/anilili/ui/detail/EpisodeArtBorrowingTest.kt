package com.anilili.ui.detail

import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.ProviderData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A viewer watching an airing season on kiwi's dub list saw an empty frame on every row while
 * bonk's rows for those same episodes — already fetched, sitting in the same merged catalog —
 * carried real stills. No metadata catalogue covers a season that recent, so the provider's own
 * thumbnail is the only picture there is, and which provider happens to be selected is no reason
 * to show none.
 */
class EpisodeArtBorrowingTest {
    private fun episode(number: Double, image: String? = null) =
        EpisodeItem(pipeId = "p:$number", number = number, title = null, image = image, filler = false)

    private fun catalog(vararg providers: ProviderData) = EpisodesResult(providers.toList())

    @Test
    fun `a row with no picture takes one from another provider holding that episode`() {
        val spine = listOf(episode(1.0), episode(2.0))
        val filled = fillEpisodeArtFromCatalog(
            spine,
            catalog(
                ProviderData(name = "kiwi", sub = emptyList(), dub = spine),
                ProviderData(
                    name = "bonk",
                    sub = listOf(episode(1.0, "still-1"), episode(2.0, "still-2")),
                    dub = emptyList(),
                ),
            ),
        )
        assertEquals(listOf("still-1", "still-2"), filled.map { it.image })
    }

    @Test
    fun `a dub row may borrow from a sub row of the same episode`() {
        val filled = fillEpisodeArtFromCatalog(
            listOf(episode(3.0)),
            catalog(
                ProviderData(
                    name = "bonk",
                    sub = listOf(episode(3.0, "still-3")),
                    dub = listOf(episode(3.0)),
                ),
            ),
        )
        assertEquals("still-3", filled.single().image)
    }

    @Test
    fun `a row that came with its own picture keeps it`() {
        val filled = fillEpisodeArtFromCatalog(
            listOf(episode(1.0, "mine")),
            catalog(ProviderData(name = "bonk", sub = listOf(episode(1.0, "theirs")), dub = emptyList())),
        )
        assertEquals("mine", filled.single().image)
    }

    @Test
    fun `an episode no provider has a picture for stays blank`() {
        val filled = fillEpisodeArtFromCatalog(
            listOf(episode(9.0)),
            catalog(ProviderData(name = "bonk", sub = listOf(episode(1.0, "still-1")), dub = emptyList())),
        )
        assertNull(filled.single().image)
    }

    @Test
    fun `a list that needs nothing is handed back untouched`() {
        val spine = listOf(episode(1.0, "mine"))
        assertSame(
            spine,
            fillEpisodeArtFromCatalog(
                spine,
                catalog(ProviderData(name = "bonk", sub = listOf(episode(1.0, "theirs")), dub = emptyList())),
            ),
        )
    }

    @Test
    fun `a blank string is not a picture`() {
        val filled = fillEpisodeArtFromCatalog(
            listOf(episode(1.0, "  ")),
            catalog(ProviderData(name = "bonk", sub = listOf(episode(1.0, "still-1")), dub = emptyList())),
        )
        assertEquals("still-1", filled.single().image)
    }
}
