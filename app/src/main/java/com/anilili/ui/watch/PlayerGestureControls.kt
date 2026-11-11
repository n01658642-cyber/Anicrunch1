package com.anilili.ui.watch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.anilili.data.settings.MIN_PLAYER_BRIGHTNESS
import com.anilili.data.settings.PLAYER_BRIGHTNESS_FOLLOW_SYSTEM
import com.anilili.data.settings.SettingsStore
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private sealed interface GestureLevel {
    val fraction: Float

    data class Brightness(override val fraction: Float) : GestureLevel
    data class Volume(override val fraction: Float) : GestureLevel
}

internal enum class GestureZone { Brightness, Volume }

internal enum class GestureIndicatorSide { Left, Right }

private enum class GestureDragAxis { Horizontal, Vertical }

private data class SeekGesture(
    val targetMs: Long,
    val deltaMs: Long,
    val durationMs: Long,
)

private const val SEEK_MS_PER_SCREEN = 120_000L

/**
 * Touch-gesture overlay shared by the native and embed players. A vertical drag anywhere in the
 * left half of the screen scrubs brightness (the Activity window's `screenBrightness`); anywhere
 * in the right half it scrubs volume (the media audio stream, which both ExoPlayer and the WebView
 * route through). A transient slider indicator shows the level while dragging. A horizontal drag
 * anywhere on the picture previews a relative seek and commits it when the finger is released.
 *
 * Taps and double-taps are handled here too so a single pointer handler owns the surface: the drag
 * detector and the tap detectors can't be separate `pointerInput`s without one consuming the down
 * the other needs. Double-tap always toggles playback. The down is always consumed, so the
 * player/page beneath never sees the touch.
 */
@Composable
internal fun PlayerGestureControls(
    modifier: Modifier = Modifier,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    onTap: (() -> Unit)? = null,
    onDoubleTap: ((PlayerDoubleTapZone) -> Unit)? = null,
    onSeek: ((Long) -> Unit)? = null,
    onHoldSpeed: ((active: Boolean) -> Unit)? = null,
    /** The temporary speed and its dedicated switch; neither depends on the swipe settings. */
    holdSpeed: Float = 2f,
    holdSpeedEnabled: Boolean = true,
    /**
     * Brightness and volume. Turning these off deliberately leaves the overlay itself in place: it
     * is what reveals the controls on tap, and on embed players it is also what stops the page
     * seeing touches at all, which keeps tap-hijack ads and the provider's own chrome out of the
     * way. Only the vertical drags go quiet.
     */
    dragGestures: Boolean = true,
    /**
     * The horizontal drag-to-seek, switched separately from [dragGestures]: it is the drag a thumb
     * triggers by accident on the way to a tap, and it moves your place in the episode when it
     * does. Off, a sideways drag is still a drag — it claims the axis and is not delivered as a
     * tap — it simply does not touch the position.
     */
    slideToSeek: Boolean = true,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnHoldSpeed by rememberUpdatedState(onHoldSpeed)
    val currentHoldSpeed by rememberUpdatedState(holdSpeed)
    val currentPositionMs by rememberUpdatedState(positionMs)
    val currentDurationMs by rememberUpdatedState(durationMs)

    var level by remember { mutableStateOf<GestureLevel?>(null) }
    var seekGesture by remember { mutableStateOf<SeekGesture?>(null) }
    var levelTick by remember { mutableIntStateOf(0) }
    var doubleTapSeek by remember { mutableStateOf<SeekGesture?>(null) }
    var doubleTapZone by remember { mutableStateOf(PlayerDoubleTapZone.PlayPause) }
    var doubleTapBaseMs by remember { mutableLongStateOf(0L) }
    var doubleTapTick by remember { mutableIntStateOf(0) }
    var activeHoldSpeed by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(levelTick) {
        if (level != null) {
            delay(700)
            level = null
        }
    }
    LaunchedEffect(doubleTapTick) {
        if (doubleTapSeek != null) {
            delay(800)
            doubleTapSeek = null
        }
    }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(activity, audioManager, dragGestures, slideToSeek, holdSpeedEnabled, holdSpeed) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val zone = if (startsInShadePullStrip(down.position.y, SHADE_PULL_STRIP.toPx())) {
                            null
                        } else {
                            playerGestureZone(
                                xPx = down.position.x,
                                widthPx = size.width.toFloat(),
                                dragGestures = dragGestures,
                            )
                        }
                        var value = when (zone) {
                            GestureZone.Brightness -> readBrightness(activity)
                            GestureZone.Volume -> readVolume(audioManager)
                            null -> 0f
                        }
                        val seekStartPositionMs = currentPositionMs
                        val seekDurationMs = currentDurationMs
                        var dragAxis: GestureDragAxis? = null
                        var holding = false
                        var up: PointerInputChange? = null
                        // The long-press deadline is anchored to the down, not to the last event,
                        // so finger tremor (which streams sub-slop moves) can't keep resetting it.
                        val holdDeadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis

                        while (true) {
                            val event = if (
                                dragAxis == null &&
                                !holding &&
                                canStartHoldSpeed(holdSpeedEnabled, currentOnHoldSpeed != null, currentHoldSpeed)
                            ) {
                                val remaining = holdDeadline - android.os.SystemClock.uptimeMillis()
                                if (remaining <= 0) {
                                    null
                                } else {
                                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                // A stationary press outlived the long-press window: temporarily use
                                // the viewer's chosen speed until the pointer is released.
                                holding = true
                                activeHoldSpeed = currentHoldSpeed
                                currentOnHoldSpeed?.invoke(true)
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                up = change
                                break
                            }
                            if (dragAxis == null && !holding) {
                                val dy = change.position.y - down.position.y
                                val dx = change.position.x - down.position.x
                                dragAxis = when {
                                    abs(dx) > slop && abs(dx) > abs(dy) -> GestureDragAxis.Horizontal
                                    abs(dy) > slop && abs(dy) > abs(dx) -> GestureDragAxis.Vertical
                                    else -> null
                                }
                            }
                            when (dragAxis) {
                                GestureDragAxis.Horizontal -> {
                                    if (canSlideSeek(slideToSeek, currentOnSeek != null, seekDurationMs)) {
                                        val target = playerSlideSeekTarget(
                                            startPositionMs = seekStartPositionMs,
                                            durationMs = seekDurationMs,
                                            horizontalDragPx = change.position.x - down.position.x,
                                            widthPx = size.width.toFloat(),
                                        )
                                        seekGesture = SeekGesture(
                                            targetMs = target,
                                            deltaMs = target - seekStartPositionMs,
                                            durationMs = seekDurationMs,
                                        )
                                    }
                                    change.consume()
                                }
                                GestureDragAxis.Vertical -> {
                                    // A vertical drag started in the middle still counts as a drag
                                    // so releasing it cannot accidentally land as a tap.
                                    if (zone != null) {
                                        val delta = -change.positionChange().y / size.height.toFloat()
                                        value = (value + delta).coerceIn(0f, 1f)
                                        when (zone) {
                                            GestureZone.Brightness -> {
                                                applyBrightness(activity, value)
                                                level = GestureLevel.Brightness(value)
                                            }
                                            GestureZone.Volume -> {
                                                applyVolume(audioManager, value)
                                                level = GestureLevel.Volume(value)
                                            }
                                        }
                                        levelTick++
                                    }
                                    change.consume()
                                }
                                null -> Unit
                            }
                            if (holding) change.consume()
                        }

                        if (holding) {
                            activeHoldSpeed = null
                            currentOnHoldSpeed?.invoke(false)
                            up?.consume()
                            return@awaitEachGesture
                        }
                        if (dragAxis != null) {
                            // One write at the release, not one per touch event: the level the
                            // finger came to rest on is the viewer's standing answer, and every
                            // preference write re-reads the whole file.
                            if (dragAxis == GestureDragAxis.Vertical && zone == GestureZone.Brightness) {
                                SettingsStore.setPlayerBrightness(value)
                            }
                            val completedSeek = seekGesture
                            seekGesture = null
                            if (dragAxis == GestureDragAxis.Horizontal && up != null) {
                                completedSeek?.let { currentOnSeek?.invoke(it.targetMs) }
                            }
                            up?.consume()
                            return@awaitEachGesture
                        }
                        if (up == null) return@awaitEachGesture
                        up.consume()
                        if (currentOnDoubleTap == null) {
                            currentOnTap?.invoke()
                            return@awaitEachGesture
                        }
                        // Wait one double-tap window for a second press before committing to a
                        // single tap, so a double-tap doesn't also fire the single-tap action.
                        val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                            awaitFirstDown(requireUnconsumed = false)
                        }
                        if (secondDown == null) {
                            currentOnTap?.invoke()
                        } else {
                            secondDown.consume()
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull { it.id == secondDown.id } ?: break
                                c.consume()
                                if (!c.pressed) break
                            }
                            val tapZone = playerDoubleTapZone(
                                xPx = secondDown.position.x,
                                widthPx = size.width.toFloat(),
                            )
                            if (tapZone != PlayerDoubleTapZone.PlayPause && currentOnSeek != null) {
                                val step = if (tapZone == PlayerDoubleTapZone.Forward) {
                                    DOUBLE_TAP_SEEK_MS
                                } else {
                                    -DOUBLE_TAP_SEEK_MS
                                }
                                val chained = doubleTapSeek
                                // A chain seeks from where it started, not from the position the
                                // earlier hops already moved to.
                                val base = if (chained != null) doubleTapBaseMs else currentPositionMs
                                val delta = (chained?.deltaMs ?: 0L) + step
                                val target = playerDoubleTapSeekTarget(base, delta, currentDurationMs)
                                doubleTapBaseMs = base
                                doubleTapZone = tapZone
                                doubleTapSeek = SeekGesture(target, delta, currentDurationMs)
                                doubleTapTick++
                                currentOnSeek?.invoke(target)
                            }
                            currentOnDoubleTap?.invoke(tapZone)
                        }
                    }
                },
        )

        // Put the level opposite the hand doing the drag. Brightness is controlled on the left, so
        // its level appears on the right; volume is controlled on the right, so its level appears
        // on the left. This keeps the thumb from covering the only feedback the gesture gives.
        level?.let {
            val side = when (gestureIndicatorSide(it.toGestureZone())) {
                GestureIndicatorSide.Left -> Alignment.CenterStart
                GestureIndicatorSide.Right -> Alignment.CenterEnd
            }
            GestureLevelIndicator(it, Modifier.align(side).padding(horizontal = 28.dp))
        }
        seekGesture?.let { SeekGestureIndicator(it, Modifier.align(Alignment.Center)) }
        doubleTapSeek?.let { seek ->
            val side = if (doubleTapZone == PlayerDoubleTapZone.Rewind) {
                Alignment.CenterStart
            } else {
                Alignment.CenterEnd
            }
            SeekGestureIndicator(seek, Modifier.align(side).padding(horizontal = 24.dp))
        }
        activeHoldSpeed?.let { speed ->
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    speed.formatPlaybackSpeed(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(
                    Icons.Default.Speed,
                    contentDescription = "Playing at ${speed.formatPlaybackSpeed()} speed",
                    tint = Color.White,
                    modifier = Modifier.padding(start = 4.dp).size(16.dp),
                )
            }
        }
    }
}

private fun GestureLevel.toGestureZone(): GestureZone = when (this) {
    is GestureLevel.Brightness -> GestureZone.Brightness
    is GestureLevel.Volume -> GestureZone.Volume
}

/** The HUD sits opposite the gesture so the viewer's thumb cannot hide the changing level. */
internal fun gestureIndicatorSide(zone: GestureZone): GestureIndicatorSide = when (zone) {
    GestureZone.Brightness -> GestureIndicatorSide.Right
    GestureZone.Volume -> GestureIndicatorSide.Left
}

/**
 * Which vertical drag a touch at [xPx] controls: the left half of the picture is brightness and the
 * right half is volume, split down the middle.
 *
 * Narrow edge strips were easy to miss mid-episode, and widening them to full halves costs nothing:
 * the caller's axis check hands every horizontal drag to seek first, so the only gesture a zone
 * ever claims is a deliberate vertical one. Returns null when drag gestures are switched off, and
 * for a zero-width surface, where there is no meaningful side to pick.
 */
/** Where a double-tap landed: the sides seek, the middle keeps toggling playback. */
internal enum class PlayerDoubleTapZone { Rewind, PlayPause, Forward }

/**
 * The middle third stays play/pause because that is what double-tap did before, and because a
 * thumb aiming for "pause" lands near the centre. The seek zones are the outer thirds, wide enough
 * to hit without looking.
 */
internal fun playerDoubleTapZone(xPx: Float, widthPx: Float): PlayerDoubleTapZone = when {
    widthPx <= 0f || !widthPx.isFinite() || !xPx.isFinite() -> PlayerDoubleTapZone.PlayPause
    xPx < widthPx * 0.35f -> PlayerDoubleTapZone.Rewind
    xPx > widthPx * 0.65f -> PlayerDoubleTapZone.Forward
    else -> PlayerDoubleTapZone.PlayPause
}

/**
 * Where a chain of double-taps lands. Each tap adds another [DOUBLE_TAP_SEEK_MS] to the total
 * rather than seeking from wherever playback has since drifted to, so four quick taps move forty
 * seconds and not four unrelated ten-second hops.
 */
internal fun playerDoubleTapSeekTarget(
    chainBaseMs: Long,
    accumulatedDeltaMs: Long,
    durationMs: Long,
): Long {
    val target = chainBaseMs + accumulatedDeltaMs
    return if (durationMs > 0L) target.coerceIn(0L, durationMs) else target.coerceAtLeast(0L)
}

internal const val DOUBLE_TAP_SEEK_MS = 10_000L

/**
 * The strip along the top edge a downward drag is pulled from to open the notification shade.
 * While the player's controls are up the status bar is up with them and its own window takes these
 * touches, but once both hide they arrive here instead — and reading one as a vertical drag is how
 * reaching for the shade mid-episode used to dim the screen or drop the volume. Drags starting
 * here claim no brightness/volume zone; the seek drag and the taps are untouched.
 */
internal val SHADE_PULL_STRIP = 36.dp

internal fun startsInShadePullStrip(yPx: Float, stripPx: Float): Boolean =
    yPx.isFinite() && stripPx > 0f && yPx < stripPx

internal fun canStartHoldSpeed(enabled: Boolean, hasHandler: Boolean, speed: Float): Boolean =
    enabled && hasHandler && speed.isFinite() && speed > 0f

internal fun playerGestureZone(xPx: Float, widthPx: Float, dragGestures: Boolean): GestureZone? = when {
    !dragGestures -> null
    widthPx <= 0f || !widthPx.isFinite() || !xPx.isFinite() -> null
    xPx < widthPx / 2f -> GestureZone.Brightness
    else -> GestureZone.Volume
}

/**
 * Whether a sideways drag scrubs: the viewer's setting, a caller that can seek at all, and a
 * duration to scrub through. Live streams and anything still resolving report no duration, and a
 * preview drawn against a length nobody knows would move the thumb over nothing.
 */
internal fun canSlideSeek(slideToSeek: Boolean, hasSeekHandler: Boolean, durationMs: Long): Boolean =
    slideToSeek && hasSeekHandler && durationMs > 0L

/** A full-width swipe moves two minutes; shorter movement scales linearly and clamps to the video. */
internal fun playerSlideSeekTarget(
    startPositionMs: Long,
    durationMs: Long,
    horizontalDragPx: Float,
    widthPx: Float,
): Long {
    val start = if (durationMs > 0L) startPositionMs.coerceIn(0L, durationMs) else startPositionMs.coerceAtLeast(0L)
    if (durationMs <= 0L || widthPx <= 0f || !widthPx.isFinite() || !horizontalDragPx.isFinite()) return start
    val target = start.toDouble() + horizontalDragPx / widthPx * SEEK_MS_PER_SCREEN
    return target.coerceIn(0.0, durationMs.toDouble()).toLong()
}

@Composable
internal fun PlaybackGestureIndicator(isPlaying: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(76.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(38.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
            contentDescription = if (isPlaying) "Playing" else "Paused",
            tint = Color.White,
            modifier = Modifier.size(38.dp),
        )
    }
}

@Composable
private fun GestureLevelIndicator(level: GestureLevel, modifier: Modifier = Modifier) {
    // A fixed width, and digits that are all one width. The card used to wrap its percentage, so
    // every change of number — "9%" to "10%", and "11%" to "18%" too, since proportional digits
    // differ — resized a card pinned to the screen edge by one side, and the icon and bar
    // re-centred with it: the whole indicator shook under a steady drag.
    Column(
        modifier
            .width(GestureLevelIndicatorWidth)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val icon = when (level) {
            is GestureLevel.Brightness -> Icons.Default.BrightnessHigh
            is GestureLevel.Volume ->
                if (level.fraction <= 0.001f) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp
        }
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .width(6.dp)
                .height(120.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.25f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(level.fraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "${(level.fraction * 100).roundToInt()}%",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
}

/** Wide enough for "100%" with the old 18 dp margins either side. */
private val GestureLevelIndicatorWidth = 72.dp

@Composable
private fun SeekGestureIndicator(seek: SeekGesture, modifier: Modifier = Modifier) {
    val forward = seek.deltaMs >= 0L
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (forward) Icons.Default.FastForward else Icons.Default.FastRewind,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
            Text(
                text = (if (forward) "+" else "−") + formatPlayerTime(abs(seek.deltaMs)),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Text(
            text = "${formatPlayerTime(seek.targetMs)} / ${formatPlayerTime(seek.durationMs)}",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Clears any brightness override this overlay set, restoring the system brightness. Call when the
 * player leaves the screen so a dim setting chosen for a night episode doesn't dim the whole app.
 */
internal fun resetPlayerBrightness(context: Context) {
    val window = context.findActivity()?.window ?: return
    window.attributes = window.attributes.apply {
        screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
}

/**
 * Holds the remembered brightness on the player's window for as long as the player is composed, and
 * hands the screen back to the system brightness on the way out.
 *
 * Both players mount this, so an episode opens at the level the last brightness swipe chose no
 * matter which one plays it, and so does the first episode after a cold start — the level is read
 * from the preferences, not from whatever the outgoing window happened to be set to.
 *
 * [enabled] is the brightness-and-volume gesture switch. It gates the override as well as the
 * gesture that writes it: without that, a viewer who dimmed the picture and then turned the swipes
 * off would be left with a dark player and nothing in the app able to brighten it.
 */
@Composable
internal fun PlayerBrightnessOverride(enabled: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val brightness by SettingsStore.playerBrightness.collectAsState()
    DisposableEffect(context) { onDispose { resetPlayerBrightness(context) } }
    LaunchedEffect(activity, enabled, brightness) {
        if (enabled && brightness != PLAYER_BRIGHTNESS_FOLLOW_SYSTEM) {
            applyBrightness(activity, brightness)
        } else {
            resetPlayerBrightness(context)
        }
    }
}

/**
 * A visible volume control — mute-toggle icon plus a draggable slider — for the player control
 * bars, so volume is discoverable rather than only reachable through the edge-drag gesture. Drives
 * the media audio stream (shared by ExoPlayer and the WebView) and polls it so the thumb tracks
 * the hardware volume keys, except while the user is actively dragging.
 *
 * The [modifier] sizes the row; the slider takes the remaining width. [onInteract] fires on every
 * change so the caller can keep its controls from auto-hiding mid-adjustment.
 */
@Composable
internal fun MediaVolumeSlider(
    modifier: Modifier = Modifier,
    showPercentLabel: Boolean = false,
    onInteract: () -> Unit = {},
) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    var volume by remember { mutableFloatStateOf(readVolume(audioManager)) }
    var lastAudible by remember { mutableFloatStateOf(volume.takeIf { it > 0f } ?: 0.5f) }
    var lastInteractMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(audioManager) {
        while (true) {
            delay(500)
            if (System.currentTimeMillis() - lastInteractMs > 700) {
                volume = readVolume(audioManager)
            }
        }
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val extraFocus1 = rememberFocusFill()
        // Sized to the settings panel's icon slot rather than M3's 48 dp touch minimum, which
        // pushed this one icon right of the speed and quality icons below it.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        IconButton(
            colors = extraFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
            onClick = {
                lastInteractMs = System.currentTimeMillis()
                if (volume > 0.001f) {
                    lastAudible = volume
                    applyVolume(audioManager, 0f)
                    volume = 0f
                } else {
                    val target = lastAudible.coerceAtLeast(0.1f)
                    applyVolume(audioManager, target)
                    volume = target
                }
                onInteract()
            }, modifier = Modifier.size(PlayerSliderIconSize).focusFill(extraFocus1, CircleShape),) {
            Icon(volumeIcon(volume), contentDescription = if (volume > 0.001f) "Mute" else "Unmute", tint = extraFocus1.content(Color.White))
        }
        }
        Slider(
            value = volume,
            onValueChange = {
                lastInteractMs = System.currentTimeMillis()
                volume = it
                applyVolume(audioManager, it)
                onInteract()
            },
            colors = midnightSliderColors(),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = PlayerSliderTrackPadding)
                .semantics { contentDescription = "Volume" }
                // Material3's Slider ignores D-pad keys, so on TV the volume could never be
                // changed here. Left/right nudge by 5%; at the ends the event is released so
                // focus can still escape (left at 0% reaches the mute button).
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val delta = when (event.key) {
                        Key.DirectionLeft -> -0.05f
                        Key.DirectionRight -> +0.05f
                        else -> return@onPreviewKeyEvent false
                    }
                    val next = (volume + delta).coerceIn(0f, 1f)
                    if (next == volume) {
                        false
                    } else {
                        lastInteractMs = System.currentTimeMillis()
                        volume = next
                        applyVolume(audioManager, next)
                        onInteract()
                        true
                    }
                },
        )
        if (showPercentLabel) {
            PlayerSliderValue("${(volume * 100).roundToInt()}%", color = Color.White)
        }
    }
}

private fun volumeIcon(fraction: Float): ImageVector = when {
    fraction <= 0.001f -> Icons.AutoMirrored.Filled.VolumeOff
    fraction < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
    else -> Icons.AutoMirrored.Filled.VolumeUp
}

private fun readBrightness(activity: Activity?): Float {
    val attr = activity?.window?.attributes?.screenBrightness ?: return 0.5f
    if (attr >= 0f) return attr.coerceIn(0f, 1f)
    // No window override set yet: seed from the system brightness so the first drag continues
    // from what the user currently sees rather than jumping.
    val system = runCatching {
        Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrNull() ?: 128
    return (system / 255f).coerceIn(0f, 1f)
}

private fun applyBrightness(activity: Activity?, value: Float) {
    val window = activity?.window ?: return
    // Floor above zero so the screen never goes fully black and strand the user in the dark.
    window.attributes = window.attributes.apply {
        screenBrightness = value.coerceIn(MIN_PLAYER_BRIGHTNESS, 1f)
    }
}

private fun readVolume(audioManager: AudioManager?): Float {
    audioManager ?: return 0.5f
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= 0) return 0.5f
    return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

private fun applyVolume(audioManager: AudioManager?, value: Float) {
    audioManager ?: return
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= 0) return
    audioManager.setStreamVolume(
        AudioManager.STREAM_MUSIC,
        (value * max).roundToInt().coerceIn(0, max),
        0,
    )
}
