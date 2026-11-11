package com.anilili.data.recs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeighbourGraphTest {
    private fun graph(vararg lines: String, header: String = "#anilili-neighbours\t1\t2026-10-03") =
        NeighbourGraph.parse((listOf(header) + lines).joinToString("\n").toByteArray())

    @Test
    fun `rows parse into neighbours, roots and flags`() {
        val g = graph(
            "10\t10\t0\t20:90,30:40",
            "20\t20\t1\t10:90",
            "30\t5\t2\t",
        )
        assertEquals(3, g.size)
        assertEquals(3, g.edgeCount)
        assertEquals(listOf(20 to 90, 30 to 40), g.neighbours(10))
        assertEquals(emptyList<Pair<Int, Int>>(), g.neighbours(30))
        assertEquals(5, g.rootOf(30))
        assertEquals(99, g.rootOf(99))
        assertTrue(g.isAdult(20))
        assertFalse(g.isAdult(10))
        assertTrue(g.isUnreleased(30))
        assertEquals("2026-10-03", g.generated)
    }

    @Test
    fun `unsorted rows are still found by id`() {
        val g = graph("30\t30\t0\t10:5", "10\t10\t0\t30:7", "20\t20\t0\t")
        assertEquals(listOf(10 to 5), g.neighbours(30))
        assertEquals(listOf(30 to 7), g.neighbours(10))
        assertEquals(emptyList<Pair<Int, Int>>(), g.neighbours(20))
    }

    @Test
    fun `a malformed row costs that row only`() {
        val g = graph("10\t10\t0\t20:90", "garbage line", "", "20\t20\t0\t10:80\r")
        assertEquals(2, g.size)
        assertEquals(listOf(10 to 80), g.neighbours(20))
    }

    @Test
    fun `weights are held to 1-100`() {
        val g = graph("10\t10\t0\t20:250,30:0")
        assertEquals(listOf(20 to 100, 30 to 1), g.neighbours(10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a body that is not a neighbour file is refused`() {
        graph("10\t10\t0\t20:90", header = "<!DOCTYPE html>")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a future format version is refused`() {
        graph("10\t10\t0\t20:90", header = "#anilili-neighbours\t2\tx")
    }

    @Test
    fun `growth past the initial capacity keeps every row`() {
        val rows = (1..40_000).map { id -> "$id\t$id\t0\t${id + 1}:50,${id + 2}:25" }
        val g = graph(*rows.toTypedArray())
        assertEquals(40_000, g.size)
        assertEquals(80_000, g.edgeCount)
        assertEquals(listOf(39_001 to 50, 39_002 to 25), g.neighbours(39_000))
    }
}
