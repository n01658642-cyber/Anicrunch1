package com.anilili.ui.detail

import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import com.anilili.data.remote.TvdbRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AniList has no season concept: "season two" is a separate entry joined by a PREQUEL/SEQUEL
 * relation, and that chain also carries spin-offs, recaps and films. The only way to tell them
 * apart without a cross-reference is `format`, which keeps a spin-off that happens to be a TV
 * series and drops a season released as an ONA.
 *
 * TheTVDB numbers a series the way viewers talk about it, so [tvdbSeasonChain] answers both
 * questions the format filter guesses at — which entries belong, and in what order. These pin the
 * cases where it must decline and let the format filter take over instead.
 */
class TvdbSeasonChainTest {
    private fun media(id: Int, title: String, format: String = "TV") =
        Media(id = id, title = MediaTitle(romaji = title, userPreferred = title), format = format)

    private val s1 = media(1, "Show")
    private val s2 = media(2, "Show 2nd Season")
    private val s3 = media(3, "Show 3rd Season")
    private val movie = media(4, "Show: The Movie", format = "MOVIE")
    private val spinOff = media(5, "Other Show")

    private fun chain(vararg refs: Pair<Int, TvdbRef?>, info: Media = s1, series: List<Media>) =
        tvdbSeasonChain(info, series, refs.toMap()::get)

    @Test
    fun `entries of one tvdb series come back in season order`() {
        val result = chain(
            1 to TvdbRef(100, 1),
            2 to TvdbRef(100, 2),
            3 to TvdbRef(100, 3),
            series = listOf(s3, s1, s2),
        )

        assertEquals(listOf(1, 2, 3), result?.map { it.id })
    }

    @Test
    fun `a title mapped to a different tvdb series is not a season of this one`() {
        // The relation chain drags in spin-offs that share no broadcast run. The format filter
        // keeps this one because it is a TV series; the cross-reference knows better.
        val result = chain(
            1 to TvdbRef(100, 1),
            2 to TvdbRef(100, 2),
            5 to TvdbRef(200, 1),
            series = listOf(s1, s2, spinOff),
        )

        assertEquals(listOf(1, 2), result?.map { it.id })
    }

    @Test
    fun `entries tvdb files outside the numbered run are left out`() {
        // Season 0 is TheTVDB's specials bucket — films, OVAs and recaps. They belong on Related.
        val result = chain(
            1 to TvdbRef(100, 1),
            2 to TvdbRef(100, 2),
            4 to TvdbRef(100, 0),
            series = listOf(s1, s2, movie),
        )

        assertEquals(listOf(1, 2), result?.map { it.id })
    }

    @Test
    fun `an unmapped title declines rather than answering empty`() {
        // About a fifth of the catalogue has no cross-reference entry. Answering an empty list
        // would read as "this has no seasons" and hide the picker; null hands it to the fallback.
        assertNull(chain(2 to TvdbRef(100, 2), series = listOf(s1, s2)))
    }

    @Test
    fun `a title tvdb files outside the numbered run declines`() {
        assertNull(chain(1 to TvdbRef(100, null), series = listOf(s1, s2)))
    }

    @Test
    fun `a lone season is not a chain`() {
        // One entry is not a picker. The fallback decides what to show instead.
        assertNull(chain(1 to TvdbRef(100, 1), series = listOf(s1)))
    }

    @Test
    fun `a chain that does not contain the page's own anime declines`() {
        // If the cross-reference and the relation chain disagree about where this page sits, a
        // picker would render with no way to select the season actually being shown.
        val result = chain(
            1 to TvdbRef(100, 1),
            2 to TvdbRef(200, 1),
            3 to TvdbRef(200, 2),
            info = s1,
            series = listOf(s2, s3),
        )

        assertNull(result)
    }
}
