package com.anilili.data.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeAlertLedgerTest {
    @Test
    fun `entries survive an encode and decode round trip`() {
        val entries = mapOf("154587:12" to 1_000L, "1:1" to 2_000L)
        assertEquals(entries, EpisodeAlertLedger.decode(EpisodeAlertLedger.encode(entries)))
    }

    @Test
    fun `missing or malformed storage decodes to nothing rather than failing`() {
        assertTrue(EpisodeAlertLedger.decode(null).isEmpty())
        assertTrue(EpisodeAlertLedger.decode("").isEmpty())
        assertEquals(mapOf("1:2" to 5L), EpisodeAlertLedger.decode("garbage,1:2=5,3:4=x"))
    }

    @Test
    fun `pruning drops entries past three weeks and keeps recent ones`() {
        val now = 30L * 24 * 60 * 60
        val pruned = EpisodeAlertLedger.prune(mapOf("old:1" to 0L, "new:1" to now - 60), now)
        assertEquals(setOf("new:1"), pruned.keys)
    }

    @Test
    fun `every path derives the same notification id for one episode`() {
        assertEquals(EpisodeAlertLedger.notificationId(154587, 12), EpisodeAlertLedger.notificationId(154587, 12))
        assertTrue(EpisodeAlertLedger.notificationId(154587, 12) != EpisodeAlertLedger.notificationId(154587, 13))
    }
}
