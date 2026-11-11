package com.anilili.data.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run and the season boundaries here are the live ones, read off Konoha and AniList for the
 * title that reported this: Konoha serves the same 85-episode Re:Zero run under all four of its
 * AniList ids (21355, 108632, 119661, 163134), because TMDB — which both catalogues derive from —
 * keeps the series as a single season of 85. Merged by number, seasons two and three showed season
 * one's episodes under a correct episode count, which is precisely how it was reported.
 */
class SeasonEpisodeAlignmentTest {
    /** Season one starts the run; every other season starts where the one before it ended. */
    private val seasonStarts = listOf(
        LocalDate.of(2016, 4, 4) to 25,
        LocalDate.of(2020, 7, 8) to 13,
        LocalDate.of(2021, 1, 6) to 12,
        LocalDate.of(2024, 10, 2) to 16,
        LocalDate.of(2026, 4, 8) to 19,
    )

    /** Konoha's answer for every Re:Zero id: all 85 episodes, numbered straight through. */
    private val wholeSeriesRun: List<EpisodeMetadata> = buildList {
        seasonStarts.forEach { (start, count) ->
            repeat(count) { week ->
                val number = size + 1
                add(
                    EpisodeMetadata(
                        number = number.toDouble(),
                        title = "run $number",
                        still = "https://image.tmdb.org/t/p/w500/$number.jpg",
                        airDate = start.plusWeeks(week.toLong()).toString(),
                    ),
                )
            }
        }
    }

    private fun season(episodes: Int?, start: LocalDate?) = Media(
        id = 1,
        episodes = episodes,
        startDate = start?.let { FuzzyDate(year = it.year, month = it.monthValue, day = it.dayOfMonth) },
    )

    @Test
    fun `the second season takes its own thirteen episodes out of the series run`() {
        val aligned = alignMetadataToSeason(wholeSeriesRun, season(13, LocalDate.of(2020, 7, 8)))

        assertEquals(13, aligned.size)
        // The run's episode 26 is this season's episode 1 — under the old merge the season showed
        // the run's episode 1, which is season one's premiere.
        assertEquals("run 26", aligned.first().title)
        assertEquals("run 38", aligned.last().title)
        assertEquals((1..13).map { it.toDouble() }, aligned.map { it.number })
    }

    @Test
    fun `the still travels with the title it belongs to`() {
        val aligned = alignMetadataToSeason(wholeSeriesRun, season(12, LocalDate.of(2021, 1, 6)))

        assertEquals("https://image.tmdb.org/t/p/w500/39.jpg", aligned.first().still)
        assertEquals("run 39", aligned.first().title)
    }

    @Test
    fun `each season of the series is cut at its own boundary`() {
        val openers = seasonStarts.map { (start, count) ->
            alignMetadataToSeason(wholeSeriesRun, season(count, start)).first().title
        }

        assertEquals(listOf("run 1", "run 26", "run 39", "run 51", "run 67"), openers)
    }

    @Test
    fun `the season the run opens with keeps the numbering it already had`() {
        val aligned = alignMetadataToSeason(wholeSeriesRun, season(25, LocalDate.of(2016, 4, 4)))

        assertEquals(25, aligned.size)
        assertEquals("run 1", aligned.first().title)
        assertEquals(1.0, aligned.first().number, 0.0)
    }

    @Test
    fun `a catalogue that answered for this season alone is returned untouched`() {
        // Konoha's own file for Re:Zero's third season is a proper sixteen-episode list; nothing
        // about it should change on the way through.
        val own = wholeSeriesRun.drop(50).take(16).mapIndexed { index, row ->
            row.copy(number = (index + 1).toDouble())
        }

        assertEquals(own, alignMetadataToSeason(own, season(16, LocalDate.of(2024, 10, 2))))
    }

    @Test
    fun `a run no date in which comes near the season is left as it is`() {
        // Konoha maps some ids onto an unrelated show outright — 163132 answers with Horimiya's
        // episodes. Cutting that at a guess would swap one wrong list for another.
        val unrelated = (1..24).map {
            EpisodeMetadata(
                number = it.toDouble(),
                title = "elsewhere $it",
                airDate = LocalDate.of(2023, 7, 1).plusWeeks(it.toLong()).toString(),
            )
        }

        assertEquals(unrelated, alignMetadataToSeason(unrelated, season(13, LocalDate.of(2020, 7, 8))))
    }

    @Test
    fun `a season AniList has no start date for is left as it is`() {
        assertEquals(wholeSeriesRun, alignMetadataToSeason(wholeSeriesRun, season(13, null)))
        // A year on its own is not a day, and there is nothing to measure a distance against.
        val yearOnly = Media(id = 1, episodes = 13, startDate = FuzzyDate(year = 2020))
        assertEquals(wholeSeriesRun, alignMetadataToSeason(wholeSeriesRun, yearOnly))
    }

    @Test
    fun `a season still airing is cut from its opener to the end of the run`() {
        // AniList states no total for some releasing seasons, which leaves nothing to take a count
        // of episodes from — the opener is still known, and everything after it is still this
        // season's.
        val aligned = alignMetadataToSeason(wholeSeriesRun, season(null, LocalDate.of(2026, 4, 8)))

        assertEquals(19, aligned.size)
        assertEquals("run 67", aligned.first().title)
    }

    @Test
    fun `a day's disagreement between the catalogues does not shift the season by one`() {
        // AniList dates a season by its streaming premiere and the catalogues by the broadcast, or
        // the other way about. Taking the first episode dated on or after the start would drop the
        // premiere and mis-title every row behind it.
        val aligned = alignMetadataToSeason(wholeSeriesRun, season(13, LocalDate.of(2020, 7, 7)))

        assertEquals("run 26", aligned.first().title)
    }

    @Test
    fun `air dates are read in both of the shapes Konoha writes them`() {
        assertEquals(LocalDate.of(2016, 4, 4), episodeAirDate("2016-04-04"))
        assertEquals(LocalDate.of(2024, 10, 2), episodeAirDate("2024-10-02T00:00:00+00:00"))
        assertEquals(LocalDate.of(2024, 10, 2), episodeAirDate(" 2024-10-02 "))
    }

    @Test
    fun `a date that says nothing is not read as one`() {
        assertNull(episodeAirDate(null))
        assertNull(episodeAirDate(""))
        assertNull(episodeAirDate("2024"))
        assertNull(episodeAirDate("not a date"))
    }

    /**
     * AniList's own episode rows have the same complaint against them, and no air date to settle
     * it with. What AniList actually returns for the three ids above is one set of sixteen
     * Crunchyroll rows numbered 51 to 66 — the fourth season's episodes, under every id.
     */
    private fun streamingRows(numbers: IntRange) = numbers.map {
        EpisodeMetadata(number = it.toDouble(), still = "https://crunchy/$it.jpg")
    }

    @Test
    fun `a listing numbered past the end of the season is not this season's`() {
        val season = season(13, LocalDate.of(2020, 7, 8))

        assertFalse(streamingRowsFitSeason(season, streamingRows(51..66), emptyList()))
    }

    @Test
    fun `a listing that overlaps the season is refused whole, not trimmed to fit`() {
        // The rows that land inside the season are the dangerous ones: they match real episodes and
        // hang the wrong picture on each. Keeping 13..24 of a run numbered 13..36 would redecorate
        // half a twenty-four episode season with the season before it.
        val season = season(24, LocalDate.of(2021, 1, 6))

        assertFalse(streamingRowsFitSeason(season, streamingRows(13..36), emptyList()))
    }

    @Test
    fun `a listing that fits the season is kept`() {
        val season = season(12, LocalDate.of(2021, 1, 6))

        assertTrue(streamingRowsFitSeason(season, streamingRows(1..12), emptyList()))
        // A season still filling up lists fewer rows than it will have, which is not a misfit.
        assertTrue(streamingRowsFitSeason(season, streamingRows(1..5), emptyList()))
    }

    @Test
    fun `a season with no count is measured against what is airing`() {
        val airing = Media(
            id = 1,
            episodes = null,
            nextAiringEpisode = NextAiringEpisode(episode = 6),
            startDate = FuzzyDate(year = 2026, month = 4, day = 8),
        )

        assertTrue(streamingRowsFitSeason(airing, streamingRows(1..5), emptyList()))
        assertFalse(streamingRowsFitSeason(airing, streamingRows(51..55), emptyList()))
    }

    @Test
    fun `a season with nothing of its own is measured against the catalogues`() {
        val unknown = Media(id = 1, episodes = null)
        val established = (1..12).map { EpisodeMetadata(number = it.toDouble(), title = "ep $it") }

        assertTrue(streamingRowsFitSeason(unknown, streamingRows(1..12), established))
        assertFalse(streamingRowsFitSeason(unknown, streamingRows(51..66), established))
    }

    @Test
    fun `rows there is no way to check are left out rather than believed`() {
        // No count, nothing airing and no catalogue to compare against. This is the one case where
        // the rows would have become the episode list themselves, numbers and all.
        val unknown = Media(id = 1, episodes = null)

        assertFalse(streamingRowsFitSeason(unknown, streamingRows(51..66), emptyList()))
        assertFalse(streamingRowsFitSeason(unknown, streamingRows(1..12), emptyList()))
        assertFalse(streamingRowsFitSeason(unknown, emptyList(), emptyList()))
    }
}
