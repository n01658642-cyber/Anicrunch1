package com.anilili.data.remote

import com.anilili.data.model.DiscoverFilters
import com.anilili.data.model.WatchedFilter
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WatchedFilterQueryTest {

    @Test
    fun `watched titles reach AniList as id_not_in, sorted`() {
        val variables = discoverVariables(
            filters = DiscoverFilters(hideWatched = WatchedFilter.STARTED),
            page = 1,
            perPage = 30,
            hideAdult = false,
            excludedIds = setOf(1535, 21),
        )
        assertEquals(listOf(JsonPrimitive(21), JsonPrimitive(1535)), variables["excludedIds"]?.jsonArray)
    }

    @Test
    fun `nothing watched sends no exclusion at all`() {
        val variables = discoverVariables(
            filters = DiscoverFilters(hideWatched = WatchedFilter.COMPLETED),
            page = 1,
            perPage = 30,
            hideAdult = false,
        )
        assertFalse("excludedIds" in variables)
    }

    @Test
    fun `the watched filter counts on the filter badge`() {
        assertEquals(0, DiscoverFilters().activeCount)
        assertEquals(1, DiscoverFilters(hideWatched = WatchedFilter.COMPLETED).activeCount)
    }
}
