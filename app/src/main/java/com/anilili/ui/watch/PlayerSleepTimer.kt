package com.anilili.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anilili.playback.SLEEP_WARNING_MS
import com.anilili.playback.SleepTimer
import com.anilili.playback.formatSleepRemaining
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.dialogs.MidnightHelper
import com.anilili.ui.dialogs.MidnightSectionLabel
import com.anilili.ui.dialogs.MidnightSettingSwitchRow
import com.anilili.ui.dialogs.MidnightUnderlineSelector
import kotlinx.coroutines.delay

/** The segment that switches the timer off; every other segment is a duration in minutes. */
private const val SLEEP_OFF = 0

/** Which segment is lit for [state]. End of episode lights none, since its switch says so. */
internal fun sleepTimerSegment(state: SleepTimer.State): Int? = when (state) {
    SleepTimer.State.Off -> SLEEP_OFF
    is SleepTimer.State.Counting -> state.minutes
    SleepTimer.State.EndOfEpisode -> null
}

internal fun sleepTimerSegmentLabel(minutes: Int): String = when {
    minutes == SLEEP_OFF -> "Off"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes}m"
}

/**
 * The sleep timer's controls, in the settings panel both players share.
 *
 * Durations go in a segmented row. "End of episode" is a switch of its own, because its label
 * will not fit in a segment on the narrowest panel. The two are still one choice: setting either
 * replaces the other.
 */
@Composable
internal fun ColumnScope.SleepTimerSection() {
    val state by SleepTimer.state.collectAsState()
    val remainingMs = rememberSleepRemaining(state)
    MidnightSectionLabel("Sleep timer")
    MidnightUnderlineSelector(
        options = listOf(SLEEP_OFF) + SleepTimer.DURATIONS_MINUTES,
        // -1 matches no segment, which leaves the row unlit while end-of-episode is the choice.
        selected = sleepTimerSegment(state) ?: -1,
        // Picking the running duration again starts it over: "30 more minutes" in one press.
        onSelect = { if (it == SLEEP_OFF) SleepTimer.cancel() else SleepTimer.start(it) },
        tabLabel = ::sleepTimerSegmentLabel,
        claimInitialFocus = false,
    )
    MidnightSettingSwitchRow(
        title = "Pause at the end of this episode",
        checked = state == SleepTimer.State.EndOfEpisode,
        onCheckedChange = { on -> if (on) SleepTimer.pauseAtEndOfEpisode() else SleepTimer.cancel() },
    )
    when {
        remainingMs != null -> MidnightHelper("Pauses in ${formatSleepRemaining(remainingMs)}")
        state == SleepTimer.State.EndOfEpisode ->
            MidnightHelper("Pauses when this episode ends, and the next one won't start")
    }
}

/**
 * The warning before the timer fires, in the same chip treatment as the clock.
 *
 * A viewer still awake should not lose the episode to a timer they forgot they set, so the last
 * minute is announced. On a touch screen the chip itself adds 15 minutes. On television it is only
 * a notice: a focusable chip over the picture would take the remote away from the player, and the
 * settings panel is one press from it.
 */
@Composable
internal fun SleepTimerWarning(modifier: Modifier = Modifier) {
    val state by SleepTimer.state.collectAsState()
    val remainingMs = rememberSleepRemaining(state) ?: return
    if (remainingMs > SLEEP_WARNING_MS) return
    val isTv = LocalAppDeviceProfile.current.isTv
    val shape = RoundedCornerShape(if (isTv) 12.dp else 10.dp)
    val text = "Sleep timer pauses in ${formatSleepRemaining(remainingMs)}" +
        if (isTv) "" else " · Tap for 15 more min"
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.62f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .then(if (isTv) Modifier else Modifier.clickable { SleepTimer.extend(15) })
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = if (isTv) 14.dp else 11.dp, vertical = if (isTv) 6.dp else 5.dp),
    ) {
        Text(
            text = text,
            style = if (isTv) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.92f),
        )
    }
}

/** Where the warning sits: top centre, clear of the clock's corner and the subtitles' band. */
internal fun Modifier.sleepTimerWarningAnchor(isTv: Boolean): Modifier = if (isTv) {
    padding(top = 16.dp)
} else {
    statusBarsPadding().displayCutoutPadding().padding(top = 8.dp)
}

/** The countdown's time left, ticking while one runs; null when the timer is not counting down. */
@Composable
private fun rememberSleepRemaining(state: SleepTimer.State): Long? {
    val remaining by produceState(SleepTimer.remainingMs(state), state) {
        value = SleepTimer.remainingMs(state)
        while (state is SleepTimer.State.Counting) {
            val left = SleepTimer.remainingMs(state) ?: break
            value = left
            if (left <= 0L) break
            // Wake as the shown second changes. The display rounds up, so that change comes at
            // each whole second of time left.
            delay(left % 1000L + 1L)
        }
    }
    // A new key keeps the old value for one frame, until its producer runs. Without this check,
    // a cancelled countdown would flash its last reading once more.
    return remaining.takeIf { state is SleepTimer.State.Counting }
}
