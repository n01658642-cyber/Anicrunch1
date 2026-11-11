package com.anilili.ui.detail

import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.Media
import com.anilili.data.model.NextAiringEpisode
import com.anilili.data.model.ProviderData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailEpisodeCatalogTest {
    @Test
    fun completedAnimeUsesAniListTotal() {
        val episodes = anilistEpisodeCatalog(Media(id = 10, episodes = 3, status = "FINISHED"))

        assertEquals(listOf(1.0, 2.0, 3.0), episodes.map { it.number })
        assertEquals(listOf("anilist:10:1", "anilist:10:2", "anilist:10:3"), episodes.map { it.pipeId })
    }

    @Test
    fun airingAnimeStopsBeforeNextScheduledEpisode() {
        val episodes = anilistEpisodeCatalog(
            Media(id = 11, episodes = 12, nextAiringEpisode = NextAiringEpisode(episode = 4)),
        )

        assertEquals(listOf(1.0, 2.0, 3.0), episodes.map { it.number })
    }

    @Test
    fun nextAiringCountWorksWhenAniListTotalIsMissing() {
        val episodes = anilistEpisodeCatalog(
            Media(id = 12, episodes = null, nextAiringEpisode = NextAiringEpisode(episode = 3)),
        )

        assertEquals(listOf(1.0, 2.0), episodes.map { it.number })
    }

    @Test
    fun absentAniListEpisodeMetadataProducesEmptyCatalog() {
        assertTrue(anilistEpisodeCatalog(Media(id = 13)).isEmpty())
    }

    @Test
    fun upcomingSeasonListsNothingEvenWithAnnouncedCount() {
        assertTrue(anilistEpisodeCatalog(Media(id = 14, episodes = 12, status = "NOT_YET_RELEASED")).isEmpty())
    }

    @Test
    fun releasingOvaWithNoTotalAndNoScheduleIsUncounted() {
        // Natsuzuma (198048) as AniList files it: out, no total, no next airing.
        assertTrue(Media(id = 198048, status = "RELEASING", format = "OVA").episodeCountUnknown())
    }

    @Test
    fun countedOrScheduledOrUpcomingSeasonsAreNotUncounted() {
        assertFalse(Media(id = 15, episodes = 2, status = "RELEASING").episodeCountUnknown())
        assertFalse(Media(id = 16, nextAiringEpisode = NextAiringEpisode(episode = 3)).episodeCountUnknown())
        assertFalse(Media(id = 17, status = "NOT_YET_RELEASED").episodeCountUnknown())
    }

    @Test
    fun providerCatalogueCountsTheUnionOfEveryProvidersNumbers() {
        fun row(number: Double) = EpisodeItem("p:$number", number, title = "t", image = "i", filler = true)
        val catalog = EpisodesResult(
            listOf(
                ProviderData("hstream", sub = listOf(row(2.0), row(1.0)), dub = emptyList()),
                ProviderData("hentaihaven", sub = listOf(row(1.0), row(3.0)), dub = listOf(row(0.0))),
            ),
        )

        val episodes = providerEpisodeCatalog(20, catalog)

        assertEquals(listOf(1.0, 2.0, 3.0), episodes.map { it.number })
        // Ids match the AniList-counted rows, so playback treats both the same way.
        assertEquals(listOf("anilist:20:1", "anilist:20:2", "anilist:20:3"), episodes.map { it.pipeId })
        assertTrue(episodes.all { it.title == null && it.image == null && !it.filler })
    }

    @Test
    fun adultTitleCountsOnlyTheAdultServersWhenAnyAnswered() {
        // Imaizumin-chi (132203) as measured: ReAnime matched Super GALS! and listed 50.
        fun rows(count: Int) = (1..count).map { EpisodeItem("p:$it", it.toDouble(), null, null, false) }
        val catalog = EpisodesResult(
            listOf(
                ProviderData("hstream", sub = rows(6), dub = emptyList()),
                ProviderData("reanime", sub = rows(50), dub = emptyList()),
                ProviderData("hanime", sub = rows(6), dub = emptyList()),
            ),
        )

        assertEquals(6, providerEpisodeCatalog(132203, catalog, adult = true).size)
        assertEquals(50, providerEpisodeCatalog(132203, catalog, adult = false).size)
        // No adult server answered: every server's listing is all there is to count.
        val generalOnly = EpisodesResult(listOf(ProviderData("reanime", sub = rows(3), dub = emptyList())))
        assertEquals(3, providerEpisodeCatalog(132203, generalOnly, adult = true).size)
    }

    @Test
    fun emptyProviderCatalogueListsNothing() {
        assertTrue(providerEpisodeCatalog(21, EpisodesResult(emptyList())).isEmpty())
    }
}
