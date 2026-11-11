package com.anilili.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import com.anilili.data.settings.SettingsStore
import kotlin.math.roundToInt

/**
 * Pulls the whole app in from the screen's edges by the viewer's overscan setting.
 *
 * Some boxes scale their output past the panel, so 5–10% of every edge is drawn but never seen:
 * the top navigation loses its tabs, the player its clock and seek bar. Nothing on the device can
 * measure that, so the viewer sets it by eye, and it moves *everything*, the video included — a
 * box that crops the menus crops the picture just as much. That also makes `TV_SAFE_AREA_INSET`
 * and this additive rather than overlapping: the safe-area inset is the margin a correctly set-up
 * television still crops, this is the box's mistake on top of it.
 *
 * The inset is a share of the screen, not a dp count, because the crop is: the same box loses the
 * same fraction at 720p and 4K. Dialogs are windows of their own and are not inset, but they are
 * centred cards well clear of the edges, so they need it least.
 *
 * Wrap the root, outside everything that measures. The margin shows the window's own background,
 * which follows the Appearance canvas.
 */
@Composable
fun AppOverscan(content: @Composable () -> Unit) {
    // Same question, same signals as AppUiScale: the box that needs this most is the one only a
    // fallback signal recognises.
    val isTv = rememberIsTelevision()
    val percent by SettingsStore.tvOverscanPercent.collectAsState()
    val fraction = if (isTv) percent / 100f else 0f
    Box(Modifier.fillMaxSize().overscanInset(fraction)) {
        content()
    }
}

private fun Modifier.overscanInset(fraction: Float): Modifier = layout { measurable, constraints ->
    val dx = if (constraints.hasBoundedWidth) (constraints.maxWidth * fraction).roundToInt() else 0
    val dy = if (constraints.hasBoundedHeight) (constraints.maxHeight * fraction).roundToInt() else 0
    val placeable = measurable.measure(constraints.offset(-2 * dx, -2 * dy))
    layout(placeable.width + 2 * dx, placeable.height + 2 * dy) {
        placeable.place(dx, dy)
    }
}
