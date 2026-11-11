package com.anilili.ui.profile

import com.anilili.data.model.AnimeStat
import com.anilili.data.model.Media
import com.anilili.data.model.MediaListEntry
import com.anilili.data.model.Viewer
import com.anilili.data.model.ViewerStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProfileStatsTest {
    private fun entry(id: Int, progress: Int, score: Double = 0.0, duration: Int? = null) =
        MediaListEntry(
            id = id,
            progress = progress,
            score = score,
            media = Media(id = id, duration = duration),
        )

    @Test
    fun `missing statistics are rebuilt from the fetched list`() {
        // AniList's post-outage Viewer responses omit `statistics` entirely; a 40-title
        // account then read 0 Anime / 0.0 Days / 0.0 Score on the Library header.
        val viewer = Viewer(id = 1, name = "user")
        val entries = listOf(
            entry(11, progress = 12, score = 8.0, duration = 24),
            entry(12, progress = 3, duration = 25),
            // Planning row: counts toward anime, adds nothing watched.
            entry(13, progress = 0),
        )

        val result = viewer.withStatsDerivedFrom(entries).statistics?.anime

        assertEquals(3, result?.count)
        assertEquals(15, result?.episodesWatched)
        // 12*24 + 3*25 minutes; the entry without a duration contributes none.
        assertEquals((12 * 24 + 3 * 25).toLong(), result?.minutesWatched)
        // Only the scored entry averages in; unscored titles must not drag it down.
        assertEquals(8.0, result?.meanScore!!, 1e-9)
    }

    @Test
    fun `statistics the account really sent are kept untouched`() {
        val real = AnimeStat(count = 7, episodesWatched = 90, minutesWatched = 2160, meanScore = 8.5)
        val viewer = Viewer(id = 1, name = "user", statistics = ViewerStatistics(anime = real))

        val result = viewer.withStatsDerivedFrom(listOf(entry(11, progress = 12)))

        assertSame(real, result.statistics?.anime)
    }

    @Test
    fun `an empty list never invents statistics`() {
        // A genuinely empty account is the one case where zeros are the truth.
        val viewer = Viewer(id = 1, name = "user")

        assertNull(viewer.withStatsDerivedFrom(emptyList()).statistics)
    }

    @Test
    fun `watch time reads in hours and minutes`() {
        // The header used to divide these minutes into days, where everything short of a full
        // day collapsed to "0.8" or less.
        assertEquals("0m", watchTimeLabel(0))
        assertEquals("45m", watchTimeLabel(45))
        assertEquals("1h", watchTimeLabel(60))
        assertEquals("20h 12m", watchTimeLabel(1212))
        // Well past a day, which is the only range the old figure could state at all.
        assertEquals("297h 30m", watchTimeLabel(17_850))
    }

    @Test
    fun `a negative minute count never prints a negative time`() {
        // Nothing should produce one, but the field is a plain Long off the wire.
        assertEquals("0m", watchTimeLabel(-30))
    }

    @Test
    fun `a mean score of zero stays zero when nothing is scored`() {
        val result = derivedAnimeStats(listOf(entry(11, progress = 5, duration = 24)))

        assertEquals(0.0, result.meanScore, 1e-9)
        assertEquals(120L, result.minutesWatched)
    }
}
