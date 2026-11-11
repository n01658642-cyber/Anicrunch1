package com.anilili.playback

import com.anilili.data.model.StreamItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeDownloadsTest {
    @Test
    fun `download id separates audio categories and normalizes case`() {
        assertEquals(
            "episode:42:dub:1.5",
            EpisodeDownloads.idFor(42, " DUB ", "1.5"),
        )
        assertEquals(
            "episode:42:sub:1.5",
            EpisodeDownloads.idFor(42, "sub", "1.5"),
        )
    }

    @Test
    fun `native hls and direct files are downloadable, embeds and dash are not`() {
        assertTrue(EpisodeDownloads.canDownload(stream("https://cdn.example/episode/master.m3u8", "hls")))
        assertFalse(EpisodeDownloads.canDownload(stream("https://embed.example/watch/1", "embed")))
        val direct = stream("https://cdn.example/episode.mp4", "video/mp4")
        assertTrue(EpisodeDownloads.canDownload(direct))
        assertTrue(EpisodeDownloads.canSaveToDevice(direct))
        assertFalse(EpisodeDownloads.canDownload(stream("https://cdn.example/episode.mpd", "dash")))
    }

    @Test
    fun `an obfuscated playlist is downloadable because the downloader decodes it too`() {
        // These were refused while the downloader's transport was header-adding only: it would
        // have cached a playlist nothing could parse. EpisodeDownloads.upstreamFactory now runs
        // the same Flixcloud and UniqueStream decoders PlaybackService does, so the key is no
        // longer a reason to refuse — it is carried in the download's metadata instead.
        val flixcloud = stream("https://flixcloud.example/master.m3u8", "hls", playlistKey = "secret")
        assertTrue(EpisodeDownloads.canDownload(flixcloud))
        assertTrue(EpisodeDownloads.canSaveToDevice(flixcloud))

        val uniqueStream = stream(
            "https://uniquestream.example/master.m3u8",
            "hls",
            playlistKey = "uniquestream:0123456789abcdef0123456789abcdef",
        )
        assertTrue(EpisodeDownloads.canDownload(uniqueStream))

        // An embed is still an embed, key or not — there is no stream behind it to fetch.
        assertFalse(
            EpisodeDownloads.canDownload(
                stream("https://embed.example/watch/1", "embed", playlistKey = "secret"),
            ),
        )
    }

    @Test
    fun `anything downloadable can also reach the downloads folder`() {
        // Every device copy is built by downloading first and rewrapping after, so the two sets
        // are the same. Embeds are in neither.
        assertTrue(EpisodeDownloads.canSaveToDevice(stream("https://cdn.example/master.m3u8", "hls")))
        assertTrue(EpisodeDownloads.canSaveToDevice(stream("https://cdn.example/episode.mkv", "mkv")))
        assertFalse(EpisodeDownloads.canSaveToDevice(stream("https://embed.example/watch/1", "embed")))
    }

    @Test
    fun `only unfinished downloads expose the appropriate pause control`() {
        val running = download(EpisodeDownloadState.DOWNLOADING)
        val paused = download(EpisodeDownloadState.STOPPED)
        val complete = download(EpisodeDownloadState.COMPLETED)

        assertTrue(running.canPause)
        assertFalse(running.canResume)
        assertTrue(paused.canResume)
        assertFalse(paused.canPause)
        assertFalse(complete.canPause)
        assertFalse(complete.canResume)
    }

    private fun stream(
        url: String,
        type: String,
        playlistKey: String? = null,
    ) = StreamItem(
        url = url,
        type = type,
        quality = "auto",
        audio = "sub",
        referer = "https://provider.example/",
        isActive = true,
        width = null,
        height = null,
        playlistKey = playlistKey,
    )

    private fun download(state: EpisodeDownloadState) = EpisodeDownload(
        id = "episode:42:sub:1",
        uri = "https://cdn.example/master.m3u8",
        metadata = EpisodeDownloadMetadata(
            anilistId = 42,
            seriesTitle = "Example",
            episodeNumber = "1",
            provider = "example",
            category = "sub",
        ),
        state = state,
        percent = 20f,
        bytesDownloaded = 1_024,
        contentLength = 5_120,
        updatedAtMs = 0,
    )
}
