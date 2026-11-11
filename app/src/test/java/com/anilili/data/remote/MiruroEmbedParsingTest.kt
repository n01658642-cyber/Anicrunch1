package com.anilili.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Miruro's `twin` is two unrelated hosts behind one provider name, and this pins the shape of both.
 *
 * The live capability map publishes `twin: {sub: true, ssub: true, variantOrder: ["sub","ssub"],
 * parent: "bonk", player: "iframe"}`, and each variant is served by different software:
 * `twinembedh` by a VidHide/EarnVids page (`otakuvid.online`) whose player config is Dean Edwards
 * packed, `twinembeds` by a plain "VibePlayer" page (`bibiemb.xyz`) that hides nothing. Fixtures
 * below are cut from the live pages, captured 2026-09-09.
 */
class MiruroEmbedParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    // A genuinely packed block carrying the live page's two links — produced by a real P.A.C.K.E.R.
    // and verified to round-trip before being pinned here, rather than hand-written. The unpacker
    // therefore runs for real, which matters because it is the part a repack breaks.
    private val vidHidePage = """
        <script type='text/javascript'>
        eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('1 2={"3":"5://6.7.8/9/3/a/b/c,l,n,h,.d/g.j","k":"5://6.m-o.p/k/a/b/c,l,n,h,.d/g.q?t=r&s=u&e=v&f=w&x=9&i=0.4&y=z&10=9&11=9&12=13"};',36,40,'|var|links|hls3||https|ESrZaEKj9iFIuE8|sunridgeadventurestudio|shop|0g7xgsanrz08|01|08591|orbfg31qtwiv_|urlset|||master|||txt|hls2||dramiyos||cdn|com|m3u8|FzUHOGc7XL_0oD9UJ_pyEzSm1dzk7n4kVBM7G9i6T8I|||1788977141|129600|42955456|srv|sp|500|p1|p2|asn|29170'.split('|')))
        </script>
    """.trimIndent()

    private val hls2 =
        "https://ESrZaEKj9iFIuE8.dramiyos-cdn.com/hls2/01/08591/" +
            "orbfg31qtwiv_,l,n,h,.urlset/master.m3u8"
    private val hls3 =
        "https://ESrZaEKj9iFIuE8.sunridgeadventurestudio.shop/0g7xgsanrz08/hls3/01/08591/" +
            "orbfg31qtwiv_,l,n,h,.urlset/master.txt"

    private val vibePage = """
        <div id="player"></div>
        <script src="/public/jw46/jwplayer.js"></script>
        <script>
        const src = "https://morning-credit-3bcc.vibevibe.workers.dev/ag282a6d061e36ab12674c20bfe5fbb3884h/master.m3u8";
        const poster = "";
        const subtitle = "https://cdn.anizara.store/subtitles/10/9c/109c95cbb60d5d2e2aec19f4b6eda92f_136739_sub_eng-0.vtt";
        </script>
    """.trimIndent()

    private val vibeUrl =
        "https://bibiemb.xyz/ag282a6d061e36ab12674c20bfe5fbb3884h" +
            "?sub=https://cdn.anizara.store/subtitles/10/9c/109c95cbb60d5d2e2aec19f4b6eda92f_136739_sub_eng-0.vtt"

    @Test
    fun `a packed VidHide page yields both of its declared links`() {
        val links = vidHideLinks(vidHidePage, json)
        assertEquals(setOf("hls3", "hls2"), links.keys)
        assertEquals(hls3, links["hls3"])
        assertEquals(hls2, links["hls2"]?.substringBefore('?'))
    }

    /**
     * The page's own order cannot be followed. JWPlayer takes `hls4||hls3||hls2`, and on the live
     * page `hls3` was a `master.txt` that answered 404 — playback only reached the working `hls2`
     * through the page's `error` handler. Ranking by signature and extension picks the live one
     * first, so a play costs one request rather than a failure and a retry.
     */
    @Test
    fun `the signed manifest wins over the unsigned alternate the page prefers`() {
        // hls3 is declared first and is what the page's own `hls4||hls3||hls2` would take.
        assertEquals(hls3, vidHideLinks(vidHidePage, json).values.first())
        assertEquals(hls2, vidHideStreamUrl(vidHidePage, json)?.substringBefore('?'))
    }

    @Test
    fun `a page with no packed player is not a page this can read`() {
        assertNull(vidHideStreamUrl("<html><body>Please disable AdBlock</body></html>", json))
        assertEquals(emptyMap<String, String>(), vidHideLinks("<html></html>", json))
    }

    @Test
    fun `a VibePlayer page yields its worker stream and not its script tags`() {
        assertEquals(
            "https://morning-credit-3bcc.vibevibe.workers.dev/" +
                "ag282a6d061e36ab12674c20bfe5fbb3884h/master.m3u8",
            vibePlayerStreamUrl(vibePage),
        )
    }

    /**
     * The subtitle is in the iframe URL, not the page: VibePlayer only echoes its own `?sub=` back
     * into a JW `tracks` entry. Verified by fetching the same embed without the parameter, which
     * returns `const subtitle = ""` — so reading the URL is both cheaper and more reliable.
     */
    @Test
    fun `the caption track is read from the iframe URL rather than the page`() {
        val expected =
            "https://cdn.anizara.store/subtitles/10/9c/109c95cbb60d5d2e2aec19f4b6eda92f_136739_sub_eng-0.vtt"
        assertEquals(expected, vibePlayerSubtitleUrl(vibeUrl, html = null))
        // And still found when only the page has it.
        assertEquals(expected, vibePlayerSubtitleUrl("https://bibiemb.xyz/ag282", vibePage))
        assertNull(vibePlayerSubtitleUrl("https://bibiemb.xyz/ag282", html = null))
    }

    /** The value arrives unencoded, so decoding is conditional; a bare `+` must survive. */
    @Test
    fun `only an encoded subtitle parameter is decoded`() {
        assertEquals(
            "https://cdn.example/a b/x.vtt",
            vibePlayerSubtitleUrl("https://e.xyz/id?sub=https%3A%2F%2Fcdn.example%2Fa%20b%2Fx.vtt"),
        )
        assertEquals(
            "https://cdn.example/a+b/x.vtt",
            vibePlayerSubtitleUrl("https://e.xyz/id?sub=https://cdn.example/a+b/x.vtt"),
        )
    }

    @Test
    fun `a subtitle names its own language, and nothing is invented when it does not`() {
        assertEquals(
            "eng",
            subtitleLanguageFromFilename(
                "https://cdn.anizara.store/subtitles/10/9c/109c_136739_sub_eng-0.vtt",
            ),
        )
        assertEquals("spa", subtitleLanguageFromFilename("https://cdn/x_1_sub_spa-1.vtt"))
        assertNull(subtitleLanguageFromFilename("https://cdn/subtitles/english.vtt"))
    }

    /**
     * Both players are tried against whatever page came back, rather than the host being routed on.
     * `twin` alone spans two unrelated domains and either could move; what the page *is* survives
     * that, a hostname table does not.
     */
    @Test
    fun `either player is recognised without being told which host served it`() {
        val vidHide = miruroEmbedStream("https://otakuvid.online/embed/orbfg31qtwiv", vidHidePage, json)
        assertEquals(hls2, vidHide?.url?.substringBefore('?'))
        assertNull(vidHide?.subtitleUrl)

        val vibe = miruroEmbedStream(vibeUrl, vibePage, json)
        assertEquals(
            "https://morning-credit-3bcc.vibevibe.workers.dev/" +
                "ag282a6d061e36ab12674c20bfe5fbb3884h/master.m3u8",
            vibe?.url,
        )
        assertEquals("eng", vibe?.subtitleLanguage)
    }

    /**
     * The soft-sub worker's live failure, which is upstream and permanent-looking rather than
     * anything a request can shape.
     *
     * `b2_authorize_account` is the worker logging in to Backblaze with its *own* credentials, so
     * our request never reaches storage: a bogus embed id returns the identical error, and a full
     * browser User-Agent with `Referer`/`Origin` of bibiemb.xyz, of miruro.bz and of the exact
     * watch page all returned byte-identical bodies. The worker also answers
     * `Access-Control-Allow-Origin: *`, i.e. it is built to be fetched by anything. There is
     * nothing to mimic, so this must read as "unresolvable" and fall back rather than retry.
     */
    @Test
    fun `the worker's Backblaze failure is not mistaken for a stream`() {
        val failure = """
            Worker Exception: b2_authorize_account failed: 403 {
              "code": "account_trouble",
              "message": "Account trouble. Please log into your b2 account at www.backblaze.com.",
              "status": 403
            }
        """.trimIndent()
        assertNull(miruroEmbedStream(vibeUrl, failure, json))
        assertNull(vibePlayerStreamUrl(failure))
    }
}
