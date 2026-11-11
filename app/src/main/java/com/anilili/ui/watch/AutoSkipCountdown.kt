package com.anilili.ui.watch

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay

/**
 * How long an auto-skip announces itself before it jumps.
 *
 * Auto-skip used to seek the instant the playhead entered a recap or an opening, so the picture
 * lurched forward with nothing on screen to say why, and a viewer who wanted this one opening had
 * no moment to take it. It now counts down on the skip button first, the way the streaming services
 * do: long enough to read and press, short enough that the setting still means "skip it".
 */
internal const val AUTO_SKIP_COUNTDOWN_SECONDS = 3

/** A pending auto-skip out of the window [startMs] until [endMs]; [segment] names it on the button. */
internal data class AutoSkipCountdown(
    val segment: String,
    val startMs: Long,
    val endMs: Long,
)

/** "Skipping Intro · 3s": the button's label while [countdown] runs. */
internal fun autoSkipCountdownLabel(countdown: AutoSkipCountdown, secondsLeft: Int): String =
    "Skipping ${countdown.segment} · ${secondsLeft.coerceAtLeast(0)}s"

/**
 * Where the sweep is heading while [secondsLeft] is on the button.
 *
 * One second ahead of the label rather than level with it: each target is animated to over the
 * second that follows, so the fill reaches the far edge as the count reaches zero instead of a
 * second after the jump.
 */
internal fun autoSkipSweepTarget(secondsLeft: Int, from: Int = AUTO_SKIP_COUNTDOWN_SECONDS): Float =
    (1f - (secondsLeft - 1).toFloat() / from).coerceIn(0f, 1f)

/**
 * Counts [secondsLeft] down to zero in played time, not wall time: a pause holds the clock, since
 * a paused viewer is often looking at exactly what is about to be skipped.
 */
internal suspend fun runAutoSkipCountdown(
    isPlaying: () -> Boolean,
    onSecondsLeft: (Int) -> Unit,
    from: Int = AUTO_SKIP_COUNTDOWN_SECONDS,
) {
    var playedMs = 0L
    val totalMs = from * 1_000L
    onSecondsLeft(from)
    while (playedMs < totalMs) {
        delay(AUTO_SKIP_TICK_MS)
        if (isPlaying()) playedMs += AUTO_SKIP_TICK_MS
        onSecondsLeft(((totalMs - playedMs + 999L) / 1_000L).toInt())
    }
}

private const val AUTO_SKIP_TICK_MS = 100L

/**
 * Lays the skip button out at the size it is drawn, so [autoSkipSweep] fills the button and not
 * the space around it.
 *
 * Material pads a button smaller than 48dp out to that size for touch, around the outside of the
 * modifier the button is given. The sweep sits on that modifier, so on a phone it painted the whole
 * 48dp box: a slab of colour sticking out above and below the 40dp outline, with square corners
 * where the outline is rounded. A TV already has the padding switched off, which is why only
 * phones showed it.
 */
@Composable
internal fun AutoSkipButtonBounds(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified, content = content)
}

/**
 * The fill sweeping across the skip button while the countdown runs. The animated value is read
 * only in the draw phase, so the sweep repaints the button without recomposing the player. The
 * button has to sit in [AutoSkipButtonBounds] for this to land on it.
 */
@Composable
internal fun Modifier.autoSkipSweep(secondsLeft: Int?, color: Color, shape: Shape): Modifier {
    val sweep = animateFloatAsState(
        targetValue = secondsLeft?.let(::autoSkipSweepTarget) ?: 0f,
        animationSpec = if (secondsLeft == null) tween(0) else tween(1_000, easing = LinearEasing),
        label = "auto-skip-sweep",
    )
    if (secondsLeft == null) return this
    return clip(shape).drawBehind {
        drawRect(color, size = Size(size.width * sweep.value, size.height))
    }
}
