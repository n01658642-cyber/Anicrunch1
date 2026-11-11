package com.anilili.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a title search is filled out from the bundled snapshot.
 *
 * AniList matches whole words and nothing less: `search: "narut"` returns an empty page and `"nar"`
 * returns *Omoide no Marnie*, so a viewer spelling a title out watched the grid empty as they typed
 * and read that as search being broken. Padding covers exactly those keystrokes — and nothing else,
 * because the snapshot was fixed at release time and a live answer is always the better one.
 */
class SnapshotPaddingTest {
    @Test
    fun `a half-typed word AniList could not answer is padded`() {
        assertTrue(shouldPadFromSnapshot(query = "narut", page = 1, remoteCount = 0))
        assertTrue(shouldPadFromSnapshot(query = "nar", page = 1, remoteCount = 2))
    }

    @Test
    fun `a finished word that filled a page is left exactly as it came back`() {
        // "naruto" returns a full page of current results. Appending release-time rows under them
        // would only push a stale copy of the same franchise into the grid.
        assertFalse(shouldPadFromSnapshot(query = "naruto", page = 1, remoteCount = 30))
        assertFalse(shouldPadFromSnapshot(query = "naruto", page = 1, remoteCount = 10))
    }

    @Test
    fun `browsing without a query is never padded`() {
        // Nothing typed means the viewer asked for a catalogue, not a title. A short answer there
        // is narrow filters doing their job, and padding would contradict the chips on screen.
        assertFalse(shouldPadFromSnapshot(query = "", page = 1, remoteCount = 0))
        assertFalse(shouldPadFromSnapshot(query = "   ", page = 1, remoteCount = 0))
    }

    @Test
    fun `only the first page is padded`() {
        // There is no second page of a list this is padding: an empty page two means the results
        // ran out, which is the truth and not a hole to fill.
        assertFalse(shouldPadFromSnapshot(query = "narut", page = 2, remoteCount = 0))
    }
}
