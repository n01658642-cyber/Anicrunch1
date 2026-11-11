package com.anilili.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.anilili.diagnostics.DiagnosticsLog

/** Mobile builds carry Google Cast. */
@OptIn(UnstableApi::class)
internal object CastRoutes {
    fun create(context: Context): CastRoute? {
        // Play Services can be present but unable to serve the cast module (an OEM shell, a
        // stale install). That is a missing capability, not a reason to fail the service —
        // playback simply runs local.
        val route = runCatching {
            val castContext = CastContext.getSharedInstance(context)
            GoogleCastRoute(castContext, CastPlayer(castContext, MiruroCastMediaItemConverter()))
        }
            .onFailure { DiagnosticsLog.throwable("PlaybackService cast unavailable", it) }
            .getOrNull()
            ?: return null
        route.observeSessions()
        return route
    }
}

@OptIn(UnstableApi::class)
private class GoogleCastRoute(
    castContext: CastContext,
    private val castPlayer: CastPlayer,
) : CastRoute {
    override val player: Player get() = castPlayer

    private val sessions = castContext.sessionManager

    override val receiverDescription: String?
        get() = sessions.currentCastSession?.describe()

    /**
     * What the receiver says about itself as it loads.
     *
     * `CastPlayer` collapses all of this into `STATE_IDLE`, which is why a refused load reached
     * [PlaybackService] as silence; see [CastRouteStatus]. The receiver's own player state and
     * idle reason are the difference between "it never started fetching", "it fetched and the
     * media was rejected" and "the viewer stopped it from the TV".
     */
    private val receiverStatus = object : RemoteMediaClient.Callback() {
        /** `onStatusUpdated` also fires for progress; only transitions are worth a line. */
        private var lastReport: String? = null

        override fun onStatusUpdated() {
            val status = sessions.currentCastSession?.mediaClientOrNull()?.mediaStatus ?: return
            val report = "state=${playerStateName(status.playerState)} " +
                "idleReason=${idleReasonName(status.idleReason)}"
            if (report == lastReport) return
            lastReport = report
            DiagnosticsLog.event("Cast receiver $report")
        }

        override fun onMediaError(error: MediaError) {
            DiagnosticsLog.event(
                "Cast receiver media error detailedCode=${error.detailedErrorCode ?: "none"} " +
                    "type=${error.type ?: "none"} reason=${error.reason ?: "none"}",
            )
        }
    }

    private val sessionEvents = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            DiagnosticsLog.event("Cast session starting receiver=${session.describe()}")
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            DiagnosticsLog.event("Cast session started receiver=${session.describe()}")
            session.mediaClientOrNull()?.registerCallback(receiverStatus)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            DiagnosticsLog.event(
                "Cast session start failed receiver=${session.describe()} status=$error",
            )
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            DiagnosticsLog.event(
                "Cast session resumed receiver=${session.describe()} wasSuspended=$wasSuspended",
            )
            session.mediaClientOrNull()?.registerCallback(receiverStatus)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            DiagnosticsLog.event(
                "Cast session resume failed receiver=${session.describe()} status=$error",
            )
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            DiagnosticsLog.event("Cast session suspended reason=$reason")
        }

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionEnded(session: CastSession, error: Int) {
            DiagnosticsLog.event("Cast session ended status=$error")
            session.mediaClientOrNull()?.unregisterCallback(receiverStatus)
        }
    }

    fun observeSessions() {
        runCatching { sessions.addSessionManagerListener(sessionEvents, CastSession::class.java) }
            .onFailure { DiagnosticsLog.throwable("PlaybackService cast session logging unavailable", it) }
        // A session can already be up when the service starts (the app was reopened onto a cast
        // that outlived it), and that one never fires onSessionStarted.
        sessions.currentCastSession?.mediaClientOrNull()?.registerCallback(receiverStatus)
    }

    override fun onSessionChanged(onAvailable: () -> Unit, onUnavailable: () -> Unit) {
        castPlayer.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = onAvailable()
            override fun onCastSessionUnavailable() = onUnavailable()
        })
    }

    override fun endSession() {
        // `false` leaves the receiver application running, so the TV does not blink back to its
        // home screen on the way past; the viewer may well be about to try again.
        runCatching { sessions.endCurrentSession(false) }
            .onFailure { DiagnosticsLog.throwable("PlaybackService cast session end failed", it) }
    }

    override fun release() {
        runCatching { sessions.currentCastSession?.mediaClientOrNull()?.unregisterCallback(receiverStatus) }
        runCatching { sessions.removeSessionManagerListener(sessionEvents, CastSession::class.java) }
        castPlayer.release()
    }
}

/**
 * Model and receiver application, never the friendly name: that is chosen by the viewer and reads
 * like "Alex's TV" often enough to count as personal, while the model is what a bug report needs.
 */
private fun CastSession.describe(): String {
    val device = runCatching { castDevice }.getOrNull()
    val model = device?.modelName?.takeIf { it.isNotBlank() } ?: "unknown"
    val version = device?.deviceVersion?.takeIf { it.isNotBlank() } ?: "unknown"
    // Throws rather than returning null while the session is still connecting, which is exactly
    // when the interesting lines are written.
    val app = runCatching { applicationMetadata?.applicationId }.getOrNull() ?: "none"
    return "$model/$version app=$app"
}

/** Also throws before the session is connected, and is only ever wanted when it is. */
private fun CastSession.mediaClientOrNull(): RemoteMediaClient? =
    runCatching { remoteMediaClient }.getOrNull()

private fun playerStateName(state: Int): String = when (state) {
    MediaStatus.PLAYER_STATE_IDLE -> "IDLE"
    MediaStatus.PLAYER_STATE_PLAYING -> "PLAYING"
    MediaStatus.PLAYER_STATE_PAUSED -> "PAUSED"
    MediaStatus.PLAYER_STATE_BUFFERING -> "BUFFERING"
    MediaStatus.PLAYER_STATE_LOADING -> "LOADING"
    MediaStatus.PLAYER_STATE_UNKNOWN -> "UNKNOWN"
    else -> "state$state"
}

/**
 * `IDLE_REASON_ERROR` is the whole point of this file: it is the receiver saying it could not play
 * what it was handed, and it is the only place that fact exists — it reaches `CastPlayer` as a
 * plain `STATE_IDLE` with no exception attached.
 */
private fun idleReasonName(reason: Int): String = when (reason) {
    MediaStatus.IDLE_REASON_NONE -> "none"
    MediaStatus.IDLE_REASON_FINISHED -> "finished"
    MediaStatus.IDLE_REASON_CANCELED -> "canceled"
    MediaStatus.IDLE_REASON_INTERRUPTED -> "interrupted"
    MediaStatus.IDLE_REASON_ERROR -> "error"
    else -> "reason$reason"
}
