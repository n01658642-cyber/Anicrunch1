package com.anilili.data

import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.ProviderData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A viewer opened a same-day episode on a TV, was told there were no servers, pressed play again
 * and it played. The episode lists are served stale-while-revalidate, so the first entry resolved
 * against a catalogue fetched two days earlier — every server listed ten episodes and the viewer
 * had asked for eleven. Nothing was contacted: the walk needs a provider that lists the number, so
 * it built no candidates and reported "no playable source on any server" in a millisecond.
 *
 * These cover the reading that tells that apart from an ordinary miss, which is what decides
 * whether the watch screen spends a round trip refetching before it gives up.
 */
class StaleCatalogEpisodeTest {

    @Test
    fun `a catalogue fetched before the episode aired does not carry it`() {
        val stale = catalog(sub = 1..10)
        assertTrue(stale.carriesEpisode(10.0))
        assertFalse(stale.carriesEpisode(11.0))
        assertEquals(10.0, stale.lastEpisodeNumber!!, 0.0)

        val refreshed = catalog(sub = 1..11)
        assertTrue(refreshed.carriesEpisode(11.0))
        assertEquals(11.0, refreshed.lastEpisodeNumber!!, 0.0)
    }

    @Test
    fun `a dub trailing its sub is not a stale catalogue`() {
        // The dub genuinely lags the sub on a running show, and that has its own fallback further
        // down the watch screen. Judging this per category would refetch on every such episode and
        // still end up on sub.
        val trailing = EpisodesResult(
            listOf(ProviderData("bonk", sub = episodes(1..11), dub = episodes(1..4))),
        )
        assertTrue(trailing.carriesEpisode(11.0))
    }

    @Test
    fun `an episode only one server carries is still carried`() {
        // The first server to publish a new episode is often the only one for an hour or two, and
        // one is all the walk needs.
        val partial = EpisodesResult(
            listOf(
                ProviderData("anibd", sub = episodes(1..11), dub = emptyList()),
                ProviderData("kiwi", sub = episodes(1..10), dub = emptyList()),
                ProviderData("pewe", sub = episodes(1..9), dub = emptyList()),
            ),
        )
        assertTrue(partial.carriesEpisode(11.0))
        assertEquals(11.0, partial.lastEpisodeNumber!!, 0.0)
    }

    @Test
    fun `specials and recaps keep their fractional numbering`() {
        val withSpecial = EpisodesResult(
            listOf(ProviderData("bee", sub = episodes(1..10) + episode(10.5), dub = emptyList())),
        )
        assertTrue(withSpecial.carriesEpisode(10.5))
        assertFalse(withSpecial.carriesEpisode(11.0))
        assertEquals(10.5, withSpecial.lastEpisodeNumber!!, 0.0)
    }

    @Test
    fun `a catalogue listing nothing reports no last episode`() {
        val empty = EpisodesResult(listOf(ProviderData("bee", emptyList(), emptyList())))
        assertNull(empty.lastEpisodeNumber)
        assertFalse(empty.carriesEpisode(1.0))
        assertNull(EpisodesResult(emptyList()).lastEpisodeNumber)
    }

    private fun catalog(sub: IntRange) = EpisodesResult(
        listOf("bee", "anibd", "hop", "ally", "anikoto").map { name ->
            ProviderData(name, sub = episodes(sub), dub = emptyList())
        },
    )

    private fun episodes(range: IntRange) = range.map { episode(it.toDouble()) }

    private fun episode(number: Double) = EpisodeItem(
        pipeId = "ep-$number",
        number = number,
        title = null,
        image = null,
        filler = false,
    )
}
