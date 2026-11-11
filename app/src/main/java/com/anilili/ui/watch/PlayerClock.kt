package com.anilili.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.components.LocalTimeOfDayFormatter
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * What the player says when it is asked "how late will this make me?".
 *
 * The elapsed and remaining figures beside the seek bar answer that only after arithmetic the viewer
 * has to do themselves, in the dark, at the exact moment they are deciding whether to watch one
 * more. This states both halves outright: the time now, and the time the episode runs out.
 *
 * Stated as a wall-clock time rather than as "24 minutes left", because the remaining figure is
 * already on the bar and is not the thing being asked.
 */
internal fun playerClockText(
    now: LocalTime,
    positionMs: Long,
    durationMs: Long,
    /** The playback rate the episode is running at; 2x finishes it in half the wall time. */
    speed: Float,
    formatter: DateTimeFormatter,
): String {
    val clock = now.format(formatter)
    val remainingMs = durationMs - positionMs
    // A live stream and a still-resolving one both report no duration, and an episode that has run
    // out has no ending left to predict. Both leave the clock standing on its own rather than
    // printing an ending that would be a guess.
    if (durationMs <= 0L || remainingMs <= 0L) return clock
    // A speed of zero is not a stopped episode, it is a player that has not reported one yet;
    // dividing by it would put the ending at infinity.
    val rate = if (speed > 0f) speed.toDouble() else 1.0
    val endsAt = now.plusSeconds((remainingMs / rate).toLong() / 1000L)
    return "$clock — Ends at ${endsAt.format(formatter)}"
}

/**
 * The wall clock and the episode's finishing time, in the player's own chip treatment.
 *
 * Callers place it and decide when it is up; see [com.anilili.data.settings.SettingsStore.playerClock]
 * for the switch that governs whether it is offered at all.
 */
@Composable
internal fun PlayerClockReadout(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    speed: Float = 1f,
) {
    val formatter = LocalTimeOfDayFormatter.current
    val minute = rememberClockMinute()
    // `now` is read here rather than held as state so that every input to the ending time comes from
    // the same instant. Read once and remembered, a stale `now` added to a fresh remaining time
    // would walk the ending *backwards* through the episode, one polled position at a time.
    val text = remember(minute, positionMs, durationMs, speed, formatter) {
        playerClockText(LocalTime.now(), positionMs, durationMs, speed, formatter)
    }
    val isTv = LocalAppDeviceProfile.current.isTv
    val shape = RoundedCornerShape(if (isTv) 12.dp else 10.dp)
    // The same chip as [PlayerTimePill] and TvPlayerControls' own: white letters over a moving
    // picture stop being readable the moment the frame goes pale, and this one sits at the top of
    // the screen where the bright half of most frames is.
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.46f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            // No contentDescription: the chip is the text, and naming it here would have a screen
            // reader announce the same time twice.
            .padding(horizontal = if (isTv) 12.dp else 9.dp, vertical = if (isTv) 4.dp else 3.dp),
    ) {
        Text(
            text = text,
            style = if (isTv) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.92f),
        )
    }
}

/**
 * Where the readout sits, for both the things that draw it.
 *
 * The touch bar carries it as the top of its own top-right stack; the host draws the same chip at
 * the same corner for the states the bar is not up in — a television, and a pause that has outlived
 * the controls. Shared rather than written twice because the two are visible in sequence: a padding
 * that drifted on one side would make the clock jump sideways every time the bar came and went.
 */
internal fun Modifier.playerClockAnchor(isTv: Boolean): Modifier = if (isTv) {
    // Tucked further into the corner than the 48.dp every other TV overlay uses, because this one
    // is a chip in the margin rather than something to aim at: the inset exists for overscan, and
    // overscan is about 48 *pixels* horizontally and 27 vertically at 1080p — which is 24.dp and
    // 14.dp on a television's usual density, not 48.dp on every side. Half the inset still clears
    // the crop with room to spare, and stops the clock floating in the middle of the sky.
    padding(end = 24.dp, top = 16.dp)
} else {
    statusBarsPadding().displayCutoutPadding().padding(end = 12.dp, top = 8.dp)
}

/**
 * The current minute, so a paused player's clock still moves.
 *
 * While the episode runs, the polled position recomposes this readout twice a second and the clock
 * comes along for free. Paused, nothing moves at all — and a pause is exactly when someone is
 * looking at it — so the minute is watched on its own.
 *
 * Timed to the boundary rather than on a fixed interval: the readout prints minutes, so one wake-up
 * per minute is all the accuracy there is to have, and a periodic tick would show the wrong minute
 * for up to half its period. The TV top bar's thirty-second poll predates this and is the behaviour
 * this deliberately does not copy.
 */
@Composable
private fun rememberClockMinute(): Long {
    var minute by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000L) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            // The 200ms is slack for an inexact wake-up, so the clock is never read a millisecond
            // before the minute it is waiting for and left a whole minute behind.
            delay(60_000L - (now % 60_000L) + 200L)
            minute = System.currentTimeMillis() / 60_000L
        }
    }
    return minute
}
