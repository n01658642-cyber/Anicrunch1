package com.anilili.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The challenge parameters come from BabaStream's own server on every request, so how much work
 * the app is asked for is the service's decision, not ours. This is the line where that stops
 * being open-ended: a server that raised the difficulty would otherwise pin a device's CPU for
 * minutes with nothing about the payload looking any different.
 */
class CapChallengeBudgetTest {
    private val budget = 20_000_000L

    /** BabaStream's live parameters, 2026-09-09: 80 proofs at difficulty 4, ~5.2M digests. */
    @Test
    fun `the challenge actually served is affordable`() {
        assertTrue(capChallengeIsAffordable(count = 80, difficulty = 4, budgetDigests = budget))
    }

    /** Each step of difficulty is sixteen times the work, so headroom runs out quickly. */
    @Test
    fun `a harder challenge is declined rather than attempted`() {
        assertFalse(capChallengeIsAffordable(count = 80, difficulty = 5, budgetDigests = budget))
        assertFalse(capChallengeIsAffordable(count = 80, difficulty = 6, budgetDigests = budget))
    }

    @Test
    fun `room is left for the service to make it somewhat harder`() {
        assertTrue(capChallengeIsAffordable(count = 240, difficulty = 4, budgetDigests = budget))
    }

    @Test
    fun `more proofs than a challenge plausibly needs is declined`() {
        assertFalse(capChallengeIsAffordable(count = 100_000, difficulty = 1, budgetDigests = budget))
    }

    /**
     * A nonsensical challenge is refused instead of being turned into work: difficulty 0 asks for a
     * digest matching an empty prefix, which every digest matches, and a negative count would
     * multiply out to a budget that always passes.
     */
    @Test
    fun `a malformed challenge is refused`() {
        assertFalse(capChallengeIsAffordable(count = 0, difficulty = 4, budgetDigests = budget))
        assertFalse(capChallengeIsAffordable(count = -1, difficulty = 4, budgetDigests = budget))
        assertFalse(capChallengeIsAffordable(count = 80, difficulty = 0, budgetDigests = budget))
        assertFalse(capChallengeIsAffordable(count = 80, difficulty = -4, budgetDigests = budget))
    }
}
