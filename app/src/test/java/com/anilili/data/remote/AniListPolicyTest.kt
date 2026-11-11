package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniListPolicyTest {
    @Test
    fun `new watched entry starts current`() {
        assertEquals(MediaListProgressUpdate(3, "CURRENT"), planMediaListProgressUpdate(null, 3, 12))
    }

    @Test
    fun `new releasing entry with unknown total starts current`() {
        assertEquals(MediaListProgressUpdate(1, "CURRENT"), planMediaListProgressUpdate(null, 1, null))
    }

    @Test
    fun `last episode completes current entry`() {
        val current = MediaListProgressSnapshot(7, "CURRENT", 11)
        assertEquals(MediaListProgressUpdate(12, "COMPLETED"), planMediaListProgressUpdate(current, 12, 12))
    }

    @Test
    fun `paused and an unfinished rewatch keep their status`() {
        assertEquals(
            MediaListProgressUpdate(5, null),
            planMediaListProgressUpdate(MediaListProgressSnapshot(7, "PAUSED", 4), 5, 12),
        )
        assertEquals(
            MediaListProgressUpdate(4, null),
            planMediaListProgressUpdate(MediaListProgressSnapshot(7, "REPEATING", 3, repeat = 1), 4, 12),
        )
    }

    @Test
    fun `the last episode of a rewatch completes it and counts it`() {
        assertEquals(
            MediaListProgressUpdate(12, "COMPLETED", repeat = 2),
            planMediaListProgressUpdate(MediaListProgressSnapshot(7, "REPEATING", 11, repeat = 1), 12, 12),
        )
    }

    @Test
    fun `a rewatch left at the full count still finishes on its last episode`() {
        // Put on Rewatching on the website, the count stays 12: every earlier episode is behind it.
        val rewatch = MediaListProgressSnapshot(7, "REPEATING", 12)
        assertNull(planMediaListProgressUpdate(rewatch, 5, 12))
        assertEquals(MediaListProgressUpdate(12, "COMPLETED", repeat = 1), planMediaListProgressUpdate(rewatch, 12, 12))
    }

    @Test
    fun `a rewatch of a series with no known length is never finished by playback`() {
        assertEquals(
            MediaListProgressUpdate(13, null),
            planMediaListProgressUpdate(MediaListProgressSnapshot(7, "REPEATING", 12), 13, null),
        )
    }

    @Test
    fun `replaying a completed title without choosing Rewatching changes nothing`() {
        val finished = MediaListProgressSnapshot(7, "COMPLETED", 12, repeat = 1)
        assertNull(planMediaListProgressUpdate(finished, 1, 12))
        assertNull(planMediaListProgressUpdate(finished, 12, 12))
    }

    @Test
    fun `rewatching a finished title starts its count again, and finishing one counts it`() {
        assertEquals(RewatchMove(resetProgress = true), planRewatchMove("COMPLETED", 1, "REPEATING"))
        assertEquals(RewatchMove(repeat = 2), planRewatchMove("REPEATING", 1, "COMPLETED"))
        // Nothing else is a rewatch: a first watch finishing, or Rewatching a title never finished.
        assertFalse(planRewatchMove("CURRENT", 0, "COMPLETED").touchesRewatch)
        assertFalse(planRewatchMove("CURRENT", 0, "REPEATING").touchesRewatch)
        assertFalse(planRewatchMove(null, 0, "COMPLETED").touchesRewatch)
        assertFalse(planRewatchMove("COMPLETED", 0, "COMPLETED").touchesRewatch)
    }

    @Test
    fun `a rewatch write sends the count and the entry's own dates back unchanged`() {
        val entry = MediaListProgressSnapshot(
            id = 7,
            status = "REPEATING",
            progress = 11,
            repeat = 1,
            startedAt = ListDate(2026, 1, 3),
            completedAt = ListDate(2026, 1, 20),
        )
        val (declarations, arguments, values) = rewatchArguments(entry, 2)
        assertEquals(", \$repeat: Int, \$startedAt: FuzzyDateInput, \$completedAt: FuzzyDateInput", declarations)
        assertEquals(", repeat: \$repeat, startedAt: \$startedAt, completedAt: \$completedAt", arguments)
        assertEquals("2", values.getValue("repeat").toString())
        assertEquals("""{"year":2026,"month":1,"day":3}""", values.getValue("startedAt").toString())
        assertEquals("""{"year":2026,"month":1,"day":20}""", values.getValue("completedAt").toString())
    }

    @Test
    fun `a date the entry does not have is left out, not sent empty`() {
        val entry = MediaListProgressSnapshot(7, "COMPLETED", 12, completedAt = ListDate(2025))
        val (declarations, arguments, values) = rewatchArguments(entry, null)
        assertEquals(", \$completedAt: FuzzyDateInput", declarations)
        assertEquals(", completedAt: \$completedAt", arguments)
        assertEquals("""{"year":2025}""", values.getValue("completedAt").toString())
        assertEquals(Triple("", "", emptyMap<String, Any>()), rewatchArguments(null, null))
    }

    @Test
    fun `completed and regressive progress are not changed`() {
        assertNull(planMediaListProgressUpdate(MediaListProgressSnapshot(7, "COMPLETED", 12), 13, 13))
        assertNull(planMediaListProgressUpdate(MediaListProgressSnapshot(7, "CURRENT", 8), 7, 12))
    }

    @Test
    fun `graphql errors are extracted from successful http envelopes`() {
        val errors = graphQlErrorMessages(
            """{"data":null,"errors":[{"message":"Invalid token","extensions":{"status":401}}]}""",
        )
        assertEquals(listOf("Invalid token (401)"), errors)
        assertTrue(graphQlErrorMessages("""{"data":{"Page":{}}}""").isEmpty())
    }
}
