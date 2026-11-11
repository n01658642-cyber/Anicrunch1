package com.anilili.playback

import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPlayerTitleTest {
    @Test
    fun seriesAndEpisodeAreJoined() {
        assertEquals("Frieren · Episode 4", externalPlayerTitle("Frieren", "Episode 4"))
    }

    @Test
    fun aBlankPartLeavesNoSeparatorBehind() {
        assertEquals("Frieren", externalPlayerTitle("Frieren", "   "))
        assertEquals("Episode 4", externalPlayerTitle("", "Episode 4"))
    }

    /** Films arrive with the episode title echoing the series; showing it twice reads as a bug. */
    @Test
    fun aRepeatedTitleIsShownOnce() {
        assertEquals("Suzume", externalPlayerTitle("Suzume", "Suzume"))
    }
}

class ExternalPlayerMimeTypeTest {
    /**
     * VLC sends anything whose type does not begin `video` down a background path that opens no
     * player and says nothing, so the accurate manifest types made every HLS hand-off — nearly
     * every stream here — look like VLC ignoring the episode. Nothing may leave the `video/` family.
     */
    @Test
    fun aManifestIsNeverDeclaredAsOne() {
        assertEquals("video/*", externalPlayerMimeType("https://h/v.m3u8", "hls"))
        assertEquals("video/*", externalPlayerMimeType("https://h/v.mpd", "dash"))
    }

    @Test
    fun everyTypeStaysInTheVideoFamily() {
        val urls = listOf("https://h/v.m3u8", "https://h/v.mpd", "https://h/v.mkv", "https://h/s")
        for (url in urls) {
            assertTrue(url, externalPlayerMimeType(url, "hls").startsWith("video"))
            assertTrue(url, externalPlayerMimeType(url, "").startsWith("video"))
        }
    }

    /** The type is carried on the stream even when the URL says nothing, and the reverse. */
    @Test
    fun eitherTheTypeOrTheExtensionIsEnough() {
        assertEquals("video/*", externalPlayerMimeType("https://h/play?id=9", "hls"))
        assertEquals("video/*", externalPlayerMimeType("https://h/v.m3u8", ""))
    }

    /** A query string is not part of the path and must not decide the type. */
    @Test
    fun aQueryStringIsIgnored() {
        assertEquals("video/x-matroska", externalPlayerMimeType("https://h/v.mkv?token=a.m3u8", ""))
    }

    /** A manifest whose path happens to end in a container extension is still a manifest. */
    @Test
    fun aManifestOutranksAStrayExtension() {
        assertEquals("video/*", externalPlayerMimeType("https://h/v.mkv", "hls"))
    }

    /**
     * Anything unrecognised goes out as the wildcard video type rather than a guess: a player that
     * filters on an exact type it does not know never appears in the chooser at all.
     */
    @Test
    fun anythingElseStaysBroad() {
        assertEquals("video/*", externalPlayerMimeType("https://h/stream", ""))
        assertEquals("video/*", externalPlayerMimeType("https://h/v.mp4", ""))
    }
}

class ExternalPlayerHeaderPairsTest {
    /** MX Player reads alternating names and values; `Name: value` lines arrive as garbage. */
    @Test
    fun headersBecomeNameValuePairs() {
        val pairs = externalPlayerHeaderPairs(
            linkedMapOf("Referer" to "https://h/", "Origin" to "https://h", "User-Agent" to "UA"),
        )
        assertEquals(listOf("Referer", "https://h/", "Origin", "https://h", "User-Agent", "UA"), pairs.toList())
    }

    /** A blank value is worse than an absent header: it overrides the receiving player's own. */
    @Test
    fun blankEntriesAreDropped() {
        val pairs = externalPlayerHeaderPairs(linkedMapOf("Referer" to "", "  " to "x", "Origin" to "https://h"))
        assertEquals(listOf("Origin", "https://h"), pairs.toList())
    }
}

class ExternalPlaybackRequestTest {
    private val stream = StreamItem(
        url = "https://cdn/v.m3u8",
        type = "hls",
        quality = "1080p",
        audio = null,
        referer = "https://provider.example/watch",
        isActive = true,
        width = null,
        height = null,
        headers = mapOf("X-Video-Token" to "abc"),
    )

    /** The receiving player fetches the stream itself, so it needs the CDN's whole request profile. */
    @Test
    fun theStreamsOwnHeadersAreCarriedOver() {
        val request = externalPlaybackRequest(stream, "Frieren", "Episode 4", 0L, emptyList())
        assertEquals("abc", request.headers["X-Video-Token"])
        assertEquals("https://provider.example/watch", request.headers["Referer"])
        assertEquals("https://provider.example", request.headers["Origin"])
    }

    /**
     * A CDN that gates on Referer usually gates on User-Agent too, and the other player's own
     * default will not be this app's.
     */
    @Test
    fun theUserAgentIsAlwaysStated() {
        val request = externalPlaybackRequest(stream, "Frieren", "Episode 4", 0L, emptyList())
        assertEquals(NATIVE_PLAYBACK_USER_AGENT, request.headers["User-Agent"])
    }

    @Test
    fun resumePositionAndSubtitlesArePassedThrough() {
        val subtitle = SubtitleItem(url = "https://cdn/en.vtt", label = "English", language = "en")
        val request = externalPlaybackRequest(stream, "Frieren", "Episode 4", 90_000L, listOf(subtitle))
        assertEquals(90_000L, request.resumePositionMs)
        assertTrue(request.subtitles.single().url == "https://cdn/en.vtt")
    }
}
