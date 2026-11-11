package com.anilili.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWatchedDeliveryTest {

    private fun mark(id: Int, at: Long = 1_000L) = WatchedEntry(anilistId = id, title = "t$id", cover = null, watchedAt = at)

    @Test
    fun `a title the account does not hold is sent`() {
        val plan = plannedWatchedDelivery(listOf(mark(1)), emptyMap())
        assertEquals(listOf(1), plan.push.map(WatchedEntry::anilistId))
        assertEquals(emptySet<Int>(), plan.settled)
    }

    @Test
    fun `a title the account already calls completed is settled, not sent`() {
        val plan = plannedWatchedDelivery(listOf(mark(1, at = 5_000L)), mapOf(1 to ("COMPLETED" to 0L)))
        assertEquals(emptyList<WatchedEntry>(), plan.push)
        assertEquals(setOf(1), plan.settled)
    }

    @Test
    fun `an older account status is overwritten by the mark`() {
        val plan = plannedWatchedDelivery(listOf(mark(1, at = 5_000L)), mapOf(1 to ("PLANNING" to 4_000L)))
        assertEquals(listOf(1), plan.push.map(WatchedEntry::anilistId))
    }

    @Test
    fun `a newer account status wins over the mark`() {
        val plan = plannedWatchedDelivery(listOf(mark(1, at = 5_000L)), mapOf(1 to ("REPEATING" to 6_000L)))
        assertEquals(emptyList<WatchedEntry>(), plan.push)
        assertEquals(setOf(1), plan.settled)
    }

    @Test
    fun `a catalogue-only title stays on the device`() {
        val plan = plannedWatchedDelivery(listOf(mark(-7)), emptyMap())
        assertEquals(emptyList<WatchedEntry>(), plan.push)
        assertEquals(emptySet<Int>(), plan.settled)
    }

    @Test
    fun `the last episode finishes the series, as an account's progress does`() {
        assertTrue(finishesSeries(12, 12))
        assertTrue(finishesSeries(13, 12))
        assertFalse(finishesSeries(11, 12))
    }

    @Test
    fun `a title with no known length is never finished by an episode`() {
        assertFalse(finishesSeries(1100, null))
        assertFalse(finishesSeries(5, 0))
    }
}
