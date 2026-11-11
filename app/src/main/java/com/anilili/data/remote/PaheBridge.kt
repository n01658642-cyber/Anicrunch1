package com.anilili.data.remote

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.anilili.data.AppGraph
import com.anilili.diagnostics.DiagnosticsLog
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * The engine AnimePahe is reached through, because it cannot be reached any other way.
 *
 * Cloudflare binds `cf_clearance` on animepahe.pw to the client that solved the challenge, and
 * measurement on 2026-09-09 was unambiguous: a clearance earned by this app's WebView is refused
 * for OkHttp, for Node, and under a rewritten desktop agent — five combinations, all 403 — while a
 * desktop browser's own clearance replays fine. So the requests have to be issued by the engine
 * that holds the clearance rather than by a client borrowing its cookie.
 *
 * That engine is a plain [WebView], deliberately. It was chosen when the pipe still ran on
 * GeckoView, which miruro.to accepted and which needed API 26, so a WebView was the only way to
 * reach this zone on an older box. The pipe runs on OkHttp now and has no engine at all, but the
 * choice stands on its own: this zone binds its clearance to the solver, and a WebView is the only
 * engine the app still has.
 *
 * Everything the app already knows how to do stays where it is: [AllAnimeJsUnpacker] expands the
 * embed and [com.anilili.playback.KwikPlaylistDataSource] plays what comes out. This only supplies
 * the two things a WebView can do and OkHttp cannot — a same-origin [fetchText] and, for the embed,
 * a [document] read that requires navigating because CORS refuses reading kwik.cx from here.
 */
internal object PaheBridge {
    private const val TAG = "PaheBridge"
    const val ORIGIN = "https://animepahe.pw"

    private val demand = ResolverDemand("animepahe")

    /** Whether the hidden resolver needs to be mounted. Collected by the app's WebView host. */
    val resolverRequired = demand.required

    private val mutableEverUsed = MutableStateFlow(false)

    /**
     * Whether this resolver has been built at least once in this process.
     *
     * Only interesting on the boxes where Chromium cannot be restarted. This mounts on demand and
     * lets go afterwards, which is right nearly everywhere — but there, *every* mount after a quiet
     * spell is the rebuild-with-no-WebView-alive that killed the process 6 times out of 6 in
     * [[chromium-restart-fatal-old-webview]]. Those devices keep the view instead: a 1dp idle tab
     * costs less than the crash it avoids, and it is the same trade the pipe already makes.
     */
    val everUsed: StateFlow<Boolean> = mutableEverUsed.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val counter = AtomicLong(0)

    /**
     * Outlives whoever asked, so an unfinished Cloudflare challenge is never abandoned.
     *
     * The catalogue cancels a provider that overruns its budget, and cancelling the coroutine that
     * is clearing the challenge tears down the WebView with it. See [clearance].
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settleLock = Mutex()

    @Volatile
    private var settling: Deferred<Boolean>? = null

    @Volatile
    private var refusedUntilMs = 0L

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var attached: CompletableDeferred<Unit> = CompletableDeferred()

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(wv: WebView) {
        DiagnosticsLog.event("$TAG.attach")
        webView = wv
        mutableEverUsed.value = true
        // Without a client, page-initiated redirects are handed to the external browser.
        // The resolver then waits on the page it left behind until catalogue discovery times out.
        wv.webViewClient = object : WebViewClient() {
            /**
             * Survives a reclaimed renderer, which a bare [WebViewClient] does not.
             *
             * The default returns false, and false tells the framework to take the app process down
             * with the renderer — no Java exception, no crash dialog, nothing in a diagnostics
             * bundle, just a launcher where the app used to be. This is the hidden resolver, so its
             * renderer is the lowest-priority one the app owns and the first a starved box reclaims;
             * a Fire TV Stick reporting this symptom sat at TRIM_MEMORY_RUNNING_CRITICAL through
             * 141 trim callbacks in 48 hours. Every other engine here already returns true —
             * [PipeBridge], [FlixcloudBridge], [HanimeBridge] and the embed player — and this one
             * was simply missed.
             */
            @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                DiagnosticsLog.event(
                    "$TAG render process gone didCrash=${detail?.didCrash()} " +
                        "priority=${detail?.rendererPriorityAtExit()}",
                )
                com.anilili.diagnostics.ResolverDiagnostics.rendererGone("animepahe", detail?.didCrash())
                view?.let(::detach)
                return true
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The embed is a video player page. Flixcloud's resolver has to let it play to get a
            // URL out; this one only ever reads the document, so playback stays behind a gesture
            // that never comes. A hidden resolver that starts decoding takes the TV's hardware
            // decoder away from the episode actually on screen — OMX 0x80001013, see
            // [[embed-webview-lessons]] — and there is no reason to risk it for a document read.
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            // Left exactly as the engine reports it. The clearance is bound to this agent, and the
            // app has already proved what rewriting it costs: a cookie nothing can spend.
        }
        if (!attached.isCompleted) attached.complete(Unit)
    }

    fun detach(wv: WebView) {
        DiagnosticsLog.event("$TAG.detach")
        if (webView === wv) {
            webView = null
            attached = CompletableDeferred()
        }
    }

    /**
     * Fetches [path] from animepahe.pw with the engine's own cookies and fingerprint.
     *
     * Same-origin, so this is an ordinary `fetch` — the expensive part is only that it has to
     * happen in here at all.
     */
    suspend fun fetchText(path: String, timeoutMs: Long = timeout()): String? = run(timeoutMs) {
        """
        (function () {
          if (typeof window.__paheJob === 'undefined' || window.__paheJob !== '$it') {
            window.__paheJob = '$it';
            window.__paheOut = '';
            fetch(${path.jsString()}, { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
              .then(function (r) { return r.text().then(function (t) { return r.status + '~~' + t; }); })
              .then(function (v) { window.__paheOut = v; })
              .catch(function (e) { window.__paheOut = '0~~' + e; });
            return '';
          }
          return window.__paheOut || '';
        })()
        """
    }?.let { raw ->
        val status = raw.substringBefore(SEP).toIntOrNull() ?: return null
        val body = raw.substringAfter(SEP)
        if (status == 200) body else null.also {
            DiagnosticsLog.event("$TAG fetch status=$status path=${path.substringBefore('?')}")
        }
    }

    /**
     * Holds the resolver open for one whole operation.
     *
     * Without this each call acquires and drops its own lease, so the view is torn down and rebuilt
     * between the search and the play page — visible in the log as attach/detach churn, and on an
     * old box the rebuild-after-a-gap that [[chromium-restart-fatal-old-webview]] says kills the
     * process. One lease per resolve, released at the end.
     */
    suspend fun <T> session(block: suspend () -> T): T {
        val lease = demand.acquire()
        return try {
            block()
        } finally {
            lease.close()
            release()
        }
    }

    /**
     * Navigates to [url] and hands back the document it rendered.
     *
     * A navigation rather than a fetch because the embed is a different origin and CORS refuses to
     * let animepahe.pw read it — which is also why this bridge owns a navigating view with a
     * lifecycle rather than being a stateless fetcher.
     */
    suspend fun document(url: String, referer: String, timeoutMs: Long = timeout()): String? = mutex.withLock {
        val view = awaitView(timeoutMs) ?: return@withLock null
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        main.post { view.loadUrl(url, mapOf("Referer" to referer)) }
        // The host has to be checked, not just readyState. Polling starts before the navigation
        // does, and the page being left is itself "complete" — so without this the first poll wins
        // and hands back the previous document, which reads downstream as an embed that carried no
        // playlist. Measured: exactly that failure, on a chain that was otherwise correct.
        poll(timeoutMs) {
            "(document.readyState === 'complete' && location.host === '$host') " +
                "? document.documentElement.outerHTML : ''"
        }
    }

    /** Blanks only an idle engine, including when the last owner was a background clearance. */
    private fun release() {
        // A catalogue timeout closes its session while settle() still owns the engine. Blanking
        // here unconditionally aborts that challenge: the September 9 release report then waited
        // the remaining 30s on a null origin and entered a five-minute cooldown. Serialize with
        // navigation and check demand on the main thread so queued cleanup cannot blank new work.
        scope.launch {
            mutex.withLock {
                withContext(Dispatchers.Main) {
                    if (!demand.required.value) webView?.loadUrl("about:blank")
                }
            }
        }
    }

    private suspend fun run(timeoutMs: Long, script: (String) -> String): String? {
        if (!clearance(timeoutMs)) return null
        return mutex.withLock {
            val lease = demand.acquire()
            try {
                val view = awaitView(timeoutMs) ?: return@withLock null
                // Cheap now: [clearance] left the engine holding cf_clearance, so this is either
                // already on the origin or one uncontested navigation away from it.
                if (!onOrigin(view, timeoutMs)) return@withLock null
                val job = counter.incrementAndGet().toString()
                poll(timeoutMs) { script(job) }
            } finally {
                lease.close()
            }
        }
    }

    /**
     * Clears Cloudflare once, on a budget of its own, and refuses to keep hammering when it fails.
     *
     * The zone answers a cold client with an interstitial that takes several seconds of scripted
     * work to clear — measured 5-6s in a browser on a healthy network. That does not fit inside what
     * the caller has: the catalogue allows a provider 6s on its fast pass, and out of that same
     * allowance the search, up to three confirming page fetches and a release page still have to
     * come. So the challenge was being started and abandoned on every episode load, twice — nine
     * engines built for seven timeouts and no answers in the 2026-09-09 report — and a run of
     * abandoned challenges is itself what makes the zone stop clearing them.
     *
     * Hence the split. The settle runs in [scope] holding its own lease, so cancelling the caller
     * leaves it running rather than destroying the engine mid-challenge; whoever asked waits only as
     * long as they have. A caller that gives up early still pays off: the clearance lands in the
     * shared [CookieManager] and the next lookup finds the origin already open. One at a time, and
     * after a failure nothing tries again until [COOLDOWN_MS] has passed.
     */
    private suspend fun clearance(callerBudgetMs: Long): Boolean {
        val started = settleLock.withLock {
            settling?.takeIf { it.isActive } ?: run {
                val waitMs = refusedUntilMs - SystemClock.elapsedRealtime()
                if (waitMs > 0) {
                    DiagnosticsLog.event("$TAG clearance cooling for ${waitMs}ms")
                    return@withLock null
                }
                scope.async { settle() }.also { settling = it }
            }
        } ?: return false
        // Awaiting from here cannot cancel it: `started` belongs to [scope], not to this coroutine.
        return withTimeoutOrNull(callerBudgetMs) { started.await() } ?: false
    }

    private suspend fun settle(): Boolean = session {
        // Takes the same lock the requests do: there is one engine, and a settle navigating it to
        // the origin while [document] is reading a kwik embed would lose both. Safe to take in this
        // order because callers await this *before* they take the lock, never while holding it.
        mutex.withLock {
            val view = awaitView(CLEARANCE_MS)
            if (view == null) {
                DiagnosticsLog.event("$TAG clearance found no engine")
                return@withLock false
            }
            if (onOrigin(view, CLEARANCE_MS)) return@withLock true
            refusedUntilMs = SystemClock.elapsedRealtime() + COOLDOWN_MS
            false
        }
    }

    /** Puts the view on animepahe.pw and waits out whatever Cloudflare wants first. */
    private suspend fun onOrigin(view: WebView, timeoutMs: Long): Boolean {
        val here = withTimeoutOrNull(1_000L) { evaluate(view) { "location.origin" } }
        if (here == ORIGIN) return true
        main.post { view.loadUrl("$ORIGIN/") }
        // A managed challenge clears itself during load, so the test is whether the real document
        // has arrived, not whether a navigation finished.
        val settled = poll(timeoutMs) {
            "(location.origin === '$ORIGIN' && document.readyState === 'complete' && " +
                "!/Just a moment/.test(document.title)) ? location.origin : ''"
        }
        if (settled != ORIGIN) describeFailedOrigin(view)
        return settled == ORIGIN
    }

    /**
     * Says what the engine was actually looking at when it could not reach the origin.
     *
     * Worth its own read because the old one-line version could never fire: it was reached only
     * after the poll's own timeout, and the poll was given the same budget as the caller, so the
     * caller's cancellation always won first. Three rounds of diagnostics reported nothing at all
     * about this step for exactly that reason.
     */
    private suspend fun describeFailedOrigin(view: WebView) {
        val seen = withTimeoutOrNull(1_000L) {
            evaluate(view) {
                "JSON.stringify({ o: location.origin, t: document.title.slice(0, 60), " +
                    "c: !!document.querySelector('script[src*=\"challenge-platform\"]') })"
            }
        }
        DiagnosticsLog.event("$TAG origin not reached seen=${seen ?: "none"}")
    }

    private suspend fun awaitView(timeoutMs: Long): WebView? {
        webView?.let { return it }
        withTimeoutOrNull(timeoutMs) { attached.await() }
        return webView
    }

    /** Evaluates [script] until it returns something non-blank, or the budget runs out. */
    private suspend fun poll(timeoutMs: Long, script: () -> String): String? =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val view = webView ?: return@withTimeoutOrNull null
                val value = evaluate(view, script)
                if (!value.isNullOrBlank()) return@withTimeoutOrNull value
                delay(POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE") null
        }

    private suspend fun evaluate(view: WebView, script: () -> String): String? {
        val deferred = CompletableDeferred<String?>()
        // evaluateJavascript serialises its expression immediately, so the scripts above park their
        // result on `window` and are polled: an async function would hand back a pending Promise,
        // which arrives here as "{}" and reads exactly like a call that found nothing.
        main.post { view.evaluateJavascript(script().trimIndent()) { deferred.complete(it) } }
        return deferred.await()?.unquote()
    }

    private fun timeout(): Long = if (AppGraph.isTv) TV_TIMEOUT_MS else PHONE_TIMEOUT_MS

    /**
     * Undoes the JSON encoding `evaluateJavascript` applies before handing a value back.
     *
     * Chromium escapes the angle brackets and ampersands too, so a document read this way is not
     * HTML any parser would accept until they are put back.
     */
    private fun String.unquote(): String = removeSurrounding("\"")
        .replace("\\u003C", "<")
        .replace("\\u003E", ">")
        .replace("\\u0026", "&")
        .replace("\\n", "\n")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .let { if (it == "null") "" else it }

    /** A JS string literal for [this], so a title with a quote in it cannot end the expression. */
    private fun String.jsString(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\""

    /** Separates the HTTP status from the body in one polled string. */
    private const val SEP = "~~"
    private const val POLL_MS = 250L
    private const val PHONE_TIMEOUT_MS = 15_000L

    /**
     * What clearing the challenge gets, as distinct from what one request gets.
     *
     * Deliberately larger than any caller's budget — the point of [clearance] is that this is not
     * spent out of the catalogue's allowance. A TV keeps it shorter for the same reason its request
     * budget is shorter: a second engine held open beside a playing episode costs more there.
     */
    private val CLEARANCE_MS: Long get() = if (AppGraph.isTv) 20_000L else 45_000L

    /**
     * How long AnimePahe is left alone after a failed clearance.
     *
     * Not politeness. Repeatedly starting and abandoning challenges is what escalates the zone from
     * one that clears in seconds to one that never clears at all, and the app was doing it twice per
     * episode load with nothing between attempts.
     */
    private const val COOLDOWN_MS = 5 * 60 * 1000L

    /**
     * TV gives up sooner. A resolver that is going nowhere still holds a second engine open beside
     * whatever is playing, and on a constrained box that is worse than simply not offering the
     * server — the same reasoning [FlixcloudBridge] uses for its own shorter TV budget.
     */
    private const val TV_TIMEOUT_MS = 8_000L
}
