package com.anilili.ui.random

import com.anilili.data.model.Media
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RandomAnimeDeckTest {
    /** Page n holds ids n*100 .. n*100+size-1, so a dealt id names the page it came from. */
    private fun page(n: Int, size: Int = 3) = (0 until size).map { Media(id = n * 100 + it) }

    @Test
    fun `a page is dealt out before another is fetched`(): Unit = runBlocking {
        val fetched = mutableListOf<Int>()
        val deck = RandomAnimeDeck({ p -> fetched += p; page(p) }, pageCount = 5, random = Random(1))
        val ids = List(3) { deck.draw()!!.id }
        assertEquals(1, fetched.size)
        assertEquals(page(fetched.single()).map { it.id }.toSet(), ids.toSet())
    }

    @Test
    fun `nothing repeats until every page has been dealt`(): Unit = runBlocking {
        val deck = RandomAnimeDeck({ p -> page(p) }, pageCount = 4, random = Random(7))
        val ids = List(12) { deck.draw()!!.id }
        assertEquals(12, ids.toSet().size)
        // The pool is spent; the deck starts over rather than running dry.
        assertNotNull(deck.draw())
    }

    @Test
    fun `skipped titles are left out while anything else remains`(): Unit = runBlocking {
        val deck = RandomAnimeDeck({ p -> page(p) }, pageCount = 1, skip = { it.id == 100 })
        val ids = List(2) { deck.draw()!!.id }.toSet()
        assertEquals(setOf(101, 102), ids)
    }

    @Test
    fun `a page with only skipped titles is still dealt`(): Unit = runBlocking {
        val deck = RandomAnimeDeck({ p -> page(p) }, pageCount = 1, skip = { true })
        assertNotNull(deck.draw())
    }

    @Test
    fun `an empty page shrinks the range so later draws land on full pages`(): Unit = runBlocking {
        val fetched = mutableListOf<Int>()
        // Only pages 1 and 2 still exist, though the deck was told there were 10.
        val deck = RandomAnimeDeck(
            { p -> fetched += p; if (p <= 2) page(p) else emptyList() },
            pageCount = 10,
            random = Random(3),
        )
        val dealt = List(12) { deck.draw() }.filterNotNull()
        // Six titles exist; once the range has shrunk onto them every one is reachable.
        assertEquals(6, dealt.take(6).map { it.id }.toSet().size)
        val empties = fetched.filter { it > 2 }
        // The range only ever shrinks below an empty page, so none is asked for twice.
        assertEquals(empties.size, empties.toSet().size)
    }

    @Test
    fun `a pool that answers nothing gives up rather than spinning`(): Unit = runBlocking {
        var calls = 0
        val deck = RandomAnimeDeck({ calls++; emptyList() }, pageCount = 3)
        assertNull(deck.draw())
        assertTrue(calls <= 4)
    }

    @Test
    fun `going round again never deals the same title twice running`(): Unit = runBlocking {
        // A two-title list goes round every other card; any seed that reshuffled the last card back
        // on top would show it twice in a row.
        repeat(20) { seed ->
            val deck = RandomAnimeDeck({ page(1, size = 2) }, pageCount = 1, random = Random(seed))
            val ids = List(10) { deck.draw()!!.id }
            ids.zipWithNext().forEach { (a, b) -> assertTrue("seed $seed dealt $a twice", a != b) }
        }
    }

    @Test
    fun `upNext is the card the next draw deals`(): Unit = runBlocking {
        val deck = RandomAnimeDeck({ p -> page(p) }, pageCount = 1, random = Random(5))
        deck.draw()
        val next = deck.upNext
        assertEquals(next?.id, deck.draw()?.id)
    }

    @Test
    fun `every third card comes from the taste source`(): Unit = runBlocking {
        val taste = listOf(Media(id = 900), Media(id = 901))
        val deck = RandomAnimeDeck({ p -> page(p, size = 10) }, pageCount = 1, random = Random(2), tasteSource = { taste })
        val ids = List(6) { deck.draw()!!.id }
        assertEquals(listOf(900, 901), listOf(ids[2], ids[5]))
        assertTrue(ids.filterIndexed { i, _ -> i % 3 != 2 }.all { it < 900 })
    }

    @Test
    fun `an empty taste source falls back to the pool and is not asked every card`(): Unit = runBlocking {
        var asked = 0
        val deck = RandomAnimeDeck({ p -> page(p, size = 20) }, pageCount = 1, tasteSource = { asked++; emptyList() })
        val ids = List(9) { deck.draw()?.id }
        assertTrue(ids.all { it != null && it < 900 })
        assertEquals(1, asked)
    }

    @Test
    fun `taste picks already dealt or skipped are not dealt again`(): Unit = runBlocking {
        val deck = RandomAnimeDeck(
            { p -> page(p, size = 10) },
            pageCount = 1,
            skip = { it.id == 902 },
            tasteSource = { listOf(Media(id = 902), Media(id = 903)) },
        )
        val ids = List(6) { deck.draw()!!.id }
        assertEquals(903, ids[2])
        assertEquals(1, ids.count { it == 903 })
        assertTrue(902 !in ids)
    }

    @Test
    fun `fact line keeps every piece, however long the run`() {
        assertEquals(listOf("TV", "1999", "1155 eps"), factPieces(format = "TV", year = 1999, episodes = 1155))
        // A film's single "episode" says nothing, so it is the one thing never shown.
        assertEquals(listOf("Movie", "2016"), factPieces(format = "Movie", year = 2016, episodes = 1))
    }

    @Test
    fun `a show still airing counts what has aired`() {
        // ONE PIECE as AniList sends it on 2026-10-03: no total, episode 1181 next.
        assertEquals(1180, episodeCount(total = null, nextAiring = 1181))
        // A planned total wins over the aired count; a finished show has only the total.
        assertEquals(12, episodeCount(total = 12, nextAiring = 5))
        assertEquals(101, episodeCount(total = 101, nextAiring = null))
        // Announced, nothing aired yet: no count rather than "0 eps".
        assertNull(episodeCount(total = null, nextAiring = 1))
    }

    @Test
    fun `synopsis loses markup and the source credit`() {
        val raw = "A boy &amp; his dog.<br><br>\n<i>They travel.</i>\n\n(Source: Crunchyroll)"
        assertEquals("A boy & his dog.\nThey travel.", plainSynopsis(raw))
        assertNull(plainSynopsis("  "))
        assertNull(plainSynopsis("(Source: AniDB)"))
    }
}
