package com.anilili.ui.components

import com.anilili.data.remote.MalClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeListActionsTest {
    @Test
    fun `library and continue watching offer the same destinations in display order`() {
        assertEquals(
            listOf("CURRENT", "REPEATING", "PLANNING", "PAUSED", "COMPLETED", "DROPPED"),
            listDestinations.map(ListDestination::status),
        )
        assertEquals(
            // Rewatching is MAL's completed entry flagged is_rewatching, not a second Watching.
            listOf("watching", "completed", "plan_to_watch", "on_hold", "completed", "dropped"),
            listDestinations.map { MalClient.malStatus(it.status) },
        )
    }

    @Test
    fun `only plan to watch keeps the device's saved row`() {
        assertTrue(savedRowSurvivesMoveTo("PLANNING"))
        listDestinations.map(ListDestination::status)
            .filterNot { it == "PLANNING" }
            .forEach { assertFalse(it, savedRowSurvivesMoveTo(it)) }
    }
}
