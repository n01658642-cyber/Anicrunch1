package com.anilili.data.remote

import com.anilili.data.model.FuzzyDate
import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every case here is a live answer TMDB gave for a title in the library that reported the bug, not
 * an invented one. The mapping is the whole risk of this integration: reaching the data is a single
 * request, and putting the wrong show's stills on an episode list is worse than showing none.
 */
class TmdbEpisodeMappingTest {
    private fun media(
        romaji: String? = null,
        english: String? = null,
        native: String? = null,
        episodes: Int? = null,
        year: Int? = null,
    ) = Media(
        id = 1,
        title = MediaTitle(romaji = romaji, english = english, native = native),
        episodes = episodes,
        seasonYear = year,
        startDate = year?.let { FuzzyDate(year = it) },
    )

    private fun series(name: String?, original: String? = null) =
        TmdbSeries(id = 1, name = name, original_name = original)

    private fun season(number: Int, episodes: Int, airDate: String?) =
        TmdbSeasonSummary(season_number = number, episode_count = episodes, air_date = airDate)

    private fun group(name: String, episodes: Int, firstAir: String?) = TmdbGroup(
        name = name,
        episodes = (1..episodes).map {
            TmdbEpisode(episode_number = it, air_date = if (it == 1) firstAir else null)
        },
    )

    @Test
    fun `spacing and case never survive the trip between catalogues`() {
        assertEquals(normalizeTitle("Dan Da Dan"), normalizeTitle("Dandadan"))
        assertEquals(normalizeTitle("Re:ZERO -Starting Life-"), normalizeTitle("re zero starting life"))
    }

    @Test
    fun `a season marker is not part of the series name`() {
        // The season is chosen by episode count and year; this only decides which show it is.
        assertEquals(normalizeTitle("Youjo Senki"), normalizeTitle("Youjo Senki II"))
        assertEquals(normalizeTitle("Kaguya-sama"), normalizeTitle("Kaguya-sama Season 3"))
        assertEquals(normalizeTitle("Shingeki no Kyojin"), normalizeTitle("Shingeki no Kyojin Final Season"))
        assertEquals(normalizeTitle("Jujutsu Kaisen"), normalizeTitle("Jujutsu Kaisen 2nd Season"))
    }

    @Test
    fun `an english-only TMDB name still matches a romaji AniList one`() {
        // AniList calls the 2026 season "Youjo Senki II"; TMDB calls the show "Saga of Tanya the Evil".
        val tanya = media(romaji = "Youjo Senki II", english = "Saga of Tanya the Evil Season 2")
        assertTrue(titlesMatch(tanya, series("Saga of Tanya the Evil")))
    }

    /**
     * The bundled id-map rates AniList 171018 (Dandadan) a HIGH-confidence match for TMDB 274861,
     * which is a 2024 Chinese drama. Taken at face value it would have illustrated Dandadan's
     * episode list with that drama's stills — the exact failure the Konoha lookup already refuses
     * to risk on adult titles, arriving by a different road.
     */
    @Test
    fun `a confidently wrong id-map entry is rejected`() {
        val dandadan = media(romaji = "Dandadan", english = "Dan Da Dan")
        assertFalse(titlesMatch(dandadan, series("完蛋我养的小白脸是大佬")))
        assertTrue(titlesMatch(dandadan, series("Dan Da Dan", original = "ダンダダン")))
    }

    @Test
    fun `a title too short to mean anything never matches on containment`() {
        assertFalse(titlesMatch(media(romaji = "One"), series("One Piece")))
    }

    @Test
    fun `the season with the right length and year wins`() {
        // Saga of Tanya the Evil: season 1 aired 2017 with 12, season 2 in 2026 with 12.
        val seasons = listOf(season(1, 12, "2017-01-06"), season(2, 12, "2026-07-08"))
        val picked = pickSeason(seasons, wantedEpisodes = 12, wantedYear = 2026)
        assertEquals(2, picked?.season_number)
    }

    @Test
    fun `specials are never mistaken for a season`() {
        val seasons = listOf(season(0, 12, "2026-01-01"), season(1, 12, "2026-04-04"))
        assertEquals(1, pickSeason(seasons, wantedEpisodes = 12, wantedYear = 2026)?.season_number)
    }

    @Test
    fun `a lone season of the right length is taken even when the year disagrees`() {
        // A season that started in December, or slipped a quarter, still has the right episodes.
        val seasons = listOf(season(1, 12, "2025-12-28"))
        assertEquals(1, pickSeason(seasons, wantedEpisodes = 12, wantedYear = 2026)?.season_number)
    }

    @Test
    fun `no season is picked when the run does not line up`() {
        // Dandadan: TMDB holds both AniList seasons as one season of 24.
        assertNull(pickSeason(listOf(season(1, 24, "2024-10-04")), wantedEpisodes = 12, wantedYear = 2026))
    }

    /**
     * Caught by replaying this against live TMDB rather than by reading it. Dandadan's merged run
     * of 24 aired in 2024 and AniList's first season is twelve episodes of 2024, so falling back to
     * a year match after the count failed accepted it — handing a twelve-episode season a
     * twenty-four episode run, which puts the wrong still on every row of the second half.
     */
    @Test
    fun `a matching year cannot rescue a season of the wrong length`() {
        assertNull(pickSeason(listOf(season(1, 24, "2024-10-04")), wantedEpisodes = 12, wantedYear = 2024))
        assertNull(pickGroup(listOf(group("All", episodes = 24, firstAir = "2024-10-04")), 12, 2024))
    }

    /**
     * Attack on Titan, live: AniList's Final Season Part 2 is twelve episodes from 2022, TMDB keeps
     * the whole Final Season as one season of 28, and its *second* season is twelve episodes from
     * 2017. Twelve was unique, so the count rule handed Part 2 the 2017 season and one of the most
     * watched titles there is carried another season's titles and stills on every row.
     */
    @Test
    fun `a count match years away from the entry is held back for the groups`() {
        val titan = listOf(
            season(1, 25, "2013-04-07"),
            season(2, 12, "2017-04-01"),
            season(3, 22, "2018-07-23"),
            season(4, 28, "2020-12-07"),
        )
        assertNull(pickSeason(titan, wantedEpisodes = 12, wantedYear = 2022))
        assertEquals(2, farYearSeason(titan, wantedEpisodes = 12, wantedYear = 2022)?.season_number)
        // The year that fits is a real match, not a fallback.
        assertNull(farYearSeason(titan, wantedEpisodes = 12, wantedYear = 2017))
    }

    @Test
    fun `a season that only slipped is a match rather than a fallback`() {
        val seasons = listOf(season(1, 12, "2025-12-28"))
        assertEquals(1, pickSeason(seasons, wantedEpisodes = 12, wantedYear = 2026)?.season_number)
        assertNull(farYearSeason(seasons, wantedEpisodes = 12, wantedYear = 2026))
    }

    /**
     * A one-episode special matches no season and no group by count, so it is found by its air date
     * in TMDB's specials season — which only works when AniList states the whole date. Attack on
     * Titan's two FINAL CHAPTERS specials are season 0 episodes 36 and 37, dated to the day.
     */
    @Test
    fun `a special is located by the day it aired`() {
        assertEquals("2023-11-05", isoAirDate(FuzzyDate(year = 2023, month = 11, day = 5)))
        assertEquals("2023-03-04", isoAirDate(FuzzyDate(year = 2023, month = 3, day = 4)))
        assertNull(isoAirDate(FuzzyDate(year = 2023, month = 11)))
        assertNull(isoAirDate(FuzzyDate(year = 2023)))
        assertNull(isoAirDate(null))
    }

    @Test
    fun `a lone season is taken when AniList has no count to check it against`() {
        // A releasing title with unknown totals still deserves whatever stills exist.
        assertEquals(1, pickSeason(listOf(season(1, 24, "2024-10-04")), null, 2024)?.season_number)
    }

    /**
     * Also caught live. TMDB indexes the series, not the season: a search for "Youjo Senki II"
     * returns nothing whatsoever, so the 2026 season of Saga of Tanya the Evil resolved to no
     * series at all until the query dropped the marker.
     */
    @Test
    fun `a search asks for the series, not the season`() {
        assertEquals("Youjo Senki", seriesSearchQuery("Youjo Senki II"))
        assertEquals("Dan Da Dan", seriesSearchQuery("Dan Da Dan Season 2"))
        assertEquals("Jujutsu Kaisen", seriesSearchQuery("Jujutsu Kaisen 2nd Season"))
        assertEquals("Shingeki no Kyojin", seriesSearchQuery("Shingeki no Kyojin Final Season"))
        // A title that is only a season marker leaves nothing to search for.
        assertNull(seriesSearchQuery("Season 2"))
        assertNull(seriesSearchQuery("   "))
        // Roman numerals inside a name are not season markers.
        assertEquals("Mobile Suit Gundam IV Fighters", seriesSearchQuery("Mobile Suit Gundam IV Fighters"))
    }

    @Test
    fun `the episode group splits a merged run back into seasons`() {
        val groups = listOf(
            group("Season 1", episodes = 12, firstAir = "2024-10-04"),
            group("Season 2", episodes = 12, firstAir = "2026-07-04"),
        )
        assertEquals("Season 2", pickGroup(groups, wantedEpisodes = 12, wantedYear = 2026)?.name)
        assertEquals("Season 1", pickGroup(groups, wantedEpisodes = 12, wantedYear = 2024)?.name)
    }

    /**
     * Saga of Tanya the Evil's specials season, live: the two chibi series sit in one season 0
     * between unrelated specials, and neither may take the other's episodes.
     */
    @Test
    fun `a spin-off takes its own run from the parent's specials by name`() {
        val specials = buildList {
            (0..6).forEach { add(TmdbEpisode(name = "Youjo Shenki: Mini-Episode %02d".format(it))) }
            add(TmdbEpisode(name = "War Report"))
            (7..12).forEach { add(TmdbEpisode(name = "Youjo Shenki: Mini-Episode %02d".format(it))) }
            add(TmdbEpisode(name = "Saga of Tanya the Evil: The Movie - Manner Movie"))
            (1..12).forEach { add(TmdbEpisode(name = "Youjo Shenki 2: Mini-Episode $it")) }
        }
        val first = specialsRunFor(media(romaji = "Youjo Shenki", episodes = 13), specials)
        assertEquals(13, first.size)
        assertEquals("Youjo Shenki: Mini-Episode 12", first.last().name)
        val second = specialsRunFor(media(romaji = "Youjo Shenki 2", episodes = 12), specials)
        assertEquals("Youjo Shenki 2: Mini-Episode 1", second.first().name)
        assertEquals(12, second.size)
        // A count that disagrees means the run is not this entry, so nothing is guessed.
        assertTrue(specialsRunFor(media(romaji = "Youjo Shenki", episodes = 12), specials).isEmpty())
    }

    /**
     * Naruto Shippuden, live: one AniList entry of 500 episodes, twenty TMDB seasons adding up to
     * exactly that. No season is 500 long, so the groups answered with a 32-episode arc instead.
     */
    @Test
    fun `seasons that add up to the entry are the whole series`() {
        val seasons = listOf(season(0, 3, "2008-02-06"), season(2, 21, "2007-11-06"), season(1, 32, "2007-02-15"))
        assertEquals(listOf(1, 2), wholeSeriesSeasons(seasons, 53).map { it.season_number })
        // Any other total is a season of the series, which the ordinary matching decides.
        assertEquals(emptyList<TmdbSeasonSummary>(), wholeSeriesSeasons(seasons, 32))
        assertEquals(emptyList<TmdbSeasonSummary>(), wholeSeriesSeasons(seasons, null))
        // One season has nothing to concatenate, and pickSeason already takes it by count.
        assertEquals(emptyList<TmdbSeasonSummary>(), wholeSeriesSeasons(listOf(season(1, 24, "2024-10-04")), 24))
    }

    /**
     * The same title's "Released Order" group files episode 11 fifth in its first arc, and a run is
     * numbered by position, so episode 5 was shown episode 11's title and still.
     */
    @Test
    fun `a group run out of broadcast order is never taken`() {
        val shuffled = TmdbGroup(
            name = "S1",
            episodes = listOf(1, 2, 3, 4, 11, 5, 6).map { TmdbEpisode(season_number = 1, episode_number = it) }
                .mapIndexed { index, episode -> if (index == 0) episode.copy(air_date = "2007-02-15") else episode },
        )
        assertNull(pickGroup(listOf(shuffled), 7, 2007, wantedStart = "2007-02-15"))
        // A merged run's second season counts on from the first, and that is in order.
        val merged = listOf(TmdbEpisode(season_number = 1, episode_number = 13), TmdbEpisode(season_number = 1, episode_number = 14))
        assertEquals(true, isBroadcastOrder(merged))
        assertEquals(true, isBroadcastOrder(listOf(TmdbEpisode(season_number = 1, episode_number = 32), TmdbEpisode(season_number = 2, episode_number = 1))))
    }

    /**
     * The Apothecary Diaries, live: TMDB's air-date grouping carries a specials run of 50 that opens
     * the day after season one. It is never a season, even at its own length.
     */
    @Test
    fun `a group's specials run is never taken for a season`() {
        val groups = listOf(
            group("Season 1", episodes = 24, firstAir = "2023-10-22"),
            group("Season 2", episodes = 24, firstAir = "2025-01-10"),
            group("Specials", episodes = 50, firstAir = "2023-10-23"),
        )
        assertEquals("Season 1", pickGroup(groups, 24, 2023, wantedStart = "2023-10-22")?.name)
        assertEquals("Season 2", pickGroup(groups, 24, 2025, wantedStart = "2025-01-10")?.name)
        assertEquals("Season 1", pickGroup(groups, 50, 2023, wantedStart = "2023-10-23")?.name)
    }

    /**
     * Space Dandy, live: TMDB keeps its two cours as two seasons of thirteen, both aired in 2014,
     * so the year cannot separate them and the first in the list won — season two was shown season
     * one's January episodes. The day it started can separate them.
     */
    @Test
    fun `two cours of one year are told apart by the day they started`() {
        val cours = listOf(season(1, 13, "2014-01-04"), season(2, 13, "2014-07-06"))
        assertEquals(2, pickSeason(cours, 13, 2014, wantedStart = "2014-07-06")?.season_number)
        // AniList dates that first season 5 January where TMDB dates it the 4th, which is what
        // nearest rather than exact is for: equality would throw the match away over a day.
        assertEquals(1, pickSeason(cours, 13, 2014, wantedStart = "2014-01-05")?.season_number)
        // With no date to go on the year rule still answers, exactly as it did before.
        assertEquals(1, pickSeason(cours, 13, 2014)?.season_number)
    }

    /**
     * The date is a tie-break among seasons of the right length, not a rule of its own. TMDB's
     * merged Dandadan season of 24 begins on exactly the day AniList's twelve-episode first season
     * does, so matching on the date alone would undo the count rule and hand it the whole run.
     */
    @Test
    fun `an exact date cannot rescue a season of the wrong length`() {
        val merged = listOf(season(1, 24, "2024-10-04"))
        assertNull(pickSeason(merged, wantedEpisodes = 12, wantedYear = 2024, wantedStart = "2024-10-04"))
    }

    /**
     * Bungou Stray Dogs, live and the report that started this. Its whole 60-episode run is a
     * single TMDB season, so only the episode group can split it, and that group gives the second
     * cour thirteen episodes — its twelve plus the OVA AniList files as a separate entry. Thirteen
     * is not twelve, so the count rule refused it and the year then handed the autumn 2016 season
     * the spring 2016 one's twelve episodes: every row of season two carried season one's title
     * and still. An episode group's runs are the broadcast split, so the run that opens on the day
     * the season opened is that season whatever its length.
     */
    @Test
    fun `a group run of the wrong length that starts on the right day is still the season`() {
        val cours = listOf(
            group("1", episodes = 12, firstAir = "2016-04-07"),
            group("2", episodes = 13, firstAir = "2016-10-06"),
            group("3", episodes = 12, firstAir = "2019-04-12"),
        )
        assertEquals("2", pickGroup(cours, 12, 2016, wantedStart = "2016-10-06")?.name)
        assertEquals("1", pickGroup(cours, 12, 2016, wantedStart = "2016-04-07")?.name)
        assertEquals("3", pickGroup(cours, 12, 2019, wantedStart = "2019-04-12")?.name)
        // A run months from the season is not it, whatever its length.
        assertNull(pickGroup(cours, 24, 2025, wantedStart = "2025-01-06"))
        // And with no date the behaviour that shipped is untouched, so the fix is the date alone.
        assertEquals("1", pickGroup(cours, 12, 2016)?.name)
    }

    @Test
    fun `an ambiguous group is refused rather than guessed`() {
        val groups = listOf(
            group("Season 1", episodes = 12, firstAir = "2024-10-04"),
            group("Season 2", episodes = 12, firstAir = "2026-07-04"),
        )
        // Same length, and a year that matches neither: a coin flip here puts the wrong half of
        // the run on screen, and every episode would be silently one season out.
        assertNull(pickGroup(groups, wantedEpisodes = 12, wantedYear = 2030))
    }

    /**
     * Inside a merged run TMDB numbers the second season's episodes 13..24, while AniList, the
     * stream providers and the viewer all call them 1..12. Position is the only thing both agree
     * on, so the run is renumbered by it.
     */
    @Test
    fun `a merged run is renumbered from its own position`() {
        val second = (13..24).map { TmdbEpisode(episode_number = it, name = "Ep $it", still_path = "/s$it.jpg") }

        val mapped = second.toMetadata()

        assertEquals(1.0, mapped.first().number, 0.0)
        assertEquals(12.0, mapped.last().number, 0.0)
        assertEquals("Ep 13", mapped.first().title)
        assertEquals("https://image.tmdb.org/t/p/w500/s13.jpg", mapped.first().still)
    }

    @Test
    fun `an episode with no still contributes no url`() {
        assertNull(TmdbEpisode(episode_number = 1, name = "Ep 1").toMetadata().single().still)
        assertNull(tmdbStillUrl(""))
    }

    private fun TmdbEpisode.toMetadata() = listOf(this).toMetadata()

    // ---- OVAs TMDB keeps as films ----

    /** AniList 100049 as AniList has it: two OVAs, the first and last release dated to the day. */
    private val reZeroOvas = Media(
        id = 100049,
        title = MediaTitle(
            romaji = "Re:Zero kara Hajimeru Isekai Seikatsu OVAs",
            english = "Re:ZERO -Starting Life in Another World- OVAs",
            native = "Re:ゼロから始める異世界生活 OVAs",
        ),
        format = "OVA",
        episodes = 2,
        startDate = FuzzyDate(year = 2018, month = 10, day = 6),
        endDate = FuzzyDate(year = 2019, month = 11, day = 8),
    )

    /** TMDB's /search/movie answer for the English title, measured 2026-09-27, decoy included. */
    private val reZeroMovies = listOf(
        TmdbMovie(
            id = 566451,
            title = "Re:ZERO -Starting Life in Another World- The Frozen Bond",
            original_title = "Re:ゼロから始める異世界生活 氷結の絆",
            release_date = "2019-11-08",
            backdrop_path = "/frozen.jpg",
        ),
        TmdbMovie(
            id = 1351929,
            title = "Re:ZERO -Starting Life in Another World-",
            original_title = "Re:ゼロから始める異世界生活　劇場型悪意",
            release_date = "2016-04-04",
        ),
        TmdbMovie(
            id = 532321,
            title = "Re:ZERO -Starting Life in Another World- Memory Snow",
            original_title = "Re:ゼロから始める異世界生活 Memory Snow",
            release_date = "2018-10-06",
            backdrop_path = "/snow.jpg",
        ),
    )

    @Test
    fun `OVAs TMDB keeps as films become the episodes, in release order`() {
        val run = pickMovieRun(reZeroOvas, reZeroMovies)
        assertEquals(listOf(532321, 566451), run?.map { it.id })
        assertEquals("Memory Snow", movieEpisodeTitle(reZeroOvas, run!!.first()))
        assertEquals("The Frozen Bond", movieEpisodeTitle(reZeroOvas, run.last()))
    }

    @Test
    fun `a film outside the entry's own run is not one of its episodes`() {
        // The 2016 film shares the series name exactly; only its date says it is something else.
        val run = pickMovieRun(reZeroOvas, reZeroMovies)
        assertFalse(run.orEmpty().any { it.id == 1351929 })
    }

    @Test
    fun `films that do not account for the whole entry are refused`() {
        assertNull(pickMovieRun(reZeroOvas, reZeroMovies.filter { it.id == 532321 }))
        assertNull(pickMovieRun(reZeroOvas.copy(episodes = 3), reZeroMovies))
    }

    @Test
    fun `another title's films are never taken on dates alone`() {
        val stranger = TmdbMovie(
            id = 9,
            title = "Some Other Film",
            original_title = "Some Other Film",
            release_date = "2018-10-06",
        )
        assertNull(pickMovieRun(reZeroOvas.copy(episodes = 1), listOf(stranger)))
    }

    @Test
    fun `the format word is not part of the name`() {
        assertEquals("Re:ZERO -Starting Life in Another World-", stripFormatMarkers(reZeroOvas.title.english))
        assertEquals("Kimetsu no Yaiba", stripFormatMarkers("Kimetsu no Yaiba Movie"))
    }
}
