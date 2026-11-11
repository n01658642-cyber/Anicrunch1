package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class DubCrawlOutcomeTest {
    @Test fun `total failure is distinct from an answered negative`() {
        assertEquals("all_probes_failed", dubCrawlOutcome(false, 0))
        assertEquals("no_dub_observed", dubCrawlOutcome(false, 2))
        assertEquals("dub", dubCrawlOutcome(true, 1))
    }
}
