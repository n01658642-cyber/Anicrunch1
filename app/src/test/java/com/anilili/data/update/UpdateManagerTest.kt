package com.anilili.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setting that enables the automatic check promises a prompt "each time the app opens". A
 * 12-hour throttle behind it meant someone who updated and reopened the app an hour later to a
 * newer release saw nothing, with no explanation anywhere — reported exactly that way when 0.1.60
 * shipped an hour behind 0.1.59. The floor that remains is only there to absorb activity
 * recreation.
 */
class UpdateCheckDueTest {
    private val floor = 5L * 60 * 1000

    @Test
    fun `an install that has never checked is due`() {
        assertTrue(updateCheckIsDue(nowMs = 1_000, lastCheckMs = 0L, floorMs = floor))
    }

    @Test
    fun `reopening the app an hour after updating is due`() {
        val checkedAt = 1_000L
        assertTrue(
            updateCheckIsDue(nowMs = checkedAt + 60L * 60 * 1000, lastCheckMs = checkedAt, floorMs = floor),
        )
    }

    @Test
    fun `an activity recreation seconds later is not`() {
        assertFalse(updateCheckIsDue(nowMs = 10_000, lastCheckMs = 5_000, floorMs = floor))
    }

    @Test
    fun `exactly at the floor counts as due`() {
        assertTrue(updateCheckIsDue(nowMs = floor + 1, lastCheckMs = 1, floorMs = floor))
    }

    @Test
    fun `a clock that moved backwards does not park the check forever`() {
        // A timezone change or a hand-set date can leave lastCheck in the future; without this the
        // app would stop checking until real time caught up.
        assertTrue(updateCheckIsDue(nowMs = 1_000, lastCheckMs = 99_999_999, floorMs = floor))
    }
}
