package com.anilili.playback

import com.anilili.data.model.StreamItem
import com.anilili.data.settings.DownloadQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a server can meet the resolution a viewer asked for — the question behind the download
 * dialog's warning and the queue's per-episode server switch.
 *
 * The report that started it: 720p chosen as the default, 480p picked for a season, and every
 * episode still came back at 1080p, because the server had only ever published 1080p.
 */
class DownloadQualityMatchTest {

    private fun hls(height: Int?, url: String = "https://cdn.example/$height/index.m3u8") = StreamItem(
        url = url,
        type = "hls",
        quality = height?.let { "${it}p" } ?: "auto",
        audio = "sub",
        referer = null,
        isActive = false,
        width = null,
        height = height,
    )

    @Test
    fun `a single 1080p rendition misses every lower choice`() {
        val offered = listOf(1080)
        assertEquals(1080, deliveredHeight(offered, DownloadQuality.P480))
        assertFalse(fitsQuality(offered, DownloadQuality.P720))
        assertFalse(fitsQuality(offered, DownloadQuality.P480))
        assertTrue(fitsQuality(offered, DownloadQuality.P1080))
    }

    @Test
    fun `a ladder with the chosen rung fits`() {
        // kaa, measured: 360/540/720/1080.
        val offered = listOf(360, 540, 720, 1080)
        assertEquals(720, deliveredHeight(offered, DownloadQuality.P720))
        assertTrue(fitsQuality(offered, DownloadQuality.P720))
        assertTrue(fitsQuality(offered, DownloadQuality.P360))
    }

    @Test
    fun `a ladder that drops far below the choice misses it too`() {
        // 720p asked of a 360/1080 ladder comes back 360p — as much a surprise as 1080p would be.
        assertEquals(360, deliveredHeight(listOf(360, 1080), DownloadQuality.P720))
        assertFalse(fitsQuality(listOf(360, 1080), DownloadQuality.P720))
        // kaa's 480p request lands on 360p: no 480 rung, so it misses.
        assertFalse(fitsQuality(listOf(360, 540, 720, 1080), DownloadQuality.P480))
    }

    @Test
    fun `a rung stored a little short still counts as its resolution`() {
        assertTrue(fitsQuality(listOf(1072), DownloadQuality.P1080))
        assertTrue(fitsQuality(listOf(704), DownloadQuality.P720))
    }

    @Test
    fun `a ladder with nothing under the ceiling misses on its smallest rung`() {
        val offered = listOf(720, 1080)
        assertEquals(720, deliveredHeight(offered, DownloadQuality.P480))
        assertFalse(fitsQuality(offered, DownloadQuality.P480))
    }

    @Test
    fun `best available always fits and takes the top rung`() {
        assertEquals(1080, deliveredHeight(listOf(480, 1080), DownloadQuality.BEST))
        assertTrue(fitsQuality(listOf(1080), DownloadQuality.BEST))
    }

    @Test
    fun `an unreadable ladder claims nothing`() {
        assertNull(deliveredHeight(emptyList(), DownloadQuality.P480))
        assertTrue(fitsQuality(emptyList(), DownloadQuality.P480))
    }

    @Test
    fun `renditions published as separate playlists are the ladder`() {
        val chosen = hls(1080)
        assertEquals(
            listOf(360, 720, 1080),
            publishedHeights(chosen, listOf(chosen, hls(720), hls(360))),
        )
    }

    @Test
    fun `a lone playlist with no declared height publishes nothing to go on`() {
        // anikoto's shape: one media playlist, labelled by nothing that says how tall it is.
        val chosen = hls(null, url = "https://cdn.example/master.m3u8")
        assertEquals(emptyList<Int>(), publishedHeights(chosen, listOf(chosen)))
    }
}
