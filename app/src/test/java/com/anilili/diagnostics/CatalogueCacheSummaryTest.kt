package com.anilili.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueCacheSummaryTest {
    private fun event(name: String, attributes: Map<String, String> = emptyMap()) = DiagnosticEvent(
        timestampUtc = "2026-08-11T00:00:00.000Z",
        elapsedRealtimeMs = 0,
        processUptimeMs = 0,
        sessionId = "s",
        sequence = 0,
        process = "main",
        pid = 1,
        thread = "t",
        threadId = 1,
        level = "INFO",
        category = "catalog",
        name = name,
        attributes = attributes,
    )

    private fun summary(
        asked: Int,
        answered: Int,
        fromDisk: Int,
        fromSeed: Int,
        fetched: Int,
        refresh: Boolean = false,
    ) = event(
        "catalog.summary",
        mapOf(
            "asked" to "$asked",
            "answered" to "$answered",
            "fromDisk" to "$fromDisk",
            "fromSeed" to "$fromSeed",
            "fetched" to "$fetched",
            "refresh" to "$refresh",
        ),
    )

    @Test
    fun `says so when nothing was looked up`() {
        assertEquals("no catalogue lookups in this window", CatalogueCacheSummary.render(emptyList()))
    }

    @Test
    fun `totals the tiers across lookups`() {
        val rendered = CatalogueCacheSummary.render(
            listOf(
                summary(asked = 2, answered = 2, fromDisk = 1, fromSeed = 1, fetched = 0),
                summary(asked = 15, answered = 9, fromDisk = 3, fromSeed = 4, fetched = 2),
            ),
        )
        assertTrue(rendered, rendered.contains("lookups=2 asked=17 answered=11 fromDisk=4 fromSeed=5 fetched=2"))
    }

    /**
     * A refresh asks every provider by design. Counted into the ratio it reads as a cache that is
     * not working, which is the false alarm this separation exists to prevent.
     */
    @Test
    fun `a refresh is counted apart from the ratio`() {
        val rendered = CatalogueCacheSummary.render(
            listOf(
                summary(asked = 2, answered = 2, fromDisk = 2, fromSeed = 0, fetched = 0),
                summary(asked = 2, answered = 2, fromDisk = 0, fromSeed = 0, fetched = 2, refresh = true),
            ),
        )
        assertTrue(rendered, rendered.contains("lookups=1 (+1 refresh)"))
        assertTrue(rendered, rendered.contains("asked=2 answered=2 fromDisk=2 fromSeed=0 fetched=0"))
    }

    @Test
    fun `rows read back are reported against rows stored`() {
        val rendered = CatalogueCacheSummary.render(
            listOf(
                summary(asked = 2, answered = 2, fromDisk = 1, fromSeed = 0, fetched = 1),
                event("catalog.availability.stored", mapOf("providers" to "8")),
                event("catalog.availability.row", mapOf("providers" to "8")),
                event("catalog.availability.row", mapOf("providers" to "-1")),
            ),
        )
        assertTrue(rendered, rendered.contains("rows stored=1 read=2 found=1"))
        assertFalse(rendered.contains("never coming back"))
    }

    /**
     * The failure that took a day to see by hand: rows are written, and every read comes back
     * empty because the cache discards what it cannot decode without saying so. Stored-only and
     * never-stored produce identical per-event logs, so the summary has to name it.
     */
    @Test
    fun `stored rows that never come back are called out`() {
        val rendered = CatalogueCacheSummary.render(
            listOf(
                summary(asked = 2, answered = 2, fromDisk = 0, fromSeed = 0, fetched = 2),
                event("catalog.availability.stored", mapOf("providers" to "2")),
                event("catalog.availability.row", mapOf("providers" to "-1")),
            ),
        )
        assertTrue(rendered, rendered.contains("rows stored=1 read=1 found=0"))
        assertTrue(rendered, rendered.contains("the cache is dropping them"))
    }

    @Test
    fun `a row that was never stored is not called a dropped row`() {
        val rendered = CatalogueCacheSummary.render(
            listOf(
                summary(asked = 2, answered = 2, fromDisk = 0, fromSeed = 0, fetched = 2),
                event("catalog.availability.row", mapOf("providers" to "-1")),
            ),
        )
        assertFalse(rendered.contains("the cache is dropping them"))
    }

    @Test
    fun `drifted answers name the provider and both values`() {
        val rendered = CatalogueCacheSummary.render(
            listOf(
                summary(asked = 2, answered = 2, fromDisk = 0, fromSeed = 0, fetched = 2, refresh = true),
                event(
                    "catalog.availability.changed",
                    mapOf(
                        "provider" to "anibd",
                        "anilistId" to "11061",
                        "from" to "seed",
                        "wasSub" to "999",
                        "nowSub" to "148",
                        "wasDub" to "0",
                        "nowDub" to "0",
                    ),
                ),
            ),
        )
        assertTrue(rendered, rendered.contains("answers that changed under a remembered value: 1"))
        assertTrue(rendered, rendered.contains("anibd id=11061 from=seed sub 999->148 dub 0->0"))
    }

    @Test
    fun `long drift lists are capped`() {
        val drift = (1..20).map {
            event(
                "catalog.availability.changed",
                mapOf(
                    "provider" to "p$it",
                    "anilistId" to "$it",
                    "from" to "disk",
                    "wasSub" to "1",
                    "nowSub" to "2",
                    "wasDub" to "0",
                    "nowDub" to "0",
                ),
            )
        }
        val rendered = CatalogueCacheSummary.render(
            listOf(summary(asked = 1, answered = 1, fromDisk = 0, fromSeed = 0, fetched = 1)) + drift,
        )
        assertTrue(rendered, rendered.contains("answers that changed under a remembered value: 20"))
        assertTrue(rendered.lines().count { it.startsWith("  p") } == 8)
    }
}
