package com.anilili.data.remote

import com.anilili.data.model.DiscoverFilters
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dubbed-only filter, which AniList cannot answer from its own fields: it is expressed as the
 * device's dub index in `id_in`, the same argument the studio filter already uses.
 */
class DubFilterTest {

    @Test
    fun `dubbed only counts as one active catalog filter`() {
        assertEquals(1, DiscoverFilters(dubbedOnly = true).activeCount)
        assertEquals(2, DiscoverFilters(dubbedOnly = true, format = "TV").activeCount)
        assertEquals(0, DiscoverFilters().activeCount)
    }

    @Test
    fun `the dub index becomes the id list AniList filters on`() {
        val ids = discoverMediaIds(
            filters = DiscoverFilters(dubbedOnly = true),
            studioMediaIds = null,
            dubbedIds = setOf(21, 1535, 113415),
        )

        assertEquals(setOf(21, 1535, 113415), ids?.toSet())
    }

    @Test
    fun `with a studio as well, only the titles in both lists answer`() {
        val ids = discoverMediaIds(
            filters = DiscoverFilters(dubbedOnly = true, studioId = 569, studioName = "MAPPA"),
            studioMediaIds = listOf(113415, 110277, 21),
            dubbedIds = setOf(21, 1535, 113415),
        )

        // Order follows the studio list, which is AniList's own ranking for that studio.
        assertEquals(listOf(113415, 21), ids)
    }

    @Test
    fun `a studio with no dubbed titles resolves to an empty list, not an unfiltered one`() {
        // Null would mean "do not narrow by id" — the whole catalog. The empty list is what tells
        // the caller nothing can match, and it is why `discover` returns an empty page rather than
        // sending a request with no id filter at all.
        val ids = discoverMediaIds(
            filters = DiscoverFilters(dubbedOnly = true, studioId = 569),
            studioMediaIds = listOf(110277),
            dubbedIds = setOf(21),
        )

        assertEquals(emptyList<Int>(), ids)
    }

    @Test
    fun `without the filter the dub index is never sent`() {
        assertNull(
            discoverMediaIds(
                filters = DiscoverFilters(),
                studioMediaIds = null,
                dubbedIds = setOf(21, 1535),
            ),
        )

        val variables = discoverVariables(
            filters = DiscoverFilters(),
            page = 1,
            perPage = 30,
            hideAdult = false,
            mediaIds = discoverMediaIds(DiscoverFilters(), null, setOf(21, 1535)),
        )
        assertFalse("mediaIds" in variables)
    }

    @Test
    fun `an index that failed to load returns nothing rather than everything`() {
        // DubIndex answers with an empty set when its asset could not be read. Falling back to an
        // unfiltered request would put undubbed titles under a chip that says Dubbed, which is the
        // one answer worse than an empty one — a viewer can see an empty result and try something
        // else, where a wrong one sends them to a server that cannot serve what it promised.
        assertTrue(
            discoverMediaIds(
                filters = DiscoverFilters(dubbedOnly = true),
                studioMediaIds = null,
                dubbedIds = emptySet(),
            )!!.isEmpty(),
        )
        assertTrue(
            discoverMediaIds(
                filters = DiscoverFilters(dubbedOnly = true, studioId = 569),
                studioMediaIds = listOf(113415),
                dubbedIds = emptySet(),
            )!!.isEmpty(),
        )
    }

    @Test
    fun `the filter reaches AniList as the query's id list`() {
        val variables = discoverVariables(
            filters = DiscoverFilters(dubbedOnly = true, format = "TV"),
            page = 3,
            perPage = 30,
            hideAdult = false,
            mediaIds = listOf(21, 1535),
        )

        assertEquals(listOf(JsonPrimitive(21), JsonPrimitive(1535)), variables["mediaIds"]?.jsonArray)
        assertEquals(JsonPrimitive("TV"), variables["format"])
        assertEquals(JsonPrimitive(3), variables["page"])
    }
}
