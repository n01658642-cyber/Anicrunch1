package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fallback order is deliberately shuffled per instance (that is the load-spreading story), so
 * these tests pin behaviour relative to whatever [KartoonsTokenRotator.pick] returned first rather
 * than naming tokens.
 */
class KartoonsTokenRotatorTest {
    private var clock = 0L

    private fun rotator(vararg tokens: String) =
        KartoonsTokenRotator(tokens.toList(), now = { clock })

    @Test
    fun `pick is sticky while the token is healthy`() {
        val pool = rotator("a", "b", "c")
        val first = pool.pick()!!
        assertEquals(first, pool.pick())
        assertEquals(first, pool.pick())
    }

    @Test
    fun `a refused token moves the pick to a different one`() {
        val pool = rotator("a", "b", "c")
        val first = pool.pick()!!
        pool.markFailed(first)
        val second = pool.pick()!!
        assertNotEquals(first, second)
    }

    @Test
    fun `a cooling token stays out until its cooldown lapses`() {
        val pool = rotator("a", "b")
        val first = pool.pick()!!
        pool.markFailed(first)
        // First cooldown step is ten minutes.
        clock += 9 * 60_000L
        val onCooldown = pool.pick()!!
        assertNotEquals(first, onCooldown)
        clock += 2 * 60_000L
        // The second token is sticky and healthy, so the pick does not move again.
        assertEquals(onCooldown, pool.pick())
        pool.markFailed(onCooldown)
        // Both cooling, but the first one's ten minutes have lapsed.
        assertEquals(first, pool.pick())
    }

    @Test
    fun `repeated failures escalate the cooldown`() {
        val pool = rotator("a")
        pool.markFailed("a") // first step: ten minutes
        clock += 9 * 60_000L
        assertNull(pool.pick())
        clock += 2 * 60_000L
        assertEquals("a", pool.pick())
        pool.markFailed("a") // second step: one hour
        clock += 11 * 60_000L
        // The first step would have let it back by now; the escalation keeps it out.
        assertNull(pool.pick())
        clock += 50 * 60_000L
        assertEquals("a", pool.pick())
    }

    @Test
    fun `a banned token never returns`() {
        val pool = rotator("a", "b")
        val first = pool.pick()!!
        pool.markBanned(first)
        clock += 100L * 24 * 60 * 60 * 1000
        val second = pool.pick()!!
        assertNotEquals(first, second)
        pool.markFailed(second)
        // Second cooling and first banned: nothing healthy, and the banned one stays dead.
        assertNull(pool.pick())
        assertFalse(pool.hasHealthy())
    }

    @Test
    fun `exhausting the pool answers null rather than a corpse`() {
        val pool = rotator("a")
        assertEquals("a", pool.pick())
        pool.markFailed("a")
        assertNull(pool.pick())
    }

    @Test
    fun `addAll merges mirror tokens without resetting health`() {
        val pool = rotator("a", "b")
        val first = pool.pick()!!
        pool.markFailed(first)
        val second = pool.pick()!!
        pool.addAll(listOf("c", first))
        pool.markFailed(second)
        // The merged token is the only healthy one; the failed first stays on its cooldown.
        assertEquals("c", pool.pick())
    }
}
