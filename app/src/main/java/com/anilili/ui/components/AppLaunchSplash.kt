package com.anilili.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.anilili.ui.adaptive.UnscaledUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long the mark takes to fill on the launch splash.
 *
 * Deliberately not [WaterFillDurationMillis]. That constant paces the *looping* loader, where a slow
 * fill is right because the thing it is measuring has no known length and a brisk loop would read as
 * impatience. The splash is the opposite case: it measures nothing, it is over when it says it is
 * over, and every millisecond of it is time the viewer spends not using the app.
 *
 * At the shared 5.76s the whole launch ran about six and a half seconds before the first tap was
 * possible, which is what "it takes a long time to open" turned out to mean — the app behind was
 * already composed and waiting the entire time.
 */
private const val LaunchSplashFillMillis = 1_600

/** The fade the splash leaves on, once [LaunchSplashFillMillis] and the hold below are spent. */
const val LaunchSplashFadeMillis = 250

/**
 * A beat on the full mark before the fade starts, so the fill has an ending rather than being cut
 * off at the top of its travel. Long enough to register, short enough not to be a second wait.
 */
private const val LaunchSplashHoldMillis = 150

/**
 * The longest the splash waits for its fill before leaving regardless.
 *
 * The splash ends when the fill reports it is full, not after a fixed delay: the delay started
 * counting before the first frame was drawn while the fill's clock started on it, so a slow cold
 * start dismissed the splash with the mark half full. The fill in turn pauses rather than skips
 * through stalled frames, so on a device that stalls badly it can run long — and past this point
 * the app behind is ready and the viewer is simply being kept from it.
 */
private const val LaunchSplashCeilingMillis = 4_000L

/**
 * The splash mark's box. Its silhouette is 0.9 of this, 165.6dp, which is what
 * `drawable/splash_vessel` is drawn at — change one and the other has to follow, or the mark jumps
 * size at the handover. It also has a ceiling: Android 12+ shows only the middle 192dp circle of
 * that icon, and the mark reaches 0.523 of its height from centre, so ~184dp is the most it gets.
 */
private val LaunchSplashLogoSize = 184.dp

/** `@color/miruro_bg`, the colour of the window and the system splash before this composes. */
private val LaunchWindowColor = Color(0xFF0B0B0F)

/** How long the background takes to move from [LaunchWindowColor] to the theme's. */
private const val LaunchSplashBlendMillis = 500f

/** Matches `drawable/splash_vessel`'s white at 0.10. */
private val LaunchVesselColor = Color.White.copy(alpha = 0.10f)

@Composable
fun AppLaunchSplash(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnFinished by rememberUpdatedState(onFinished)
    var filled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withTimeoutOrNull(LaunchSplashCeilingMillis) { snapshotFlow { filled }.first { it } }
        delay(LaunchSplashHoldMillis.toLong())
        latestOnFinished()
    }

    // Starts on the window colour the system splash (and, before Android 12, the bare window) was
    // showing, and settles into the theme's, which the viewer may have set to anything — pure
    // black on AMOLED — so arriving on it directly was a visible step at the handover. Counted in
    // frames like the fill, because a wall-clock fade finished during the startup stall and landed
    // as a single jump.
    val themeBackground = MaterialTheme.colorScheme.background
    val blend = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        cappedFrameSteps { step ->
            blend.floatValue = (blend.floatValue + step / LaunchSplashBlendMillis).coerceAtMost(1f)
            blend.floatValue < 1f
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind { drawRect(lerp(LaunchWindowColor, themeBackground, blend.floatValue)) },
        contentAlignment = Alignment.Center,
    ) {
        // One size everywhere, in real rather than interface-scaled dp: it has to land exactly on
        // the system splash's icon (drawable/splash_vessel), which Android 12+ draws before this
        // composes and which knows neither the screen width nor the viewer's interface size.
        UnscaledUi {
            WaterFillLogoIndicator(
                size = LaunchSplashLogoSize,
                playOnce = true,
                fillDurationMillis = LaunchSplashFillMillis,
                // Neutral, like the system splash's copy of it (drawable/splash_vessel), which
                // cannot know the viewer's theme: the glass stays one colour across the handover
                // and only the liquid carries the accent.
                vesselColor = LaunchVesselColor,
                onFilled = { filled = true },
            )
        }
    }
}
