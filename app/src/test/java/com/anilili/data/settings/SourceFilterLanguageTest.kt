package com.anilili.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceFilterLanguageTest {
    @Test
    fun `selected languages round trip in picker order`() {
        val selected = setOf(
            SourceFilterLanguage.JAPANESE,
            SourceFilterLanguage.ENGLISH,
            SourceFilterLanguage.HINDI,
        )

        val stored = encodeSourceFilterLanguages(selected)

        assertEquals("eng,hin,jpn", stored)
        assertEquals(selected, decodeSourceFilterLanguages(stored))
    }

    /**
     * The table is hand-written and will be appended to. A repeated code makes [fromStored] answer
     * with the wrong language, and a repeated tag puts two servers under the same badge.
     */
    @Test
    fun `stored codes and tags identify one language each`() {
        val all = SourceFilterLanguage.entries
        assertEquals(all.size, all.mapTo(mutableSetOf()) { it.storedValue }.size)
        assertEquals(all.size, all.mapTo(mutableSetOf()) { it.tag }.size)
    }

    @Test
    fun `blank means all and unknown saved values are ignored`() {
        assertEquals(emptySet<SourceFilterLanguage>(), decodeSourceFilterLanguages(null))
        assertEquals(emptySet<SourceFilterLanguage>(), decodeSourceFilterLanguages(""))
        assertEquals(
            setOf(SourceFilterLanguage.HINDI),
            decodeSourceFilterLanguages("future-language, hin, hin"),
        )
    }
}
