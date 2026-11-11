package com.anilili.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesLogoTest {
    private val clear = 0x00FFFFFF
    private val ink = 0xFFFFFFFF.toInt()
    private val faint = 0x10FFFFFF // alpha 16: the floor itself, which counts as empty

    /** A width x height canvas that is [clear] except where [paint] says otherwise. */
    private fun bounds(width: Int, height: Int, paint: (x: Int, y: Int) -> Int?): IntArray? =
        TrimTransparentEdges.visibleBounds(width, height) { y, row ->
            for (x in 0 until width) row[x] = paint(x, y) ?: clear
        }

    @Test
    fun `a wordmark centred in a square is bounded by its ink`() {
        // The TVDB `icons/` shape, scaled down: a wide band across the middle of a square.
        val result = bounds(100, 100) { x, y -> if (x in 1..98 && y in 37..64) ink else null }
        assertArrayEquals(intArrayOf(1, 37, 98, 64), result)
    }

    @Test
    fun `left and right come from the widest rows, not the first`() {
        // A stacked lockup: a narrow top line over a wide bottom one.
        val result = bounds(50, 20) { x, y ->
            when {
                y in 2..5 && x in 20..29 -> ink
                y in 10..14 && x in 3..46 -> ink
                else -> null
            }
        }
        assertArrayEquals(intArrayOf(3, 2, 46, 14), result)
    }

    @Test
    fun `margin noise at the alpha floor does not hold the box open`() {
        val result = bounds(40, 40) { x, y ->
            when {
                x == 0 || y == 39 -> faint
                x in 10..30 && y in 15..20 -> ink
                else -> null
            }
        }
        assertArrayEquals(intArrayOf(10, 15, 30, 20), result)
    }

    @Test
    fun `an entirely transparent image has no bounds`() {
        assertNull(bounds(16, 16) { _, _ -> null })
    }
}
