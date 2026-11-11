package com.anilili.ui.theme

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class AppBackdropTest {

    @Test
    fun `a wide picture on a tall phone keeps its middle`() {
        // 1920x1080 onto 540x1200: the full height, a centred 486-wide column.
        assertEquals(IntRect(717, 0, 1203, 1080), centreCrop(1920, 1080, 540, 1200))
    }

    @Test
    fun `a tall picture on a television keeps its middle band`() {
        assertEquals(IntRect(0, 577, 1080, 1185), centreCrop(1080, 1763, 960, 540))
    }

    @Test
    fun `a picture already the window's shape is used whole`() {
        assertEquals(IntRect(0, 0, 1920, 1080), centreCrop(1920, 1080, 960, 540))
    }
}
