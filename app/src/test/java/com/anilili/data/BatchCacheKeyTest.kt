package com.anilili.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BatchCacheKeyTest {
    @Test
    fun `batches that share a hashCode still get distinct keys`() {
        val a = listOf(1, 63)
        val b = listOf(2, 32)

        // Both are ascending, positive, and exactly what chunked saved-title ids look like — and
        // they collide, which is why the ids themselves have to be in the key.
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(batchCacheKey("anilist_batch:v3:", a), batchCacheKey("anilist_batch:v3:", b))
    }

    @Test
    fun `keys are stable and namespaced`() {
        assertEquals("anilist_batch:v3:1,2,3", batchCacheKey("anilist_batch:v3:", listOf(1, 2, 3)))
        assertNotEquals(
            batchCacheKey("anilist_batch:v3:", listOf(1, 2)),
            batchCacheKey("malmap:v2:", listOf(1, 2)),
        )
    }
}
