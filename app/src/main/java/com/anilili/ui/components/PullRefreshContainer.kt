package com.anilili.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.anilili.data.settings.GlassSection
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Touch pull-to-refresh; TV keeps normal D-pad scrolling.
 *
 * The gesture is Compose Material's, deliberately, rather than Material 3's `PullToRefreshBox`:
 * Material 3 1.3.1 can finish an animation after its modifier node has detached and then crash while
 * reading `LocalDensity`. The Material implementation is on the same Compose BOM as the rest of the
 * app and does not read a CompositionLocal from a modifier node.
 *
 * Only the nested-scroll state is Material's. The thing you actually see is [PullRefreshPuck], for
 * the reason recorded there.
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun PullRefreshContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * How much chrome the caller draws over the top of this box.
     *
     * These screens deliberately fill the whole window rather than the Scaffold's content area, so
     * rows pass under the bars as they retreat — which also means the top of this box is *behind*
     * the app bar. Left at zero the puck appears there, hidden, and a pull looks like it did nothing
     * at all. Callers that draw under a bar pass its height; the puck sits [PuckGap] below it.
     */
    indicatorTopPadding: Dp = 0.dp,
    /**
     * How much chrome the caller draws over the bottom of this box, for [pullUpAtEnd]'s puck.
     *
     * Same problem as [indicatorTopPadding] at the other end: the floating navigation capsule sits
     * over the last rows, and a puck left at zero comes up behind it.
     */
    indicatorBottomPadding: Dp = 0.dp,
    /**
     * Reload on a drag *up* past the end of [content] as well, when this reports the content is
     * there; null keeps the top pull as the only gesture.
     *
     * The pull at the top of a page is unreachable from the bottom of a long one. The anime page is
     * a single list, so by the time a viewer is looking at the last episode the gesture that
     * reloads it is the whole episode list behind them — and a just-aired episode is exactly what
     * they came down here to look for. The end of the list gets the same gesture upside down.
     *
     * Both halves of what the caller reports matter. Without "at the end", an upward drag anywhere
     * on the page would count. Without "has somewhere to scroll back to", a page too short to
     * scroll at all would reload on any upward flick — and on a short page that flick means
     * "show me what is below", which is nothing.
     */
    pullUpAtEnd: (() -> Boolean)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    if (LocalAppDeviceProfile.current.isTv) {
        // No gesture — a pull would fight D-pad scrolling — but the reload a remote can now ask for
        // still has to look like it happened. [HomeViewModel] already holds `isRefreshing` for a
        // minimum time precisely so a fast, cache-warm refresh is visible rather than a blink; with
        // nothing drawn here that deliberate hold was spent on an empty screen.
        Box(modifier) {
            content()
            TvRefreshPuck(
                refreshing = isRefreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = indicatorTopPadding + PuckGap),
            )
        }
    } else {
        val state = rememberPullRefreshState(isRefreshing, onRefresh)
        val upThresholdPx = with(LocalDensity.current) { PullUpThreshold.toPx() }
        val upState = remember(upThresholdPx) { PullUpRefreshState(upThresholdPx) }
        // Which end started the refresh, so only that end's puck spins. Both of them are on screen
        // the whole time — this box is the window, not the visible slice of the list — so leaving
        // them both to `isRefreshing` puts a spinner at the top of the page for someone who asked
        // from the bottom, and another at the bottom for someone who asked from the top.
        var startedFromEnd by remember { mutableStateOf(false) }
        LaunchedEffect(isRefreshing) {
            if (isRefreshing) upState.cancel() else startedFromEnd = false
        }
        val refreshingNow = rememberUpdatedState(isRefreshing)
        val atEnd = rememberUpdatedState(pullUpAtEnd)
        val currentOnRefresh = rememberUpdatedState(onRefresh)
        val upConnection = remember(upState) {
            PullUpRefreshConnection(
                state = upState,
                armed = { atEnd.value?.invoke() == true && !refreshingNow.value },
                onRefresh = {
                    startedFromEnd = true
                    currentOnRefresh.value()
                },
            )
        }
        // Live glass for the puck. The page is only recorded for it while a puck is on screen:
        // a source records its whole content every frame, and on Home that is the entire feed,
        // paid on every scroll for a disc that is almost never there. It stays on a moment past
        // the end so the puck keeps its blur through its own fade-out.
        val backdrop = if (glassEnabled(GlassSection.REFRESH)) remember { HazeState() } else null
        var sampling by remember { mutableStateOf(false) }
        if (backdrop != null) {
            LaunchedEffect(state, upState) {
                snapshotFlow { refreshingNow.value || state.progress > 0f || upState.progress > 0f }
                    .collectLatest { shown ->
                        if (shown) {
                            sampling = true
                        } else {
                            delay(GlassLingerMillis)
                            sampling = false
                        }
                    }
            }
        }
        val puckBackdrop = backdrop?.takeIf { sampling }
        // The pull-up is the inner connection of the two, which is the end of the chain: it takes
        // the leftover a list at its end could not use, and the top pull never sees one there.
        Box(modifier.pullRefresh(state).nestedScroll(upConnection)) {
            // The source is a box of its own beside the pucks, never around them: a disc inside
            // the source it blurs would be sampling itself.
            Box(
                Modifier
                    .matchParentSize()
                    .then(if (puckBackdrop != null) Modifier.hazeSource(puckBackdrop) else Modifier),
            ) {
                content()
            }
            PullRefreshPuck(
                refreshing = isRefreshing && !startedFromEnd,
                progress = { state.progress },
                backdrop = puckBackdrop,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = indicatorTopPadding + PuckGap),
            )
            if (pullUpAtEnd != null) {
                PullRefreshPuck(
                    refreshing = isRefreshing && startedFromEnd,
                    progress = { upState.progress },
                    backdrop = puckBackdrop,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = indicatorBottomPadding + PuckGap),
                )
            }
        }
    }
}

/**
 * How far the pull-up travels before it means it.
 *
 * Material's own top threshold, so the two ends of the same page ask for the same gesture rather
 * than one of them going off early under a thumb that was still scrolling.
 */
private val PullUpThreshold = 80.dp

/**
 * How far the viewer has dragged up past the end of the content.
 *
 * Held apart from the connection below because it is the only part worth reading: the connection is
 * sign arithmetic, and this is what the puck draws. Internal for the same reason the remote's press
 * tracker is: where the threshold lands is worth testing without a finger on the glass.
 */
@Stable
internal class PullUpRefreshState(private val thresholdPx: Float) {
    private var offset by mutableFloatStateOf(0f)

    val progress: Float
        get() = if (thresholdPx <= 0f) 0f else (offset / thresholdPx).coerceIn(0f, 1f)

    val pulled: Boolean get() = offset > 0f

    /**
     * Takes [delta] pixels of pull — positive pulls the indicator in — and reports how much of it
     * was actually used, which is all of it except where the unwind hits zero.
     */
    fun drag(delta: Float): Float {
        val next = (offset + delta).coerceAtLeast(0f)
        val used = next - offset
        offset = next
        return used
    }

    /** The release. Reports whether it went far enough to mean a refresh, and unwinds either way. */
    fun release(): Boolean {
        val reached = offset >= thresholdPx
        offset = 0f
        return reached
    }

    fun cancel() {
        offset = 0f
    }
}

/**
 * The pull-down gesture read backwards: the scroll a list at its end had no use for.
 *
 * Compose scroll deltas are negative when the finger travels up, so the pull is `-y` throughout and
 * the amount consumed is `-used` — the two sign flips are why every branch here reads the wrong way
 * round at a glance.
 */
private class PullUpRefreshConnection(
    private val state: PullUpRefreshState,
    private val armed: () -> Boolean,
    private val onRefresh: () -> Unit,
) : NestedScrollConnection {
    /**
     * Give the pull back before the list scrolls again.
     *
     * A finger that reverses mid-gesture is taking back what it offered, exactly as a pull-down
     * unwinds before the list moves. Without this the indicator would stay out at full stretch
     * while the content scrolled away underneath it.
     */
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y <= 0f || !state.pulled) return Offset.Zero
        return Offset(0f, -state.drag(-available.y))
    }

    /** What the list could not use, once it has nothing left to scroll. */
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (source != NestedScrollSource.UserInput || available.y >= 0f || !armed()) return Offset.Zero
        return Offset(0f, -state.drag(-available.y))
    }

    /**
     * The release, which a drag always produces even when it ends still.
     *
     * The velocity is swallowed only when there was a pull to end, so an ordinary fling at the
     * bottom of a list still reaches the overscroll effect.
     */
    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!state.pulled) return Velocity.Zero
        if (state.release()) onRefresh()
        return available
    }
}

/**
 * The refresh puck: it grows into place instead of sliding down into it.
 *
 * Material's own `PullRefreshIndicator` slides, and it pays for that with a clip. Its transform is
 * `translationY = position - height` wrapped in a `clipRect(top = 0f)`, so the spinner is drawn a
 * full height above its own layout box and wiped into view as the pull pushes it back down. That
 * reads correctly when the box starts at the top edge of the window, where the half being withheld
 * is off-screen anyway. Here the box is the whole window and the indicator is padded down clear of
 * the header, which leaves that clip line in open air over the hero: the circle rose out of it cut
 * off along a hard horizontal edge with nothing drawn on it, because what does the cutting is the
 * indicator's own bounds.
 *
 * More padding cannot fix that. The clip travels with the node — it is always exactly one indicator
 * height above where the spinner comes to rest — and the only thing that could hide it is opaque
 * chrome sitting on that line, which this app does not have: [chromeFade] has gone to nothing by its
 * own bottom edge so artwork can run unbroken beneath it.
 *
 * So the puck holds its position and scales and fades in instead. It never draws outside its own
 * bounds, so nothing can slice it. The ring doubles as the gauge — it closes exactly as the pull
 * reaches the threshold, then spins while the refresh runs.
 *
 * Serves both ends of the page: [progress] is the only thing that differs between the pull at the
 * top and the one at the bottom, and the puck itself is the same object either way up.
 */
@Composable
private fun PullRefreshPuck(
    refreshing: Boolean,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    /** The page behind, when the puck is live glass; null keeps the painted disc. */
    backdrop: HazeState? = null,
) {
    // The gauge moves on every frame of a drag, so it is read here in the leaf and nowhere else:
    // reading it up in the container would recompose the entire screen underneath it at 60Hz.
    val pull = progress().coerceIn(0f, 1f)
    val presence by animateFloatAsState(
        targetValue = if (refreshing) 1f else pull,
        // Stiff enough to sit under the finger rather than trail it, and it is a scale rather than a
        // position, where a frame of lag is invisible. Critically damped: a bouncy settle lands
        // while the thumb is usually still on the glass and reads as a twitch.
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pullRefreshPresence",
    )
    if (presence <= 0f) return

    val arcColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .graphicsLayer {
                // Opaque by the time the pull is a third of the way in — a puck that reaches full
                // strength only at the threshold spends most of the gesture as a smudge over cover
                // art. The scale keeps running to the end, so the growth is still what carries it.
                alpha = (presence * 3f).coerceAtMost(1f)
                val scale = 0.55f + 0.45f * presence
                scaleX = scale
                scaleY = scale
            }
            .size(PuckSize)
            // No drop shadow under live glass: Android draws an elevation shadow beneath the whole
            // outline, and through a translucent disc it reads as a grey fill over the blur.
            .then(if (backdrop == null) Modifier.shadow(12.dp, CircleShape) else Modifier)
            .clip(CircleShape)
            .then(
                if (backdrop != null) {
                    // Only a light smoke over the blur, for the white ring's sake over bright art.
                    // Anything painted on top as well turns the disc into flat grey: the blur
                    // already averages the art down, and a second fill finishes the job.
                    Modifier.backdropBlur(backdrop, tint = Color.Black.copy(alpha = 0.18f), radius = 6.dp)
                } else {
                    Modifier
                },
            )
            .then(if (backdrop == null) Modifier.background(puckGlass()) else Modifier)
            .border(1.dp, puckRim(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (refreshing) {
            val spin = rememberInfiniteTransition(label = "pullRefreshSpin").animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(SpinMillis, easing = LinearEasing)),
                label = "pullRefreshSpinAngle",
            )
            Canvas(
                Modifier
                    .size(RingSize)
                    .semantics { contentDescription = "Refreshing" }
                    // Read inside the layer block so the spin stays in the draw phase; read at
                    // composition it would recompose this puck for every frame of a rotation.
                    .graphicsLayer { rotationZ = spin.value },
            ) {
                drawRing(arcColor, RefreshTurns)
            }
        } else {
            // The gauge follows [presence] rather than the raw pull so that a gesture abandoned
            // short of the threshold unwinds with the puck. Material's state drops its distance to
            // zero the instant the finger lifts, which would leave an empty ring shrinking away.
            Canvas(Modifier.size(RingSize)) { drawRing(arcColor, presence) }
        }
    }
}

/**
 * The same puck, for a screen that has no gesture to gauge.
 *
 * A television never pulls, so there is no progress to show and nothing to unwind — it is either
 * refreshing or absent. It still grows and fades rather than appearing, for the same reason the
 * touch puck does: on a ten-foot display a control that pops into existence reads as a glitch in
 * the artwork behind it.
 */
@Composable
private fun TvRefreshPuck(refreshing: Boolean, modifier: Modifier = Modifier) {
    val presence by animateFloatAsState(
        targetValue = if (refreshing) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "tvRefreshPresence",
    )
    if (presence <= 0f) return

    val arcColor = MaterialTheme.colorScheme.primary
    val spin = rememberInfiniteTransition(label = "tvRefreshSpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SpinMillis, easing = LinearEasing)),
        label = "tvRefreshSpinAngle",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = (presence * 3f).coerceAtMost(1f)
                val scale = 0.55f + 0.45f * presence
                scaleX = scale
                scaleY = scale
            }
            .size(PuckSize)
            .shadow(12.dp, CircleShape)
            .clip(CircleShape)
            .background(puckGlass())
            .border(1.dp, puckRim(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .size(RingSize)
                .semantics { contentDescription = "Refreshing" }
                .graphicsLayer { rotationZ = spin.value },
        ) {
            drawRing(arcColor, RefreshTurns)
        }
    }
}

/** An arc [turns] of the way round, starting at twelve o'clock, inset to keep its stroke inside. */
private fun DrawScope.drawRing(color: Color, turns: Float) {
    val stroke = RingStroke.toPx()
    drawArc(
        color = color,
        startAngle = -90f,
        sweepAngle = 360f * turns,
        useCenter = false,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
}

/**
 * How long the page stays recorded after the last puck has gone. Longer than the puck's
 * medium-stiffness fade, so the blur never drops out from under a disc that is still visible.
 */
private const val GlassLingerMillis = 600L

/** Gap between the caller's chrome and the top of the puck. */
private val PuckGap = 14.dp

private val PuckSize = 44.dp
private val RingSize = 22.dp
private val RingStroke = 2.5.dp

/** What the ring keeps of itself once it is spinning; a closed circle reads as stalled. */
private const val RefreshTurns = 0.78f

/** One rotation. Fast enough to say "working", slow enough not to look frantic at 22dp. */
private const val SpinMillis = 900

/**
 * Smoked glass condensed onto a disc: transparency at
 * the top edge where a bright poster showing through reads as light on glass, near-opaque across the
 * middle where the ring has to stay legible over whatever it is floating on.
 *
 * This small, transient indicator keeps a stronger rim than the persistent navigation bar so it
 * remains visible over moving artwork.
 */
@Composable
private fun puckGlass(): Brush {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        Brush.verticalGradient(
            0f to scheme.surfaceContainerHighest.copy(alpha = 0.78f),
            0.45f to scheme.surfaceContainer.copy(alpha = 0.90f),
            1f to scheme.surfaceContainerLowest.copy(alpha = 0.94f),
        )
    }
}

/** The lit rim, brightest along the top arc and fading to a trace of the accent at the base. */
@Composable
private fun puckRim(): Brush {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        Brush.verticalGradient(
            0f to scheme.onSurface.copy(alpha = 0.24f),
            0.55f to scheme.onSurface.copy(alpha = 0.06f),
            1f to scheme.primary.copy(alpha = 0.18f),
        )
    }
}
