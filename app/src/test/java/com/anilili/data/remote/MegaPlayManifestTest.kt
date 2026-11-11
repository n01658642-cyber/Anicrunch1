package com.anilili.data.remote

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MegaPlayManifestTest {
    private val master = "https://fetch.example/anime/show/encode/master.m3u8?token=1"
    private val legacy = "https://fetch.example/anime/show/encode/index-f1-v1-a1.m3u8?token=1"
    private val modernLadder = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=5500000,RESOLUTION=1920x1080
        index-f1.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
        index-f2.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
        index-f3.m3u8
    """.trimIndent()
    private val media = "#EXTM3U\n#EXTINF:4.004,\nhttps://cdn.example/seg-f1-00000.html\n#EXT-X-ENDLIST"

    @Test fun `working modern master never requests the missing legacy sibling`() = runBlocking {
        val requested = mutableListOf<String>()
        assertEquals(master, resolveMegaPlayManifest(master) {
            requested += it
            if (it == master) modernLadder else throw IOException("404")
        })
        assertEquals(listOf(master), requested)
    }

    @Test fun `existing legacy rendition cannot hide lower qualities from a working master`() = runBlocking {
        val ladder = modernLadder.replace("index-f1.m3u8", "index-f1-v1-a1.m3u8")
        assertEquals(master, resolveMegaPlayManifest(master) { if (it == master) ladder else media })
    }

    @Test fun `refused master uses verified legacy rendition with query intact`() = runBlocking {
        val requested = mutableListOf<String>()
        assertEquals(legacy, resolveMegaPlayManifest(master) {
            requested += it
            if (it == master) throw IOException("HTTP 403") else media
        })
        assertEquals(listOf(master, legacy), requested)
    }

    @Test fun `two failures return no native URL so embed remains the fallback`() = runBlocking {
        assertNull(resolveMegaPlayManifest(master) { throw IOException("offline") })
        assertNull(resolveMegaPlayManifest(master) { "<html>Access denied</html>" })
    }

    @Test fun `header and trick play metadata without media do not count as playable`() {
        assertFalse(megaPlayManifestCanPlay("#EXTM3U\n"))
        assertFalse(megaPlayManifestCanPlay("#EXTM3U\n#EXT-X-I-FRAME-STREAM-INF:URI=\"iframes.m3u8\""))
        assertFalse(megaPlayManifestCanPlay("#EXTM3U\n#EXTINF:4,\n#EXT-X-ENDLIST"))
        assertTrue(megaPlayManifestCanPlay(" \n$media"))
    }

    @Test fun `direct rendition is accepted without inventing a sibling`() = runBlocking {
        val requested = mutableListOf<String>()
        assertEquals(legacy, resolveMegaPlayManifest(legacy) { requested += it; media })
        assertEquals(listOf(legacy), requested)
    }

    @Test fun `cancelled resolution cannot issue a fallback request`() = runBlocking {
        val requested = mutableListOf<String>()
        try {
            resolveMegaPlayManifest(master) { requested += it; throw CancellationException("leaving player") }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) {
            assertEquals(listOf(master), requested)
        }
    }
}
