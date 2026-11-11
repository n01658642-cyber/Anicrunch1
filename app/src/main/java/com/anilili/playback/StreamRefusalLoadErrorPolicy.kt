package com.anilili.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Gives up immediately on a CDN that has refused us, instead of retrying a decision.
 *
 * Media3's default policy retries any load error five times with a growing backoff, which is right
 * for a timeout and wrong for a 403: the URL is fixed, carries no token, and the answer will not
 * change. Bonk publishes two streams per episode and the first one's segments are all on a
 * ByteDance ImageX domain that answers every request `{"code":1004,"error":"domain forbidden"}`,
 * so those five retries were **fifteen seconds of a blank player** before the error went fatal and
 * [com.anilili.ui.watch.WatchViewModel.onPlaybackError] switched to the second stream, which plays.
 *
 * Fifteen seconds is longer than anyone waits. The reports that led here describe pressing play
 * twenty times and changing servers four times; every one of those restarted the countdown, so the
 * fallback that fixes it never got to run until the viewer happened to leave it alone. Refusing to
 * retry brings the same recovery forward to about three seconds.
 *
 * Only 401 and 403 are treated this way. A 404 or a 5xx can genuinely be a bad edge node that a
 * retry lands past, and every other error keeps the default ladder — this narrows *when* the app
 * gives up on a stream, not *whether* it does.
 */
@UnstableApi
internal class StreamRefusalLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val responseCode = (loadErrorInfo.exception as? InvalidResponseCodeException)?.responseCode
        if (isStreamRefusal(responseCode)) return C.TIME_UNSET
        if (deadLoadExhausted(
                errorCount = loadErrorInfo.errorCount,
                bytesLoaded = loadErrorInfo.loadEventInfo.bytesLoaded,
            )
        ) {
            return C.TIME_UNSET
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    /**
     * Refusing to retry was not on its own enough: Media3 lists 403 as eligible for a *fallback*,
     * so it excluded the 1080p rendition for a minute and walked to 720p, then 360p, reloading the
     * media playlist between each — measured at 19 seconds on the fix that only stopped retries,
     * against 15 before it. Every rendition of that stream is on the one refusing host, so the walk
     * cannot end anywhere but the same 403.
     *
     * A *location* fallback is a different server and is worth taking; a track fallback is another
     * rendition of the server that just refused us, and there is nothing there.
     */
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        val responseCode = (loadErrorInfo.exception as? InvalidResponseCodeException)?.responseCode
        val hasOtherServer =
            fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION)
        if (refusalEndsStream(responseCode, hasOtherServer)) return null
        return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
    }
}

/**
 * Whether an HTTP status means "not for you" rather than "not right now".
 *
 * Kept out of the policy above because a `LoadErrorInfo` cannot be built in a JVM unit test — every
 * route to one needs a `DataSpec`, which needs a real `android.net.Uri`.
 */
internal fun isStreamRefusal(responseCode: Int?): Boolean =
    responseCode == 401 || responseCode == 403

/**
 * Whether a refusal leaves this stream with nowhere to go, so the player should say so at once.
 *
 * True only when the refused load has no other *server* to try. Another rendition of the same one
 * is not somewhere else, and walking through them is what turned a three-second failure into a
 * twenty-second one.
 */
internal fun refusalEndsStream(responseCode: Int?, hasOtherServer: Boolean): Boolean =
    isStreamRefusal(responseCode) && !hasOtherServer

/**
 * Whether a load that has never delivered a byte has been given enough chances.
 *
 * [isStreamRefusal] above only catches a host that answers. A host that never gets that far keeps
 * the full ladder, and two tester reports on 2026-09-10 are what that costs:
 *
 *  - bee's playlist loaded from MegaPlay while every segment sat on `1oe.lostproject.club`, which
 *    closes the connection during the TLS handshake. On an onn television that produced **65**
 *    load errors across two minutes and fifty seconds, the player limping through 4.6 seconds of
 *    video and then 28 seconds of nothing, while bee's other stream and its embed sat unused.
 *  - bonk's `vivibebe.site` timed out fetching the *manifest*, and each retry waited out the socket
 *    timeout again: 8.2s, 11.1s, 22.0s, 32.3s, 48.0s on one episode, zero bytes every time.
 *
 * Deliberately counted in bytes rather than in exception types. The failures arrive as an
 * `SSLHandshakeException` from OkHttp on one device and as Cronet's `net::ERR_CONNECTION_CLOSED`
 * on another — same dead host, two unrelated classes — while "this load has produced nothing twice
 * running" is true in both and needs no taxonomy to recognise.
 *
 * One retry is still allowed, which is the case the comment above wanted kept: a 404 or a 5xx from
 * a bad edge node is answered by the *second* attempt landing elsewhere, not the fifth. What goes
 * is the long tail after it, where the same dead host is asked again with a growing backoff and
 * the fallback that actually recovers never gets to run.
 */
internal fun deadLoadExhausted(errorCount: Int, bytesLoaded: Long): Boolean =
    bytesLoaded == 0L && errorCount >= 2
