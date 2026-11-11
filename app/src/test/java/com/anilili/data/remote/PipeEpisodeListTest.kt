package com.anilili.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipeEpisodeListTest {
    /** Naruto Shippuden 1-19 are `mixed_canon/filler`, and every provider flagged them filler. */
    @Test
    fun `only the filler kind counts as filler`() {
        assertTrue(isFillerEpisode(true, "filler"))
        assertFalse(isFillerEpisode(true, "mixed_canon/filler"))
        assertFalse(isFillerEpisode(false, "manga_canon"))
        // A row that does not say which kind keeps its flag.
        assertTrue(isFillerEpisode(true, null))
        assertFalse(isFillerEpisode(null, null))
    }
}
