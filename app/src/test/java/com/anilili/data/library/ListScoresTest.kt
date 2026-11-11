package com.anilili.data.library

import com.anilili.data.model.Media
import com.anilili.data.model.MediaListEntry
import com.anilili.data.remote.aniListScoreRaw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ListScoresTest {
    @Test
    fun `an unscored entry is no score, not a zero`() {
        val scores = remoteListScores(
            listOf(
                MediaListEntry(status = "COMPLETED", score = 8.0, media = Media(id = 1)),
                MediaListEntry(status = "CURRENT", score = 0.0, media = Media(id = 2)),
                MediaListEntry(status = "COMPLETED", score = 7.5, media = Media(id = 3)),
                MediaListEntry(status = "COMPLETED", score = 9.0, media = null),
            ),
        )

        assertEquals(mapOf(1 to 8.0, 3 to 7.5), scores)
    }

    @Test
    fun `scores read as whole points unless the website gave a half`() {
        assertEquals("8", formatListScore(8.0))
        assertEquals("7.5", formatListScore(7.5))
    }

    @Test
    fun `every point on the scale has MyAnimeList's word for it and nothing else does`() {
        assertEquals("Masterpiece", listScoreLabel(10))
        assertEquals("Appalling", listScoreLabel(1))
        assertEquals(10, (1..10).mapNotNull(::listScoreLabel).distinct().size)
        assertNull(listScoreLabel(0))
        assertNull(listScoreLabel(11))
    }

    @Test
    fun `AniList is written in its format-independent hundred-point raw score`() {
        assertEquals(80, aniListScoreRaw(8))
        assertEquals(0, aniListScoreRaw(0))
        assertThrows(IllegalArgumentException::class.java) { aniListScoreRaw(11) }
    }
}
