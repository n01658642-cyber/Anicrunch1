package com.anilili.ui.watch

import com.anilili.data.model.StreamItem
import com.anilili.playback.selectDownloadStream
import com.anilili.data.settings.DownloadQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRenditionChoiceTest {

    private fun hls(height: Int) = StreamItem(
        url = "https://vault-08.uwucdn.top/stream/08/13/${height}hash/uwu.m3u8",
        type = "hls",
        quality = "${height}p",
        audio = "sub",
        referer = "https://kwik.si/",
        isActive = height == 1080,
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

    @Test
    fun `separate hls renditions honour the chosen resolution`() {
        // Kwik ships each rendition as its own playlist, so the URL is the only thing that decides
        // — track selection has a single video track to choose from. Asking for 360p used to
        // download 1080p.
        val renditions = listOf(hls(1080), hls(720), hls(360))

        assertEquals(
            hls(360).url,
            selectDownloadStream(hls(1080), renditions, DownloadQuality.P360).url,
        )
        assertEquals(
            hls(720).url,
            selectDownloadStream(hls(1080), renditions, DownloadQuality.P720).url,
        )
        assertEquals(
            hls(1080).url,
            selectDownloadStream(hls(360), renditions, DownloadQuality.BEST).url,
        )
    }

    @Test
    fun `a lone hls entry is left to track selection`() {
        // It may be a multivariant manifest whose renditions live inside it, and swapping the URL
        // would throw away the adaptive ladder the player could otherwise use.
        val master = hls(1080)

        assertEquals(master.url, selectDownloadStream(master, listOf(master), DownloadQuality.P360).url)
    }

    @Test
    fun `the smallest rendition is taken when nothing meets the ceiling`() {
        val renditions = listOf(hls(1080), hls(720))

        assertEquals(
            hls(720).url,
            selectDownloadStream(hls(1080), renditions, DownloadQuality.P360).url,
        )
    }

    @Test
    fun `progressive files still choose by height`() {
        val files = listOf(file(1080), file(720), file(360))

        assertEquals(
            file(720).url,
            selectDownloadStream(file(1080), files, DownloadQuality.P720).url,
        )
    }
}
