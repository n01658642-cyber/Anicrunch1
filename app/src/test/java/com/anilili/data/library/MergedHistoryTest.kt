package com.anilili.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

class MergedHistoryTest {
    private fun entry(id: Int, updatedAt: Long, title: String = "Title $id", fromRemote: Boolean = false) =
        HistoryEntry(
            anilistId = id,
            title = title,
            cover = null,
            episodeNumber = 1.0,
            provider = "auto",
            category = "sub",
            updatedAt = updatedAt,
            fromRemote = fromRemote,
        )

    @Test
    fun `a title the account reports twice becomes one row`() {
        // AniList can return a title under more than one status, and two MyAnimeList ids can map
        // to a single AniList id. These ids are LazyColumn keys, and a repeat crashes the library
        // screen — three device reports on 0.1.65 died exactly that way after an account refresh.
        val seeded = listOf(
            entry(127399, updatedAt = 300, title = "Kept", fromRemote = true),
            entry(127399, updatedAt = 100, title = "Duplicate", fromRemote = true),
            entry(21, updatedAt = 200, fromRemote = true),
        )

        val merged = mergedHistory(local = emptyList(), seeded = seeded, max = 50)

        assertEquals(listOf(127399, 21), merged.map { it.anilistId })
        assertEquals("Kept", merged.first().title)
    }

    @Test
    fun `the newest record of a title wins over an older one`() {
        val local = listOf(entry(7, updatedAt = 900, title = "Played here"))
        val seeded = listOf(entry(7, updatedAt = 100, title = "Seeded", fromRemote = true))

        val merged = mergedHistory(local, seeded, max = 50)

        assertEquals(1, merged.size)
        assertEquals("Played here", merged.single().title)
    }

    @Test
    fun `a duplicate does not spend one of the capped slots`() {
        val seeded = listOf(
            entry(1, updatedAt = 500),
            entry(1, updatedAt = 400),
            entry(2, updatedAt = 300),
        )

        // Two distinct titles exist, so a cap of two must return both rather than one plus a repeat.
        assertEquals(listOf(1, 2), mergedHistory(emptyList(), seeded, max = 2).map { it.anilistId })
    }

    @Test
    fun `ordering stays newest first`() {
        val merged = mergedHistory(
            local = listOf(entry(1, updatedAt = 10), entry(2, updatedAt = 30)),
            seeded = listOf(entry(3, updatedAt = 20, fromRemote = true)),
            max = 50,
        )

        assertEquals(listOf(2, 3, 1), merged.map { it.anilistId })
    }
}
