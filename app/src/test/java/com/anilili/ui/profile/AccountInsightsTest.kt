package com.anilili.ui.profile

import com.anilili.data.library.activityStreak
import com.anilili.data.library.mergedActivity
import com.anilili.data.model.FuzzyDate
import com.anilili.data.model.Media
import com.anilili.data.model.MediaListEntry
import com.anilili.data.model.StudioConnection
import com.anilili.data.model.StudioNode
import com.anilili.data.model.Viewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountInsightsTest {
    private fun entry(
        id: Int,
        status: String,
        progress: Int = 0,
        score: Double = 0.0,
        episodes: Int? = null,
        year: Int? = null,
        format: String? = "TV",
        genres: List<String> = emptyList(),
        studio: String? = null,
    ) = MediaListEntry(
        id = id,
        status = status,
        progress = progress,
        score = score,
        updatedAt = id.toLong(),
        media = Media(
            id = id,
            episodes = episodes,
            format = format,
            genres = genres,
            startDate = year?.let { FuzzyDate(year = it) },
            studios = StudioConnection(listOfNotNull(studio?.let { StudioNode(id, it, isAnimationStudio = true) })),
        ),
    )

    private fun profile(
        watching: List<MediaListEntry> = emptyList(),
        planning: List<MediaListEntry> = emptyList(),
        completed: List<MediaListEntry> = emptyList(),
        dropped: List<MediaListEntry> = emptyList(),
    ) = AniListProfile(
        viewer = Viewer(1, "viewer"),
        watching = watching,
        rewatching = emptyList(),
        planning = planning,
        paused = emptyList(),
        completed = completed,
        dropped = dropped,
    )

    @Test
    fun `a streak runs back from today, or from yesterday until tonight's episode`() {
        val days = mapOf(10L to 2, 9L to 1, 8L to 3, 6L to 1)
        assertEquals(3, activityStreak(days, today = 10))
        assertEquals(3, activityStreak(days, today = 11))
        assertEquals(0, activityStreak(days, today = 12))
    }

    @Test
    fun `a day counted on the device and on the account keeps the larger number`() {
        assertEquals(
            mapOf(1L to 3, 2L to 4, 3L to 1),
            mergedActivity(local = mapOf(1L to 3, 2L to 1), account = mapOf(2L to 4, 3L to 1, 4L to 0)),
        )
    }

    @Test
    fun `release years are listed year by year, then by decade once the list spans more than twelve`() {
        val close = accountInsights(profile(completed = listOf(entry(1, "COMPLETED", year = 2024), entry(2, "COMPLETED", year = 2026))))
        assertEquals(listOf(yearLabel(2024) to 1, yearLabel(2025) to 0, yearLabel(2026) to 1), close.releaseYears)
        val wide = accountInsights(profile(completed = listOf(entry(1, "COMPLETED", year = 1998), entry(2, "COMPLETED", year = 2021))))
        assertEquals(listOf("90s" to 1, "00s" to 0, "10s" to 0, "20s" to 1), wide.releaseYears)
    }

    @Test
    fun `completion rate, episodes to go and the score spread come from the lists`() {
        val insights = accountInsights(
            profile(
                watching = listOf(entry(1, "CURRENT", progress = 3, episodes = 12), entry(2, "CURRENT", progress = 5)),
                completed = listOf(entry(3, "COMPLETED", score = 8.4), entry(4, "COMPLETED", score = 8.0), entry(5, "COMPLETED")),
                dropped = listOf(entry(6, "DROPPED", score = 3.0)),
            ),
        )
        assertEquals(75, insights.completionRate)
        // Only the series with a known length counts; the other has no end to count down to.
        assertEquals(9, insights.episodesToGo)
        assertEquals(2, insights.scoreSpread.first { it.first == "8" }.second)
        assertEquals(1, insights.scoreSpread.first { it.first == "3" }.second)
        assertNull(accountInsights(profile(planning = listOf(entry(7, "PLANNING")))).completionRate)
    }

    @Test
    fun `genres, formats and studios rank what has been started, most first`() {
        val insights = accountInsights(
            profile(
                watching = listOf(entry(1, "CURRENT", genres = listOf("Drama", "Action"), studio = "Bones")),
                completed = listOf(entry(2, "COMPLETED", format = "MOVIE", genres = listOf("Drama"), studio = "Bones")),
                planning = listOf(entry(3, "PLANNING", genres = listOf("Comedy"), studio = "Trigger")),
            ),
        )
        assertEquals(listOf("Drama" to 2, "Action" to 1), insights.topGenres)
        assertEquals(listOf("Bones" to 2), insights.studios)
        assertEquals(listOf("TV" to 2, "Movie" to 1), insights.formats)
    }

    @Test
    fun `every format stays visible even when a library contains all seven`() {
        val formats = listOf("TV", "TV_SHORT", "MOVIE", "OVA", "ONA", "SPECIAL", "MUSIC")
        val insights = accountInsights(profile(completed = formats.mapIndexed { index, format ->
            entry(index + 1, "COMPLETED", format = format)
        }))
        assertEquals(7, insights.formats.size)
        assertEquals(7, insights.formats.sumOf { it.second })
    }

    @Test
    fun `unknown media and invalid release years do not lose ratings or create centuries of bars`() {
        val insights = accountInsights(profile(completed = listOf(
            entry(1, "COMPLETED", year = 0),
            entry(2, "COMPLETED", year = 2026),
            entry(3, "COMPLETED", score = 7.0).copy(media = null),
            entry(4, "COMPLETED", score = 9.0).copy(media = null),
        )))
        assertEquals(listOf(yearLabel(2026) to 1), insights.releaseYears)
        assertEquals(2, insights.scoreSpread.sumOf { it.second })
    }
}
