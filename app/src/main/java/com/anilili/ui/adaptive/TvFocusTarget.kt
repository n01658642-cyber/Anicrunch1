package com.anilili.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import com.anilili.diagnostics.DiagnosticsLog

/**
 * A [FocusRequester] that also reports whether anything is holding it and whether focus is on it.
 *
 * The D-pad screens hand focus across composables that do not share a parent — the top nav gives
 * Down to the home rows, Right to the search box. [tvFocusHandoff] performs those hand-offs, and
 * [isAttached] is the guard it checks first: the destination screens are only composed on some
 * routes and only once their data lands, and [FocusRequester.requestFocus] throws
 * `IllegalStateException: FocusRequester is not initialized` when no node holds the requester.
 *
 * That guard is not a guarantee — it can only report that *a* node holds the requester, never that
 * the node will accept focus — so [holdsFocus] answers the question the guard cannot: after the
 * request, is focus really here? A hand-off that reads false there has moved nothing and must give
 * the press back rather than consume it.
 */
@Stable
class TvFocusTarget {
    val requester = FocusRequester()

    // A count rather than a flag: during a navigation transition the outgoing and incoming copies
    // of a screen are both composed for a frame, and a flag would land on whichever of the two
    // callbacks ran last instead of on whether anything still holds the requester.
    private var holders by mutableStateOf(0)
    private var focusedHolders by mutableStateOf(0)

    /** True while at least one composable is applying [tvFocusTarget] for this target. */
    val isAttached: Boolean get() = holders > 0

    /**
     * True while focus is on this target or inside it.
     *
     * Counted for the same reason as [holders], and read straight after a request rather than
     * observed: Compose dispatches focus events synchronously inside `requestFocus`, so by the
     * time it returns this already says whether the request landed.
     */
    val holdsFocus: Boolean get() = focusedHolders > 0

    internal fun onAttach() {
        holders++
    }

    internal fun onDetach() {
        holders--
    }

    internal fun onFocusChanged(focused: Boolean) {
        focusedHolders += if (focused) 1 else -1
    }
}

@Composable
fun rememberTvFocusTarget(): TvFocusTarget = remember { TvFocusTarget() }

/**
 * Attaches [target]'s requester here and tracks that attachment for the redirect sites.
 *
 * Null is accepted so phone layouts can pass nothing through the same parameter.
 */
fun Modifier.tvFocusTarget(target: TvFocusTarget?): Modifier =
    if (target == null) this else this
        .focusRequester(target.requester)
        .then(TvFocusTargetElement(target))

/**
 * Hands [direction] to [target], leaving the press to ordinary focus search when it cannot land.
 *
 * Deliberately not `Modifier.focusProperties { down = target.requester }`. Compose consults a
 * custom directional destination *instead of* the spatial search rather than before it:
 * `FocusOwnerImpl.focusSearch` returns `findFocusTargetNode()`'s result directly for any
 * destination that is not [FocusRequester.Default], and never reaches `twoDimensionalFocusSearch`.
 * A hand-off that fails therefore swallows the press and moves nothing, which on a television is
 * a remote that has gone dead rather than a key that did something unexpected. These targets are
 * held above the navigation graph so the viewer cannot even navigate the fault away — the app has
 * to be restarted.
 *
 * Driving the hand-off from a key handler keeps the fallback: returning false leaves the press
 * with Compose, which then runs the same search it would have run with no redirect at all.
 *
 * What the press is answered with is [TvFocusTarget.holdsFocus], not whether the request threw.
 * [FocusRequester.requestFocus] discards the boolean it gets back and only throws when *nothing at
 * all* holds the requester, so a node that holds it and then refuses focus used to read as a
 * success and eat the key. Asking the target where focus actually ended up tells the two apart.
 */
fun Modifier.tvFocusHandoff(direction: Key, target: TvFocusTarget?): Modifier =
    if (target == null) this else onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.key != direction) {
            return@onPreviewKeyEvent false
        }
        if (!target.isAttached) return@onPreviewKeyEvent false
        runCatching { target.requester.requestFocus() }
            .onFailure { DiagnosticsLog.throwable("TV focus hand-off has no target to move to", it) }
        target.holdsFocus
    }

private data class TvFocusTargetElement(
    val target: TvFocusTarget,
) : ModifierNodeElement<TvFocusTargetNode>() {
    override fun create() = TvFocusTargetNode(target)

    override fun update(node: TvFocusTargetNode) {
        node.setTarget(target)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "tvFocusTarget"
        properties["target"] = target
    }
}

private class TvFocusTargetNode(
    private var target: TvFocusTarget,
) : Modifier.Node(), FocusEventModifierNode {
    // What this node last told the target, so a repeated event of the same kind — and there are
    // several per focus change — does not count twice.
    private var reportedFocus = false

    override fun onFocusEvent(focusState: FocusState) {
        report(focusState.isFocused || focusState.hasFocus)
    }

    override fun onAttach() {
        target.onAttach()
    }

    override fun onDetach() {
        report(false)
        target.onDetach()
    }

    fun setTarget(next: TvFocusTarget) {
        if (next === target) return
        // The chain swapped destinations underneath us; the old one is no longer held here.
        if (isAttached) {
            val focused = reportedFocus
            report(false)
            target.onDetach()
            next.onAttach()
            target = next
            report(focused)
            return
        }
        target = next
    }

    private fun report(focused: Boolean) {
        if (focused == reportedFocus) return
        reportedFocus = focused
        target.onFocusChanged(focused)
    }
}
