package com.anilili.ui.watch

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportRowLiftTest {
    /** Where the row's bottom edge ends up above the frame's bottom edge, for a given padding. */
    private fun rowBottom(frameHeight: Dp, rowHeight: Dp, padding: Dp): Dp = (frameHeight - rowHeight + padding) / 2

    /** The skip button's visible top: its 48dp box sits on the gap with the pill centred in it. */
    private fun actionTop(seekAnchor: Dp, compactHeight: Boolean, actionHeight: Dp): Dp =
        seekAnchor + (if (compactHeight) CompactControlsBarActionGap else ControlsBarActionGap) +
            (maxOf(actionHeight, 48.dp) + actionHeight) / 2

    /**
     * The inline 16:9 frame on one phone at Android's Display size steps: the same glass measures
     * more dp as the setting goes down. 540dp is what "Small" made of a Pixel 10 Pro, and it is over
     * the 300dp compact line, which is the case the old tier check missed. Below 400dp the chrome
     * is drawn smaller (PlayerChromeBox), so it is laid out in the frame as that scale measures it.
     */
    @Test
    fun `the row clears the skip action at every display size`() {
        for (screenWidthDp in listOf(320, 360, 384, 392, 411, 448, 480, 540, 600)) {
            val widthDp = (screenWidthDp / playerChromeScale(screenWidthDp.dp)).toInt()
            assertTrue("chrome laid out at ${widthDp}dp for a ${screenWidthDp}dp frame", widthDp >= 392)
            val frameHeight = (widthDp * 9 / 16).dp
            val metrics = playerLayoutMetrics(widthDp, frameHeight.value.toInt())
            val compact = metrics.compactHeight
            val rowHeight = metrics.playButtonSize
            // The bar is shorter in the compact tier, where the time row shares a line with the icons.
            val seekAnchor = if (compact) 72.dp else 96.dp
            for (actionHeight in listOf(if (compact) CompactSkipActionHeight else 40.dp, 52.dp)) {
                val padding = transportRowBottomPadding(frameHeight, rowHeight, seekAnchor, compact, actionHeight)
                val bottom = rowBottom(frameHeight, rowHeight, padding)
                val top = frameHeight - bottom - rowHeight
                assertTrue("negative padding at ${widthDp}dp", padding >= 0.dp)
                assertTrue("row leaves the frame at ${widthDp}dp", top >= 0.dp)
                // Where there is room for both, the row is clear; where there is not, it is as high as it goes.
                val roomForBoth = frameHeight - rowHeight >= actionTop(seekAnchor, compact, actionHeight) + 8.dp
                if (roomForBoth) {
                    assertTrue(
                        "row bottom $bottom under the action top at ${widthDp}dp/$actionHeight",
                        bottom >= actionTop(seekAnchor, compact, actionHeight),
                    )
                }
            }
        }
    }

    @Test
    fun `only frames narrower than the minimum are drawn smaller`() {
        assertEquals(1f, playerChromeScale(400.dp))
        assertEquals(1f, playerChromeScale(914.dp))
        assertEquals(0.9f, playerChromeScale(360.dp), 0.0001f)
        assertEquals(0.8f, playerChromeScale(320.dp), 0.0001f)
        // Picture-in-picture sized: not shrunk past the point of being pressable.
        assertEquals(0.75f, playerChromeScale(200.dp), 0.0001f)
        // Not yet measured.
        assertEquals(1f, playerChromeScale(0.dp))
    }

    @Test
    fun `a tall frame keeps the row centred`() {
        // Fullscreen phone landscape: plenty of room, so nothing moves.
        assertEquals(0.dp, transportRowBottomPadding(411.dp, 54.dp, 104.dp, compactHeight = false, actionHeight = 40.dp))
    }

    @Test
    fun `the compact tier still centres the row above the bar when that is higher`() {
        // 80915d7e's placement, kept as the floor.
        assertEquals(72.dp, transportRowBottomPadding(231.dp, 48.dp, 72.dp, compactHeight = true, actionHeight = 28.dp))
    }

    @Test
    fun `an unmeasured row or a collapsed frame yields no negative padding`() {
        assertTrue(transportRowBottomPadding(0.dp, 0.dp, 0.dp, compactHeight = true, actionHeight = 28.dp) >= 0.dp)
        assertTrue(transportRowBottomPadding(40.dp, 48.dp, 72.dp, compactHeight = true, actionHeight = 28.dp) >= 0.dp)
        assertTrue(transportRowBottomPadding(300.dp, 0.dp, 0.dp, compactHeight = false, actionHeight = 40.dp) >= 0.dp)
    }
}
