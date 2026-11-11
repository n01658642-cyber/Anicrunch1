package com.anilili.ui.watch

import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.ProviderData
import com.anilili.data.model.SourceVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSourceOptionsTest {
    @Test
    fun excludesServersWithoutCurrentEpisodeAndAudioModesUnavailableOnIt() {
        val catalog = EpisodesResult(
            listOf(
                ProviderData(
                    name = "kiwi",
                    sub = listOf(episode(1)),
                    dub = listOf(episode(3)),
                ),
                ProviderData(
                    name = "anikoto",
                    sub = listOf(episode(1), episode(2)),
                    dub = listOf(episode(2)),
                ),
            ),
        )

        val options = availableSourceOptions(catalog, number = 2.0)

        assertFalse(options.any { it.provider == "kiwi" })
        assertEquals(setOf("sub", "dub"), options.filter { it.provider == "anikoto" }.map { it.category.api }.toSet())
        assertTrue(options.all { it.hasCurrentEpisode })
    }

    @Test
    fun defaultPriorityLeadsOverMeasuredSpeedButNotOverTheViewersRanking() {
        val catalog = EpisodesResult(
            listOf("kiwi", "anikoto", "bee", "anipm").map { name ->
                ProviderData(name = name, sub = listOf(episode(1)), dub = emptyList())
            },
        )
        // kiwi and anipm measured quickest on this device; bee and anikoto are in the default
        // priority and still come first, in that priority's order.
        val speed = mapOf("kiwi" to 100.0, "anipm" to 200.0, "bee" to 5_000.0, "anikoto" to 4_000.0)

        val auto = availableSourceOptions(catalog, number = 1.0, speedScore = { speed.getValue(it) })
        assertEquals(listOf("bee", "anikoto", "kiwi", "anipm"), auto.map { it.provider }.distinct())

        val ranked = availableSourceOptions(
            catalog, number = 1.0, priority = listOf("anipm"), speedScore = { speed.getValue(it) },
        )
        assertEquals(listOf("anipm", "bee", "anikoto", "kiwi"), ranked.map { it.provider }.distinct())
    }

    @Test
    fun fastServersShowImmediatelyWhileSlowScrapersWaitForConfirmation() {
        val candidates = listOf(
            option("anikoto"), // fast (API-backed Anivexa)
            option("kiwi"),   // fast (Miruro pipe native HLS)
            option("animegg"), // slow scraper, but one the sweep does validate
            option("anineko"), // slow scraper, but one the sweep does validate
        )

        // Nothing confirmed yet (first paint), nothing proven unavailable.
        val shown = visibleSourceOptions(candidates, isConfirmed = { false }, isUnavailable = { false })

        assertEquals(setOf("anikoto", "kiwi"), shown.map { it.provider }.toSet())
    }

    @Test
    fun serversTheSweepNeverValidatesAreOfferedUnconfirmed() {
        // These are held back from validation during playback, so requiring confirmation hid
        // them permanently: in the catalogue, carrying the episode, impossible to select.
        val candidates = listOf(option("allanime"), option("reanime"))

        val shown = visibleSourceOptions(candidates, isConfirmed = { false }, isUnavailable = { false })

        assertEquals(setOf("allanime", "reanime"), shown.map { it.provider }.toSet())
        assertFalse(candidates.any { validatesDuringPlayback(it.provider) })
    }

    @Test
    fun aNeverValidatedServerProvenDeadThisEpisodeIsDemotedNotRemoved() {
        // It used to be dropped. Dropping is what the "servers disappear" reports were: the same
        // set carries 1-10 minute cooldowns, so one refusal took the row away until the viewer
        // left the screen — and re-entering restored whichever timers had since run out. It is
        // kept and marked instead, because "tried, did not answer" is a different claim from
        // "this server does not exist".
        val shown = visibleSourceOptions(
            listOf(option("reanime")),
            isConfirmed = { false },
            isUnavailable = { it.provider == "reanime" },
        )

        assertEquals(listOf("reanime"), shown.map { it.provider })
        assertTrue(shown.single().recentlyFailed)
    }

    @Test
    fun aFailedServerSitsBehindEveryServerThatStillAnswers() {
        // Order is the whole mechanism: the viewer's next press has to land on something that
        // works, and the failed row has to still be reachable for the case where it was a bad
        // twenty seconds rather than a dead server.
        val candidates = listOf(option("kiwi"), option("anikoto"), option("hop"))

        val shown = visibleSourceOptions(
            candidates,
            isConfirmed = { false },
            isUnavailable = { it.provider == "kiwi" },
        )

        assertEquals(listOf("anikoto", "hop", "kiwi"), shown.map { it.provider })
        assertEquals(listOf(false, false, true), shown.map { it.recentlyFailed })
    }

    @Test
    fun beingProvenDeadDoesNotPromoteAnUnconfirmedSlowScraper() {
        // The demotion only rescues rows that had earned a place. A scraper the sweep validates
        // and has not confirmed yet was never advertised, and a failed probe is not the event
        // that should first advertise it.
        val shown = visibleSourceOptions(
            listOf(option("animegg")),
            isConfirmed = { false },
            isUnavailable = { true },
        )

        assertTrue(shown.isEmpty())
        assertTrue(validatesDuringPlayback("animegg"))
    }

    @Test
    fun `a server offers only the subtitle variants its backend still serves`() {
        // The variants come from the catalog rather than being assumed, because a backend can drop
        // one: bonk's hard-sub category answered HTTP 444 for three weeks from 2026-08-18 and an
        // entry that could only fail also became the first fallback from its own soft-sub. It
        // serves both again (2026-09-06), so the case is carried here by hop, which is soft-sub
        // only in the live capability map.
        val catalog = EpisodesResult(
            listOf(
                ProviderData("hop", sub = listOf(episode(1)), dub = emptyList()),
                ProviderData("bonk", sub = listOf(episode(1)), dub = emptyList()),
                ProviderData("twin", sub = listOf(episode(1)), dub = emptyList()),
            ),
        )

        val options = availableSourceOptions(catalog, number = 1.0)

        assertEquals(
            listOf(SourceVariant.SOFT_SUB),
            options.filter { it.provider == "hop" }.map(WatchSourceOption::variant),
        )
        // Servers that genuinely serve both still get two entries, in the backend's own order.
        assertEquals(
            listOf(SourceVariant.SOFT_SUB, SourceVariant.HARD_SUB),
            options.filter { it.provider == "bonk" }.map(WatchSourceOption::variant),
        )
        assertEquals(
            listOf(SourceVariant.HARD_SUB, SourceVariant.SOFT_SUB),
            options.filter { it.provider == "twin" }.map(WatchSourceOption::variant),
        )
    }

    @Test
    fun confirmedSlowScraperLeadsAndFailedFastServerFallsToTheBack() {
        val candidates = listOf(option("anikoto"), option("reanime"))

        val shown = visibleSourceOptions(
            candidates,
            isConfirmed = { it.provider == "reanime" }, // validated playable
            isUnavailable = { it.provider == "anikoto" }, // fast but proven dead this episode
        )

        assertEquals(listOf("reanime", "anikoto"), shown.map { it.provider })
        assertFalse(shown.first().recentlyFailed)
        assertTrue(shown.last().recentlyFailed)
    }

    @Test
    fun miruroRetryIsOnlyNeededWhenEveryPipeProviderIsMissing() {
        // Both fixtures named senshi until 2026-09-10. It was removed that day, and an unknown name
        // reads as Miruro by construction — `sourceOf` is "Anivexa if listed, else Miruro" — so the
        // negative case quietly became a positive one. Named providers only here.
        assertTrue(hasMiruroSourceOptions(listOf(option("kiwi"), option("anikoto"))))
        assertTrue(hasMiruroSourceOptions(listOf(option("bonk"))))
        assertFalse(hasMiruroSourceOptions(listOf(option("anikoto"), option("anichan"))))
    }

    private fun option(provider: String) = WatchSourceOption(
        provider = provider,
        category = com.anilili.data.model.Category.SUB,
        hasCurrentEpisode = true,
        episodeCount = 1,
    )

    private fun episode(number: Int) = EpisodeItem(
        pipeId = "episode-$number",
        number = number.toDouble(),
        title = null,
        image = null,
        filler = false,
    )

    @Test
    fun `the viewer's own server ranking leads the picker`() {
        // The resolver already tries their order. Sorting the picker purely by the catalogue made
        // the two disagree about what "first" means, so someone who ranked a server top still saw
        // it partway down the sheet.
        val catalog = EpisodesResult(
            listOf(
                ProviderData(name = "bonk", sub = listOf(episode(1)), dub = emptyList()),
                ProviderData(name = "anichan", sub = listOf(episode(1)), dub = emptyList()),
                ProviderData(name = "animegg", sub = listOf(episode(1)), dub = emptyList()),
            ),
        )

        val ranked = availableSourceOptions(catalog, number = 1.0, priority = listOf("animegg", "bonk"))

        assertEquals(listOf("animegg", "bonk", "anichan"), ranked.map { it.provider }.distinct())
    }

    @Test
    fun `servers the viewer never ranked keep the catalogue's speed-first order`() {
        val catalog = EpisodesResult(
            listOf(
                ProviderData(name = "animegg", sub = listOf(episode(1)), dub = emptyList()),
                ProviderData(name = "bonk", sub = listOf(episode(1)), dub = emptyList()),
            ),
        )

        val unranked = availableSourceOptions(catalog, number = 1.0)

        assertEquals(listOf("bonk", "animegg"), unranked.map { it.provider }.distinct())
    }
}
