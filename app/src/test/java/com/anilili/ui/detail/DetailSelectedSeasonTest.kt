package com.anilili.ui.detail

import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import com.anilili.data.model.Category
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The television page describes one season at a time, and the chips pick which. Everything that
 * follows the chips — the title, the synopsis, the watch action, the library entry — reads
 * [DetailData.selectedSeason], so what it answers is what the page says.
 */
class DetailSelectedSeasonTest {
    @Test
    fun `an unchanged page describes the anime it was opened on`() {
        assertEquals(season(1).id, data().selectedSeason.id)
    }

    @Test
    fun `choosing a season describes that season instead`() {
        val chosen = data(selectedSeasonId = season(2).id).selectedSeason

        assertEquals(season(2).id, chosen.id)
        assertEquals("Season 2", chosen.title.preferred)
    }

    @Test
    fun `a season the chain no longer holds falls back to the page's own anime`() {
        assertEquals(season(1).id, data(selectedSeasonId = 999).selectedSeason.id)
    }

    /**
     * Movies and specials are on the chain but never on the chips, so they cannot be selected and
     * must not be answered with either — the chip row and this lookup read the same list.
     */
    @Test
    fun `a movie in the same chain is not a season`() {
        val movie = Media(id = 40, title = MediaTitle(english = "The Movie"), format = "MOVIE")
        val page = data(series = listOf(season(1), movie, season(2)))

        assertEquals(listOf(season(1).id, season(2).id), page.seasons.map(Media::id))
        assertEquals(season(1).id, page.copy(selectedSeasonId = movie.id).selectedSeason.id)
    }

    private fun season(number: Int) = Media(
        id = 10 * number,
        title = MediaTitle(english = "Season $number"),
        format = "TV",
        episodes = 12,
    )

    private fun data(
        selectedSeasonId: Int = season(1).id,
        series: List<Media> = listOf(season(1), season(2)),
    ) = DetailData(
        info = season(1),
        episodes = emptyList(),
        preferredCategory = Category.SUB,
        series = series,
        selectedSeasonId = selectedSeasonId,
    )
}
