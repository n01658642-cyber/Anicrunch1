package com.anilili.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * How long "Skip Recap" and "Skip Intro" show by themselves once the playhead enters their window.
 *
 * Both used to appear only with the controls up, so the opening started with nothing on screen and
 * the button turned up only after a tap. Viewers asked for it to pop up the way "Skip Outro" does.
 * They were tied to the controls for a reason, though (e2216d98): a window lasts as long as the
 * opening it describes and can start at 0:00, and ReAnime's 0-89s intro kept the button over the
 * picture for a minute and a half with no way to dismiss it. So the button pops up for this long,
 * the way the streaming services do it, and after that it shows only with the controls.
 */
internal const val TIMED_SKIP_POPUP_MS = 10_000L

private const val TIMED_SKIP_POPUP_TICK_MS = 250L

/**
 * Whether a timed skip is still within its pop-up after the playhead entered its window
 * ([startMs] until [endMs]).
 *
 * The clock runs on time spent playing, not wall time: the playhead sits at 0:00 inside an opening
 * while a stream loads or a resume seek lands, and a wall clock had used up the pop-up before the
 * first frame. Leaving the window re-arms it, so seeking back into an opening offers the button again.
 */
@Composable
internal fun rememberTimedSkipPopup(inWindow: Boolean, playing: Boolean, startMs: Long?, endMs: Long?): Boolean {
    // Kept out of snapshot state so the countdown does not recompose the player four times a second.
    val remainingMs = remember(startMs, endMs) { longArrayOf(TIMED_SKIP_POPUP_MS) }
    var showing by remember(startMs, endMs) { mutableStateOf(true) }
    LaunchedEffect(inWindow, playing, remainingMs) {
        if (!inWindow) {
            remainingMs[0] = TIMED_SKIP_POPUP_MS
            showing = true
            return@LaunchedEffect
        }
        while (playing && remainingMs[0] > 0L) {
            delay(TIMED_SKIP_POPUP_TICK_MS)
            remainingMs[0] -= TIMED_SKIP_POPUP_TICK_MS
        }
        if (remainingMs[0] <= 0L) showing = false
    }
    return inWindow && showing
}
