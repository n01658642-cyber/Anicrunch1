package com.anilili.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class CachedRegexTest {
    @Test
    fun `the same pattern and options compile once`() {
        val first = Regex.cached("""<iframe\b[^>]*>""", RegexOption.IGNORE_CASE)
        val second = Regex.cached("""<iframe\b[^>]*>""", RegexOption.IGNORE_CASE)
        assertSame(first, second)
    }

    @Test
    fun `options are part of the identity`() {
        val sensitive = Regex.cached("abc")
        val insensitive = Regex.cached("abc", RegexOption.IGNORE_CASE)
        assertNotSame(sensitive, insensitive)
        assertEquals(false, sensitive.containsMatchIn("ABC"))
        assertEquals(true, insensitive.containsMatchIn("ABC"))
    }

    @Test
    fun `a set of options matches the single-option form`() {
        assertSame(
            Regex.cached("x+", RegexOption.IGNORE_CASE),
            Regex.cached("x+", setOf(RegexOption.IGNORE_CASE)),
        )
    }
}
