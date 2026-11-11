package com.anilili.data.remote

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.anilili.data.AppGraph
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/**
 * Hidden hanime resolver.
 *
 * Two jobs, both of which need the site's own WebAssembly and so cannot be reimplemented here:
 *
 *  - **Credentials.** `vendor.js` computes a rotating signature in WASM and parks it on
 *    `window.ssignature` / `window.stime`. Those two headers are all the catalogue download needs,
 *    so that request is a plain OkHttp GET once we have read them.
 *  - **Streams.** The v11 player does not simply sign a request: it encrypts the handshake payload
 *    and decrypts the reply's `x-token`, both in WASM, through module-scoped functions that
 *    injected JS cannot reach. So rather than imitate the flow we let the real page perform it and
 *    read the result out of it.
 *
 * That last part used to watch the network for a `.m3u8`, which the site's move from Nuxt to Astro
 * broke twice over. Its sources now look like `/hls/2465/bscd0xnf…` — a relative path with no
 * `.m3u8` anywhere in it — and the page runs a preroll ad before hls.js fetches anything, so the
 * manifest request never happens at all while [QUIET_VIDEOS_JS] is holding the video still. Both
 * problems go away by taking the sources from the handshake's decrypted payload instead of from a
 * request: [HOOK_SOURCES_JS] keeps whatever `JSON.parse` is handed that carries a `sources` array,
 * which lands before any video would have played and yields the whole quality ladder rather than
 * the one URL that happened to be fetched first.
 */
@SuppressLint("StaticFieldLeak")
object HanimeBridge {
    private const val ORIGIN = "https://hanime.tv"
    private const val TAG = "HanimeBridge"
    private val demand = ResolverDemand("hanime")
    val resolverRequired = demand.required

    /** `stime` is a timestamp and the signature expires with it; re-read well inside its life. */
    private const val CREDENTIAL_TTL_MS = 5 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private val streamMutex = Mutex()
    private val credentialMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var webView: WebView? = null
    @Volatile private var cached: HanimeCredentials? = null

    /** Set when the renderer dies mid-resolve, so the poll below gives up rather than waiting out. */
    @Volatile private var rendererGone = false

    /** True while a resolve owns the page, so a concurrent credential read will not park it. */
    @Volatile private var resolving = false

    data class HanimeCredentials(val signature: String, val time: String, val readAtMs: Long)

    /**
     * One rung of the ladder the handshake hands back.
     *
     * `src` is relative to [ORIGIN] and opaque. The 1080p rung is always present and always empty,
     * carrying `kind: "promotion"` — it is the premium upsell rather than a stream — so only
     * `normal` rungs are worth anything here.
     */
    @Serializable
    data class HanimeSource(
        val src: String = "",
        val label: String = "",
        val kind: String = "",
        val width: Int? = null,
        val height: Int? = null,
    ) {
        val isPlayable: Boolean get() = kind == "normal" && src.isNotBlank()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(wv: WebView) {
        DiagnosticsLog.event("$TAG.attach")
        webView = wv
        com.anilili.diagnostics.ResolverDiagnostics.viewChanged("hanime", true)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            userAgentString = userAgentString.replace("; wv", "")
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean {
                DiagnosticsLog.event("$TAG blocked popup")
                return false
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url ?: return true
                if (!request.isForMainFrame) return false
                val host = target.host.orEmpty().lowercase()
                val allowed = target.scheme == "https" && (host == "hanime.tv" || host.endsWith(".hanime.tv"))
                if (!allowed) DiagnosticsLog.event("$TAG blocked nav host=$host")
                return !allowed
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Stop the picture the moment the page has one. A muted video still decodes, and
                // on a TV stick that costs the episode being watched its hardware decoder.
                view?.evaluateJavascript(QUIET_VIDEOS_JS, null)
            }

            @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                DiagnosticsLog.event("$TAG render process gone didCrash=${detail?.didCrash()}")
                com.anilili.diagnostics.ResolverDiagnostics.rendererGone("hanime", detail?.didCrash())
                rendererGone = true
                if (webView === view) {
                    webView = null
                    com.anilili.diagnostics.ResolverDiagnostics.viewChanged("hanime", false)
                }
                return true
            }
        }
    }

    fun detach(wv: WebView) {
        if (webView !== wv) return
        DiagnosticsLog.event("$TAG.detach")
        webView = null
        com.anilili.diagnostics.ResolverDiagnostics.viewChanged("hanime", false)
    }

    /**
     * Signature headers for the catalogue download, re-read once the cached pair ages out. Loading
     * the home page is enough — `vendor.js` runs and sets the globals without any video involved.
     */
    suspend fun credentials(timeoutMs: Long = 20_000): HanimeCredentials? = credentialMutex.withLock {
        cached?.takeIf { SystemClock.elapsedRealtime() - it.readAtMs < CREDENTIAL_TTL_MS }?.let { return it }
        val lease = demand.acquire()
        try {
            val wv = awaitWebView(timeoutMs) ?: return null
            loadOnMain(wv, ORIGIN)
            // The WASM publishes the pair a moment after the page settles, so poll rather than guess.
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                val pair = withTimeoutOrNull(2_000) { readCredentials(wv) }
                if (pair != null) {
                    DiagnosticsLog.event("$TAG credentials read time=${pair.time}")
                    cached = pair
                    // A resolve owns the page while it runs; parking it here would cancel one.
                    main.post { if (!resolving) wv.loadUrl("about:blank") }
                    return pair
                }
                kotlinx.coroutines.delay(400)
            }
            DiagnosticsLog.event("$TAG credentials timeout")
            return null
        } finally {
            lease.close()
        }
    }

    /**
     * The quality ladder for one video, by letting hanime's own player negotiate it. Serialised
     * because a single hidden WebView cannot run two page loads at once.
     *
     * Returns only the rungs that are actually streams — see [HanimeSource.isPlayable] — sharpest
     * first, with each `src` still relative to [ORIGIN] for the caller to resolve.
     */
    suspend fun resolveStream(slug: String, timeoutMs: Long = 25_000): List<HanimeSource> {
        val lease = demand.acquire()
        try {
            return streamMutex.withLock {
                val wv = awaitWebView(timeoutMs) ?: return@withLock emptyList()
                val target = "$ORIGIN/videos/hentai/$slug"
                DiagnosticsLog.event("$TAG resolve slug=$slug")
                rendererGone = false
                resolving = true
                try {
                    loadOnMain(wv, target)
                    val found = awaitSources(wv, timeoutMs)
                    if (found.isEmpty()) DiagnosticsLog.event("$TAG resolve found nothing slug=$slug")
                    found
                } finally {
                    resolving = false
                    // Always park the page — a live hanime tab left open keeps timers and media alive.
                    main.post { wv.loadUrl("about:blank") }
                }
            }
        } finally {
            lease.close()
        }
    }

    /**
     * Poll the hook until the handshake has been parsed, or the deadline passes.
     *
     * Polling rather than a callback because the hook cannot signal back into Kotlin: adding a
     * `@JavascriptInterface` would expose it to the whole of hanime.tv and every script it loads,
     * for a saving of a few hundred milliseconds.
     */
    private suspend fun awaitSources(wv: WebView, timeoutMs: Long): List<HanimeSource> {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var polls = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            if (rendererGone) return emptyList()
            // Installed from here rather than from onPageStarted, which is called as the frame
            // begins loading: script evaluated then can still run against the document being
            // navigated away from, and is thrown out with it. Re-running every poll costs nothing
            // — the hook is idempotent and its flag goes with the old document — and puts it in
            // place within a poll of the new one, still seconds before hydration reaches the
            // handshake it is waiting for.
            withTimeoutOrNull(2_000) { evaluate(wv, HOOK_SOURCES_JS) }
            val raw = withTimeoutOrNull(2_000) { readHookedSources(wv) }
            if (!raw.isNullOrBlank()) {
                val parsed = runCatching {
                    json.decodeFromString(ListSerializer(HanimeSource.serializer()), raw)
                }.onFailure { DiagnosticsLog.throwable("$TAG sources did not decode", it) }
                    .getOrDefault(emptyList())
                val playable = parsed.filter(HanimeSource::isPlayable)
                    .sortedByDescending { it.height ?: 0 }
                DiagnosticsLog.event("$TAG sources rungs=${parsed.size} playable=${playable.size}")
                if (playable.isNotEmpty()) return playable
            }
            // A resolve that finds nothing is otherwise indistinguishable from one that was never
            // given a page, which is exactly the confusion that let the old sniffer look healthy
            // while it could not possibly match. Report what the page is doing, occasionally.
            if (polls % 20 == 5) {
                val state = withTimeoutOrNull(2_000) { evaluate(wv, PAGE_STATE_JS) }
                DiagnosticsLog.event("$TAG waiting $state")
            }
            polls++
            kotlinx.coroutines.delay(300)
        }
        return emptyList()
    }

    /** One `evaluateJavascript` round trip, as its raw JSON-encoded result. */
    private suspend fun evaluate(wv: WebView, script: String): String? {
        val deferred = CompletableDeferred<String?>()
        main.post { wv.evaluateJavascript(script) { raw -> deferred.complete(raw) } }
        return deferred.await()
    }

    /**
     * The captured `sources` array as JSON text, or null until the handshake has landed.
     *
     * `evaluateJavascript` hands back a *JSON-encoded* result, so a JS string arrives quoted and
     * escaped. Decoding it as JSON rather than stripping quotes by hand is what keeps the nested
     * payload intact — the sources themselves are full of `"` and `\/`.
     */
    private suspend fun readHookedSources(wv: WebView): String? {
        val deferred = CompletableDeferred<String?>()
        main.post {
            wv.evaluateJavascript(READ_SOURCES_JS) { raw ->
                deferred.complete(
                    raw?.takeIf { it.isNotBlank() && it != "null" }
                        ?.let { encoded ->
                            runCatching { json.parseToJsonElement(encoded).jsonPrimitive.content }
                                .getOrNull()
                        }
                        ?.takeIf(String::isNotBlank),
                )
            }
        }
        return deferred.await()
    }

    private suspend fun readCredentials(wv: WebView): HanimeCredentials? {
        val deferred = CompletableDeferred<HanimeCredentials?>()
        main.post {
            wv.evaluateJavascript(READ_CREDENTIALS_JS) { raw ->
                val cleaned = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"")
                val parts = cleaned?.split('|')?.takeIf { it.size == 2 && it.all(String::isNotBlank) }
                deferred.complete(
                    parts?.let { HanimeCredentials(it[0], it[1], SystemClock.elapsedRealtime()) },
                )
            }
        }
        return deferred.await()
    }

    private suspend fun awaitWebView(timeoutMs: Long): WebView? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            webView?.let { return it }
            kotlinx.coroutines.delay(100)
        }
        DiagnosticsLog.event("$TAG no WebView attached; is adult content enabled?")
        return null
    }

    private fun loadOnMain(wv: WebView, url: String) = main.post {
        wv.stopLoading()
        wv.loadUrl(url)
    }

    /**
     * Keep whatever `JSON.parse` is handed that looks like the player's decrypted handshake.
     *
     * The payload is only ever JSON text on its way through this one function: it arrives as an
     * encrypted `x-token`, is decrypted in WASM, parsed, and then lives in a module-scoped Vue ref
     * that nothing outside the component can reach. Wrapping the parse is the one point where it
     * is both decrypted and still reachable.
     */
    private val HOOK_SOURCES_JS = """
        (function() {
          if (window.__aniliHanimeHook) return;
          window.__aniliHanimeHook = true;
          window.__aniliHanimeSources = null;
          var original = JSON.parse;
          JSON.parse = function(text, reviver) {
            var value = original.call(JSON, text, reviver);
            try {
              if (!window.__aniliHanimeSources && value && typeof value === 'object' &&
                  Object.prototype.toString.call(value.sources) === '[object Array]') {
                window.__aniliHanimeSources = JSON.stringify(value.sources);
              }
            } catch (e) {}
            return value;
          };
        })();
    """.trimIndent()

    private val READ_SOURCES_JS = "window.__aniliHanimeSources || null;"

    /** Enough to tell "the page never loaded" from "it loaded and the handshake never ran". */
    private val PAGE_STATE_JS = """
        (function() {
          try {
            return [
              'ready=' + document.readyState,
              'hook=' + (window.__aniliHanimeHook ? 1 : 0),
              'videos=' + document.querySelectorAll('video').length,
              'url=' + location.pathname,
              'len=' + document.body.innerHTML.length
            ].join(' ');
          } catch (e) { return 'state-unreadable'; }
        })();
    """.trimIndent()

    /** Read the pair the WASM parks on `window`; empty until it has run. */
    private val READ_CREDENTIALS_JS = """
        (function() {
          try {
            var s = window.ssignature, t = window.stime;
            return (s && t) ? (String(s) + '|' + String(t)) : '';
          } catch (e) { return ''; }
        })();
    """.trimIndent()

    /**
     * Nothing here needs a decoded frame — the URL is taken off the network — so keep the page
     * running and the picture stopped. Muting alone does not stop a decoder.
     */
    private val QUIET_VIDEOS_JS = """
        (function() {
          if (window.__aniliHanimeQuiet) return;
          window.__aniliHanimeQuiet = true;
          function quiet() {
            try {
              var videos = document.querySelectorAll('video');
              for (var i = 0; i < videos.length; i++) {
                var video = videos[i];
                video.muted = true;
                video.autoplay = false;
                if (!video.paused) video.pause();
              }
            } catch (e) {}
          }
          quiet();
          setInterval(quiet, 100);
        })();
    """.trimIndent()
}
