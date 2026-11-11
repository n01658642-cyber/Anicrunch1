package com.anilili.ui.profile

import com.anilili.data.library.WatchlistEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class CombinedWatchlistTest {
    @Test
    fun `a saved list holding one anime twice still yields one card`() {
        // These ids become LazyColumn keys, and a repeat is fatal: Compose throws
        // IllegalArgumentException("Key 185874 was already used") from inside measure, with no app
        // frame in the stack to point at. Reported from a phone on the library screen.
        val local = listOf(
            WatchlistEntry(anilistId = 185874, title = "Dandadan", cover = null, addedAt = 2),
            WatchlistEntry(anilistId = 185874, title = "Dandadan", cover = null, addedAt = 1),
            WatchlistEntry(anilistId = 21, title = "One Piece", cover = null, addedAt = 3),
        )

        val cards = buildCombinedWatchlist(profile = null, local = local, history = emptyList())

        assertEquals(listOf(185874, 21), cards.map { it.id })
        assertEquals(cards.map { it.id }.distinct(), cards.map { it.id })
    }

    @Test
    fun `the first copy of a duplicate is the one kept`() {
        val local = listOf(
            WatchlistEntry(anilistId = 7, title = "Kept", cover = "a", addedAt = 1),
            WatchlistEntry(anilistId = 7, title = "Dropped", cover = "b", addedAt = 2),
        )

        val cards = buildCombinedWatchlist(profile = null, local = local, history = emptyList())

        assertEquals(1, cards.size)
        assertEquals("Kept", cards.single().title)
    }
}
