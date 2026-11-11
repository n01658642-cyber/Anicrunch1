package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [forEachMatch] and friends to `findAll`'s results.
 *
 * They exist only to stop Android copying the input into native memory once per match, so the one
 * thing that must never differ is *what they find*. The interesting case is a pattern that can
 * match nothing: `findAll` steps past an empty match explicitly, and a bare `Matcher.find()` does
 * the same thing internally — if that ever stopped being true the walk would not terminate.
 */
class MatchScanTest {

    private val patterns = listOf(
        Regex("""<li\b[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE),
        Regex("""\d+"""),
        Regex("""a*"""),
        Regex("""(?:)"""),
        Regex("""x(y)?"""),
    )

    private val inputs = listOf(
        "",
        "bb",
        "aaa b aa",
        "<li>1</li><li class='x'>2</li>",
        "xy x xy",
        "12 34 56",
    )

    @Test
    fun `forEachMatch finds what findAll finds`() {
        for (pattern in patterns) {
            for (input in inputs) {
                val expected = pattern.findAll(input).map { it.value }.toList()
                val actual = mutableListOf<String>()
                pattern.forEachMatch(input) { actual += it.group() }
                assertEquals("$pattern over \"$input\"", expected, actual)
            }
        }
    }

    @Test
    fun `mapMatches drops nulls like mapNotNull and keeps order`() {
        val input = "<li>7</li><li>skip</li><li>9</li>"
        val pattern = Regex("""<li>([\s\S]*?)</li>""")
        val expected = pattern.findAll(input).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        val actual = pattern.mapMatches(input) { it.group(1)?.toIntOrNull() }
        assertEquals(listOf(7, 9), expected)
        assertEquals(expected, actual)
    }

    @Test
    fun `firstMatchNotNullOf stops at the first hit`() {
        val input = "<li>a</li><li>4</li><li>5</li>"
        val pattern = Regex("""<li>([\s\S]*?)</li>""")
        var visited = 0
        val found = pattern.firstMatchNotNullOf(input) {
            visited++
            it.group(1)?.toIntOrNull()
        }
        assertEquals(4, found)
        assertEquals(2, visited)
    }

    @Test
    fun `firstMatchNotNullOf is null when nothing transforms`() {
        assertNull(Regex("""\d""").firstMatchNotNullOf("abc") { it.group() })
    }

    /** A group that did not participate reads as null, where `groupValues` would give "". */
    @Test
    fun `an optional group is null rather than empty`() {
        val found = Regex("""x(y)?""").mapMatches("xy x") { it.group(1) }
        assertEquals(listOf("y"), found)
    }
}
