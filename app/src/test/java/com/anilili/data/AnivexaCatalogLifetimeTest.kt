package com.anilili.data

import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.ProviderData
import org.junit.Assert.assertEquals
import org.junit.Test

class AnivexaCatalogLifetimeTest {

    private val fullTtl = 2L * 60 * 60 * 1000

    private fun catalogue(providers: Int, cutOff: List<String> = emptyList()) = EpisodesResult(
        providers = (1..providers).map { index ->
            ProviderData(
                name = "provider$index",
                sub = listOf(
                    EpisodeItem(
                        pipeId = "p$index-1",
                        number = 1.0,
                        title = "Episode 1",
                        image = null,
                        filler = false,
                    ),
                ),
                dub = emptyList(),
            )
        },
        cutOff = cutOff,
    )

    @Test
    fun `a complete pass keeps the full lifetime`() {
        assertEquals(fullTtl, anivexaCatalogTtlMs(catalogue(providers = 8), fullTtl))
    }

    @Test
    fun `a pass that lost providers to the budget expires in minutes, not hours`() {
        // The measured case: eight of sixteen answered because the other eight were still working
        // when the budget ran out. The head count cannot see that — this is the same eight-provider
        // list as the test above.
        val result = catalogue(providers = 8, cutOff = listOf("animegg", "toonhub", "uniquestream"))

        assertEquals(PARTIAL_EPISODES_TTL, anivexaCatalogTtlMs(result, fullTtl))
    }

    @Test
    fun `an incomplete pass never lengthens a deliberately shortened lifetime`() {
        // A measurement run shortens the catalogue TTL so the same title can be opened twice and
        // actually refetch. Handing it the partial lifetime instead would undo that.
        val measurementTtl = 30_000L
        val result = catalogue(providers = 2, cutOff = listOf("animegg"))

        assertEquals(measurementTtl, anivexaCatalogTtlMs(result, measurementTtl))
    }

    @Test
    fun `the partial lifetime outlasts browsing one title but not returning to it`() {
        // Ten minutes is the whole trade: short enough that coming back re-runs the fan-out, long
        // enough that switching servers on one episode does not.
        assertEquals(10L * 60 * 1000, PARTIAL_EPISODES_TTL)
    }

    @Test
    fun `a cached catalogue decodes as complete rather than as cut off`() {
        // cutOff is @Transient on purpose: it describes one fetch, and a stored row was already
        // judged when it was written. If it survived the round trip the entry would be re-shortened
        // every time it was read, which is a refetch loop.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val stored = catalogue(providers = 3, cutOff = listOf("animegg"))

        val roundTripped = json.decodeFromString(
            EpisodesResult.serializer(),
            json.encodeToString(EpisodesResult.serializer(), stored),
        )

        assertEquals(emptyList<String>(), roundTripped.cutOff)
        assertEquals(stored.providerNames, roundTripped.providerNames)
        assertEquals(fullTtl, anivexaCatalogTtlMs(roundTripped, fullTtl))
    }
}
