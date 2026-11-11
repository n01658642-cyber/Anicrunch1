package com.anilili.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class FillerIndexParseTest {
    /** The shape `scripts/filler_index_build.py` publishes, including a checked title with none. */
    @Test
    fun `the published index parses with empty lists kept`() {
        val raw = """{"v":1,"generated":"2026-09-25","titles":{"20":[26,97],"5114":[]}}"""
        val index = Json { ignoreUnknownKeys = true }.decodeFromString(FillerIndex.serializer(), raw)
        assertEquals(listOf(26, 97), index.titles["20"])
        assertEquals(emptyList<Int>(), index.titles["5114"])
    }
}
