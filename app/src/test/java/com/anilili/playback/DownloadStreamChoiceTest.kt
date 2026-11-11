package com.anilili.playback

import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.settings.DownloadQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the queue behind the anime page takes when a provider answers.
 *
 * It used to take `streams.firstOrNull(canDownload)` — the provider's list order, which says
 * nothing about resolution and nothing about which of a provider's sources it would rather serve.
 */
class DownloadStreamChoiceTest {

    private fun hls(height: Int, active: Boolean = false) = StreamItem(
        url = "https://vault.example/$height/index.m3u8",
        type = "hls",
        quality = "${height}p",
        audio = "sub",
        referer = "https://kwik.si/",
        isActive = active,
        width = height * 16 / 9,
        height = height,
    )

    private fun file(height: Int) = StreamItem(
        url = "https://files.example/$height.mp4",
        type = "mp4",
        quality = "${height}p",
        audio = "sub",
        referer = null,
        isActive = false,
        width = height * 16 / 9,
        height = height,
    )

    private fun embed() = StreamItem(
        url = "https://embed.example/watch/1",
        type = "embed",
        quality = "embed",
        audio = null,
        referer = "https://embed.example/",
        isActive = false,
        width = null,
        height = null,
    )

    private fun sources(vararg streams: StreamItem) = SourcesResult(
        streams = streams.toList(),
        subtitles = emptyList(),
        skip = null,
        download = null,
    )

    @Test
    fun `a ladder published as separate playlists honours the requested ceiling`() {
        // Kwik's shape: one playlist per rendition, listed best-first. Track selection has a single
        // video track inside each, so the URL is the only thing that decides — and the queue picked
        // it by list position, which is how a 360p request downloaded 1080p.
        val sources = sources(hls(1080, active = true), hls(720), hls(360))

        assertEquals(
            hls(360).url,
            downloadStreamFor("kiwi", sources, DownloadQuality.P360)?.url,
        )
        assertEquals(
            hls(720).url,
            downloadStreamFor("kiwi", sources, DownloadQuality.P720)?.url,
        )
        assertEquals(
            hls(1080).url,
            downloadStreamFor("kiwi", sources, DownloadQuality.BEST)?.url,
        )
    }

    @Test
    fun `a ceiling no rendition reaches takes the smallest rather than nothing`() {
        val sources = sources(hls(1080, active = true), hls(720))

        assertEquals(
            hls(720).url,
            downloadStreamFor("kiwi", sources, DownloadQuality.P360)?.url,
        )
    }

    @Test
    fun `the provider's own preference decides which source is used`() {
        // Ally mixes AllAnime progressive files with an unreliable HLS mirror and its policy puts
        // the files first; list order puts the mirror first. The download follows the policy, so
        // the saved copy is the one the player would have used.
        val mirror = hls(1080, active = true)
        val sources = sources(mirror, file(1080))

        assertEquals(file(1080).url, downloadStreamFor("ally", sources, DownloadQuality.BEST)?.url)
        assertEquals(mirror.url, downloadStreamFor("kiwi", sources, DownloadQuality.BEST)?.url)
    }

    @Test
    fun `an embed listed first is skipped rather than refused`() {
        val sources = sources(embed(), hls(1080, active = true))

        assertEquals(
            hls(1080).url,
            downloadStreamFor("bonk", sources, DownloadQuality.BEST)?.url,
        )
    }

    @Test
    fun `a provider offering only embeds has nothing to download`() {
        assertNull(downloadStreamFor("bonk", sources(embed()), DownloadQuality.BEST))
    }

    @Test
    fun `a single multivariant manifest is left for track selection to narrow`() {
        // The renditions are inside this one playlist, so there is no URL choice to make and the
        // ceiling is applied later, against the manifest's own ladder.
        val master = StreamItem(
            url = "https://hls.example/manifest/master.m3u8",
            type = "hls",
            quality = "auto",
            audio = "sub",
            referer = null,
            isActive = true,
            width = null,
            height = null,
        )

        assertEquals(
            master.url,
            downloadStreamFor("kaa", sources(master), DownloadQuality.P360)?.url,
        )
    }
}
