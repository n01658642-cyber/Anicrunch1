package com.anilili.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.anilili.data.settings.SettingsStore
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.dialogs.MidnightDialogFooter
import com.anilili.ui.dialogs.MidnightDialogHost
import com.anilili.ui.dialogs.MidnightHelper
import com.anilili.ui.dialogs.MidnightMediaHeader
import com.anilili.ui.dialogs.MidnightSelectionRow
import com.anilili.ui.theme.Midnight

/**
 * Arrange Home: drag a row by its handle (touch), or move it with the arrows (a remote has no
 * drag), and tick which rows are shown.
 *
 * Every change is saved as it is made, except a drag, which is saved when the finger lifts: a
 * drag across five rows is five swaps, and each settings write re-applies every preference.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeLayoutDialog(onDismiss: () -> Unit) {
    val device = LocalAppDeviceProfile.current
    val stored by SettingsStore.homeLayout.collectAsState()
    val saved = remember(stored, device.isTv) { HomeLayout.decode(stored, device.isTv) }
    // The drag handler is installed once per row and outlives recompositions; it has to read the
    // layout saved *now*, not the one there was when the row first appeared.
    val latestSaved by rememberUpdatedState(saved)
    // What the list draws: the saved layout, or the one a drag in progress is reshaping.
    var dragging by remember { mutableStateOf<HomeLayout?>(null) }
    val layout = dragging ?: saved
    val save = { next: HomeLayout -> SettingsStore.setHomeLayout(next.encode()) }

    // The row the remote's cursor starts on. Fixed at opening: claiming "whichever row is first"
    // re-fired the claim on every move that changed the first row, and the cursor jumped off the
    // arrow being pressed — so a second OK hid a row instead of moving the first one again.
    val openingRow = remember { layout.order.first() }

    val scope = rememberCoroutineScope()
    var draggedRow by remember { mutableStateOf<HomeRow?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }

    MidnightDialogHost(
        onDismiss = onDismiss,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = "Home",
                title = "Arrange Home",
                metadata = "${layout.visible.size} of ${layout.order.size} rows shown",
                onDismiss = onDismiss.takeIf { showClose },
            )
        },
        footer = {
            MidnightDialogFooter(
                extraQuietText = "Reset".takeIf { stored != null },
                onExtraQuiet = { SettingsStore.setHomeLayout(null) },
                quietText = "Done",
                onQuiet = onDismiss,
            )
        },
    ) {
        MidnightHelper(
            if (device.isTv) {
                "Move rows with the arrows; select a row to show or hide it."
            } else {
                "Drag a row by its handle or use the arrows; tick the rows to show."
            },
        )
        Column(Modifier.padding(top = 8.dp)) {
            layout.order.forEachIndexed { index, row ->
              // Keyed by row, not position: a swap mid-drag would otherwise hand this slot — and
              // the gesture running in it — to a different row, and the drag would end.
              key(row) {
                val isDragged = row == draggedRow
                // A row moved by its arrow carries the cursor with it, and the body only scrolls
                // when the cursor *moves*. Pressing Down five times walked the row — and the focused
                // arrow — off the bottom of the dialog with nothing on screen showing where it was.
                val bringer = remember { BringIntoViewRequester() }
                val moveAndFollow = { to: Int ->
                    save(layout.move(row, to))
                    scope.launch {
                        // Two frames: one for the new order to compose, one for it to be placed.
                        withFrameNanos { }
                        withFrameNanos { }
                        bringer.bringIntoView()
                    }
                    Unit
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(bringer)
                        .onSizeChanged { if (rowHeight == 0) rowHeight = it.height }
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragged) dragOffset else 0f
                            shadowElevation = if (isDragged) 12f else 0f
                        },
                ) {
                    if (!device.isTv) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .pointerInput(row) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggedRow = row
                                            dragOffset = 0f
                                            dragging = latestSaved
                                        },
                                        onDragEnd = {
                                            dragging?.let(save)
                                            draggedRow = null
                                            dragOffset = 0f
                                            dragging = null
                                        },
                                        onDragCancel = {
                                            draggedRow = null
                                            dragOffset = 0f
                                            dragging = null
                                        },
                                    ) { change, amount ->
                                        change.consume()
                                        val current = dragging ?: return@detectDragGestures
                                        val step = rowHeight.toFloat().takeIf { it > 0 } ?: return@detectDragGestures
                                        dragOffset += amount.y
                                        val at = current.order.indexOf(row)
                                        // Swap once the row is past half of its neighbour, and keep
                                        // the finger's position by taking a row's height back off.
                                        if (dragOffset > step / 2 && at < current.order.lastIndex) {
                                            dragging = current.move(row, at + 1)
                                            dragOffset -= step
                                        } else if (dragOffset < -step / 2 && at > 0) {
                                            dragging = current.move(row, at - 1)
                                            dragOffset += step
                                        }
                                    }
                                },
                        ) {
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = "Drag ${row.label(false)}",
                                tint = Midnight.Text3,
                            )
                        }
                    }
                    MidnightSelectionRow(
                        title = row.label(device.isTv),
                        subtitle = rowDescription(row),
                        selected = row !in layout.hidden,
                        onSelectedChange = { show -> save(layout.withHidden(row, hide = !show)) },
                        claimInitialFocus = row == openingRow,
                        modifier = Modifier.weight(1f),
                    )
                    MoveButton(
                        up = true,
                        enabled = index > 0,
                        label = row.label(device.isTv),
                        onClick = { moveAndFollow(index - 1) },
                    )
                    MoveButton(
                        up = false,
                        enabled = index < layout.order.lastIndex,
                        label = row.label(device.isTv),
                        onClick = { moveAndFollow(index + 1) },
                    )
                }
              }
            }
        }
    }
}

/**
 * One arrow. At the end of the list it is dimmed and does nothing, but stays focusable: a disabled
 * button drops the remote's cursor, and moving a row to the bottom would then throw focus
 * somewhere else in the dialog the moment it arrived.
 */
@Composable
private fun MoveButton(up: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    val focus = rememberFocusFill()
    IconButton(
        onClick = { if (enabled) onClick() },
        colors = focus.iconButtonColors(IconButtonDefaults.iconButtonColors()),
        modifier = Modifier
            .focusFill(focus, CircleShape)
            .alpha(if (enabled) 1f else 0.3f),
    ) {
        Icon(
            if (up) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (up) "Move $label up" else "Move $label down",
        )
    }
}

/** What each row is, for a viewer deciding whether to keep it. */
private fun rowDescription(row: HomeRow): String? = when (row) {
    HomeRow.CONTINUE -> "Episodes you've started"
    HomeRow.FOR_YOU -> "Picked from everything you've watched"
    HomeRow.BECAUSE -> "Like the last title you finished"
    HomeRow.NEWEST -> "Episodes out in the last few days"
    HomeRow.UPCOMING -> "Announced, not out yet"
    else -> null
}
