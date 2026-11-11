package com.anilili.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.anilili.data.settings.PlaybackBufferSize
import com.anilili.diagnostics.DiagnosticsLog

/**
 * The four durations Media3 loads against, plus a byte ceiling for the memory they cost.
 *
 * [minimumMs] is loaded unconditionally, [maximumMs] is where loading stops, and the other two are
 * how much has to be buffered before the picture starts — the second only after a stall, which is
 * why it is the larger of the pair: resuming the instant one frame arrives is what turns a single
 * stall into a stutter.
 */
internal data class PlaybackBufferPolicy(
    val minimumMs: Int,
    val maximumMs: Int,
    val playbackMs: Int,
    val rebufferMs: Int,
    /** Null keeps Media3's own per-renderer ceiling, which is roughly 137 MB for muxed video. */
    val targetBufferBytes: Int? = null,
)

/**
 * How much runway the player is given, from the viewer's choice and what the device can hold.
 *
 * [PlaybackBufferSize.AUTOMATIC] is the behaviour this app shipped with, and stays exact: a
 * television, a low-RAM device or a small heap is held to 30 seconds because the buffer competes
 * with a Compose UI and up to two WebViews for the same heap, and anything larger keeps Media3's
 * defaults. That policy reads the device and never the network, so it is meanest on precisely the
 * devices most likely to be on unsteady Wi-Fi. An explicit choice overrides it, and only an
 * explicit choice carries [PlaybackBufferPolicy.targetBufferBytes] — automatic must stay
 * byte-for-byte what it was.
 */
@OptIn(UnstableApi::class)
internal fun playbackBufferPolicy(
    size: PlaybackBufferSize,
    isTv: Boolean,
    isLowRamDevice: Boolean,
    memoryClassMb: Int,
    isProgressive: Boolean = false,
): PlaybackBufferPolicy {
    val bytes = playbackBufferBytes(memoryClassMb)
    return when (size) {
        PlaybackBufferSize.AUTOMATIC ->
            if (isTv || isLowRamDevice || memoryClassMb <= 256) {
                if (isProgressive) progressiveConstrainedPolicy(bytes) else PlaybackBufferPolicy(
                    minimumMs = 10_000,
                    maximumMs = 30_000,
                    playbackMs = 1_500,
                    rebufferMs = 3_000,
                )
            } else {
                PlaybackBufferPolicy(
                    minimumMs = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    maximumMs = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    playbackMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    rebufferMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
            }

        PlaybackBufferSize.SMALL -> PlaybackBufferPolicy(
            minimumMs = 5_000,
            maximumMs = 15_000,
            playbackMs = 1_000,
            rebufferMs = 2_000,
            targetBufferBytes = bytes,
        )

        PlaybackBufferSize.MEDIUM -> PlaybackBufferPolicy(
            minimumMs = 15_000,
            maximumMs = 40_000,
            playbackMs = 2_000,
            rebufferMs = 4_000,
            targetBufferBytes = bytes,
        )

        PlaybackBufferSize.LARGE -> PlaybackBufferPolicy(
            minimumMs = 30_000,
            maximumMs = 90_000,
            playbackMs = 2_500,
            rebufferMs = 6_000,
            targetBufferBytes = bytes,
        )

        PlaybackBufferSize.VERY_LARGE -> PlaybackBufferPolicy(
            minimumMs = 60_000,
            maximumMs = 240_000,
            playbackMs = 3_000,
            rebufferMs = 10_000,
            targetBufferBytes = bytes,
        )
    }
}

/**
 * The constrained-device buffer, widened for a file the player has to keep re-opening.
 *
 * `DefaultLoadControl` loads until [PlaybackBufferPolicy.maximumMs] and then stops until the buffer
 * falls back under [PlaybackBufferPolicy.minimumMs], so the gap between those two is how much
 * playback happens per load. The 10s/30s pair above means a refill every twenty seconds, which is
 * the right shape for segmented media — the next segment is a fresh short request whatever the
 * buffer says — and the wrong one for a single file: each of those refills cancels the open read
 * and issues a new range request into the middle of it.
 *
 * On a television that cost real stalls. A tester's onn 4K pro, 2026-09-14, on Lili's 546 MB
 * progressive MP4: five rebuffers totalling 30.6s inside 40s of playback, on a link that was
 * delivering 1.77 MB/s against a 0.37 MB/s stream. Bandwidth was never the problem — it spent 21s
 * of a 71s session loading at all, and the rest waiting on reopens. 30s/90s makes that one refill a
 * minute instead of three, roughly three per episode rather than seventy.
 *
 * Seconds alone would be reckless here, which is why this is the one automatic policy that names a
 * [PlaybackBufferPolicy.targetBufferBytes]: ninety seconds of a 3 Mbps encode is 34 MB and fine,
 * ninety seconds of a 20 Mbps one is 225 MB on a heap that has 384. The ceiling answers that the
 * same way an explicit choice does — whichever limit arrives first wins.
 *
 * The two start thresholds are left exactly as they are. This changes how long the player goes
 * between refills, not how long a viewer waits for a picture or for one to recover.
 */
private fun progressiveConstrainedPolicy(bytes: Int) = PlaybackBufferPolicy(
    minimumMs = 30_000,
    maximumMs = 90_000,
    playbackMs = 1_500,
    rebufferMs = 3_000,
    targetBufferBytes = bytes,
)

/**
 * The heap a chosen buffer is allowed to occupy: a quarter of the device's ordinary memory class.
 *
 * Seconds are the unit the viewer thinks in and bytes are the unit that runs the device out of
 * memory, and the exchange rate between them is the stream's bitrate — four minutes of a 10 Mbps
 * encode is 300 MB. So the seconds are a request and this is the answer: loading stops at whichever
 * arrives first, which on a cheap television means a viewer who asks for four minutes gets however
 * much of it fits rather than an OutOfMemoryError. `largeHeap` is on, so the real ceiling is
 * [android.app.ActivityManager.getLargeMemoryClass] and this leaves most of it for the UI, the
 * image cache and the resolver WebViews.
 */
internal fun playbackBufferBytes(memoryClassMb: Int): Int =
    (memoryClassMb / 4).coerceIn(16, 192) * 1024 * 1024

/**
 * A [LoadControl] that can be re-sized without rebuilding the player.
 *
 * Media3 fixes a load control's durations at construction and ExoPlayer holds one for the life of
 * the player, so a buffer setting written into a running app would otherwise reach nobody:
 * [PlaybackService] outlives the watch screen on purpose, and a viewer changing the setting because
 * episodes keep stalling would have to force-stop the app to find out whether it helped.
 *
 * The swap happens in [onPrepared] rather than the moment the preference is written. That callback
 * runs on the playback thread, which is the only thread a `DefaultLoadControl` tolerates, and it
 * runs before the successor is asked for any loading state — a delegate swapped in mid-episode
 * would be asked about a player it never saw prepared and would throw. Every episode here arrives
 * through a setMediaItem/prepare pair, so "the next episode" is the whole of the wait.
 *
 * The allocator is shared across delegates because it holds the buffered bytes themselves; handing
 * the successor a fresh one would strand the outgoing episode's memory on an object nothing asks
 * for again.
 */
@OptIn(UnstableApi::class)
internal class AdjustableLoadControl(
    private val requestedPolicy: () -> PlaybackBufferPolicy,
) : LoadControl {
    private val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    private var appliedPolicy = requestedPolicy()
    private var delegate = build(appliedPolicy)

    /** The policy the player is loading against, for diagnostics. */
    val policy: PlaybackBufferPolicy get() = appliedPolicy

    private fun build(policy: PlaybackBufferPolicy): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                policy.minimumMs,
                policy.maximumMs,
                policy.playbackMs,
                policy.rebufferMs,
            )
            .apply { policy.targetBufferBytes?.let(::setTargetBufferBytes) }
            .build()

    override fun onPrepared(playerId: PlayerId) {
        val requested = requestedPolicy()
        if (requested != appliedPolicy) {
            DiagnosticsLog.event(
                "AdjustableLoadControl buffer resized maxMs=${appliedPolicy.maximumMs}" +
                    "->${requested.maximumMs} bytes=${requested.targetBufferBytes ?: -1}",
            )
            appliedPolicy = requested
            delegate = build(requested)
        }
        delegate.onPrepared(playerId)
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(): Allocator = allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldContinueLoading(parameters)

    override fun shouldContinuePreloading(
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)
}
