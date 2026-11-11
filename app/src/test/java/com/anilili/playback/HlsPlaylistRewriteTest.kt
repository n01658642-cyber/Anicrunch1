package com.anilili.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LooksLikeHlsPlaylistTest {
    @Test
    fun theDeclaredTypeIsEnough() {
        assertTrue(looksLikeHlsPlaylist("application/vnd.apple.mpegurl", ByteArray(0)))
        assertTrue(looksLikeHlsPlaylist("application/x-mpegURL; charset=utf-8", ByteArray(0)))
    }

    /**
     * These CDNs serve manifests as octet-stream and text/plain constantly, and one that is not
     * recognised is one whose segments go back to the CDN bare and 403.
     */
    @Test
    fun aMislabelledPlaylistIsStillRecognised() {
        val body = "#EXTM3U\n#EXT-X-VERSION:3\n".encodeToByteArray()
        assertTrue(looksLikeHlsPlaylist("application/octet-stream", body))
        assertTrue(looksLikeHlsPlaylist("text/plain", body))
        assertTrue(looksLikeHlsPlaylist(null, body))
    }

    @Test
    fun aBomAndBlankLinesBeforeTheMarkerAreTolerated() {
        val body = "\uFEFF\r\n  \n#EXTM3U\n#EXT-X-VERSION:3".encodeToByteArray()
        assertTrue(looksLikeHlsPlaylist("application/octet-stream", body))
    }

    @Test
    fun mediaIsNotMistakenForAPlaylist() {
        assertFalse(looksLikeHlsPlaylist("video/mp2t", byteArrayOf(0x47, 0x40, 0x00, 0x10)))
        assertFalse(looksLikeHlsPlaylist(null, "not a playlist".encodeToByteArray()))
    }
}

class RewriteHlsPlaylistTest {
    /** Stands in for the proxy's registry: records what it was given, hands back a loopback URL. */
    private val registered = mutableListOf<String>()
    private val register: (String) -> String = { url ->
        registered += url
        "http://127.0.0.1:9000/t${registered.size}"
    }

    @Test
    fun relativeSegmentsAreResolvedAgainstThePlaylist() {
        val playlist = """
            #EXTM3U
            #EXTINF:4.0,
            seg0.ts
            #EXTINF:4.0,
            sub/seg1.ts
        """.trimIndent()
        val out = rewriteHlsPlaylist(playlist, "https://cdn.example/a/b/index.m3u8", register)
        assertEquals(
            listOf("https://cdn.example/a/b/seg0.ts", "https://cdn.example/a/b/sub/seg1.ts"),
            registered,
        )
        assertTrue(out.contains("http://127.0.0.1:9000/t1"))
        assertTrue(out.contains("http://127.0.0.1:9000/t2"))
        assertFalse(out.contains("seg0.ts"))
    }

    @Test
    fun absoluteSegmentsOnAnotherHostAreRewrittenToo() {
        val playlist = "#EXTM3U\n#EXTINF:4.0,\nhttps://other.cdn/x/seg.ts\n"
        rewriteHlsPlaylist(playlist, "https://cdn.example/index.m3u8", register)
        assertEquals(listOf("https://other.cdn/x/seg.ts"), registered)
    }

    /**
     * The key is the one URL that must not be missed: without it the player has every segment and
     * can decrypt none of them, which looks like a corrupt stream rather than a refused request.
     */
    @Test
    fun theEncryptionKeyIsRewritten() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x0123
            #EXTINF:4.0,
            seg0.ts
        """.trimIndent()
        val out = rewriteHlsPlaylist(playlist, "https://cdn.example/index.m3u8", register)
        assertTrue(registered.contains("https://cdn.example/key.bin"))
        assertTrue(out.contains("""#EXT-X-KEY:METHOD=AES-128,URI="http://127.0.0.1:9000/t1",IV=0x0123"""))
    }

    @Test
    fun mapAndMediaUrisAreRewritten() {
        val playlist = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="English",URI="audio/en.m3u8"
        """.trimIndent()
        rewriteHlsPlaylist(playlist, "https://cdn.example/index.m3u8", register)
        assertEquals(
            listOf("https://cdn.example/init.mp4", "https://cdn.example/audio/en.m3u8"),
            registered,
        )
    }

    /** A master playlist's variants are themselves playlists and must come back here to be rewritten. */
    @Test
    fun variantPlaylistsAreRewritten() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
            1080/index.m3u8
        """.trimIndent()
        rewriteHlsPlaylist(playlist, "https://cdn.example/master.m3u8", register)
        assertEquals(
            listOf("https://cdn.example/360/index.m3u8", "https://cdn.example/1080/index.m3u8"),
            registered,
        )
    }

    /** Everything not naming a URL is content the receiving player parses; it must survive exactly. */
    @Test
    fun tagsThatNameNoUrlAreUntouched() {
        val playlist = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-ENDLIST"
        assertEquals(playlist, rewriteHlsPlaylist(playlist, "https://cdn.example/i.m3u8", register))
        assertTrue(registered.isEmpty())
    }

    /** An unencrypted stream says so with a bare METHOD=NONE and no URI to replace. */
    @Test
    fun aKeyWithoutAUriIsLeftAlone() {
        val playlist = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n"
        assertEquals(playlist, rewriteHlsPlaylist(playlist, "https://cdn.example/i.m3u8", register))
        assertTrue(registered.isEmpty())
    }

    @Test
    fun windowsLineEndingsAreKept() {
        val playlist = "#EXTM3U\r\n#EXTINF:4.0,\r\nseg0.ts\r\n"
        val out = rewriteHlsPlaylist(playlist, "https://cdn.example/i.m3u8", register)
        assertTrue(out.contains("\r\n"))
        assertFalse(out.contains("\n\n"))
    }

    /**
     * A query string is how these CDNs carry their per-playback token, so it has to survive into
     * the registered URL — stripping it is the same 403 by another route.
     */
    @Test
    fun aSegmentsQueryStringIsPreserved() {
        val playlist = "#EXTM3U\n#EXTINF:4.0,\nseg0.ts?token=abc123\n"
        rewriteHlsPlaylist(playlist, "https://cdn.example/a/i.m3u8", register)
        assertEquals(listOf("https://cdn.example/a/seg0.ts?token=abc123"), registered)
    }
}

class ProxyRequestParsingTest {
    private fun parse(raw: String) = readRequest(raw.encodeToByteArray().inputStream())

    @Test
    fun theTokenIsTakenFromThePath() {
        val request = parse("GET /abc123 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertEquals("abc123", request?.token)
        assertEquals("GET", request?.method)
    }

    /** Seeking is a Range request, and a player that cannot seek looks broken. */
    @Test
    fun rangeHeadersAreCarriedUpstream() {
        val request = parse("GET /abc HTTP/1.1\r\nRange: bytes=100-200\r\n\r\n")
        assertEquals(listOf("Range" to "bytes=100-200"), request?.forwardable)
    }

    /**
     * The receiving player's own Referer must not reach the CDN: it would overwrite the one that
     * makes the request work, which is the whole reason for standing in the middle.
     */
    @Test
    fun identityHeadersAreNotCarriedUpstream() {
        val request = parse("GET /abc HTTP/1.1\r\nReferer: http://127.0.0.1/\r\nUser-Agent: VLC\r\n\r\n")
        assertTrue(request?.forwardable.orEmpty().isEmpty())
    }

    @Test
    fun aQueryStringIsNotPartOfTheToken() {
        assertEquals("abc", parse("HEAD /abc?x=1 HTTP/1.1\r\n\r\n")?.token)
    }

    @Test
    fun methodsThatAreNotReadsAreRefused() {
        assertEquals(null, parse("POST /abc HTTP/1.1\r\n\r\n"))
        assertEquals(null, parse("garbage\r\n\r\n"))
    }
}

class ReadHeadTest {
    @Test
    fun aShortBodyIsReportedComplete() {
        val head = "#EXTM3U".encodeToByteArray().inputStream().readHead(1024)
        assertTrue(head.complete)
        assertEquals("#EXTM3U", head.bytes.decodeToString())
    }

    /** The bytes already read must still come back, because the stream cannot be rewound. */
    @Test
    fun anOverlongBodyKeepsWhatWasRead() {
        val head = ByteArray(100) { 'x'.code.toByte() }.inputStream().readHead(10)
        assertFalse(head.complete)
        assertEquals(10, head.bytes.size)
    }

    @Test
    fun aRecognisedBodyIsCappedWithoutDiscardingTheOverflowByte() {
        val stream = "456789".encodeToByteArray().inputStream()
        val body = stream.readBody("123".encodeToByteArray(), limit = 5)
        assertFalse(body.complete)
        assertEquals("123456", body.bytes.decodeToString())
        assertEquals("789", stream.readBytes().decodeToString())
    }

    @Test
    fun aRecognisedBodyUnderTheCapIsComplete() {
        val body = "456".encodeToByteArray().inputStream()
            .readBody("123".encodeToByteArray(), limit = 20)
        assertTrue(body.complete)
        assertEquals("123456", body.bytes.decodeToString())
    }
}

class ProxyNeedTest {
    @Test
    fun aUserAgentAloneDoesNotAddAProxyHop() {
        assertFalse(needsLocalStreamProxy(mapOf("User-Agent" to "Anilili/1")))
    }

    @Test
    fun aGatingHeaderDoesNeedTheProxy() {
        assertTrue(
            needsLocalStreamProxy(
                mapOf("User-Agent" to "Anilili/1", "Referer" to "https://embed.example/"),
            ),
        )
        assertTrue(needsLocalStreamProxy(mapOf("Cookie" to "session=abc")))
    }
}
