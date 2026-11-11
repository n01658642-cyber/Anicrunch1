package com.anilili.playback

import com.anilili.data.model.StreamItem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KwikPlaylistIvTest {

    /** Kwik's real shape: implicit IV, and segment names that skip while the sequence counts. */
    private val kwikPlaylist = """
        #EXTM3U
        #EXT-X-TARGETDURATION:18
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXT-X-KEY:METHOD=AES-128,URI="mon.key"
        #EXT-X-VERSION:3
        #EXT-X-MEDIA-SEQUENCE:1
        #EXTINF:10.010,
        segment-1-v1-a1.jpg
        #EXTINF:13.263,
        segment-5-v1-a1.jpg
        #EXTINF:8.425,
        segment-6-v1-a1.jpg
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun `every segment is given the iv its filename implies`() {
        val rewritten = requireNotNull(rewriteKwikPlaylistIvs(kwikPlaylist))
        val ivs = Regex("IV=(0x[0-9a-f]{32})").findAll(rewritten).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "0x00000000000000000000000000000001",
                "0x00000000000000000000000000000005",
                "0x00000000000000000000000000000006",
            ),
            ivs,
        )
    }

    @Test
    fun `each key tag keeps the original uri so the key is still fetched once`() {
        val rewritten = requireNotNull(rewriteKwikPlaylistIvs(kwikPlaylist))
        val keyLines = rewritten.lines().filter { it.startsWith("#EXT-X-KEY:") }
        assertEquals(4, keyLines.size) // the original, plus one per segment
        assertTrue(keyLines.all { it.contains("""URI="mon.key"""") })
        assertTrue(keyLines.all { it.contains("METHOD=AES-128") })
    }

    @Test
    fun `each iv tag precedes the segment it belongs to`() {
        val lines = requireNotNull(rewriteKwikPlaylistIvs(kwikPlaylist)).lines()
        val segmentIndex = lines.indexOfFirst { it.trim() == "segment-5-v1-a1.jpg" }
        assertTrue(lines[segmentIndex - 1].contains("IV=0x00000000000000000000000000000005"))
    }

    @Test
    fun `segment lines and every other line survive untouched`() {
        val rewritten = requireNotNull(rewriteKwikPlaylistIvs(kwikPlaylist))
        val withoutInserted = rewritten.lines().filterNot {
            it.startsWith("#EXT-X-KEY:") && it.contains(",IV=")
        }
        assertEquals(kwikPlaylist.lines(), withoutInserted)
    }

    @Test
    fun `a playlist that already states its iv is standard and left alone`() {
        val explicit = kwikPlaylist.replace(
            """#EXT-X-KEY:METHOD=AES-128,URI="mon.key"""",
            """#EXT-X-KEY:METHOD=AES-128,URI="mon.key",IV=0x000000000000000000000000000000ff""",
        )
        assertNull(rewriteKwikPlaylistIvs(explicit))
    }

    @Test
    fun `playlists from other providers are passed through`() {
        assertNull(
            rewriteKwikPlaylistIvs(
                """
                #EXTM3U
                #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
                #EXTINF:10.0,
                chunk-000.ts
                """.trimIndent(),
            ),
        )
        assertNull(
            rewriteKwikPlaylistIvs(
                """
                #EXTM3U
                #EXTINF:10.0,
                segment-1-v1-a1.jpg
                """.trimIndent(),
            ),
        )
        assertNull(rewriteKwikPlaylistIvs("not a playlist at all"))
    }

    @Test
    fun `a playlist whose segments are only partly kwik shaped is not half rewritten`() {
        val mixed = kwikPlaylist.replace("segment-6-v1-a1.jpg", "trailer.ts")
        assertNull(rewriteKwikPlaylistIvs(mixed))
    }

    @Test
    fun `windows line endings are preserved`() {
        val rewritten = requireNotNull(rewriteKwikPlaylistIvs(kwikPlaylist.replace("\n", "\r\n")))
        assertTrue(rewritten.contains("IV=0x00000000000000000000000000000005\r\n"))
        assertTrue(rewritten.contains("segment-5-v1-a1.jpg\r\n"))
    }

    @Test
    fun `a kwik playlist has a progressive mp4 behind the same path`() {
        val hls = kwikStream("https://vault-08.uwucdn.top/stream/08/13/deadbeef/uwu.m3u8")
        val mp4 = requireNotNull(kwikProgressiveMp4(hls, "One Piece 1172.mp4"))

        assertEquals(
            "https://vault-08.uwucdn.top/mp4/08/13/deadbeef?file=One%20Piece%201172.mp4",
            mp4.url,
        )
        assertEquals("mp4", mp4.type)
    }

    @Test
    fun `only kwik's own vault hosts and path shape are rewritten`() {
        // A different host, a different path, or an already-progressive stream is left alone —
        // this is an undocumented endpoint and guessing it elsewhere would break the download.
        assertNull(
            kwikProgressiveMp4(kwikStream("https://cdn.example/stream/08/13/x/uwu.m3u8"), "a.mp4"),
        )
        assertNull(
            kwikProgressiveMp4(kwikStream("https://vault-08.uwucdn.top/hls/08/13/x/uwu.m3u8"), "a.mp4"),
        )
        assertNull(
            kwikProgressiveMp4(kwikStream("https://vault-08.uwucdn.top/stream/08/13/x/master.m3u8"), "a.mp4"),
        )
        assertNull(
            kwikProgressiveMp4(
                kwikStream("https://vault-08.uwucdn.top/mp4/08/13/x", type = "mp4"),
                "a.mp4",
            ),
        )
    }

    private fun kwikStream(url: String, type: String = "hls") = StreamItem(
        url = url,
        type = type,
        quality = "1080p",
        audio = null,
        referer = "https://kwik.si/",
        isActive = true,
        width = 1920,
        height = 1080,
    )

    @Test
    fun `the iv is a 128 bit big endian hex literal`() {
        assertEquals("0x00000000000000000000000000000001", hlsIv(1))
        assertEquals("0x00000000000000000000000000000087", hlsIv(135))
        assertEquals("0x000000000000000000000000000000ff", hlsIv(255))
    }
}
