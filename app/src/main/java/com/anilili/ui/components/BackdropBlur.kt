package com.anilili.ui.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import com.anilili.data.settings.GlassSection
import com.anilili.data.settings.SettingsStore
import com.anilili.ui.adaptive.LocalAppDeviceProfile

/**
 * Whether a live blur can be drawn here: RenderEffect arrived in Android 12, and below it Haze can
 * only lay a flat scrim, which is worse than the painted translucency every caller already has.
 */
val backdropBlurAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Whether [section] draws live glass right now: the viewer has not switched it off in Appearance,
 * the phone can blur at all, and this is not a television. A television is left out even on
 * Android 12+: a box's GPU is a phone's from years ago, and a D-pad interface spends no time
 * scrolling things under chrome, which is the only moment glass is visible.
 */
@Composable
fun glassEnabled(section: GlassSection): Boolean {
    val on by SettingsStore.glass.getValue(section).collectAsState()
    return on && backdropBlurAvailable && !LocalAppDeviceProfile.current.isTv
}

/**
 * Frosted glass over whatever [state]'s `hazeSource` draws, tinted with [tint] on top of the blur.
 * The caller keeps its own clip (Haze draws inside it) and its painted fallback for when
 * [backdropBlurAvailable] is false; with a null [state] this is a no-op.
 *
 * The input is sampled at a third of its resolution: the blur throws that detail away anyway, and
 * on the S25 it took the nav capsule's GPU p90 from 10ms to 6ms with no visible difference.
 *
 * Every area of [state] is drawn, whatever its zIndex. Haze otherwise only draws areas below the
 * source the effect itself sits inside, and every screen sits inside the nav capsule's source in
 * MainActivity — so a genre pill blurring the Home feed drew nothing at all, not even its tint.
 * Each state here holds only the one source it was made for, so there is no parent to filter out.
 */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.backdropBlur(state: HazeState?, tint: Color, radius: Dp = 16.dp): Modifier =
    if (state == null || !backdropBlurAvailable) {
        this
    } else {
        hazeEffect(
            state,
            HazeStyle(
                backgroundColor = Color.Black,
                tint = HazeTint(tint),
                blurRadius = radius,
                noiseFactor = 0f,
            ),
        ) {
            inputScale = HazeInputScale.Fixed(0.33f)
            canDrawArea = { true }
        }
    }
