package com.anilili.playback

import androidx.media3.common.Player
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Whether a receiver that was handed the queue has stopped answering for it.
 *
 * Media3 1.8.x's `CastPlayer` reports a receiver-side load failure as nothing more than
 * `STATE_IDLE`: no `PlaybackException` reaches [PlaybackService]'s listener, so `onPlayerError`
 * never fires, and every recovery the player screen owns is keyed on `STATE_BUFFERING`. A cast the
 * receiver refuses therefore landed in a state with no error, no timeout and no failover, under a
 * loading overlay waiting for a picture the phone was never going to draw — the whole of the "pick
 * a device and it loads forever" report.
 *
 * Extracted from the service so the one thing that matters about the rule can be stated as a test:
 * `STATE_BUFFERING` must never reach here. A receiver that is still fetching says BUFFERING, the
 * player screen's twenty-second stall failover already owns that case, and treating it as a dead
 * cast would end sessions that were about to work.
 */
internal fun shouldGiveUpOnCastLoad(
    isRemoteRoute: Boolean,
    playbackState: Int,
    playWhenReady: Boolean,
): Boolean = isRemoteRoute && playbackState == Player.STATE_IDLE && playWhenReady

/**
 * How the service says why a cast the viewer asked for is not what they are watching.
 *
 * It needs saying because the two halves are in different places: [PlaybackService] is what decides
 * a cast did not or cannot happen, and it has no screen; the watch screen is where the viewer is
 * looking, and it has no view of the route. TV builds ship no Cast stack, so nothing ever emits.
 */
object CastRouteStatus {
    private val _playingHere = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * One line explaining why the episode is on this device rather than on the television.
     *
     * Covers both shapes of that, because to a viewer they are the same disappointment: a receiver
     * that took the queue and never started it, which this service hands back by itself, and a
     * source it declined to hand over at all because nothing on the far end could have read it.
     *
     * An event rather than a state, and deliberately without replay: a viewer opening an episode an
     * hour later must not be told about a cast that failed during a different one. Never emitted for
     * something the viewer chose — disconnecting from the cast dialog is already self-explanatory,
     * and saying so again would be noise.
     */
    val playingHere = _playingHere.asSharedFlow()

    internal fun reportPlayingHere(message: String) {
        _playingHere.tryEmit(message)
    }
}
