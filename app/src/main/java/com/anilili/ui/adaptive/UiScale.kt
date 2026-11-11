package com.anilili.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.anilili.data.settings.SettingsStore

/**
 * The interface scale in force for the surrounding content; 1f everywhere the viewer has not asked
 * for a different size, and everywhere but television.
 *
 * Read this only to undo the scale ([UnscaledUi]) or to convert a fixed count into a scaled one.
 * Nothing needs it to size itself: [AppUiScale] applies the scale to the density, so every `dp`
 * and `sp` in the app already carries it.
 */
val LocalUiScale = staticCompositionLocalOf { 1f }

/**
 * Applies the viewer's chosen interface size to everything drawn inside.
 *
 * This works by overriding the density rather than by scaling a set of named size tokens. The TV
 * layouts hold their sizes in three unrelated places — [AppDeviceProfile], per-file constants like
 * `TvMediaCardWidth`, and several hundred literals inline in the composables (the hero's width, its
 * title's font size, every button's content padding). A token-scaling approach reaches only the
 * first of those, so the cards would grow while the hero and the buttons stayed put. Density is the
 * one lever that moves all three at once, and it needs no edit in any component.
 *
 * Wrap the whole composition. Television is the only form factor that scales: a phone or tablet is
 * held at arm's length and already honours the system's display-size and font-size settings, which
 * a television does not offer at all.
 */
@Composable
fun AppUiScale(content: @Composable () -> Unit) {
    // Not `rememberAppDeviceProfile`: this composable is what *provides* the scale that profile
    // reads, so asking it here would be circular. It asks the same question of the same signals
    // instead — which it has to, or a box caught only by a fallback signal would get the television
    // layout from the profile and a scale of 1f from here, and the one setting written for people
    // sitting across a room from that box would quietly do nothing.
    val isTv = rememberIsTelevision()
    val chosen by SettingsStore.uiScale.collectAsState()
    val scale = if (isTv) chosen.factor else 1f
    val base = LocalDensity.current
    CompositionLocalProvider(
        // fontScale is left alone: the system's own font setting is a separate choice by the same
        // viewer, and multiplying the density already carries text along with everything else.
        LocalDensity provides remember(base, scale) { Density(base.density * scale, base.fontScale) },
        LocalUiScale provides scale,
        content = content,
    )
}

/**
 * Restores the device's real density inside a subtree, cancelling [AppUiScale].
 *
 * The player is deliberately exempt. Its overlays are tuned against the picture behind them and
 * against the television's overscan, neither of which changes when the viewer picks a different
 * interface size — growing the controls would push them into the cropped edge that
 * `TV_SAFE_AREA_INSET` exists to stay clear of.
 */
@Composable
fun UnscaledUi(content: @Composable () -> Unit) {
    val scale = LocalUiScale.current
    val scaled = LocalDensity.current
    val device = LocalAppDeviceProfile.current
    CompositionLocalProvider(
        LocalDensity provides remember(scaled, scale) { Density(scaled.density / scale, scaled.fontScale) },
        LocalUiScale provides 1f,
        // The profile's grid columns are scaled counts, so it has to come back out of scale too or
        // the player's episode grid would keep the browse surface's column count.
        LocalAppDeviceProfile provides remember(device) { device.copy(uiScale = 1f) },
        content = content,
    )
}
