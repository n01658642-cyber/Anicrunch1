package com.anilili.playback

import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitBackoffTest {

    @Test
    fun `without Retry-After the wait doubles from five seconds`() {
        assertEquals(
            listOf(5_000L, 10_000L, 20_000L, 40_000L),
            (0 until MAX_RATE_LIMIT_RETRIES).map { rateLimitDelayMs(it, null, 0L) },
        )
    }

    @Test
    fun `Retry-After in seconds wins over the schedule`() {
        assertEquals(7_000L, rateLimitDelayMs(attempt = 3, retryAfter = "7", nowMs = 0L))
    }

    @Test
    fun `Retry-After as an HTTP date is measured from now`() {
        val now = 784_111_777_000L // Sun, 06 Nov 1994 08:49:37 GMT
        assertEquals(
            30_000L,
            rateLimitDelayMs(0, "Sun, 06 Nov 1994 08:50:07 GMT", now),
        )
    }

    @Test
    fun `an absurd or useless Retry-After is clamped`() {
        assertEquals(120_000L, rateLimitDelayMs(0, "86400", 0L))
        assertEquals(1_000L, rateLimitDelayMs(0, "0", 0L))
        // Unparseable: falls back to the schedule rather than to no wait at all.
        assertEquals(5_000L, rateLimitDelayMs(0, "soon", 0L))
    }

    @Test
    fun `a cooled-down host makes every caller wait out the remainder`() {
        var now = 1_000L
        val slept = mutableListOf<Long>()
        val cooldowns = HostCooldowns(clock = { now }, sleep = { slept += it; now += it })
        cooldowns.coolDown("cdn.example", 10_000L)
        now += 4_000L
        cooldowns.awaitClear("cdn.example")
        assertEquals(listOf(6_000L), slept)
        // Cleared now: a second caller does not wait again.
        cooldowns.awaitClear("cdn.example")
        assertEquals(listOf(6_000L), slept)
    }

    @Test
    fun `cooldowns are per host and never shortened`() {
        var now = 0L
        val slept = mutableListOf<Long>()
        val cooldowns = HostCooldowns(clock = { now }, sleep = { slept += it; now += it })
        cooldowns.coolDown("a.example", 20_000L)
        cooldowns.coolDown("a.example", 5_000L)
        cooldowns.awaitClear("b.example")
        assertTrue(slept.isEmpty())
        cooldowns.awaitClear("a.example")
        assertEquals(listOf(20_000L), slept)
    }

    private class TooMany(val retryAfter: String? = null) : IOException("429")

    private fun limitedOf(error: IOException) = (error as? TooMany)?.let { RateLimited(it.retryAfter) }

    @Test
    fun `a 429 is waited out and the request retried until it succeeds`() {
        var now = 0L
        val slept = mutableListOf<Long>()
        val cooldowns = HostCooldowns(clock = { now }, sleep = { slept += it; now += it })
        var calls = 0
        val result = openWithRateLimitBackoff("cdn.example", cooldowns, ::limitedOf, nowMs = { now }) {
            calls++
            if (calls <= 2) throw TooMany() else 42L
        }
        assertEquals(42L, result)
        assertEquals(3, calls)
        assertEquals(listOf(5_000L, 10_000L), slept)
    }

    @Test
    fun `a 429 that never lifts is rethrown after the last retry`() {
        var now = 0L
        val cooldowns = HostCooldowns(clock = { now }, sleep = { now += it })
        var calls = 0
        val error = runCatching {
            openWithRateLimitBackoff("cdn.example", cooldowns, ::limitedOf, nowMs = { now }) {
                calls++
                throw TooMany()
            }
        }.exceptionOrNull()
        assertTrue(error is TooMany)
        assertEquals(MAX_RATE_LIMIT_RETRIES + 1, calls)
    }

    @Test
    fun `any other failure is rethrown at once, without waiting`() {
        val slept = mutableListOf<Long>()
        val cooldowns = HostCooldowns(clock = { 0L }, sleep = { slept += it })
        var calls = 0
        val error = runCatching {
            openWithRateLimitBackoff("cdn.example", cooldowns, ::limitedOf) {
                calls++
                throw IOException("403")
            }
        }.exceptionOrNull()
        assertEquals("403", error?.message)
        assertEquals(1, calls)
        assertTrue(slept.isEmpty())
    }

    @Test(expected = InterruptedIOException::class)
    fun `a cancelled download stops waiting immediately`() {
        val cooldowns = HostCooldowns(clock = { 0L }, sleep = { throw InterruptedException() })
        cooldowns.coolDown("cdn.example", 10_000L)
        try {
            cooldowns.awaitClear("cdn.example")
        } finally {
            Thread.interrupted() // clear the flag the cooldown restored, for the next test
        }
    }
}
