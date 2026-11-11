package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One Piece 1180 as `getSourcesNew` served it on 2026-09-30: the manifest on a fast host, the
 * subtitle on a pool host that holds the body for 10-12 s — past Cronet's read timeout.
 */
class MegaPlaySubtitleHostTest {
    private val prefix = "/anime/f899139df5e1059396431415e770c6dd/9b1f2a88329da501e20b5013bca68d9c"
    private val stream = "https://fetch.nexabloom.top$prefix/master.m3u8"
    private val slowSubtitle = "https://2xznd.forgotwhichbutton.online$prefix/subtitles/track_0_CR_English_eng.vtt"

    @Test
    fun `moves a pool subtitle onto the stream's host`() {
        assertEquals(
            "https://fetch.nexabloom.top$prefix/subtitles/track_0_CR_English_eng.vtt",
            megaPlaySubtitleOnStreamHost(slowSubtitle, stream),
        )
    }

    @Test
    fun `a borrowed file from another encode keeps its own path`() {
        val borrowed = "https://qoiz0.laggedduringcutscene.site/anime/53adb96c287c3931b3bc41cebb003788/" +
            "ddff1cdefe993d317922762a05f296fb/subtitles/ara-6.vtt?x=1"
        val dubStream = "https://fetch.nexabloom.top/anime/53adb96c287c3931b3bc41cebb003788/" +
            "ca8c2fc74649c9eee8774e41797556d2/master.m3u8"
        assertEquals(
            "https://fetch.nexabloom.top/anime/53adb96c287c3931b3bc41cebb003788/" +
                "ddff1cdefe993d317922762a05f296fb/subtitles/ara-6.vtt?x=1",
            megaPlaySubtitleOnStreamHost(borrowed, dubStream),
        )
    }

    @Test
    fun `leaves the subtitle alone without a native stream or outside the pool shape`() {
        assertEquals(slowSubtitle, megaPlaySubtitleOnStreamHost(slowSubtitle, null))
        // Older MegaPlay streams sat at the root of a different CDN, with no `/anime/` prefix.
        val rootStream = "https://9hjkrt.nekostream.site/06b5f6218cc47f55b40a9c165b39c10b/" +
            "8750f75c6c6d1a7564ee94cf872c1f0b/master.m3u8"
        assertEquals(slowSubtitle, megaPlaySubtitleOnStreamHost(slowSubtitle, rootStream))
        val foreign = "https://cdn.example.com/subs/eng.vtt"
        assertEquals(foreign, megaPlaySubtitleOnStreamHost(foreign, stream))
    }
}
