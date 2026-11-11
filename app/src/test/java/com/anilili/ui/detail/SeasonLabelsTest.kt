package com.anilili.ui.detail

import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import com.anilili.data.remote.TvdbRef
import org.junit.Assert.assertEquals
import org.junit.Test

/** Season picker names, against the real AniList entries and TheTVDB placements they carry. */
class SeasonLabelsTest {

    private fun media(id: Int, english: String?, romaji: String? = null) =
        Media(id = id, title = MediaTitle(english = english, romaji = romaji))

    private fun labels(seasons: List<Media>, refs: Map<Int, TvdbRef> = emptyMap()) =
        seasonLabels(seasons, refs::get).let { map -> seasons.map { map.getValue(it.id) } }

    /** The report: Mushoku's second season's second half was "Season 4", its third "Season 5". */
    @Test
    fun `split seasons are named by TheTVDB's season with a part each`() {
        val mushoku = listOf(
            media(108465, "Mushoku Tensei: Jobless Reincarnation"),
            media(127720, "Mushoku Tensei: Jobless Reincarnation Cour 2"),
            media(146065, "Mushoku Tensei: Jobless Reincarnation Season 2"),
            media(166873, "Mushoku Tensei: Jobless Reincarnation Season 2 Part 2"),
            media(178789, "Mushoku Tensei: Jobless Reincarnation Season 3"),
        )
        val refs = mapOf(
            108465 to TvdbRef(371310, 1), 127720 to TvdbRef(371310, 1),
            146065 to TvdbRef(371310, 2), 166873 to TvdbRef(371310, 2),
            178789 to TvdbRef(371310, 3),
        )
        assertEquals(
            listOf("Season 1 Part 1", "Season 1 Part 2", "Season 2 Part 1", "Season 2 Part 2", "Season 3"),
            labels(mushoku, refs),
        )
    }

    /** Only TheTVDB knows "STONE WARS" is season two; the titles never say so. */
    @Test
    fun `TheTVDB names seasons the titles do not number`() {
        val stone = listOf(
            media(105333, "Dr. STONE"),
            media(113936, "Dr. STONE: STONE WARS"),
            media(131518, "Dr. STONE New World"),
            media(162670, "Dr. STONE New World Part 2"),
        )
        val refs = mapOf(
            105333 to TvdbRef(355774, 1), 113936 to TvdbRef(355774, 2),
            131518 to TvdbRef(355774, 3), 162670 to TvdbRef(355774, 3),
        )
        assertEquals(listOf("Season 1", "Season 2", "Season 3 Part 1", "Season 3 Part 2"), labels(stone, refs))
    }

    /** Bleach's Thousand-Year Blood War is all TheTVDB season 17; "Season 17 Part 3" helps nobody. */
    @Test
    fun `one season in several parts is named by part alone`() {
        val tybw = listOf(
            media(116674, "BLEACH: Thousand-Year Blood War"),
            media(159322, "BLEACH: Thousand-Year Blood War - The Separation"),
            media(169755, "BLEACH: Thousand-Year Blood War - The Conflict"),
        )
        val refs = tybw.associate { it.id to TvdbRef(74796, 17) }
        assertEquals(listOf("Part 1", "Part 2", "Part 3"), labels(tybw, refs))
    }

    @Test
    fun `without TheTVDB the titles decide, and an unnumbered part joins the season before it`() {
        val slime = listOf(
            media(101280, "That Time I Got Reincarnated as a Slime"),
            media(108511, "That Time I Got Reincarnated as a Slime Season 2"),
            media(116742, "That Time I Got Reincarnated as a Slime Season 2 Part 2"),
            media(156822, null, romaji = "Tensei Shitara Slime Datta Ken 3rd Season"),
        )
        assertEquals(listOf("Season 1", "Season 2 Part 1", "Season 2 Part 2", "Season 3"), labels(slime))

        val spy = listOf(
            media(140960, "SPY x FAMILY"),
            media(142838, "SPY x FAMILY Cour 2"),
            media(158927, "SPY x FAMILY Season 2"),
        )
        assertEquals(listOf("Season 1 Part 1", "Season 1 Part 2", "Season 2"), labels(spy))
    }

    /** A partial or mixed cross-reference is not trusted: one missing entry sends it to the titles. */
    @Test
    fun `TheTVDB is only used when it places every entry in one series`() {
        val seasons = listOf(
            media(1, "Show"),
            media(2, "Show Season 2"),
            media(3, "Show Season 2 Part 2"),
        )
        val partial = mapOf(1 to TvdbRef(10, 1), 2 to TvdbRef(10, 2))
        assertEquals(listOf("Season 1", "Season 2 Part 1", "Season 2 Part 2"), labels(seasons, partial))
        val mixed = mapOf(1 to TvdbRef(10, 1), 2 to TvdbRef(11, 1), 3 to TvdbRef(11, 1))
        assertEquals(listOf("Season 1", "Season 2 Part 1", "Season 2 Part 2"), labels(seasons, mixed))
    }

    @Test
    fun `plain sequels keep counting`() {
        val seasons = listOf(media(1, "Show"), media(2, "Show: The Next Arc"), media(3, "Show: Final Arc"))
        assertEquals(listOf("Season 1", "Season 2", "Season 3"), labels(seasons))
    }
}
