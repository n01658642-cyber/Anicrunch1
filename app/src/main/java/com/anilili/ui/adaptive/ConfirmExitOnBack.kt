package com.anilili.ui.adaptive

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anilili.ui.theme.Midnight
import com.anilili.ui.watch.findActivity
import kotlinx.coroutines.delay

/** How long the first press stays good for. */
const val ConfirmExitWindowMillis = 3_000L

/**
 * Whether a Back press lands inside the window opened by an earlier one.
 *
 * Pulled out of the composable so the timing is testable without a device: it is the whole
 * behaviour, and getting it wrong in either direction is bad — too short and the confirmation is
 * unusable with a remote, too long and a press from a minute ago silently arms the exit.
 */
internal fun exitConfirmed(
    promptedAtMillis: Long?,
    nowMillis: Long,
    windowMillis: Long = ConfirmExitWindowMillis,
): Boolean = promptedAtMillis != null && nowMillis - promptedAtMillis in 0..windowMillis

/**
 * Makes leaving the app cost two Back presses, with a prompt in between.
 *
 * Every other Back in this app moves one screen and is undone by pressing OK again. This one is
 * not: on a TV box Back at the top of the stack hands the screen to the launcher, and coming back
 * means a cold start with whatever was on screen gone — the slowest thing the app ever does, on
 * the hardware least able to hurry it. A remote's Back sits right beside the D-pad, so the press
 * that costs the most is also the easiest one to hit by accident.
 *
 * Nothing else is guarded. A confirmation on a cheap, reversible action is just a second button
 * press, and asking for one everywhere is how people learn to press Back twice out of habit —
 * which would put them back where they started.
 *
 * @param enabled arm only where Back would actually leave the app; anywhere the navigation graph
 *   can still pop, this must be false or it would swallow an ordinary Back.
 */
@Composable
fun BoxScope.ConfirmExitOnBack(enabled: Boolean, message: String) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var promptedAt by remember { mutableStateOf<Long?>(null) }

    // Disarm on the way out. Navigating away and back is not a second press, and an armed prompt
    // that survived the trip would turn the next stray Back into the exit it was meant to catch.
    LaunchedEffect(enabled) {
        if (!enabled) promptedAt = null
    }

    // The prompt stays up for exactly as long as it is true. Leaving it any longer would promise a
    // second press that no longer works; taking it away early would hide one that still does.
    LaunchedEffect(promptedAt) {
        if (promptedAt == null) return@LaunchedEffect
        delay(ConfirmExitWindowMillis)
        promptedAt = null
    }

    BackHandler(enabled = enabled) {
        // elapsedRealtime, not wall clock: a network time correction landing between the two
        // presses must not decide whether the app closes.
        val now = SystemClock.elapsedRealtime()
        if (exitConfirmed(promptedAt, now)) {
            promptedAt = null
            activity?.finish()
        } else {
            promptedAt = now
        }
    }

    AnimatedVisibility(
        visible = promptedAt != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
    ) {
        Text(
            text = message,
            color = Midnight.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Midnight.Panel2)
                .border(1.dp, Midnight.Line2, RoundedCornerShape(999.dp))
                .padding(horizontal = 24.dp, vertical = 14.dp)
                // Announced when it appears: a viewer using TalkBack gets no other signal that the
                // press did something, and would otherwise press Back again to find out.
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
