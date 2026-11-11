package com.anilili.ui.watch

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.anilili.data.settings.SettingsStore

/** Which system bars a player screen wants on screen at a given moment. */
internal enum class PlayerBars {
    /** Not the player's screen to dress: hand the bars back to the rest of the app. */
    ALL,

    /** The status bar keeps the controls company; the navigation bar stays away from the seek bar. */
    STATUS_ONLY,

    /** Nothing over the picture. */
    NONE,
}

/**
 * Held apart from the effect below so the decision can be read — and tested — without a window.
 *
 * @param statusBarOverPlayer the viewer's setting; off means the picture is never shared.
 */
internal fun playerBarsFor(
    immersive: Boolean,
    controlsVisible: Boolean,
    isTv: Boolean,
    statusBarOverPlayer: Boolean,
): PlayerBars = when {
    !immersive -> PlayerBars.ALL
    controlsVisible && !isTv && statusBarOverPlayer -> PlayerBars.STATUS_ONLY
    else -> PlayerBars.NONE
}

/**
 * The window policy both player screens share: which system bars show over the picture, and how the
 * window sits against a display cutout while they come and go.
 *
 * @param activity the window's activity, or null when the player is not hosted by one.
 * @param immersive whether the player owns the whole screen. False leaves the bars alone.
 * @param controlsVisible whether the player has its own chrome up; the status bar rides along.
 * @param isTv a television has no bars to show and stays fully immersive.
 */
@Composable
internal fun PlayerSystemBarsEffect(
    activity: Activity?,
    immersive: Boolean,
    controlsVisible: Boolean,
    isTv: Boolean,
) {
    val statusBarOverPlayer by SettingsStore.playerStatusBar.collectAsState()

    // The picture used to change size under the tap that raised the controls. Nothing about the
    // layout moved: in the default cutout mode the system re-frames the window whenever a bar
    // appears or goes — extending it under the camera hole while a bar covers that strip, and
    // laying it out clear of the hole once nothing does. Claiming the cutout for as long as the
    // player is on screen makes the window one size in both states, so the video stays put.
    DisposableEffect(activity, immersive, isTv) {
        val window = activity?.window
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        val previousMode = if (supported) window?.attributes?.layoutInDisplayCutoutMode else null
        if (window != null && supported && !isTv && immersive) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        onDispose {
            if (window != null && previousMode != null) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = previousMode
                }
            }
        }
    }

    // The status bar keeps the player's controls company: the tap that raises the controls brings
    // the clock, signal and battery back with them, and both leave together when the controls time
    // out. Without it the only way to read the time mid-episode was to drag the shade down over the
    // picture, which the gesture overlay took for a brightness/volume drag. Viewers who would
    // rather have nothing but the video turn it off in Settings. The navigation bar is never
    // invited — it lands on top of the seek bar.
    LaunchedEffect(activity, immersive, controlsVisible, isTv, statusBarOverPlayer) {
        val window = activity?.window ?: return@LaunchedEffect
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        insets.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        when (playerBarsFor(immersive, controlsVisible, isTv, statusBarOverPlayer)) {
            PlayerBars.ALL -> insets.show(WindowInsetsCompat.Type.systemBars())
            PlayerBars.STATUS_ONLY -> {
                insets.hide(WindowInsetsCompat.Type.navigationBars())
                insets.show(WindowInsetsCompat.Type.statusBars())
            }
            PlayerBars.NONE -> insets.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
