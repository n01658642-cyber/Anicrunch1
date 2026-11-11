package com.anilili.ui.watch

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anilili.ui.theme.LocalAppColors

/**
 * The played position as a fraction, or 0 while the duration is still unknown.
 *
 * A live or still-resolving stream reports duration 0 or [androidx.media3.common.C.TIME_UNSET], and
 * dividing by either produces a thumb parked off the end of the bar rather than at its start.
 */
internal fun playedFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** Where a press or drag at [xPx] across a bar [widthPx] wide lands, in milliseconds. */
internal fun seekTargetMs(xPx: Float, widthPx: Float, durationMs: Long): Long {
    if (widthPx <= 0f || durationMs <= 0L) return 0L
    return ((xPx / widthPx).coerceIn(0f, 1f) * durationMs).toLong()
}

/**
 * The player's timeline: a hand-drawn three-band track with its own thumb, scrubbable by finger and
 * by remote.
 *
 * Hand-drawn rather than a Material `Slider` for two reasons. The first is that the buffered band
 * has nowhere to go in Material's own track, which draws two bands and no more — and that band is
 * the one thing distinguishing a stalled source from a paused one. The second is that the slider's
 * appearance has moved twice across Material releases, so a bar built on it changes shape whenever
 * the dependency does; this one looks the same on every device the app supports.
 *
 * This is the touch bar. TV draws its own in [TvPlayerControls], which has to do considerably more:
 * a remote scrubs in accelerating steps that only commit once the run of presses stops, and the
 * drawn track is far too thin for an air-mouse to hit without a wider band around it.
 */
@Composable
internal fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onInteract: () -> Unit = {},
    /** Reports the position under the finger while it is down, so a caller can preview it. */
    onScrubChange: (Long?) -> Unit = {},
    /** The intro, outro and recap windows to paint on the track; empty where none are known. */
    markers: List<PlayerSeekMarker> = emptyList(),
    trackHeight: Dp = 4.dp,
    touchHeight: Dp = 28.dp,
) {
    val enabled = durationMs > 0L
    // Held while a finger is down so the bar tracks the finger rather than the once-a-second
    // position update, and the seek is committed on release instead of on every drag sample.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnInteract by rememberUpdatedState(onInteract)
    val currentOnScrubChange by rememberUpdatedState(onScrubChange)
    val currentDurationMs by rememberUpdatedState(durationMs)

    val fraction = scrubFraction ?: playedFraction(positionMs, durationMs)
    val buffered = bufferedFraction(bufferedPositionMs, durationMs)
    val markerFractions = remember(markers, durationMs) {
        playerSeekMarkerFractions(markers, durationMs)
    }
    val active = scrubFraction != null
    val drawnTrackHeight by animateDpAsState(
        targetValue = if (active) trackHeight * 1.5f else trackHeight,
        label = "seekBarTrackHeight",
    )
    val thumbRadius by animateDpAsState(
        targetValue = when {
            !enabled -> 0.dp
            active -> 9.dp
            else -> 6.dp
        },
        label = "seekBarThumbRadius",
    )

    // White unless the viewer asked for the accent in the player (Appearance): the seek bar sits on
    // the picture, where the player is monochrome by default (see PlayerPanelTheme).
    val played = LocalAppColors.current.playerAccent.let { if (enabled) it else it.copy(alpha = 0.38f) }
    val bufferedColor = Color.White.copy(alpha = if (enabled) 0.50f else 0.24f)
    val remaining = Color.White.copy(alpha = if (enabled) 0.30f else 0.16f)

    fun commit(target: Long) {
        currentOnSeek(target.coerceIn(0L, currentDurationMs))
        currentOnInteract()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(touchHeight)
            .semantics { contentDescription = "Seek" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    commit(seekTargetMs(offset.x, size.width.toFloat(), currentDurationMs))
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        val next = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        scrubFraction = next
                        currentOnScrubChange((next * currentDurationMs).toLong())
                        currentOnInteract()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val next = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        scrubFraction = next
                        currentOnScrubChange((next * currentDurationMs).toLong())
                        currentOnInteract()
                    },
                    onDragEnd = {
                        scrubFraction?.let { commit((it * currentDurationMs).toLong()) }
                        scrubFraction = null
                        currentOnScrubChange(null)
                    },
                    onDragCancel = {
                        scrubFraction = null
                        currentOnScrubChange(null)
                    },
                )
            },
    ) {
        val density = LocalDensity.current
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(touchHeight),
        ) {
            val centreY = size.height / 2f
            val strokePx = with(density) { drawnTrackHeight.toPx() }
            val thumbPx = with(density) { thumbRadius.toPx() }
            // The thumb must not run off either end, so the track it rides is inset by its own
            // radius and every band is drawn in that same space.
            val left = thumbPx
            val right = (size.width - thumbPx).coerceAtLeast(left)
            val span = right - left

            fun band(color: Color, bandFraction: Float) {
                if (bandFraction <= 0f || span <= 0f) return
                drawLine(
                    color = color,
                    start = Offset(left, centreY),
                    end = Offset(left + span * bandFraction.coerceIn(0f, 1f), centreY),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round,
                )
            }
            band(remaining, 1f)
            band(bufferedColor, maxOf(buffered, fraction))
            band(played, fraction)
            drawPlayerSeekMarkers(
                fractions = markerFractions,
                left = left,
                span = span,
                centreY = centreY,
                strokePx = strokePx,
            )
            if (thumbPx > 0f) {
                drawCircle(
                    color = played,
                    radius = thumbPx,
                    center = Offset(left + span * fraction.coerceIn(0f, 1f), centreY),
                )
            }
        }
    }
}
