package com.anilili.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeControlsTest {

    @Test
    fun `hex parses with or without the hash, in either case`() {
        assertEquals(0xFFD1102B.toInt(), parseHexColour("#D1102B"))
        assertEquals(0xFFD1102B.toInt(), parseHexColour("d1102b"))
        assertEquals(0xFF000000.toInt(), parseHexColour(" #000000 "))
    }

    @Test
    fun `anything that is not six hex digits is refused`() {
        assertNull(parseHexColour("#FFF"))
        assertNull(parseHexColour("#GG0000"))
        assertNull(parseHexColour("#FF000000"))
        assertNull(parseHexColour(""))
    }
}
