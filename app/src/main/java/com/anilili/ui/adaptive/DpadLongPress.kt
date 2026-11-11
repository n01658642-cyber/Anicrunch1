package com.anilili.ui.adaptive

import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** What a completed remote press turned out to be. */
internal enum class DpadPress { CLICK, LONG_CLICK }

/**
 * Decides whether a held OK button is a click or a long press.
 *
 * Kept apart from the modifier because the interesting part is the timing, and timing is worth
 * testing without a remote in hand.
 */
internal class DpadPressTracker(private val longPressTimeoutMs: Long) {
    private var heldLongEnough = false

    /**
     * A key-down at [heldMs] after the press began. Android repeats a held key, so this arrives
     * again and again during a hold.
     *
     * It deliberately reports nothing. Opening a menu while the button is still down was the first
     * attempt and it was unusable: the menu takes focus, and then the viewer's release — a key-up
     * whose key-down the menu never saw — lands on whichever button the menu had focused, because
     * Compose's clickable acts on a key-up alone. Letting go dismissed the menu, or worse chose
     * something in it, so there was never a moment in which to press anything. All the hold does
     * is record that it has gone long.
     */
    fun onKeyDown(heldMs: Long) {
        if (heldMs >= longPressTimeoutMs) heldLongEnough = true
    }

    /**
     * The release, which is the only thing that ever acts.
     *
     * The duration is measured again rather than trusted to [onKeyDown]: not every television box
     * auto-repeats a held OK, and on those the release is the only event that arrives at all.
     */
    fun onKeyUp(heldMs: Long): DpadPress {
        val long = heldLongEnough || heldMs >= longPressTimeoutMs
        heldLongEnough = false
        return if (long) DpadPress.LONG_CLICK else DpadPress.CLICK
    }
}

private val DpadClickKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/**
 * The button a viewer reaches for first when they want options, on the remotes that have one.
 *
 * A hidden long press is only discoverable from the line of help text above the row, so Menu opens
 * the same thing outright — no hold, no timing.
 */
private val ContextMenuKeys = setOf(Key.Menu)

/**
 * Gives a remote the long press that `combinedClickable` only ever offered a finger.
 *
 * Compose's clickable handles hardware keys in `AbstractClickableNode.onKeyEvent`, which is
 * `final` and has exactly two branches: a key-down raises the press indication (repeats are
 * explicitly discarded) and a key-up calls `onClick`. There is no long-press path for keys at all
 * — `onLongClick` is reachable only through `detectTapGestures`, which needs a pointer, and
 * through the semantics action, which needs an accessibility service. So on a television, holding
 * OK on a Continue Watching card did not open the manage menu; it resumed the episode on release.
 *
 * This runs in the preview pass, which always precedes the clickable's own handler, and consumes
 * the OK key outright so the two cannot both act on one press. [onClick] is therefore invoked from
 * here for a short press. Keep `combinedClickable` alongside it: that is still what makes the card
 * focusable, what carries the click and long-click labels for accessibility, and what serves a
 * finger on a touchscreen.
 *
 * Both the click and the long press act on the **release**, never during the hold — see
 * [DpadPressTracker.onKeyDown] for the reason, which is that a menu opened under a held button is
 * dismissed by the very act of letting go.
 *
 * The remote's Menu button, where present, opens the same thing without the hold.
 */
@Composable
fun Modifier.dpadClickable(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    enabled: Boolean = true,
): Modifier {
    // The platform's own definition, so a hold that opens a menu everywhere else on the
    // television opens this one after the same wait.
    val tracker = remember { DpadPressTracker(ViewConfiguration.getLongPressTimeout().toLong()) }
    return onPreviewKeyEvent { event ->
        if (!enabled) return@onPreviewKeyEvent false
        if (event.key in ContextMenuKeys) {
            // On the release, so the menu does not open under a button the viewer is still holding.
            if (event.type == KeyEventType.KeyUp) onLongClick()
            return@onPreviewKeyEvent true
        }
        if (event.key !in DpadClickKeys) return@onPreviewKeyEvent false
        val native = event.nativeKeyEvent
        val heldMs = native.eventTime - native.downTime
        when (event.type) {
            KeyEventType.KeyDown -> tracker.onKeyDown(heldMs)
            KeyEventType.KeyUp -> when (tracker.onKeyUp(heldMs)) {
                DpadPress.CLICK -> onClick()
                DpadPress.LONG_CLICK -> onLongClick()
            }
            else -> return@onPreviewKeyEvent false
        }
        true
    }
}
