package com.anilili.data.remote

import com.anilili.data.model.DiscoverFilters
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SeasonFilterTest {

    @Test
    fun `season and year are two filters, and either stands alone`() {
        assertEquals(1, DiscoverFilters(season = "SUMMER").activeCount)
        assertEquals(2, DiscoverFilters(season = "SUMMER", year = 2025).activeCount)
    }

    @Test
    fun `discover variables carry the season AniList's enum spells it`() {
        val variables = discoverVariables(
            filters = DiscoverFilters(season = "FALL", year = 2024),
            page = 1,
            perPage = 30,
            hideAdult = false,
        )

        assertEquals(JsonPrimitive("FALL"), variables["season"])
        assertEquals(JsonPrimitive(2024), variables["year"])
    }

    @Test
    fun `no season means no season argument, not a null one`() {
        // The query declares $season as a nullable MediaSeason, so sending it explicitly as null
        // would still be an unfiltered request — but leaving it out keeps the cache key and the
        // request identical to what Browse sent before a season could be picked at all.
        val variables = discoverVariables(
            filters = DiscoverFilters(year = 2024),
            page = 1,
            perPage = 30,
            hideAdult = false,
        )

        assertFalse("season" in variables)
    }
}
