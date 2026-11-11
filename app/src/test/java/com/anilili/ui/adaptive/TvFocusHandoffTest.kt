package com.anilili.ui.adaptive

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class TvFocusHandoffTest {
    @Test
    fun noTargetLeavesTheChainAlone() {
        // Phones and the routes with nowhere named to go pass null through the same parameter.
        // Returning the receiver keeps the key interceptor off those chains entirely, so nothing
        // stands between the D-pad and Compose's own focus search where there is no hand-off to
        // make. A "simplification" that always installed the node would put one there.
        assertSame(Modifier, Modifier.tvFocusHandoff(Key.DirectionDown, null))
    }

    @Test
    fun aTargetInstallsTheHandoff() {
        assertNotSame(Modifier, Modifier.tvFocusHandoff(Key.DirectionDown, TvFocusTarget()))
    }

    @Test
    fun attachmentIsCountedSoOverlappingScreensDoNotCancelEachOther() {
        // A navigation transition composes the outgoing and incoming copies of a screen together.
        // The count has to survive the outgoing one leaving while the incoming one still holds it.
        val target = TvFocusTarget()
        target.onAttach()
        target.onAttach()
        target.onDetach()

        assert(target.isAttached)

        target.onDetach()

        assert(!target.isAttached)
    }

    @Test
    fun focusIsCountedTheSameWaySoAHandoffIsNotToldItMissed() {
        // The hand-off answers the key press with this, so a transition that hands focus from the
        // outgoing copy of a screen to the incoming one must never read as "focus left". Were this
        // a flag rather than a count, the outgoing node's parting event would clear what the
        // incoming node had just set, and Down would be given back to a focus search that has
        // nowhere left to send it.
        val target = TvFocusTarget()
        target.onFocusChanged(true)
        target.onFocusChanged(true)
        target.onFocusChanged(false)

        assert(target.holdsFocus)

        target.onFocusChanged(false)

        assert(!target.holdsFocus)
    }

    @Test
    fun aFreshTargetHoldsNoFocus() {
        // The starting point matters: a hand-off asked before anything has ever been focused reads
        // this, and has to conclude that it moved nothing.
        assert(!TvFocusTarget().holdsFocus)
    }
}
