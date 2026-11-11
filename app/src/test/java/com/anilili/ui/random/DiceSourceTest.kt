package com.anilili.ui.random

import com.anilili.data.library.CustomList
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.WatchlistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiceSourceTest {
    private fun saved(id: Int) = WatchlistEntry(anilistId = id, title = "t$id", cover = null)
    private fun played(id: Int) = HistoryEntry(
        anilistId = id, title = "t$id", cover = null, episodeNumber = 1.0, provider = "auto", category = "sub",
    )

    private fun sources(
        saved: List<WatchlistEntry> = emptyList(),
        statuses: Map<Int, String> = emptyMap(),
        history: List<HistoryEntry> = emptyList(),
        marked: Set<Int> = emptySet(),
        lists: List<CustomList> = emptyList(),
    ) = diceSources(saved, statuses, history, marked, lists).associateBy { it.key }

    @Test
    fun `with no lists only anything is offered`() {
        assertEquals(listOf(DiceSource.ANYWHERE_KEY), sources().keys.toList())
    }

    @Test
    fun `plan to watch is the saves plus planning the saves have not caught up with`() {
        val got = sources(saved = listOf(saved(1), saved(2)), statuses = mapOf(2 to "PLANNING", 3 to "PLANNING"))
        assertEquals(listOf(1, 2, 3), got.getValue("status:PLANNING").ids)
    }

    @Test
    fun `watching takes the account and this device's progress, but not what is finished`() {
        val got = sources(
            statuses = mapOf(1 to "CURRENT", 2 to "REPEATING", 3 to "COMPLETED", 4 to "DROPPED"),
            history = listOf(played(3), played(4), played(5), played(6)),
            marked = setOf(6),
        )
        assertEquals(listOf(1, 2, 5), got.getValue("status:CURRENT").ids)
        assertEquals(listOf(3), got.getValue("status:COMPLETED").ids)
        assertEquals(listOf(4), got.getValue("status:DROPPED").ids)
    }

    @Test
    fun `empty lists and catalogue-native ids are not offered`() {
        val got = sources(
            lists = listOf(
                CustomList("Faves", listOf(saved(7))),
                CustomList("Empty"),
                CustomList("Native only", listOf(saved(-1_000_000_001))),
            ),
        )
        assertEquals(listOf(7), got.getValue("list:faves").ids)
        assertNull(got["list:empty"])
        assertNull(got["list:native only"])
        assertNull(got["status:PAUSED"])
    }
}
