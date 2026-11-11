package com.anilili.ui.detail

import com.anilili.data.model.FuzzyDate
import com.anilili.data.model.Media
import com.anilili.data.model.MediaRelationConnection
import com.anilili.data.model.MediaRelationEdge
import com.anilili.data.model.MediaTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The series walk follows PREQUEL/SEQUEL and nothing else, because its job is to find the seasons.
 * A watch guide has to answer more than that — the films and the OVAs are the entries a viewer
 * actually needs told where to put — and they hang off relation types the walk never follows.
 *
 * These pin what belongs in the guide, what must stay out of it, and the numbering, which is the
 * only part of the row that says anything a plain list of related titles did not.
 */
class WatchOrderTest {
    private fun media(
        id: Int,
        title: String,
        format: String = "TV",
        year: Int? = null,
        isAdult: Boolean = false,
        relations: List<Pair<String, Media>> = emptyList(),
    ) = Media(
        id = id,
        title = MediaTitle(english = title),
        format = format,
        startDate = year?.let { FuzzyDate(year = it) },
        isAdult = isAdult,
        relations = MediaRelationConnection(
            relations.map { (type, node) -> MediaRelationEdge(relationType = type, node = node) },
        ),
    )

    private val movie = media(30, "The Movie", format = "MOVIE", year = 2020)
    private val ova = media(40, "The OVA", format = "OVA", year = 2019)
    private val special = media(50, "The Special", format = "SPECIAL", year = 2018)
    private val manga = media(60, "The Manga", format = "MANGA")
    private val unrelated = media(70, "Something Else", format = "TV", year = 2021)

    private val season2 = media(20, "Season 2", year = 2021)
    private val season1 = media(
        id = 10,
        title = "Season 1",
        year = 2017,
        relations = listOf(
            "SEQUEL" to season2,
            "SIDE_STORY" to movie,
            "SIDE_STORY" to ova,
            "SUMMARY" to special,
            "SOURCE" to manga,
            "CHARACTER" to unrelated,
        ),
    )

    private fun guide(
        info: Media = season1,
        series: List<Media> = listOf(season1, season2),
        seasons: List<Media> = listOf(season1, season2),
        hideAdult: Boolean = true,
    ) = buildWatchOrder(info, series, seasons, hideAdult)

    @Test
    fun `entries the season walk never follows are on the guide`() {
        val byId = guide().associateBy { it.media.id }

        assertEquals(WatchOrderGroup.SEASONS, byId.getValue(season1.id).group)
        assertEquals(WatchOrderGroup.SEASONS, byId.getValue(season2.id).group)
        assertEquals(WatchOrderGroup.MOVIES, byId.getValue(movie.id).group)
        assertEquals(WatchOrderGroup.OVAS, byId.getValue(ova.id).group)
        assertEquals(WatchOrderGroup.SPECIALS, byId.getValue(special.id).group)
    }

    @Test
    fun `a manga and a title that only shares a character are not part of the story`() {
        val ids = guide().map { it.media.id }

        assertTrue("$ids", manga.id !in ids)
        assertTrue("$ids", unrelated.id !in ids)
    }

    /**
     * The number has to mean the same thing whichever chip is selected, or filtering to Movies
     * renumbers the films 1, 2, 3 as though they were the whole series.
     */
    @Test
    fun `the running order is counted across the whole franchise in airing order`() {
        val positions = guide().associate { it.media.title.preferred to it.position }

        assertEquals(
            mapOf(
                "Season 1" to 1,      // 2017
                "The Special" to 2,   // 2018
                "The OVA" to 3,       // 2019
                "The Movie" to 4,     // 2020
                "Season 2" to 5,      // 2021
            ),
            positions,
        )
    }

    /** A side story that aired after Season 1 is listed after Season 1, not under a Movies heading. */
    @Test
    fun `films and extras are listed between the seasons they aired between`() {
        assertEquals(
            listOf("Season 1", "The Special", "The OVA", "The Movie", "Season 2"),
            guide().map { it.media.title.preferred },
        )
    }

    /** The seasons keep their numbering even when a start date disagrees; the extras stay put. */
    @Test
    fun `a misdated season does not swap places with another season`() {
        val misdated = media(20, "Season 2", year = 2016)
        val entries = guide(
            series = listOf(season1, misdated),
            seasons = listOf(season1, misdated),
        )

        assertEquals(
            listOf("Season 1", "Season 2", "The Special", "The OVA", "The Movie"),
            entries.map { it.media.title.preferred },
        )
        assertEquals(listOf(1, 2), entries.take(2).map { it.seasonNumber })
        assertEquals(listOf(1, 2, 3, 4, 5), entries.map { it.position })
    }

    @Test
    fun `an entry outside the season chain is labelled by how it joins the series`() {
        val byId = guide().associateBy { it.media.id }

        assertEquals("Side story", byId.getValue(movie.id).relationLabel)
        assertEquals("Summary", byId.getValue(special.id).relationLabel)
        // A season is placed by its number; naming the relation as well says nothing.
        assertNull(byId.getValue(season2.id).relationLabel)
    }

    @Test
    fun `the page's own entry is on its own guide, marked`() {
        val current = guide(info = season2).single { it.isCurrent }

        assertEquals(season2.id, current.media.id)
    }

    /** A single-entry chain is the anime itself, and calling that "Season 1" invents a series. */
    @Test
    fun `a lone anime is not given a season number`() {
        val lone = media(80, "Only", year = 2022)

        assertNull(guide(info = lone, series = listOf(lone), seasons = listOf(lone)).single().seasonNumber)
    }

    @Test
    fun `an adult side story is left off unless the viewer asked for it`() {
        val adult = media(90, "Adult Side Story", format = "OVA", year = 2020, isAdult = true)
        val root = media(100, "Root", year = 2019, relations = listOf("SIDE_STORY" to adult))
        val series = listOf(root)

        assertTrue(guide(root, series, series).none { it.media.id == adult.id })
        assertTrue(
            guide(root, series, series, hideAdult = false).any { it.media.id == adult.id },
        )
    }

    /**
     * Relation nodes carry the compact field set; the walk fetches each season in full. When both
     * describe the same title the full record has to win, or a season loses its synopsis.
     */
    @Test
    fun `the walked record wins over a relation node for the same title`() {
        val full = season2.copy(description = "The real synopsis")
        val entry = guide(series = listOf(season1, full)).single { it.media.id == season2.id }

        assertEquals("The real synopsis", entry.media.description)
    }
}
