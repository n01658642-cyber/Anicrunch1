package com.anilili.data.remote

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.anilili.data.AppGraph
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** The Miruro mirrors, in the order they are tried. */
internal val pipeOrigins: List<String> = listOf(
    "https://www.miruro.to",
    "https://www.miruro.tv",
    "https://www.miruro.bz",
    "https://www.miruro.ru",
)

/** Raw pipe response as returned by the in-page fetch, before deobfuscation. */
data class RawPipeResponse(val ok: Boolean, val status: Int, val obf: String?, val body: String?, val error: String?)

/**
 * Tries the most recently healthy origin first, then wraps through every remaining mirror once.
 * An unknown persisted value falls back to the configured order.
 */
internal fun orderedPipeOrigins(origins: List<String>, lastWorkingOrigin: String): List<String> {
    require(origins.isNotEmpty()) { "origins must not be empty" }
    val startIndex = origins.indexOf(lastWorkingOrigin).takeIf { it >= 0 } ?: 0
    return List(origins.size) { offset -> origins[(startIndex + offset) % origins.size] }
}

/** Full cold-start budget: every mirror gets its watchdog window, then the winning page settles. */
internal fun pipePageReadyTimeoutMs(
    originCount: Int,
    mirrorTimeoutMs: Long,
    pageSettleMs: Long,
    schedulerGraceMs: Long,
    rememberedMirrorExtraMs: Long = 0L,
): Long {
    require(originCount > 0) { "originCount must be positive" }
    require(
        mirrorTimeoutMs >= 0 && pageSettleMs >= 0 && schedulerGraceMs >= 0 &&
            rememberedMirrorExtraMs >= 0,
    ) {
        "pipe timeout components must be non-negative"
    }
    return originCount.toLong() * mirrorTimeoutMs + rememberedMirrorExtraMs +
        pageSettleMs + schedulerGraceMs
}

/** Outer provider budget must not cancel the bridge while its bounded cold start is still active. */
internal fun pipeSourceAttemptTimeoutMs(
    pageReadyTimeoutMs: Long,
    responseTimeoutMs: Long,
    completionGraceMs: Long,
): Long = pageReadyTimeoutMs + responseTimeoutMs + completionGraceMs

/**
 * A mirror that already worked on this network gets a little longer to answer before failover.
 * This preserves the useful part of the old always-warm resolver without making every cold,
 * unknown mirror extend the full startup walk.
 */
internal fun pipeMirrorWatchdogTimeoutMs(
    origin: String,
    lastWorkingOrigin: String,
    standardTimeoutMs: Long,
    rememberedTimeoutMs: Long,
): Long = if (lastWorkingOrigin.isNotBlank() && origin == lastWorkingOrigin) {
    rememberedTimeoutMs
} else {
    standardTimeoutMs
}

/**
 * Whether the remembered mirror should survive [failedOrigin] having just failed.
 *
 * The last working origin is only ever written on a real clear, so nothing used to demote a mirror
 * that later stopped answering. Left pinned, a dead one is tried first on every attach *and* keeps
 * the longer remembered watchdog, so the walk spends its budget there before reaching a mirror that
 * still clears — and the human check parks on it rather than rolling on. Measured on a Galaxy S25:
 * 71 of 78 page loads went to a mirror that never cleared while two others cleared in under 7s.
 */
internal fun pipeRememberedOriginSurvivesFailure(
    failedOrigin: String,
    lastWorkingOrigin: String,
): Boolean = lastWorkingOrigin.isBlank() || failedOrigin != lastWorkingOrigin

/**
 * Whether a finished page is Cloudflare's interstitial rather than Miruro itself.
 *
 * The interstitial is a *successful* load — 200, `onPageFinished`, no `onReceivedError` — so
 * without this the bridge declares itself ready and every fetch afterwards comes back empty. The
 * app then reports "no Miruro sources" for a title that has plenty, which is indistinguishable
 * from the site being down.
 *
 * Matched on the title because it is the one signal available before the challenge widget has
 * rendered; [PIPE_CHALLENGE_PROBE_JS] covers the page once it has.
 */
internal fun looksLikePipeChallengeTitle(title: String?): Boolean {
    val normalized = title?.trim()?.lowercase() ?: return false
    if (normalized.isEmpty()) return false
    return CHALLENGE_TITLE_MARKERS.any(normalized::contains)
}

private val CHALLENGE_TITLE_MARKERS = listOf(
    "just a moment",
    "attention required",
    "security verification",
    "verify you are human",
    "checking your browser",
    "ddos-guard",
)

/** Cloudflare's challenge scaffolding, whether it self-solves or waits for a tap. */
internal const val PIPE_CHALLENGE_PROBE_JS = """
(function(){
  try {
    return !!document.querySelector(
      '#challenge-form,#challenge-stage,#challenge-running,#challenge-error-text,' +
      '[id^="cf-chl-widget"],.cf-turnstile,iframe[src*="challenges.cloudflare.com"]'
    );
  } catch (e) { return false; }
})()
"""

internal const val PIPE_SOURCE_RESPONSE_TIMEOUT_MS = 8_000L

/**
 * Routes pipe requests through a real (hidden) WebView. Cloudflare fingerprints the HTTP client,
 * so a plain OkHttp call gets a 403 WAF block; a same-origin `fetch()` from inside a loaded
 * miruro.to page rides the browser's TLS fingerprint + `cf_clearance` cookie and is allowed.
 *
 * The WebView is created and attached to the window by [com.anilili.ui.PipeWebView]; this
 * object owns the JS bridge and the request/response plumbing.
 */
@SuppressLint("StaticFieldLeak")
object PipeBridge {
    private val demand = ResolverDemand("pipe")
    val resolverRequired = demand.required

    private val mutableHumanCheck = MutableStateFlow<String?>(null)
    private val mutableHumanCheckPending = MutableStateFlow(false)

    /**
     * The mirror whose challenge is on screen right now, or null when nothing is being shown.
     *
     * Cloudflare's non-interactive challenges clear themselves in a few seconds and nobody has to
     * know. The interactive one cannot be cleared by any amount of retrying — and a clearance
     * earned in the user's own browser does not carry over, because the app's WebView keeps its
     * own cookie store. So the hidden tab has to be shown for a tap.
     */
    val humanCheck: StateFlow<String?> = mutableHumanCheck.asStateFlow()

    /**
     * Whether a challenge is waiting to be answered, separate from whether it is being shown.
     *
     * These were one flag to begin with, which meant the challenge took over the screen the moment
     * any resolve happened to hit it — roughly a minute into whatever the viewer was already
     * watching. Nothing about it is urgent: the other providers answer regardless, and the only
     * thing behind it is a source the viewer may not even want. So the bridge just remembers, the
     * source sheet offers it, and [showHumanCheck] is what actually puts it on screen.
     */
    val humanCheckPending: StateFlow<Boolean> = mutableHumanCheckPending.asStateFlow()

    /** Puts the pending challenge on screen. Called when the viewer asks for it, never on our own. */
    fun showHumanCheck() {
        if (!mutableHumanCheckPending.value) return
        humanCheckAttempts = 0
        humanCheckDeadlineMs = SystemClock.elapsedRealtime() + HUMAN_CHECK_BUDGET_MS
        mutableHumanCheck.value = activeOrigin
        main.post {
            main.removeCallbacks(idleRunnable)
            main.removeCallbacks(humanCheckExpiry)
            main.postDelayed(humanCheckExpiry, HUMAN_CHECK_BUDGET_MS)
            webView?.onResume()
            // Same reason as ever: a page laid out for a 1dp tab has to be re-run once the tab has
            // real size, or the card is a black rectangle.
            main.postDelayed({ webView?.reload() }, HUMAN_CHECK_RELAYOUT_MS)
        }
    }

    /**
     * Mirror domains, tried in order. ISPs block these piecemeal (user logs show .to timing out
     * on one network while others resolve), so a main-frame load failure rolls to the next
     * mirror instead of taking every pipe request down with it.
     */
    private val ORIGINS = pipeOrigins

    private val main = Handler(Looper.getMainLooper())

    /**
     * The hidden tab hosts the full miruro SPA, whose scripts/animations otherwise run for the
     * whole session — on a Fire TV stick its compositor can exhaust Chromium's tile budget and
     * leave the visible episode black while audio continues. Idle (View.onPause) the WebView
     * shortly after the last pipe request completes; each fetch resumes it first. The CF
     * session/cookies survive onPause just fine.
     */
    private const val IDLE_AFTER_MS = 2_000L
    private const val PAGE_SETTLE_MS = 2_000L

    /**
     * How long a challenged mirror gets to clear itself before the bridge stops waiting on it.
     *
     * Cloudflare's managed challenge navigates on its own within a few seconds when it is happy
     * with the client; anything still sitting on the interstitial after this either wants a human
     * or is never going to pass, and another mirror is the cheaper bet.
     */
    private const val CHALLENGE_SETTLE_MS = 12_000L

    /**
     * Times round the mirror list before the challenge is treated as needing a person.
     *
     * Two, because the first pass is what *earns* a clearance and the second is what spends it —
     * see [spendEarnedClearance]. One pass can never succeed on a mirror that challenges at all,
     * which is why every mirror used to look blocked.
     */
    private const val MAX_CHALLENGE_PASSES = 2

    /** Long enough for Compose to have applied the tab's real size before the page is re-run. */
    private const val HUMAN_CHECK_RELAYOUT_MS = 400L

    /**
     * How long the viewer gets with the challenge on screen before the bridge stops waiting.
     *
     * This used to be a count — two rounds of "answer it, get it back" and give up — which took
     * about seven seconds and was measured, wrongly, as proof that the challenge could not be
     * passed. Cloudflare re-arming twice inside seven seconds says nothing about whether a person
     * can answer it; it takes longer than that to notice the card, let alone read it. A managed
     * challenge that is going to yield to a human does so in well under two minutes, and there is
     * no cost to waiting: the tab is one the viewer deliberately opened, and every other provider
     * has been answering the whole time.
     */
    private const val HUMAN_CHECK_BUDGET_MS = 120_000L
    private const val READY_SCHEDULER_GRACE_MS = 1_000L
    private const val ATTEMPT_COMPLETION_GRACE_MS = 1_000L
    private const val FAILURE_COOLDOWN_MS = 15_000L
    private val idleRunnable = Runnable {
        if (mutableHumanCheck.value != null) return@Runnable
        webView?.onPause()
        DiagnosticsLog.event("PipeBridge webview idled")
    }

    /**
     * Fires when a mirror is still showing the interstitial. Another mirror may not be challenged
     * at all, so those are tried first; only once every one of them wants a human is the hidden
     * tab surfaced.
     */
    // Explicit type: the body re-arms the watchdog, and inference cannot chase its own tail.
    private val challengeWatchdog: Runnable = Runnable {
        if (ready.isCompleted && pageReadyState == true) return@Runnable
        if (originIndex < originAttempts.lastIndex) {
            DiagnosticsLog.event("PipeBridge challenge did not clear on $activeOrigin; next mirror")
            advanceMirror()
        } else if (challengePasses < MAX_CHALLENGE_PASSES - 1) {
            challengePasses++
            spendEarnedClearance()
        } else {
            DiagnosticsLog.event("PipeBridge every mirror wants a human check on $activeOrigin")
            // Remembered, not shown. The source sheet offers it; see [humanCheckPending].
            mutableHumanCheckPending.value = true
            // Fail the requests waiting on this bridge rather than holding them behind a dialog:
            // the other providers can still answer.
            pageReadyState = false
            unavailableUntilElapsedMs = SystemClock.elapsedRealtime() + FAILURE_COOLDOWN_MS
            if (!ready.isCompleted) ready.complete(false)
        }
    }

    private fun scheduleIdle() {
        // A paused WebView neither renders nor takes taps, so never idle one the user is looking at.
        if (mutableHumanCheck.value != null) return
        main.removeCallbacks(idleRunnable)
        main.postDelayed(idleRunnable, IDLE_AFTER_MS)
    }

    private fun scheduleIdleIfUnused() {
        main.post {
            if (pending.isEmpty()) scheduleIdle()
        }
    }

    /**
     * How long a mirror gets to finish loading before it is treated as unreachable.
     *
     * `onReceivedError` only fires once the network stack gives up, which on a blocking ISP took
     * 32 seconds per mirror in a user's log — 64 seconds of dead time before the third mirror was
     * even tried, and by then the catalog wait had long since expired and the app reported "no
     * sources" for a title with plenty. Rolling over on our own clock keeps that bounded.
     */
    private const val MIRROR_TIMEOUT_MS = 7_000L
    private const val REMEMBERED_MIRROR_TIMEOUT_MS = 12_000L
    private val PAGE_READY_TIMEOUT_MS = pipePageReadyTimeoutMs(
        originCount = ORIGINS.size,
        mirrorTimeoutMs = MIRROR_TIMEOUT_MS,
        pageSettleMs = PAGE_SETTLE_MS,
        schedulerGraceMs = READY_SCHEDULER_GRACE_MS,
        rememberedMirrorExtraMs = REMEMBERED_MIRROR_TIMEOUT_MS - MIRROR_TIMEOUT_MS,
    )

    /** Used by the repository's outer timeout so it cannot pre-empt an on-demand cold start. */
    internal fun sourceAttemptTimeoutMs(): Long = pipeSourceAttemptTimeoutMs(
        pageReadyTimeoutMs = PAGE_READY_TIMEOUT_MS,
        responseTimeoutMs = PIPE_SOURCE_RESPONSE_TIMEOUT_MS,
        completionGraceMs = ATTEMPT_COMPLETION_GRACE_MS,
    )

    @Volatile private var webView: WebView? = null
    @Volatile private var ready = CompletableDeferred<Boolean>()
    @Volatile private var originAttempts = ORIGINS
    @Volatile private var originIndex = 0
    @Volatile private var rememberedOrigin = ""
    @Volatile private var pageReadyState: Boolean? = null
    @Volatile private var unavailableUntilElapsedMs = 0L

    /** Times round the mirror list on this attach; bounded by [MAX_CHALLENGE_PASSES]. */
    @Volatile private var challengePasses = 0

    /**
     * The pipe's own cookie jar, or null on a WebView too old for [WebViewFeature.MULTI_PROFILE].
     *
     * This jar was added to make a clearance purge possible, and that purge is gone: a clearance
     * still sitting there under the interstitial turned out to be the *answer waiting to be spent*,
     * not a stale token to throw away (see [spendEarnedClearance]). Deleting it was destroying the
     * one artifact that gets a 200.
     *
     * The isolation is kept anyway, on its own merits — miruro's cookies have no business sharing a
     * jar with the viewer's AniList, MAL and Discord sessions, and keeping them apart means anything
     * this bridge ever does to its own cookies stays exact and cheap.
     */
    @Volatile private var profileCookies: CookieManager? = null

    /** The jar this bridge's cookies actually live in. */
    private fun cookieJar(): CookieManager = profileCookies ?: CookieManager.getInstance()

    /** Answers given to the challenge that Cloudflare then threw away, this session. */
    @Volatile private var humanCheckAttempts = 0

    /** When the on-screen challenge stops being worth waiting on; see [HUMAN_CHECK_BUDGET_MS]. */
    @Volatile private var humanCheckDeadlineMs = 0L

    /**
     * Ends the wait when the budget runs out with nothing having happened.
     *
     * The deadline is also checked on every re-arm, but a challenge that simply sits there without
     * re-arming would never reach that check, and a card the viewer has walked away from should not
     * hold the tab open for the rest of the session.
     */
    private val humanCheckExpiry = Runnable {
        if (mutableHumanCheck.value == null) return@Runnable
        DiagnosticsLog.event(
            "PipeBridge human check budget spent with no answer attempts=$humanCheckAttempts",
        )
        dismissHumanCheck()
    }
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val counter = AtomicLong(0)

    private val activeOrigin: String get() = originAttempts[originIndex]

    private val mirrorWatchdog = Runnable {
        DiagnosticsLog.event(
            "PipeBridge mirror timed out after ${activeMirrorTimeoutMs()}ms origin=$activeOrigin",
        )
        advanceMirror()
    }

    private fun activeMirrorTimeoutMs(): Long = pipeMirrorWatchdogTimeoutMs(
        origin = activeOrigin,
        lastWorkingOrigin = rememberedOrigin,
        standardTimeoutMs = MIRROR_TIMEOUT_MS,
        rememberedTimeoutMs = REMEMBERED_MIRROR_TIMEOUT_MS,
    )

    private fun armMirrorWatchdog() {
        main.removeCallbacks(mirrorWatchdog)
        main.postDelayed(mirrorWatchdog, activeMirrorTimeoutMs())
    }

    /**
     * Drops the remembered mirror when it is the one that just failed, so the next attach starts on
     * a mirror that might answer instead of paying for this one again. See
     * [pipeRememberedOriginSurvivesFailure]. Main thread only.
     */
    private fun forgetRememberedOriginIfFailed() {
        if (pipeRememberedOriginSurvivesFailure(activeOrigin, rememberedOrigin)) return
        DiagnosticsLog.event("PipeBridge forgetting dead remembered mirror $activeOrigin")
        rememberedOrigin = ""
        SettingsStore.setLastWorkingPipeOrigin("")
    }

    /**
     * Starts the walk over so a mirror that earned a clearance on the first pass gets the second
     * navigation that spends it. Main thread only.
     *
     * Cloudflare's managed challenge does not hand back the page it challenged. It issues
     * `cf_clearance` and expects the client to ask again. Captured on the wire from this very
     * WebView, one mirror, back to back:
     *
     * ```
     * REQ  https://www.miruro.bz/   cf_clearance SENT: false  []
     * RESP status=403  cf-mitigated=challenge
     * REQ  https://www.miruro.bz/   cf_clearance SENT: true   [cf_clearance]
     * RESP status=200  cf-mitigated=-
     * ```
     *
     * A single pass abandons every mirror exactly one navigation short of that, so a clearance the
     * app had already earned was never spent — and because the walk then parks on the last mirror,
     * it retried only that one for the rest of the session. That is what made a per-mirror problem
     * look like the whole zone refusing us.
     */
    private fun spendEarnedClearance() {
        main.removeCallbacks(mirrorWatchdog)
        main.removeCallbacks(challengeWatchdog)
        originIndex = 0
        DiagnosticsLog.event("PipeBridge second pass to spend clearance, from $activeOrigin")
        armMirrorWatchdog()
        webView?.loadUrl("$activeOrigin/")
    }

    /** Rolls to the next mirror, or gives up once they are exhausted. Main thread only. */
    private fun advanceMirror() {
        main.removeCallbacks(mirrorWatchdog)
        main.removeCallbacks(challengeWatchdog)
        if (ready.isCompleted && pageReadyState == true) return
        forgetRememberedOriginIfFailed()
        if (originIndex < originAttempts.lastIndex) {
            originIndex++
            DiagnosticsLog.event("PipeBridge trying mirror $activeOrigin")
            armMirrorWatchdog()
            webView?.loadUrl("$activeOrigin/")
        } else {
            DiagnosticsLog.event("PipeBridge all mirrors failed")
            unavailableUntilElapsedMs = SystemClock.elapsedRealtime() + FAILURE_COOLDOWN_MS
            pageReadyState = false
            ready.complete(false)
        }
    }

    /**
     * Puts the challenge away without answering it. The mirrors stay unavailable until the cooldown
     * lapses, so dismissing it does not put the same page straight back on screen.
     */
    fun dismissHumanCheck() {
        DiagnosticsLog.event("PipeBridge human check dismissed origin=$activeOrigin")
        mutableHumanCheck.value = null
        // Still pending, so the source sheet keeps offering it — this only takes it off the screen.
        unavailableUntilElapsedMs = SystemClock.elapsedRealtime() + FAILURE_COOLDOWN_MS
        main.post {
            main.removeCallbacks(humanCheckExpiry)
            scheduleIdle()
        }
    }

    /**
     * Keeps the hidden browser mounted across a related sequence such as episode-catalog lookup
     * followed by the first source lookup. The caller must close the returned session. Individual
     * fetches still hold their own leases, so closing a session cannot interrupt an active request.
     */
    internal fun acquireWarmSession(reason: String): AutoCloseable {
        DiagnosticsLog.event("PipeBridge warm session acquired reason=$reason")
        val lease = demand.acquire()
        return AutoCloseable {
            DiagnosticsLog.event("PipeBridge warm session released reason=$reason")
            lease.close()
        }
    }

    /**
     * Allows one user/WatchViewModel-directed retry to bypass the passive failure cooldown. If a
     * failed page is still attached, restart its mirror walk; otherwise the next demand mounts a
     * fresh WebView and [attach] starts it normally.
     */
    internal suspend fun prepareForRetry() {
        // Never restart the walk while a challenge is on screen: the reload would throw away the
        // half-answered page under the user's finger, and the retry that follows puts up another
        // one. Answering it is what makes the bridge ready again.
        if (mutableHumanCheck.value != null) return
        unavailableUntilElapsedMs = 0L
        // There is no engine and no page to restart in this build: the pipe is a stateless HTTP
        // request, so a viewer retry is just the next request. Clearing the cooldown above is the
        // whole of it.
        DiagnosticsLog.event("PipeBridge viewer retry; next request goes straight out")
        return
        withContext(Dispatchers.Main.immediate) {
            val wv = webView ?: return@withContext
            if (pageReadyState != false) return@withContext
            main.removeCallbacks(idleRunnable)
            main.removeCallbacks(mirrorWatchdog)
            main.removeCallbacks(challengeWatchdog)
            mutableHumanCheck.value = null
            // Deliberately keeps humanCheckPending: a retry is not an answer. Clearing it here made
            // the source sheet flip back to "Retry Miruro servers" every 15 seconds, so the button
            // that actually unlocks anything was never on screen long enough to press. Only a page
            // that comes back without a challenge clears it.
            challengePasses = 0
            ready = CompletableDeferred()
            pageReadyState = null
            rememberedOrigin = SettingsStore.lastWorkingPipeOrigin.value
            originAttempts = orderedPipeOrigins(ORIGINS, rememberedOrigin)
            originIndex = 0
            DiagnosticsLog.event("PipeBridge explicit retry origin=$activeOrigin")
            wv.onResume()
            armMirrorWatchdog()
            wv.loadUrl("$activeOrigin/")
        }
    }

    /** Called from the hosting Composable on the main thread with a freshly created WebView. */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(wv: WebView) {
        DiagnosticsLog.event("PipeBridge.attach")
        webView = wv
        challengePasses = 0
        humanCheckAttempts = 0
        com.anilili.diagnostics.ResolverDiagnostics.viewChanged("pipe", true)
        if (ready.isCompleted) ready = CompletableDeferred()
        pageReadyState = null

        // First, before a single load or setting: setProfile is only legal on an unused WebView.
        profileCookies = if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching {
                val profile = ProfileStore.getInstance().getOrCreateProfile(PIPE_PROFILE_NAME)
                WebViewCompat.setProfile(wv, PIPE_PROFILE_NAME)
                profile.cookieManager
            }.onFailure {
                DiagnosticsLog.throwable("PipeBridge could not isolate its cookie jar", it)
            }.getOrNull()
        } else {
            null
        }
        DiagnosticsLog.event("PipeBridge cookie jar isolated=${profileCookies != null}")

        cookieJar().setAcceptCookie(true)
        cookieJar().setAcceptThirdPartyCookies(wv, true)
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // Deliberately NOT stripping the "; wv" token here, unlike the other two bridges.
            //
            // Measured on a Galaxy S25 against a live managed challenge: with the token stripped,
            // Cloudflare issued a cf_clearance cookie and then re-challenged the very next load
            // (logged as clearance=true while the interstitial was still up). A WebView can spoof
            // the user agent string but not its Client Hints, so `Sec-CH-UA` keeps saying Android
            // WebView while the UA claims Chrome, and the pair disagreeing is worse than either
            // one being honest.
        }
        // Android stamps `X-Requested-With: <package name>` onto every request a WebView makes, and
        // no browser sends it. It is the whole reason the pipe cannot reach miruro.
        //
        // Isolated against the live site on a desktop Chrome — one variable, a fresh cookie jar per
        // arm, UA and Client Hints never touched, control passing either side of every treatment:
        //
        //     no header                          PASS  (x3, interleaved)
        //     X-Requested-With: com.miruronative  WALL
        //     …: com.miruronative.debug          WALL
        //     …: XMLHttpRequest                  WALL
        //     …: "" (empty)                      WALL
        //
        // The value is irrelevant; presence is what is matched. So it has to be *absent*, and
        // blanking it is not a fallback — an empty header is still a header. The earlier note here
        // said this had been tried and did not move the challenge; the wire capture below is why
        // that conclusion was unsound, and the reading is logged now rather than assumed.
        //
        // Leaving the WebView does not help, because the zone challenges on a second, independent
        // signal — the TLS fingerprint — and no transport available to an app satisfies both:
        //
        //     WebView   Chrome fingerprint, always sends the header    WALL
        //     OkHttp    no header, not a browser fingerprint           WALL
        //     Cronet    no header, Chromium's own net stack            WALL
        //
        // Cronet was the one with a real chance and it was measured, not assumed: Play Services
        // Cronet 151.0.7922.83, four header sets from bare defaults up to a full Chrome mimic with
        // Client Hints and an XHR-shaped `Sec-Fetch-*` + Referer set, over h2 and h3. Every arm
        // answered 403; `/health` with `cf-mitigated: challenge`, `/api/secure/jwks` with
        // Cloudflare's block page. Nor is skipping the navigation a way out: a same-origin fetch
        // issued from inside the interstitial — already on miruro's origin — is challenged too, so
        // the rule covers XHR and not just documents. There is no arrangement of these parts that
        // gets through; what is missing is a way to drop the header.
        val allowListSupported =
            WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)
        if (allowListSupported) {
            runCatching {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(wv.settings, emptySet())
            }.onFailure { DiagnosticsLog.throwable("PipeBridge could not drop X-Requested-With", it) }
        }
        val allowListReadBack = if (allowListSupported) {
            runCatching {
                WebSettingsCompat.getRequestedWithHeaderOriginAllowList(wv.settings).joinToString("|")
            }.getOrElse { "unreadable" }
        } else {
            "n/a"
        }
        DiagnosticsLog.event(
            "PipeBridge X-Requested-With suppression supported=$allowListSupported " +
                "allowList=[$allowListReadBack]",
        )
        DiagnosticsLog.event("PipeBridge user agent=${wv.settings.userAgentString}")
        wv.addJavascriptInterface(Bridge, "AndroidPipe")
        wv.webViewClient = object : WebViewClient() {
            // Pin the tab to Miruro's mirrors — block ad popunders / intent: redirects that
            // would navigate away and kill the same-origin pipe session.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return true
                val host = url.host.orEmpty().lowercase()
                val allowed = url.scheme == "https" && ORIGINS.any { origin ->
                    val originHost = origin.removePrefix("https://www.")
                    host == originHost || host.endsWith(".$originHost")
                }
                if (!allowed) {
                    DiagnosticsLog.event("PipeBridge blocked nav: $url")
                    Log.d(TAG, "blocked nav: $url")
                }
                return !allowed
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                DiagnosticsLog.event("PipeBridge page started: ${url ?: "unknown"}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                DiagnosticsLog.event("PipeBridge page finished: ${url ?: "unknown"} title=${view?.title ?: "none"}")
                Log.d(TAG, "onPageFinished: $url  title=${view?.title}")
                if (url == null || !url.startsWith(activeOrigin)) return
                main.removeCallbacks(mirrorWatchdog)
                classifyFinishedPage(view)
            }

            @android.annotation.TargetApi(android.os.Build.VERSION_CODES.M)
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                DiagnosticsLog.event(
                    "PipeBridge main-frame error code=${error?.errorCode} " +
                        "description=${error?.description} origin=$activeOrigin",
                )
                // This mirror is unreachable (ISP block, DNS, site down): roll to the next one.
                // Only once all mirrors fail do waiters unblock into the cache/error path.
                main.post { advanceMirror() }
            }

            @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                DiagnosticsLog.event(
                    "PipeBridge render process gone didCrash=${detail?.didCrash()} " +
                        "priority=${detail?.rendererPriorityAtExit()}",
                )
                com.anilili.diagnostics.ResolverDiagnostics.rendererGone("pipe", detail?.didCrash())
                view?.let(::detach)
                return true
            }
        }
        // Start from whichever mirror last answered on this network, then wrap around the list so
        // a stale last-working value cannot make earlier mirrors unreachable.
        rememberedOrigin = SettingsStore.lastWorkingPipeOrigin.value
        originAttempts = orderedPipeOrigins(ORIGINS, rememberedOrigin)
        originIndex = 0
        DiagnosticsLog.event("PipeBridge load origin=$activeOrigin")
        armMirrorWatchdog()
        wv.loadUrl("$activeOrigin/")
    }

    /**
     * Decides whether the page that just finished is Miruro or Cloudflare's interstitial. Main
     * thread only; [WebView.evaluateJavascript] calls back there too.
     */
    private fun classifyFinishedPage(view: WebView?) {
        val wv = view ?: return
        val challengedByTitle = looksLikePipeChallengeTitle(wv.title)
        wv.evaluateJavascript(PIPE_CHALLENGE_PROBE_JS) { result ->
            if (challengedByTitle || result?.trim() == "true") onChallengeSeen() else onPageCleared()
        }
    }

    private fun onChallengeSeen() {
        if (ready.isCompleted && pageReadyState == true) return
        // Already surfaced: the user is answering it, and there is nothing left to time out.
        if (mutableHumanCheck.value != null) {
            // Cloudflare has put the wall back up. That is not a verdict — a managed challenge
            // re-arms while it is still deciding, and the viewer may not have touched it yet — so
            // the tab stays up until its budget runs out rather than being taken away after a
            // couple of rounds. [onPageCleared] is what ends this successfully, and it fires on the
            // page actually being Miruro, not on a cf_clearance cookie appearing: the cookie is
            // granted and refused on the very next request, so polling for it declares victory over
            // a wall that is still standing.
            humanCheckAttempts++
            val remainingMs = humanCheckDeadlineMs - SystemClock.elapsedRealtime()
            DiagnosticsLog.event(
                "PipeBridge challenge re-armed while shown attempt=$humanCheckAttempts " +
                    "remainingMs=$remainingMs ${clearanceState()}",
            )
            if (remainingMs <= 0L) {
                DiagnosticsLog.event(
                    "PipeBridge human check budget spent after $humanCheckAttempts attempts",
                )
                dismissHumanCheck()
            }
            return
        }
        DiagnosticsLog.event("PipeBridge challenge showing on $activeOrigin ${clearanceState()}")
        main.removeCallbacks(challengeWatchdog)
        main.postDelayed(challengeWatchdog, CHALLENGE_SETTLE_MS)
    }

    private fun clearanceState(): String {
        val cookies = runCatching { cookieJar().getCookie(activeOrigin) }
            .getOrNull()
            .orEmpty()
        return "clearance=${cookies.contains(CLEARANCE_COOKIE)} cookies=${cookies.count { it == ';' } + 1}"
    }

    private const val CLEARANCE_COOKIE = "cf_clearance"

    /** Names the pipe's jar; see [profileCookies] for why it has one. */
    private const val PIPE_PROFILE_NAME = "anilili-pipe"

    private fun onPageCleared() {
        main.removeCallbacks(challengeWatchdog)
        main.removeCallbacks(humanCheckExpiry)
        val clearedByHand = mutableHumanCheck.value != null
        if (clearedByHand) DiagnosticsLog.event("PipeBridge human check cleared on $activeOrigin")
        mutableHumanCheck.value = null
        mutableHumanCheckPending.value = false
        // Remember what worked. On a network that blocks the first mirrors, starting from scratch
        // every launch means paying the whole failover walk every time.
        SettingsStore.setLastWorkingPipeOrigin(activeOrigin)
        main.postDelayed(
            {
                // A hand-cleared challenge arrives after waiters were already told the bridge was
                // unavailable, so that settled answer has to be replaced rather than kept.
                if (ready.isCompleted && pageReadyState != true) ready = CompletableDeferred()
                if (!ready.isCompleted) {
                    unavailableUntilElapsedMs = 0L
                    pageReadyState = true
                    ready.complete(true)
                }
                scheduleIdle()
            },
            PAGE_SETTLE_MS,
        )
    }

    /** Releases the attached browser and fails requests that can no longer complete. */
    fun detach(wv: WebView) {
        if (webView !== wv) return
        DiagnosticsLog.event("PipeBridge.detach")
        main.removeCallbacks(idleRunnable)
        main.removeCallbacks(mirrorWatchdog)
        main.removeCallbacks(challengeWatchdog)
        main.removeCallbacks(humanCheckExpiry)
        mutableHumanCheck.value = null
        mutableHumanCheckPending.value = false
        webView = null
        com.anilili.diagnostics.ResolverDiagnostics.viewChanged("pipe", false)
        ready = CompletableDeferred()
        pageReadyState = null
        pending.entries.toList().forEach { (id, request) ->
            if (pending.remove(id, request)) {
                request.complete("""{"ok":false,"status":-1,"error":"webview released"}""")
            }
        }
        wv.removeJavascriptInterface("AndroidPipe")
    }

    object Bridge {
        @JavascriptInterface
        fun onResult(id: String, json: String) {
            pending.remove(id)?.complete(json)
        }
    }

    /** Runs `fetch('/api/secure/pipe?e=…')` inside the page and returns the raw JSON bridge payload. */
    /**
     * Whether a request would find the bridge already loaded and past Cloudflare.
     *
     * A cold bridge has to build a WebView, load a mirror and clear the challenge before it can
     * run a single fetch; a warm one runs the fetch immediately. Recording which of the two a
     * resolve started from is what separates "our resolver was asleep" from "this server is
     * genuinely slow upstream" — the two look identical in a wall-clock duration.
     */
    fun isWarm(): Boolean = webView != null && pageReadyState == true

    suspend fun fetch(e: String, timeoutMs: Long = 30_000): String {
        // No engine at all in this build -- see [OkHttpPipe] for the measurement that made that
        // possible and for what happens if the zone tightens again.
        return OkHttpPipe.fetch(e, timeoutMs)
    }

    /** Unused for this zone; kept so the challenge/human-check machinery still has a caller. */
    private suspend fun fetchViaWebView(e: String, timeoutMs: Long): String {
        // The tab is showing a challenge; anything sent into it now would race the answer.
        if (mutableHumanCheck.value != null) {
            return """{"ok":false,"status":-1,"error":"waiting on a human check"}"""
        }
        val fetchStartedMs = SystemClock.elapsedRealtime()
        val startedWarm = isWarm()
        var cooldownRemainingMs =
            (unavailableUntilElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (cooldownRemainingMs > 0L || pageReadyState == false) {
            prepareForRetry()
            cooldownRemainingMs =
                (unavailableUntilElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (cooldownRemainingMs > 0L && pageReadyState == false) {
                DiagnosticsLog.event(
                    "PipeBridge cold-start cooldown e.len=${e.length} remainingMs=$cooldownRemainingMs",
                )
                return """{"ok":false,"status":-1,"error":"resolver temporarily unavailable"}"""
            }
        }
        val lease = demand.acquire()
        var requestId: String? = null
        try {
            var pageReady = withTimeoutOrNull(PAGE_READY_TIMEOUT_MS) { ready.await() } == true
            if (!pageReady && pageReadyState == false) {
                prepareForRetry()
                pageReady = withTimeoutOrNull(PAGE_READY_TIMEOUT_MS) { ready.await() } == true
            }
            // How much of this request was spent getting the browser ready, as opposed to talking
            // to the provider. The two are indistinguishable in the resolve's total duration, and
            // the answer decides whether keeping the resolver resident is worth its memory.
            DiagnosticsLog.event(
                category = "pipe",
                name = "bridge.ready",
                attributes = mapOf(
                    "warm" to startedWarm,
                    "waitMs" to (SystemClock.elapsedRealtime() - fetchStartedMs),
                    "ready" to pageReady,
                ),
            )
            if (!pageReady) {
                unavailableUntilElapsedMs = SystemClock.elapsedRealtime() + FAILURE_COOLDOWN_MS
                DiagnosticsLog.event(
                    "PipeBridge page unavailable e.len=${e.length} " +
                        "readyBudgetMs=$PAGE_READY_TIMEOUT_MS mirrors=${originAttempts.size}",
                )
                return """{"ok":false,"status":-1,"error":"webview page unavailable"}"""
            }

            val id = counter.incrementAndGet().toString()
            requestId = id
            val deferred = CompletableDeferred<String>()
            pending[id] = deferred

            val js = """
                (function(){
                  try {
                    fetch('/api/secure/pipe?e=$e', { headers: { 'Accept': '*/*' }, credentials: 'include' })
                      .then(function(r){
                        return r.text().then(function(b){
                          AndroidPipe.onResult('$id', JSON.stringify({
                            ok: r.ok, status: r.status,
                            obf: (r.headers.get('x-obfuscated') || ''), body: b
                          }));
                        });
                      })
                      .catch(function(err){
                        AndroidPipe.onResult('$id', JSON.stringify({ ok:false, status:-1, error:String(err) }));
                      });
                  } catch (err) {
                    AndroidPipe.onResult('$id', JSON.stringify({ ok:false, status:-1, error:String(err) }));
                  }
                })();
            """.trimIndent()

            main.post {
                val wv = webView
                if (wv == null) {
                    pending.remove(id)?.complete("""{"ok":false,"status":-1,"error":"webview not ready"}""")
                } else {
                    main.removeCallbacks(idleRunnable)
                    wv.onResume()
                    wv.evaluateJavascript(js, null)
                }
            }

            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: run {
                    pending.remove(id)
                    DiagnosticsLog.event("PipeBridge fetch timeout e.len=${e.length}")
                    """{"ok":false,"status":-1,"error":"timeout"}"""
                }
            scheduleIdleIfUnused()
            Log.d(TAG, "fetch(e.len=${e.length}) -> ${result.take(180)}")
            return result
        } finally {
            requestId?.let(pending::remove)
            lease.close()
        }
    }

    private const val TAG = "PipeBridge"
}
