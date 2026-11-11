package com.anilili.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.anilili.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reordering Home's rows in place: hold a row's title, drag, let go.
 *
 * The same order Settings → Arrange Home edits, saved the same way — once, when the finger lifts,
 * because each settings write re-applies every preference and a drag across five rows is five
 * swaps. While the finger is down the feed is drawn from [order], a private copy the drag reshapes.
 *
 * The dragged row is followed by its LazyColumn key, never by index: a swap changes every index
 * after it, and the row's composition — with the gesture running in it — has to stay the same one.
 * Its on-screen position is held as [fingerTop], in the list's viewport coordinates, so it stays
 * under the finger while rows move around it and while the list scrolls under it; the row is drawn
 * at its laid-out place plus whatever translation closes the gap.
 */
@Stable
class HomeRowDragState internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
) {
    /** The order being dragged into, or null when no drag is in progress. */
    var order by mutableStateOf<HomeLayout?>(null)
        private set

    /** The row lifted by the finger, or by the drop's settle animation once it has let go. */
    var lifted by mutableStateOf<HomeRow?>(null)
        private set

    /** True from the long press to the end of the settle, while the other rows animate their moves. */
    val active: Boolean get() = lifted != null

    private var draggedKey: Any? = null
    private var fingerTop by mutableFloatStateOf(0f)
    private var grabY = 0f
    private val settle = Animatable(0f)
    private var settling by mutableStateOf(false)

    // Set by the composable each composition, so the frame loop sees the feed as currently drawn.
    internal var keyToRow: Map<Any, HomeRow> = emptyMap()
    internal var topInsetPx = 0f
    internal var bottomInsetPx = 0f
    internal var edgeZone = 0f
    internal var onSwap: () -> Unit = {}

    internal fun start(row: HomeRow, key: Any, saved: HomeLayout, grabWithinRow: Float) {
        val item = itemFor(key) ?: return
        scope.launch { settle.snapTo(0f) }
        settling = false
        order = saved
        lifted = row
        draggedKey = key
        fingerTop = item.offset.toFloat()
        grabY = grabWithinRow
    }

    internal fun drag(dy: Float) {
        if (order == null) return
        fingerTop += dy
        swapIfPastNeighbour()
    }

    /** Lets go: saves the order and springs the row from under the finger into its slot. */
    internal fun drop(save: Boolean) {
        val finished = order ?: return
        val from = translation()
        if (save) SettingsStore.setHomeLayout(finished.encode())
        order = null
        draggedKey = null
        settling = true
        scope.launch {
            settle.snapTo(from)
            settle.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
            settling = false
            lifted = null
        }
    }

    /** How far the lifted row is drawn from where the list lays it out. */
    internal fun translation(): Float {
        if (settling) return settle.value
        val key = draggedKey ?: return 0f
        val item = itemFor(key) ?: return 0f
        return fingerTop - item.offset
    }

    /** One frame of edge scrolling: the closer the finger to the top or bottom, the faster. */
    internal suspend fun autoScroll() {
        if (order == null || edgeZone <= 0f) return
        // Read here, outside composition: reading layoutInfo while composing would recompose the
        // whole feed on every frame the list scrolls.
        val viewport = listState.layoutInfo
        val edgeTop = viewport.viewportStartOffset + topInsetPx
        val edgeBottom = viewport.viewportEndOffset - bottomInsetPx
        val finger = fingerTop + grabY
        val speed = when {
            finger < edgeTop + edgeZone -> -(edgeTop + edgeZone - finger) / edgeZone
            finger > edgeBottom - edgeZone -> (finger - (edgeBottom - edgeZone)) / edgeZone
            else -> 0f
        }.coerceIn(-1f, 1f)
        if (speed == 0f) return
        listState.scrollBy(speed * MAX_SCROLL_PER_FRAME_PX)
        swapIfPastNeighbour()
    }

    private fun itemFor(key: Any): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    /**
     * Swaps with the row above or below once the dragged row is past that row's middle.
     *
     * Measured against the neighbour's middle rather than "the dragged row's middle is inside the
     * neighbour": rows here differ wildly in height — an expanded rail is a page of posters — and a
     * short row dragged over a tall one would otherwise be inside it again straight after the swap,
     * and swap back, every frame.
     */
    private fun swapIfPastNeighbour() {
        val current = order ?: return
        val row = lifted ?: return
        val key = draggedKey ?: return
        val rows = listState.layoutInfo.visibleItemsInfo.filter { it.key in keyToRow }
        val at = rows.indexOfFirst { it.key == key }
        if (at < 0) return
        val dragged = rows[at]
        val top = fingerTop
        val bottom = top + dragged.size
        val below = rows.getOrNull(at + 1)
        val above = rows.getOrNull(at - 1)
        val (anchor, after) = when {
            below != null && bottom > below.offset + below.size / 2f -> below to true
            above != null && top < above.offset + above.size / 2f -> above to false
            else -> return
        }
        // LazyColumn holds its scroll position by the first visible item's key. When that item is
        // one of the two swapping, the list follows it to its new place and the feed jumps; pinning
        // the position by index first keeps the viewport where it is.
        if (dragged.index == listState.firstVisibleItemIndex || anchor.index == listState.firstVisibleItemIndex) {
            val index = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            scope.launch { listState.scrollToItem(index, offset) }
        }
        val anchorRow = keyToRow[anchor.key] ?: return
        order = current.placeBeside(row, anchorRow, after)
        onSwap()
    }

    internal fun isLifted(row: HomeRow) = lifted == row

    private companion object {
        const val MAX_SCROLL_PER_FRAME_PX = 28f
    }
}

/**
 * The state for one feed. [keyToRow] maps each row's LazyColumn key to its row, for the rows drawn
 * right now; [topInset] and [bottomInset] are the chrome floating over the list's ends, where a
 * finger is already out of sight of the row it holds and the list should scroll.
 */
@Composable
fun rememberHomeRowDragState(
    listState: LazyListState,
    keyToRow: Map<Any, HomeRow>,
    topInset: Dp,
    bottomInset: Dp,
): HomeRowDragState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val state = remember(listState) { HomeRowDragState(listState, scope) }
    val density = LocalDensity.current
    state.keyToRow = keyToRow
    with(density) {
        state.topInsetPx = topInset.toPx()
        state.bottomInsetPx = bottomInset.toPx()
        state.edgeZone = 72.dp.toPx()
    }
    // A tick per swap is the "magnet": the row clicks into each new place as it passes.
    state.onSwap = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val dragging = state.order != null
    LaunchedEffect(state, dragging) {
        while (dragging) {
            withFrameNanos { }
            state.autoScroll()
        }
    }
    return state
}

/**
 * The lifted look for a row's item: drawn over its neighbours, raised on a card of its own, and
 * slightly larger — a drag needs to read as "this row is in your hand".
 */
fun Modifier.homeRowDragItem(state: HomeRowDragState, row: HomeRow): Modifier = composed {
    val lifted = state.isLifted(row)
    this
        .zIndex(if (lifted) 1f else 0f)
        .graphicsLayer {
            translationY = if (lifted) state.translation() else 0f
            val scale = if (lifted) 1.02f else 1f
            scaleX = scale
            scaleY = scale
            shadowElevation = if (lifted) 18.dp.toPx() else 0f
            shape = RoundedCornerShape(20.dp)
            clip = false
        }
        .then(
            if (lifted) {
                Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
            } else {
                Modifier
            },
        )
}

/**
 * What a row's title wears: a long press lifts the row, and the drag moves it. A screen reader gets
 * Move up / Move down actions instead, which save straight away.
 *
 * [grabTop] is how far down the row the title sits, so edge scrolling follows the finger rather
 * than the row's top edge.
 */
fun Modifier.homeRowDragHandle(
    state: HomeRowDragState,
    row: HomeRow,
    key: Any,
    saved: HomeLayout,
    visibleRows: List<HomeRow>,
    grabTop: Dp = 0.dp,
): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val latestSaved by rememberUpdatedState(saved)
    val density = LocalDensity.current
    val at = visibleRows.indexOf(row)
    this
        .pointerInput(state, row, key) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.start(row, key, latestSaved, with(density) { grabTop.toPx() } + offset.y)
                },
                onDragEnd = { state.drop(save = true) },
                onDragCancel = { state.drop(save = true) },
            ) { change, amount ->
                change.consume()
                state.drag(amount.y)
            }
        }
        .semantics {
            customActions = buildList {
                visibleRows.getOrNull(at - 1)?.let { above ->
                    add(
                        CustomAccessibilityAction("Move ${row.label(false)} up") {
                            SettingsStore.setHomeLayout(saved.placeBeside(row, above, after = false).encode())
                            true
                        },
                    )
                }
                visibleRows.getOrNull(at + 1)?.let { below ->
                    add(
                        CustomAccessibilityAction("Move ${row.label(false)} down") {
                            SettingsStore.setHomeLayout(saved.placeBeside(row, below, after = true).encode())
                            true
                        },
                    )
                }
            }
        }
}
