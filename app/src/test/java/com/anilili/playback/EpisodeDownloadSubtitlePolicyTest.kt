package com.anilili.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeDownloadSubtitlePolicyTest {
    @Test
    fun `offline subtitle status distinguishes pending partial and unavailable`() {
        assertNull(offlineSubtitleStatusLabel(total = 0, downloaded = 0, pending = false))
        assertNull(offlineSubtitleStatusLabel(total = 2, downloaded = 2, pending = false))
        assertEquals("Saving subtitles…", offlineSubtitleStatusLabel(2, 0, true))
        assertEquals("Subtitles unavailable offline", offlineSubtitleStatusLabel(2, 0, false))
        assertEquals("1 of 2 subtitles available offline", offlineSubtitleStatusLabel(2, 1, false))
    }

    @Test
    fun `subtitle headers prefer track profile and retain a minimal retry`() {
        val metadata = EpisodeDownloadMetadata(
            anilistId = 1,
            seriesTitle = "Title",
            episodeNumber = "1",
            provider = "Provider",
            category = "sub",
            referer = "https://video.example/watch",
            headers = mapOf("X-Video" to "token"),
        )
        val subtitle = EpisodeDownloadSubtitle(
            url = "https://subs.example/1.vtt",
            label = "English",
            language = "en",
            referer = "https://subs.example/embed",
            headers = mapOf("X-Subtitle" to "key"),
        )

        val profiles = subtitleRequestHeaderProfiles(metadata, subtitle, "Anilili-Test")

        assertEquals("https://subs.example/embed", profiles.first()["Referer"])
        assertEquals("key", profiles.first()["X-Subtitle"])
        assertEquals("token", profiles.first()["X-Video"])
        assertEquals(mapOf("User-Agent" to "Anilili-Test", "X-Subtitle" to "key"), profiles.last())
    }
}
