package com.anilili.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Removing a Continue Watching row used to opt the title out of account sync on that device for
 * good. Clearing history on the tablet meant the account could never seed it again, and the device
 * that finished a series was the one device a rewatch begun elsewhere could not reach.
 */
class DismissedRemoteRowTest {
    @Test
    fun `a dismissal survives while the account has not moved`() {
        val dismissed = listOf(DismissedRemoteRow(anilistId = 21, progressAtDismissal = 8))

        assertEquals(dismissed, retainedDismissals(dismissed, mapOf(21 to 8)))
    }

    @Test
    fun `watching further on another device brings the row back`() {
        val dismissed = listOf(DismissedRemoteRow(anilistId = 21, progressAtDismissal = 8))

        assertEquals(emptyList<DismissedRemoteRow>(), retainedDismissals(dismissed, mapOf(21 to 9)))
    }

    @Test
    fun `a rewatch elsewhere resets the count and also brings the row back`() {
        // A rewatch moves the count downwards. That is as much evidence of activity on another
        // device as watching further is, so it lapses the dismissal too.
        val dismissed = listOf(DismissedRemoteRow(anilistId = 21, progressAtDismissal = 12))

        assertEquals(emptyList<DismissedRemoteRow>(), retainedDismissals(dismissed, mapOf(21 to 0)))
    }

    @Test
    fun `a dismissal stored before this field existed adopts the account's count`() {
        // Upgrading must not lapse every dismissal the viewer already had at once.
        val dismissed = listOf(DismissedRemoteRow(anilistId = 21, progressAtDismissal = null))

        assertEquals(
            listOf(DismissedRemoteRow(anilistId = 21, progressAtDismissal = 8)),
            retainedDismissals(dismissed, mapOf(21 to 8)),
        )
    }

    @Test
    fun `a title the account is no longer watching keeps its dismissal untouched`() {
        // Nothing would be seeded for it anyway, and dropping the record would resurface the row
        // the moment the title returned to the watching list.
        val dismissed = listOf(DismissedRemoteRow(anilistId = 21, progressAtDismissal = 8))

        assertEquals(dismissed, retainedDismissals(dismissed, emptyMap()))
    }

    @Test
    fun `each dismissal is judged against its own title`() {
        val dismissed = listOf(
            DismissedRemoteRow(anilistId = 21, progressAtDismissal = 8),
            DismissedRemoteRow(anilistId = 30, progressAtDismissal = 3),
        )

        val retained = retainedDismissals(dismissed, mapOf(21 to 8, 30 to 4))

        assertEquals(listOf(21), retained.map { it.anilistId })
    }
}
