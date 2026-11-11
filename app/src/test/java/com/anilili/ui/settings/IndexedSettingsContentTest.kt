package com.anilili.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class IndexedSettingsContentTest {
    @Test
    fun `scroll target follows rendered order including notices and conditional rows`() {
        for (showConditional in listOf(false, true)) {
            val content = IndexedSettingsContent().apply {
                item(key = "First setting") {}
                if (showConditional) item(key = "Conditional setting") {}
                item {} // A transient notice still occupies a position.
                item(key = "Last setting") {}
            }
            assertEquals(if (showConditional) 3 else 2, content.indexOf("Last setting"))
        }
    }

    @Test
    fun `batched items contribute their full count without composing rows`() {
        val content = IndexedSettingsContent().apply {
            items(count = 20, key = { "Setting $it" }) { error("Must stay lazy") }
            item(key = "Target") { error("Must stay lazy") }
        }
        assertEquals(19, content.indexOf("Setting 19"))
        assertEquals(20, content.indexOf("Target"))
    }

    @Test
    fun `missing or cleared targets never fall back to an unrelated row`() {
        val content = IndexedSettingsContent().apply {
            item {}
            item(key = "Visible setting") {}
        }
        assertEquals(-1, content.indexOf("Hidden setting"))
        assertEquals(-1, content.indexOf(null))
        assertEquals(1, content.indexOf("Visible setting"))
    }
}
