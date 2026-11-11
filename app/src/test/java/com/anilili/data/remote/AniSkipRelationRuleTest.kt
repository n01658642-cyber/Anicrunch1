package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AniSkipRelationRuleTest {
    /** The live table for MAL 16498 (Attack on Titan), which is why this mapping exists at all. */
    private val attackOnTitan = listOf(
        AniSkipRelationRule(fromStart = 26, fromEnd = 37, toMalId = 25777, toStart = 1, toEnd = 12),
        AniSkipRelationRule(fromStart = 38, fromEnd = 49, toMalId = 35760, toStart = 1, toEnd = 12),
        AniSkipRelationRule(fromStart = 50, fromEnd = 59, toMalId = 38524, toStart = 1, toEnd = 10),
        AniSkipRelationRule(fromStart = 60, fromEnd = 75, toMalId = 40028, toStart = 1, toEnd = 16),
        AniSkipRelationRule(fromStart = 76, fromEnd = null, toMalId = 48583, toStart = 1, toEnd = null),
    )

    @Test
    fun `an absolutely numbered episode lands on the cour that holds its timings`() {
        // Episode 50 of the provider's numbering is episode 1 of the third season's second part.
        assertEquals(38524 to 1, attackOnTitan.redirect(malId = 16498, episode = 50))
        assertEquals(38524 to 10, attackOnTitan.redirect(malId = 16498, episode = 59))
        assertEquals(25777 to 4, attackOnTitan.redirect(malId = 16498, episode = 29))
    }

    @Test
    fun `an open ended rule keeps counting past its start`() {
        assertEquals(48583 to 1, attackOnTitan.redirect(malId = 16498, episode = 76))
        assertEquals(48583 to 25, attackOnTitan.redirect(malId = 16498, episode = 100))
    }

    @Test
    fun `episodes the table does not cover are left alone`() {
        // The first cour needs no redirect: its own numbering already agrees with MAL's.
        assertNull(attackOnTitan.redirect(malId = 16498, episode = 1))
        assertNull(attackOnTitan.redirect(malId = 16498, episode = 25))
        assertNull(emptyList<AniSkipRelationRule>().redirect(malId = 21, episode = 1000))
    }

    @Test
    fun `the first matching rule wins when ranges overlap`() {
        // MAL 40028 really does publish both of these, and they both match episode 70.
        val overlapping = listOf(
            AniSkipRelationRule(fromStart = 60, fromEnd = 75, toMalId = 40028, toStart = 1, toEnd = 16),
            AniSkipRelationRule(fromStart = 17, fromEnd = 75, toMalId = 48583, toStart = 1, toEnd = 59),
        )

        assertEquals(40028 to 11, overlapping.redirect(malId = 16498, episode = 70))
    }

    @Test
    fun `a rule pointing back at the lookup already made is not a redirect`() {
        // Following it would spend a second request to ask the identical question.
        val identity = listOf(
            AniSkipRelationRule(fromStart = 1, fromEnd = 12, toMalId = 40028, toStart = 1, toEnd = 12),
        )

        assertNull(identity.redirect(malId = 40028, episode = 5))
    }

    @Test
    fun `a mapping that runs off the end of the target is rejected`() {
        val truncated = listOf(
            AniSkipRelationRule(fromStart = 1, fromEnd = 24, toMalId = 999, toStart = 1, toEnd = 12),
        )

        assertEquals(999 to 12, truncated.redirect(malId = 1, episode = 12))
        assertNull(truncated.redirect(malId = 1, episode = 13))
    }
}
