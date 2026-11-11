package com.anilili.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderAttemptOrderTest {
    private val providers = listOf(
        "bonk",
        "kiwi",
        "pewe",
        "bee",
        "ally",
        "anikoto",
        "allanime",
        "animekai",
    )

    @Test
    fun `default order leads with the catalogue's default priority`() {
        // bee, anichan, anikoto, animepahe, lili (xfdm) — the servers judged fastest on
        // 2026-09-27. They head the picker and the automatic walk ahead of anything measured.
        assertEquals(
            ProviderCatalog.defaultPriority,
            ProviderCatalog.selectableProviders(hideAdult = false).take(ProviderCatalog.defaultPriority.size),
        )
        // hop is second in Miruro's ranking and spent a month demoted for never returning a
        // stream. It returns them again, so it has to be raced as a fast provider for that ranking
        // to mean anything — a demotion left in place would quietly undo the reorder for it.
        assertEquals(true, ProviderCatalog.isFast("hop"))

        val all = listOf("anichan", "bee", "kiwi", "anikoto")
        assertEquals(
            listOf("bee", "anichan", "anikoto"),
            all.sortedBy { ProviderCatalog.sortKey(it) }.take(3),
        )
        // Fallback from the default keeps the pair first, then spreads across backends.
        assertEquals(
            listOf("bee", "anichan", "kiwi", "anikoto"),
            providerAttemptOrder("bee", all).take(4),
        )
    }

    /**
     * senshi is gone (2026-09-10) and this is what stops it drifting back.
     *
     * It led the row until 2026-08-03, was demoted when /episodes/<malId> began answering 502, and
     * was removed once senshi.live stopped completing a TLS handshake at all -- 20 of 20 failed
     * lookups across four tester devices on two continents. An unknown name sorts to the end and
     * is never raced, so a re-added senshi that nobody re-measured fails here rather than costing
     * every viewer a lane in the catalogue walk again.
     */
    @Test
    fun `senshi is no longer a provider at all`() {
        assertEquals(false, ProviderCatalog.isFast("senshi"))
        assertEquals(false, ProviderCatalog.selectableProviders(hideAdult = false).contains("senshi"))
        val all = listOf("senshi", "anichan", "bonk", "kiwi", "anikoto")
        val sorted = all.sortedBy { ProviderCatalog.sortKey(it) }
        assertEquals(sorted.last(), "senshi")
    }

    // The Miruro server in the fifth slot follows the site's own providerOrder, which put pewe ahead
    // of kiwi when the catalog API replaced the pipe (2026-10-03). What these pin is that an
    // independent backend is reached within the budget, not which Miruro server comes next.
    @Test
    fun `miruro preference reaches independent backend within attempt budget`() {
        assertEquals(
            listOf("bonk", "anikoto", "bee", "allanime", "pewe"),
            providerAttemptOrder("bonk", providers).take(5),
        )
    }

    @Test
    fun `anivexa preference reaches independent backend within attempt budget`() {
        assertEquals(
            listOf("anikoto", "bee", "allanime", "pewe", "animekai"),
            providerAttemptOrder("anikoto", providers).take(5),
        )
    }

    @Test
    fun `settings fallbacks are tried straight after the preferred server`() {
        assertEquals(
            listOf("bonk", "animekai", "ally"),
            providerAttemptOrder("bonk", providers, listOf("animekai", "ally")).take(3),
        )
    }

    @Test
    fun `empty, auto, duplicate and self-referencing fallback slots are ignored`() {
        val plain = providerAttemptOrder("bonk", providers)
        // "auto" is what an unset slot stores, so a half-filled pair behaves like none at all.
        assertEquals(plain, providerAttemptOrder("bonk", providers, listOf("auto", "auto")))
        assertEquals(plain, providerAttemptOrder("bonk", providers, listOf("", "  ")))
        // Naming the preferred server again must not push a real fallback out of its slot.
        assertEquals(
            listOf("bonk", "ally"),
            providerAttemptOrder("bonk", providers, listOf("bonk", "ally")).take(2),
        )
        assertEquals(
            listOf("bonk", "ally"),
            providerAttemptOrder("bonk", providers, listOf("ally", "ally")).take(2),
        )
    }

    @Test
    fun `every provider still appears exactly once when fallbacks are set`() {
        val order = providerAttemptOrder("bonk", providers, listOf("animekai", "ally"))
        assertEquals(providers.size, order.size)
        assertEquals(providers.toSet(), order.toSet())
        assertEquals(order.size, order.distinct().size)
    }
}
