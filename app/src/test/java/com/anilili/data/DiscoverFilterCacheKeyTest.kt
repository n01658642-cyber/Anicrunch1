package com.anilili.data

import com.anilili.data.model.DiscoverFilters
import com.anilili.data.remote.discoverVariables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DiscoverFilterCacheKeyTest {

    private val base = DiscoverFilters(query = "frieren")

    /** One change per field the Browse query can carry; each must land on a page of its own. */
    private val oneFieldChanges: Map<String, DiscoverFilters> = mapOf(
        "query" to base.copy(query = "dandadan"),
        "genres" to base.copy(genres = setOf("Action")),
        "tags" to base.copy(tags = setOf("Isekai")),
        "excludedGenres" to base.copy(excludedGenres = setOf("Action")),
        "excludedTags" to base.copy(excludedTags = setOf("Isekai")),
        "studioId" to base.copy(studioId = 11),
        "year" to base.copy(year = 2023),
        "season" to base.copy(season = "FALL"),
        "yearFrom" to base.copy(yearFrom = 2010),
        "yearTo" to base.copy(yearTo = 2019),
        "status" to base.copy(status = "FINISHED"),
        "format" to base.copy(format = "TV"),
        "country" to base.copy(country = "JP"),
        "source" to base.copy(source = "MANGA"),
        "episodesMin" to base.copy(episodesMin = 13),
        "episodesMax" to base.copy(episodesMax = 26),
        "minimumScore" to base.copy(minimumScore = 80),
        "dubbedOnly" to base.copy(dubbedOnly = true),
        "sort" to base.copy(sort = "SCORE_DESC"),
    )

    @Test
    fun `changing any single filter changes the key`() {
        val baseKey = discoverCacheKey(base, hideAdult = false)
        oneFieldChanges.forEach { (field, changed) ->
            assertNotEquals("$field is missing from the cache key", baseKey, discoverCacheKey(changed, hideAdult = false))
        }
    }

    @Test
    fun `every single-filter change lands on a key of its own`() {
        val keys = oneFieldChanges.values.map { discoverCacheKey(it, hideAdult = false) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `an excluded genre is not the same page as that genre required`() {
        assertNotEquals(
            discoverCacheKey(base.copy(genres = setOf("Action")), hideAdult = false),
            discoverCacheKey(base.copy(excludedGenres = setOf("Action")), hideAdult = false),
        )
    }

    @Test
    fun `the 18 plus switch changes the key, as it changes the query`() {
        assertNotEquals(discoverCacheKey(base, hideAdult = false), discoverCacheKey(base, hideAdult = true))
    }

    @Test
    fun `a filter the query sends is a filter the key covers`() {
        // The guard behind the one above: every field that changes AniList's variables must also
        // change the key, or a new filter can be added to the query and forgotten here again.
        val baseVariables = discoverVariables(base, page = 1, perPage = 30, hideAdult = false)
        oneFieldChanges.forEach { (field, changed) ->
            val sendsIt = discoverVariables(changed, page = 1, perPage = 30, hideAdult = false) != baseVariables
            if (sendsIt) {
                assertNotEquals(
                    "$field reaches AniList but not the cache key",
                    discoverCacheKey(base, hideAdult = false),
                    discoverCacheKey(changed, hideAdult = false),
                )
            }
        }
    }

    @Test
    fun `the same filters written differently share a page`() {
        assertEquals(
            discoverCacheKey(base.copy(genres = setOf("Action", "Drama")), hideAdult = false),
            discoverCacheKey(base.copy(genres = setOf("Drama", "Action")), hideAdult = false),
        )
        assertEquals(
            discoverCacheKey(base.copy(query = "  Frieren "), hideAdult = false),
            discoverCacheKey(base, hideAdult = false),
        )
    }
}
