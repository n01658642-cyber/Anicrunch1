package com.anilili.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShowColourThemeTest {

    @Test
    fun `a coloured cover seeds the page`() {
        assertEquals(0xFFE4A15D.toInt(), showSeed("#e4a15d"))
        assertEquals(0xFF1A5DE4.toInt(), showSeed("1a5de4"))
    }

    @Test
    fun `a grey cover leaves the app's own theme`() {
        assertNull(showSeed("#1a1a1a"))
        assertNull(showSeed("#e4e4e4"))
        assertNull(showSeed("#5d5b5e"))
    }

    @Test
    fun `nothing usable leaves the app's own theme`() {
        assertNull(showSeed(null))
        assertNull(showSeed(""))
        assertNull(showSeed("#12345"))
        assertNull(showSeed("#zzzzzz"))
    }
}
