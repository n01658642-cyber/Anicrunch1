package com.anilili.ui.watch

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsSeekAnchorTest {
    @Test
    fun `the anchor measures from the seek slider down`() {
        assertEquals(96.dp, controlsSeekAnchorFor(barHeight = 120.dp, barTopPadding = 24.dp))
    }

    @Test
    fun `a bar shorter than its own top padding yields no negative distance`() {
        // What leaving picture-in-picture produces: the bar is re-measured at the small window while
        // the metrics have already moved to the full-size tier's 24dp top padding.
        assertEquals(0.dp, controlsSeekAnchorFor(barHeight = 8.dp, barTopPadding = 24.dp))
        assertEquals(0.dp, controlsSeekAnchorFor(barHeight = 0.dp, barTopPadding = 26.dp))
    }

    @Test
    fun `every anchor survives being turned into a padding`() {
        // The crash this guards was an IllegalArgumentException out of Modifier.padding, so the
        // property that matters is the one the caller relies on, not the subtraction itself.
        for (barHeightDp in 0..200) {
            for (barTopPaddingDp in listOf(8, 20, 24, 26)) {
                val bottom = controlsSeekAnchorFor(barHeightDp.dp, barTopPaddingDp.dp) +
                    ControlsBarActionGap
                assertTrue("negative padding at ${barHeightDp}dp/${barTopPaddingDp}dp", bottom >= 0.dp)
            }
        }
    }
}
