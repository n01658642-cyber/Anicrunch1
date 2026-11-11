package com.anilili.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How long a committed swipe takes to hand one tab over to the next, both halves in step. */
private const val TAB_SWIPE_MS = 300

/** The share of the page's width a drag has to cover to turn the page on release. */
private const val TAB_SWIPE_COMMIT_FRACTION = 0.18f

/**
 * How far the page follows the finger. Less than one to one, because what a drag uncovers is the
 * canvas rather than the next page — the neighbour is not composed until the swipe commits — and a
 * page tracking the finger exactly opened a wide empty strip beside it.
 */
private const val TAB_SWIPE_FOLLOW = 0.6f

/** Where there is no tab to turn to, the page gives a little and springs back. */
private const val TAB_SWIPE_EDGE_FOLLOW = 0.2f

/**
 * A swipe across the phone's tab pages, and the direction the tab it committed to arrives from.
 *
 * Shared between the gesture on the host and the host's transitions: a swipe has to hand over
 * sideways, the next page arriving beside the one leaving the way a pager turns, while a tap on the
 * capsule keeps the cross-fade it always had. [direction] is how the transitions tell the two
 * apart — +1 while turning to the next tab, -1 to the previous, 0 for everything else.
 */
@Stable
class TabSwipeState internal constructor(internal val scope: CoroutineScope) {
    /** How far the page on show has been dragged, in pixels. */
    internal val offset = Animatable(0f)

    var direction by mutableIntStateOf(0)
        internal set

    /** Navigates to the tab in a direction; replaced on every composition of the host. */
    internal var onTurn: (Int) -> Unit = {}

    /** The swipe hand-over when one is under way, otherwise [fallback]. */
    fun enter(fallback: EnterTransition): EnterTransition {
        val dir = direction
        if (dir == 0) return fallback
        return slideInHorizontally(tween(TAB_SWIPE_MS, easing = FastOutSlowInEasing)) { it * dir }
    }

    fun exit(fallback: ExitTransition): ExitTransition {
        val dir = direction
        if (dir == 0) return fallback
        return slideOutHorizontally(tween(TAB_SWIPE_MS, easing = FastOutSlowInEasing)) { -it * dir }
    }
}

@Composable
fun rememberTabSwipeState(): TabSwipeState {
    val scope = rememberCoroutineScope()
    return remember { TabSwipeState(scope) }
}

/** NavHost's own default hand-over, kept for every navigation that is not a swipe. */
val DefaultNavEnter: EnterTransition = fadeIn(tween(700))
val DefaultNavExit: ExitTransition = fadeOut(tween(700))

/**
 * Lets a horizontal swipe turn the phone's tab pages, the way Instagram's do.
 *
 * The page follows the finger, and past [TAB_SWIPE_COMMIT_FRACTION] of the width — or on a flick —
 * the next tab slides in beside it as it leaves. Short of that it springs back.
 *
 * Anything on the page that scrolls sideways keeps its own swipes: a rail, the spotlight, the chips,
 * a slider. They consume the drag before this sees it, because the page's children take pointer
 * events ahead of their parent, so the page only turns from where nothing else wanted the gesture.
 * Once it has claimed a drag it takes the rest of it first, so a vertical list underneath does not
 * start scrolling as the finger drifts.
 *
 * [onTurn] navigates; it is asked only for a direction that has a tab to go to.
 */
fun Modifier.swipeBetweenTabs(
    state: TabSwipeState,
    enabled: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onTurn: (Int) -> Unit,
): Modifier {
    // Held on the state rather than keyed into the gesture: the host builds a new lambda on every
    // composition, and a key that changes restarts the detector — mid-drag, dropping the swipe.
    state.onTurn = onTurn
    return this
        .graphicsLayer { translationX = state.offset.value }
        .then(
            if (!enabled) {
                Modifier
            } else {
                Modifier.pointerInput(state, hasPrevious, hasNext) {
                    val flingVelocity = 800.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var overSlop = 0f
                        val claimed = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                            change.consume()
                            overSlop = over
                        } ?: return@awaitEachGesture
                        val width = size.width.toFloat()
                        val velocity = VelocityTracker()
                        velocity.addPointerInputChange(claimed)
                        fun follow(delta: Float) {
                            val current = state.offset.value
                            // Towards the next tab is a leftward drag, a negative offset.
                            val towardsNeighbour = if (current + delta < 0f) hasNext else hasPrevious
                            val factor = if (towardsNeighbour) TAB_SWIPE_FOLLOW else TAB_SWIPE_EDGE_FOLLOW
                            val next = (current + delta * factor).coerceIn(-width, width)
                            state.scope.launch { state.offset.snapTo(next) }
                        }
                        follow(overSlop)
                        var released = false
                        while (true) {
                            // Initial pass: taken ahead of the children for the rest of the gesture.
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == claimed.id } ?: break
                            if (!change.pressed) {
                                released = true
                                break
                            }
                            velocity.addPointerInputChange(change)
                            follow(change.positionChange().x)
                            change.consume()
                        }
                        val offset = state.offset.value
                        val speed = if (released) velocity.calculateVelocity().x else 0f
                        val dir = when {
                            offset < 0f && hasNext -> 1
                            offset > 0f && hasPrevious -> -1
                            else -> 0
                        }
                        val far = abs(offset) > width * TAB_SWIPE_COMMIT_FRACTION
                        // A flick counts in the direction it was thrown, not merely in any direction.
                        val flicked = abs(speed) > flingVelocity && (speed < 0f) == (dir == 1)
                        if (dir != 0 && (far || flicked)) {
                            state.direction = dir
                            state.onTurn(dir)
                            // The page on show keeps its dragged position as it slides out, and the
                            // host's offset returns to rest over the same span and easing — so the
                            // outgoing page travels from where the finger left it and the incoming one
                            // arrives flush against it, as in a pager.
                            state.scope.launch {
                                // Cleared however the slide ends — a new drag interrupting it included —
                                // or a later tap on the capsule would slide instead of cross-fading.
                                try {
                                    state.offset.animateTo(0f, tween(TAB_SWIPE_MS, easing = FastOutSlowInEasing))
                                } finally {
                                    state.direction = 0
                                }
                            }
                        } else {
                            state.scope.launch {
                                state.offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        }
                    }
                }
            },
        )
}
