package com.anilili.ui.watch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anilili.R
import com.anilili.data.settings.SettingsStore
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import kotlin.math.roundToInt

/**
 * The gap between the seek bar and a floating action sitting above it.
 *
 * The anchor it is added to is measured and reported rather than assumed: a constant per form
 * factor was tried and put the skip button on top of the transport row in the inline player,
 * because the bar there is far shorter than the fullscreen one it had been sized against.
 */
internal val ControlsBarActionGap = 12.dp

/**
 * The same gap in the short inline player ([PlayerLayoutMetrics.compactHeight]), where the skip
 * button shares a frame barely 200dp tall with the transport row and the bar under it. The 12dp
 * above was the margin that put it level with "Next episode" on a 360dp-wide phone.
 */
internal val CompactControlsBarActionGap = 6.dp

/** How tall the floating skip action is drawn in that short player, against the button's own 40dp. */
internal val CompactSkipActionHeight = 28.dp

/**
 * Hands [content] whether the frame it floats in is the short inline player, so the skip button
 * can come down to suit it. The host draws that button outside the controls scaffold, which is the
 * only other thing that knows the frame's size; this reads it the same way the scaffold does, at
 * the same [PlayerChromeBox] scale, so the seek anchor the scaffold reports is in this frame's units.
 */
@Composable
internal fun BoxScope.PlayerFloatingActionFrame(content: @Composable BoxScope.(compactHeight: Boolean) -> Unit) {
    PlayerChromeBox(Modifier.matchParentSize()) {
        content(playerLayoutMetrics(maxWidth.value.toInt(), maxHeight.value.toInt()).compactHeight)
    }
}

/**
 * The narrowest frame the touch controls are laid out for. A narrower one draws them smaller, by
 * [playerChromeScale], instead of laying them out in a frame they do not fit.
 *
 * The inline player is the phone's width, and Android's Display size setting decides how many dp
 * that is: a Pixel at "Large" or "Largest", or a 1080px phone that starts out at 360dp, hands the
 * player a frame of 320-384dp by 180-216dp. Five transport buttons, the top-right icons, the skip
 * button and the seek bar do not fit in that at full size — "+85s" landed on "Next episode", and
 * lifting the row out of its way only drove it into "Add to My List" instead. Measured on the phone
 * emulator across 288-540dp, every frame from 392dp up lays out with nothing touching, so below
 * this the chrome is drawn as it is at 400dp: the size it would have at a smaller display size.
 */
internal val PlayerChromeMinWidth = 400.dp

/** Beyond this the controls would be too small to press; a frame that narrow is picture-in-picture. */
private const val MIN_PLAYER_CHROME_SCALE = 0.75f

/** The factor [PlayerChromeBox] draws the controls at for a frame [frameWidth] wide. */
internal fun playerChromeScale(frameWidth: Dp): Float =
    if (frameWidth <= 0.dp || frameWidth >= PlayerChromeMinWidth) {
        1f
    } else {
        (frameWidth / PlayerChromeMinWidth).coerceAtLeast(MIN_PLAYER_CHROME_SCALE)
    }

/**
 * A frame for the touch controls that measures at least [PlayerChromeMinWidth] wide to its
 * [content], by drawing everything in it at [playerChromeScale]. Text keeps the viewer's font size
 * relative to the controls around it. Fullscreen, tablets and TV are wider than the minimum, so
 * nothing there moves.
 */
@Composable
internal fun PlayerChromeBox(modifier: Modifier, content: @Composable BoxWithConstraintsScope.() -> Unit) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val scale = playerChromeScale(maxWidth)
        // Provided even at 1f, so crossing the minimum does not tear down the controls' state.
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
            BoxWithConstraints(Modifier.fillMaxSize(), content = content)
        }
    }
}

/**
 * How far the seek slider sits above the bottom edge, given the measured bar and its top padding.
 *
 * The subtraction goes negative whenever the bar measures shorter than its own top padding, which a
 * resize between two layout tiers produces: leaving picture-in-picture re-measures the bar at the
 * old 331x186dp window while [playerLayoutMetrics] has already switched barTopPadding from 8dp to
 * 24dp. Callers add this to a gap and hand the result to `Modifier.padding`, which throws
 * `IllegalArgumentException: Padding must be non-negative` — a hard crash on the frame that restores
 * the player. Clamped here, at the one place the anchor is produced, so no caller can be handed a
 * distance that does not exist.
 */
internal fun controlsSeekAnchorFor(barHeight: Dp, barTopPadding: Dp): Dp =
    (barHeight - barTopPadding).coerceAtLeast(0.dp)

/** Air left between the transport row and the top of the skip action once the row has risen clear of it. */
private val TransportActionClearance = 4.dp

/**
 * The height Material gives a pressable control whatever it draws. A skip button drawn 28dp tall is
 * laid out 48dp tall with the pill centred in it, so its visible top sits 10dp above its box's.
 */
private val MinTouchTarget = 48.dp

/**
 * How much bottom padding the centred transport row needs to stay clear of the skip action.
 *
 * The host floats "Skip Intro" and "+85s" just above the seek bar at the right edge, and on a phone
 * the row is too wide for the button to sit beside it, so the only way to keep "Next episode" visible
 * is to keep the row above the band the button can occupy. That used to be decided by
 * [PlayerLayoutMetrics.compactHeight] alone, and Android's Display size setting breaks a tier check:
 * at "Small" a Pixel 10 Pro's inline frame measures over 300dp tall, falls out of the compact tier,
 * and the row sat centred in the whole frame again with a full-size button over its right end. At
 * "Large" the frame is so short that nothing fits, which [PlayerChromeBox] answers. So this is
 * worked out from the frame's measured geometry rather than from any tier: the row rises just far
 * enough for its bottom to clear the button's visible top, and never further than the frame has
 * room for.
 *
 * In the compact tier the row still centres in the picture above the bar ([seekAnchor]) when that
 * is higher, which is where 80915d7e put it. [actionHeight] is what [skipActionHeight] reports.
 */
internal fun transportRowBottomPadding(
    frameHeight: Dp,
    rowHeight: Dp,
    seekAnchor: Dp,
    compactHeight: Boolean,
    actionHeight: Dp,
): Dp {
    val gap = if (compactHeight) CompactControlsBarActionGap else ControlsBarActionGap
    // The button's box sits on the gap; what shows is centred in it (see MinTouchTarget).
    val actionTop = seekAnchor + gap + (maxOf(actionHeight, MinTouchTarget) + actionHeight) / 2
    // A row centred over a bottom padding p ends (frameHeight - rowHeight + p) / 2 above the bottom
    // edge; solve that for the band's top plus the clearance.
    val needed = (actionTop + TransportActionClearance) * 2 - frameHeight + rowHeight
    val base = if (compactHeight) seekAnchor else 0.dp
    // Its top stays inside the frame however short the frame gets.
    val most = (frameHeight - rowHeight - TransportActionClearance * 2).coerceAtLeast(0.dp)
    return maxOf(needed, base).coerceIn(0.dp, most)
}

/**
 * How tall the host draws the skip action: [CompactSkipActionHeight] in the short player, otherwise
 * the OutlinedButton's 40dp minimum or its label plus the 6dp padding above and below, whichever is
 * taller. The label wins once the system font size is turned up, and a reserve of a flat 40dp then
 * let the taller button reach the row again.
 */
@Composable
internal fun skipActionHeight(compactHeight: Boolean): Dp {
    if (compactHeight) return CompactSkipActionHeight
    val labelHeight = with(LocalDensity.current) { MaterialTheme.typography.labelLarge.lineHeight.toDp() }
    return maxOf(40.dp, labelHeight + 12.dp)
}

/**
 * How much of the bottom action row has to come down to fit the width it was given, as a fraction.
 *
 * A `Row` that is short of room measures its last child with whatever is left over, and
 * `Modifier.size` honours the incoming constraint rather than its own — so the control at the end of
 * the row is drawn at the leftover instead of at its size. Settings is last in the phone player's
 * row, and on a 360dp-wide window, where the lock button, the clock and six controls share one line,
 * the shortage was about 9dp: the gear came out as 8dp of glyph in a 28dp pill beside 18dp glyphs in
 * 38dp pills. Every other control was exactly right, so it was reported as a gear that is too small
 * — which is what a silent squeeze looks like from the outside.
 *
 * The whole row comes down by the same fraction rather than the last control being clipped: the row
 * then still reads as one size. On the window above, that fraction is 0.96.
 */
internal fun playerActionRowScale(naturalWidthPx: Int, availableWidthPx: Int): Float {
    // Nothing to fit: an empty row is not a row that has to shrink.
    if (naturalWidthPx <= 0) return 1f
    return (availableWidthPx.toFloat() / naturalWidthPx).coerceIn(0f, 1f)
}

/**
 * How much of the episode has been fetched, as a fraction of its length.
 *
 * Clamped to at least the played position: a player that has not reported a buffer yet returns 0,
 * and a bar showing less downloaded than played would read as a fault rather than as "unknown".
 */
internal fun bufferedFraction(bufferedPositionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L || bufferedPositionMs <= 0L) return 0f
    return (bufferedPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * The seek bar's three bands: the whole episode, the part already fetched, and the part played.
 *
 * The middle band is the one viewers look for on a slow source — without it a stalled stream and a
 * paused one look identical. Material3's own track draws two bands only, so the slider takes this
 * as its track slot instead.
 */
@Composable
internal fun BufferedSeekTrack(
    playedFraction: Float,
    bufferedFraction: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    /** Intro/outro windows as start..end fractions; see [playerSeekMarkerFractions]. */
    markerFractions: List<ClosedFloatingPointRange<Float>> = emptyList(),
    trackHeight: Dp = 4.dp,
) {
    val played = Color.White.let { if (enabled) it else it.copy(alpha = 0.38f) }
    val buffered = Color.White.copy(alpha = if (enabled) 0.50f else 0.24f)
    val remaining = Color.White.copy(alpha = if (enabled) 0.30f else 0.16f)
    Canvas(modifier.fillMaxWidth().height(trackHeight)) {
        val y = size.height / 2f
        fun band(color: Color, fraction: Float) {
            if (fraction <= 0f) return
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width * fraction.coerceIn(0f, 1f), y),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
        }
        band(remaining, 1f)
        band(buffered, maxOf(bufferedFraction, playedFraction))
        band(played, playedFraction)
        drawPlayerSeekMarkers(
            fractions = markerFractions,
            left = 0f,
            span = size.width,
            centreY = y,
            strokePx = size.height,
        )
    }
}

/**
 * The shared touch-player chrome: a center transport cluster (prev / −10s / play-pause / +10s /
 * next) over a bottom bar (seek slider, elapsed/total time, and a caller-supplied icon row). Both
 * the native and embed players render this so they are visually identical; each supplies its own
 * wiring and its own trailing icons (the embed has only a settings gear, the native adds subtitle,
 * cast, and fullscreen).
 *
 * [bottomLeftIcons] sits ahead of the elapsed/total time, which is where players conventionally put
 * the screen lock.
 */
@Composable
internal fun PlayerControlsScaffold(
    seriesTitle: String = "",
    episodeTitle: String = "",
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long = 0L,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onInteract: () -> Unit = {},
    /**
     * What the episode is running at, so the clock's finishing time is a wall-clock answer rather
     * than a media-time one — at 2x it is half as far away.
     */
    playbackSpeed: Float = 1f,
    /** The episode's intro/outro/recap windows, drawn on the timeline; empty where none are known. */
    markers: List<PlayerSeekMarker> = emptyList(),
    /**
     * How far the top of the seek bar sits above the bottom edge, so a caller can float an action
     * just clear of the timeline rather than clear of the whole bar.
     */
    onSeekAnchorChanged: (Dp) -> Unit = {},
    /**
     * A Back arrow in the top-left corner. The windowed player has one drawn over it by the watch
     * screen; fullscreen had none, so a landscape viewer's only way out was the system gesture.
     */
    showBack: Boolean = false,
    /** The FILLER tag and its skip, under the episode title; null when the episode is not filler. */
    fillerSkip: PlayerFillerSkip? = null,
    topRightIcons: @Composable RowScope.() -> Unit = {},
    bottomLeftIcons: @Composable RowScope.() -> Unit = {},
    bottomRightIcons: @Composable RowScope.() -> Unit = {},
) {
    // The position under the finger while a scrub is in progress, so the time readout follows the
    // drag; PlayerSeekBar owns the drag itself and commits the seek when the finger lifts.
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    // Read here rather than threaded through both callers: the bar is shared by the native and embed
    // players, and this is a statement about the chrome, not about either source.
    val showPlayerClock by SettingsStore.playerClock.collectAsState()
    PlayerChromeBox(modifier.fillMaxSize()) {
        val metrics = playerLayoutMetrics(maxWidth.value.toInt(), maxHeight.value.toInt())
        val compactHeight = metrics.compactHeight
        // The bottom bar has always carried a scrim; the top row never did, and the title and its
        // icons sat unbacked over whatever the frame happened to be. A bright sky is where that
        // fails, and it fails for the controls the same way it failed for the title text.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(if (compactHeight) 84.dp else 132.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.62f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        // The status bar is on screen for exactly as long as this bar is (see WatchScreen), so the
        // top row sits below it rather than under the clock. The inset is zero everywhere the bar
        // is hidden — TV, and the moment these controls time out — so nothing else shifts.
        val showTitle = !compactHeight && (seriesTitle.isNotBlank() || episodeTitle.isNotBlank())
        if (showBack || showTitle) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    // The arrow's own 48dp touch target carries its inset, so it starts nearer the
                    // edge than the title would on its own.
                    .padding(start = if (showBack) 4.dp else metrics.horizontalPadding, top = if (showBack) 4.dp else 14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (showBack) PlayerBackArrow()
                if (showTitle) {
                    Column(Modifier.padding(top = if (showBack) 10.dp else 0.dp)) {
                        PlayerTitleCard(
                            seriesTitle = seriesTitle,
                            episodeTitle = episodeTitle,
                        )
                        fillerSkip?.let { skip ->
                            PlayerFillerTag(
                                skip = skip,
                                onInteract = onInteract,
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        // The windowed player is too short for a title, and its top-left corner belongs to the
        // watch screen's own Back arrow, so the tag stands alone at the top centre instead.
        if (!showTitle && fillerSkip != null) {
            PlayerFillerTag(
                skip = fillerSkip,
                onInteract = onInteract,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 10.dp),
            )
        }
        // A column rather than a row, with the clock as its first item: the host draws the same
        // readout at this exact anchor while a pause outlives the controls, so anything placed
        // beside the clock instead of under it would slide it sideways every time the bar came up.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                // This bar is the touch one; TV draws its own (see TvPlayerControls).
                .playerClockAnchor(isTv = false),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showPlayerClock) {
                PlayerClockReadout(
                    positionMs = scrubPositionMs ?: positionMs,
                    durationMs = durationMs,
                    speed = playbackSpeed,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalPlayerLayoutMetrics provides metrics) { topRightIcons() }
            }
        }
        val density = LocalDensity.current
        // The bar's measured height above its own bottom edge — the same anchor handed to the host.
        var seekAnchor by remember { mutableStateOf(0.dp) }
        var transportHeight by remember { mutableStateOf(0.dp) }
        Row(
            // Rises out of the band the host floats the skip button in, just above the seek bar.
            // Centred in the frame, the row came down level with it and "Skip Intro" covered "Next
            // episode"; see transportRowBottomPadding for why that is measured and not tiered.
            modifier = Modifier
                .align(Alignment.Center)
                .padding(
                    bottom = transportRowBottomPadding(
                        frameHeight = maxHeight,
                        rowHeight = transportHeight,
                        seekAnchor = seekAnchor,
                        compactHeight = compactHeight,
                        actionHeight = skipActionHeight(compactHeight),
                    ),
                )
                .onSizeChanged { transportHeight = with(density) { it.height.toDp() } },
            horizontalArrangement = Arrangement.spacedBy(metrics.centerGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerControlIconButton("Previous episode", Icons.Default.SkipPrevious, enabled = hasPrevious, onClick = onPrevious)
            PlayerControlIconButton("Rewind 10 seconds", Icons.Default.FastRewind, onClick = onRewind)
            // Play/pause keeps the accent — it and the seek bar are the only things that carry it.
            val extraFocus1 = rememberFocusFill()
            IconButton(
                colors = extraFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                onClick = onPlayPause,
                modifier = Modifier
                    .size(metrics.playButtonSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.24f), CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape).focusFill(extraFocus1, CircleShape),
            ) {
                Icon(
                    painter = painterResource(
                        if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                    ),
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = extraFocus1.content(Color.White),
                    modifier = Modifier.size(metrics.playGlyphSize),
                )
            }
            PlayerControlIconButton("Forward 10 seconds", Icons.Default.FastForward, onClick = onForward)
            PlayerControlIconButton("Next episode", Icons.Default.SkipNext, enabled = hasNext, onClick = onNext)
        }
        // Everything the bar can show — the time row and both icon rows, including the icons that
        // only some sources contribute — hangs below the seek slider, so the reported anchor drops
        // the bar's top padding and measures from the slider down. An action cleared of the slider
        // is cleared of every control underneath it, whichever of them happen to be on screen.
        val barTopPadding = metrics.barTopPadding
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged {
                    seekAnchor = controlsSeekAnchorFor(with(density) { it.height.toDp() }, barTopPadding)
                    onSeekAnchorChanged(seekAnchor)
                }
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        .32f to Color.Black.copy(alpha = 0.68f),
                        1f to Color.Black.copy(alpha = 0.94f),
                    ),
                )
                // The picture keeps the camera hole (see PlayerSystemBarsEffect); the seek bar and
                // the buttons under it do not have to share it. The scrim above is left full-bleed
                // so the inset does not read as a gap in the gradient.
                .displayCutoutPadding()
                .padding(
                    start = metrics.horizontalPadding,
                    end = metrics.horizontalPadding,
                    top = barTopPadding,
                    bottom = 8.dp,
                ),
        ) {
            PlayerSeekBar(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedPositionMs = bufferedPositionMs,
                onSeek = onSeek,
                onInteract = onInteract,
                onScrubChange = { scrubbedMs -> scrubPositionMs = scrubbedMs },
                markers = markers,
                trackHeight = metrics.seekTrackHeight,
                touchHeight = metrics.seekTouchHeight,
            )
            val shownMs = scrubPositionMs ?: positionMs
            if (compactHeight) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CompositionLocalProvider(LocalPlayerLayoutMetrics provides metrics) {
                            bottomLeftIcons()
                        }
                        PlayerTimePill(formatPlayerTime(shownMs), fontSize = metrics.timeTextSize)
                    }
                    PlayerActionRow {
                        CompositionLocalProvider(LocalPlayerLayoutMetrics provides metrics) {
                            bottomRightIcons()
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerTimePill(formatPlayerTime(shownMs), fontSize = metrics.timeTextSize)
                    PlayerTimePill(
                        text = "-${formatPlayerTime((durationMs - shownMs).coerceAtLeast(0L))}",
                        fontSize = metrics.timeTextSize,
                        emphasis = 0.68f,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CompositionLocalProvider(LocalPlayerLayoutMetrics provides metrics) {
                            bottomLeftIcons()
                        }
                    }
                    // Icon-only controls carry their own spacing in the 48dp slot round each circle.
                    PlayerActionRow(horizontalSpacing = if (metrics.showPillLabels) 6.dp else 0.dp) {
                        CompositionLocalProvider(LocalPlayerLayoutMetrics provides metrics) {
                            bottomRightIcons()
                        }
                    }
                }
            }
        }
    }
}

/**
 * The row of actions along the bottom of the bar, drawn to the width it is given.
 *
 * The controls in it are sized once and measured unconstrained first, so the row knows what they
 * want and can fit the whole group into the space it actually has — see [playerActionRowScale]. A
 * plain `Row` cannot be asked that after the fact: by the time it is known to be too narrow, its
 * last child has already been measured short and drawn that way.
 */
@Composable
private fun PlayerActionRow(
    horizontalSpacing: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val spacingPx = with(LocalDensity.current) { horizontalSpacing.roundToPx() }
    Layout(content = content) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val gaps = spacingPx * (placeables.size - 1).coerceAtLeast(0)
        val naturalWidth = placeables.sumOf { it.width } + gaps
        val scale = playerActionRowScale(naturalWidth, constraints.maxWidth)
        val naturalHeight = placeables.maxOfOrNull { it.height } ?: 0
        // The row's own box follows what is drawn, so the bar measures the height it can see and the
        // seek anchor above it stays true.
        val height = (naturalHeight * scale).roundToInt().coerceIn(0, constraints.maxHeight)
        layout(width = naturalWidth.coerceAtMost(constraints.maxWidth), height = height) {
            var x = 0f
            placeables.forEach { placeable ->
                placeable.placeWithLayer(
                    x = x.roundToInt(),
                    y = ((height - placeable.height * scale) / 2f).roundToInt(),
                ) {
                    scaleX = scale
                    scaleY = scale
                    // About the top-left, so the row shrinks towards its own start edge and stays
                    // packed instead of each control drifting towards its centre.
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                x += (placeable.width + spacingPx) * scale
            }
        }
    }
}

/**
 * A control that says what it does, for the row along the bottom of the player.
 *
 * The bare icons it replaces were guessable at best — a viewer had to press one to find out whether
 * it opened subtitles or audio — and the row is not short of space in the one orientation the player
 * ever runs in. On the narrowest screens the label is dropped and the glyph stands alone, because
 * there the row genuinely does run out of room.
 */
@Composable
internal fun PlayerActionPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = LocalPlayerLayoutMetrics.current.showPillLabels,
    enabled: Boolean = true,
) = PlayerActionPill(
    label = label,
    painter = rememberVectorPainter(icon),
    onClick = onClick,
    modifier = modifier,
    showLabel = showLabel,
    enabled = enabled,
)

@Composable
internal fun PlayerActionPill(
    label: String,
    painter: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = LocalPlayerLayoutMetrics.current.showPillLabels,
    enabled: Boolean = true,
) {
    // Without a word the pill is the same control as the cast and lock buttons beside it, so it is
    // drawn as one. It used to be a 38x34dp pill packed edge to edge while the cast button kept its
    // 48dp slot, which spread the row out unevenly around that one icon — and left no gap at all
    // between the others.
    if (!showLabel) {
        PlayerControlIconButton(
            label = label,
            painter = painter,
            enabled = enabled,
            onClick = onClick,
            modifier = modifier,
        )
        return
    }
    val shape = RoundedCornerShape(20.dp)
    val contentAlpha = if (enabled) 1f else 0.35f
    val fillFocus1 = rememberFocusFill()
    Row(
        modifier = modifier
            .focusFill(fillFocus1, shape)
            .clip(shape)
            .background(fillFocus1.background(Color.Black.copy(alpha = 0.42f)), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (showLabel) 12.dp else 10.dp, vertical = 8.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = fillFocus1.content(Color.White.copy(alpha = contentAlpha)),
            modifier = Modifier.size(18.dp),
        )
        if (showLabel) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = fillFocus1.content(Color.White.copy(alpha = contentAlpha)),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A timestamp in a chip of its own, rather than bare letters on the picture.
 *
 * The elapsed and remaining times sit directly over a moving image, and white text on a pale frame
 * is exactly where a plain label stops being readable. The chip carries its own dark fill and a
 * hairline edge so the number holds whatever is behind it, which is also what stops the two of them
 * reading as part of the video rather than part of the player.
 */
@Composable
internal fun PlayerTimePill(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    emphasis: Float = 1f,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.46f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = emphasis),
        )
    }
}

/** The same compact title treatment used by the TV player, sized for a touch screen. */
/**
 * Back, as the system gesture would do it from here: the arrow presses the same dispatcher, so it
 * answers to whatever the screen has registered — leaving fullscreen on the watch screen, leaving
 * the player on a downloaded episode — and cannot drift from what the gesture does.
 */
@Composable
private fun PlayerBackArrow() {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val focus = rememberFocusFill()
    IconButton(
        colors = focus.iconButtonColors(IconButtonDefaults.iconButtonColors()),
        onClick = { dispatcher?.onBackPressed() },
        modifier = Modifier.focusFill(focus, CircleShape),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = focus.content(Color.White),
        )
    }
}

@Composable
private fun PlayerTitleCard(
    seriesTitle: String,
    episodeTitle: String,
    modifier: Modifier = Modifier,
) {
    // No panel behind the title: the box read as chrome sitting on the picture. The text carries
    // its own shadow instead, which is what keeps it legible over a bright frame — a pale sky
    // behind white letters is exactly where a plain label disappears.
    val titleShadow = Shadow(color = Color.Black.copy(alpha = 0.75f), offset = Offset(0f, 2f), blurRadius = 7f)
    Column(
        modifier = modifier
            .fillMaxWidth(0.62f)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (seriesTitle.isNotBlank()) {
            PlayerSeriesName(
                title = seriesTitle,
                maxHeight = 34.dp,
                maxWidth = 220.dp,
                modifier = Modifier.padding(bottom = 3.dp),
            ) {
                Text(
                    text = seriesTitle,
                    color = Color.White,
                    fontSize = 18.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Bold,
                    style = LocalTextStyle.current.copy(shadow = titleShadow),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (episodeTitle.isNotBlank()) {
            Text(
                text = episodeTitle,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall.copy(shadow = titleShadow),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun PlayerControlIconButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = PlayerControlIconButton(
    label = label,
    painter = rememberVectorPainter(icon),
    enabled = enabled,
    onClick = onClick,
    modifier = modifier,
)

/** The same button drawn from a drawable, for the glyphs the Material set has no equivalent of. */
@Composable
internal fun PlayerControlIconButton(
    label: String,
    painter: Painter,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // On a phone the accent belongs to play/pause and the seek bar alone — ringing every control
    // in purple made the overlay shout over the video. The dark circle already separates the icons
    // from the picture, so the ring adds nothing there.
    //
    // A TV keeps it: with no touch, that outline is what tells a viewer across the room where the
    // controls are, and it is the shape the focus highlight sits on.
    val isTv = LocalAppDeviceProfile.current.isTv
    // The phone's chrome is deliberately smaller than the TV's. The button stays a full 48dp of
    // touch target — shrinking that would make the overlay harder to hit, not lighter — and only
    // the drawn circle and glyph come down, so the controls stop competing with the picture.
    val circle = if (isTv) 48.dp else 36.dp
    val glyph = if (isTv) 24.dp else 19.dp
    val extraFocus2 = rememberFocusFill()
    IconButton(
        colors = extraFocus2.iconButtonColors(IconButtonDefaults.iconButtonColors()),
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = label }.focusFill(extraFocus2, CircleShape),
    ) {
        Box(
            modifier = Modifier
                .size(circle)
                .clip(CircleShape)
                .background(extraFocus2.background(Color.Black.copy(alpha = if (isTv) 0.58f else 0.42f)), CircleShape)
                .then(
                    if (isTv && !extraFocus2.focused) {
                        Modifier.border(
                            width = 1.5.dp,
                            color = extraFocus2.content(MaterialTheme.colorScheme.primary
                                .copy(alpha = if (enabled) 0.82f else 0.24f)),
                            shape = CircleShape,
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = extraFocus2.content(Color.White.copy(alpha = if (enabled) 1f else 0.35f)),
                modifier = Modifier.size(glyph),
            )
        }
    }
}

internal fun formatPlayerTime(valueMs: Long): String {
    val totalSeconds = valueMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
