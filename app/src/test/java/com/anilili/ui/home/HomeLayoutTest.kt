package com.anilili.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HomeLayoutTest {
    private val phone = HomeLayout.default(isTv = false)

    @Test
    fun `nothing stored is the device's default`() {
        assertEquals(phone, HomeLayout.decode(null, isTv = false))
        assertEquals(phone, HomeLayout.decode("", isTv = false))
        assertEquals(HomeRow.TRENDING, HomeLayout.decode(null, isTv = true).order[3])
        assertEquals(HomeRow.NEWEST, phone.order[3])
    }

    @Test
    fun `a layout survives being stored and read back`() {
        val custom = phone.move(HomeRow.MOVIES, 0).withHidden(HomeRow.UPCOMING, true)
        assertEquals(custom, HomeLayout.decode(custom.encode(), isTv = false))
        assertEquals(HomeRow.MOVIES, custom.order.first())
        assertEquals(false, HomeRow.UPCOMING in custom.visible)
    }

    @Test
    fun `unknown keys and repeats are dropped`() {
        val layout = HomeLayout.decode("movies,banana,movies,-continue", isTv = false)
        assertEquals(1, layout.order.count { it == HomeRow.MOVIES })
        assertEquals(HomeRow.entries.size, layout.order.size)
        assertEquals(setOf(HomeRow.CONTINUE), layout.hidden)
    }

    @Test
    fun `a row the stored layout never mentioned returns to its default place`() {
        // Stored by a build that had no For You / Because rows, with Movies moved to the top.
        val layout = HomeLayout.decode("movies,continue,newest,trending,popular,top_rated,upcoming", isTv = false)
        assertEquals(
            listOf(
                HomeRow.MOVIES, HomeRow.CONTINUE, HomeRow.FOR_YOU, HomeRow.BECAUSE, HomeRow.NEWEST,
                HomeRow.TRENDING, HomeRow.POPULAR, HomeRow.TOP_RATED, HomeRow.UPCOMING,
            ),
            layout.order,
        )
    }

    @Test
    fun `a missing first row goes to the top`() {
        val layout = HomeLayout.decode("newest,movies", isTv = false)
        assertEquals(HomeRow.CONTINUE, layout.order.first())
    }

    @Test
    fun `moving is clamped and a no-op move returns the same layout`() {
        assertEquals(HomeRow.CONTINUE, phone.move(HomeRow.CONTINUE, 99).order.last())
        assertEquals(HomeRow.UPCOMING, phone.move(HomeRow.UPCOMING, -5).order.first())
        assertSame(phone, phone.move(HomeRow.NEWEST, phone.order.indexOf(HomeRow.NEWEST)))
    }

    @Test
    fun `dragging past an on-screen neighbour lands beside it, skipping rows Home does not draw`() {
        // Because you watched is hidden and For You empty, so on screen Continue's next neighbour is
        // New Episodes. Dragged past it, Continue goes right after New Episodes; the two undrawn rows
        // keep their places rather than being jumped over as index arithmetic would.
        val layout = phone.withHidden(HomeRow.BECAUSE, true)
        val moved = layout.placeBeside(HomeRow.CONTINUE, HomeRow.NEWEST, after = true)
        assertEquals(
            listOf(
                HomeRow.FOR_YOU, HomeRow.BECAUSE, HomeRow.NEWEST, HomeRow.CONTINUE, HomeRow.TRENDING,
                HomeRow.POPULAR, HomeRow.TOP_RATED, HomeRow.MOVIES, HomeRow.UPCOMING,
            ),
            moved.order,
        )
        assertEquals(setOf(HomeRow.BECAUSE), moved.hidden)
        assertEquals(phone.order, moved.placeBeside(HomeRow.CONTINUE, HomeRow.FOR_YOU, after = false).order)
        assertSame(phone, phone.placeBeside(HomeRow.MOVIES, HomeRow.MOVIES, after = true))
    }
}
