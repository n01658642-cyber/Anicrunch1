package com.anilili.ui.detail

import com.anilili.data.model.Category
import com.anilili.data.model.SourceVariant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The subtitle shape a batch carries, which the queue previously had no way to be told.
 *
 * Only bonk and twin publish both today, and they publish them in opposite orders, so "the first
 * one" is not a stand-in for either answer.
 */
class DownloadVariantChoiceTest {

    @Test
    fun `an explicit choice is kept while the server still publishes it`() {
        assertEquals(
            SourceVariant.HARD_SUB,
            effectiveDownloadVariant(
                choice = SourceVariant.HARD_SUB,
                options = listOf(SourceVariant.SOFT_SUB, SourceVariant.HARD_SUB),
                category = Category.SUB,
            ),
        )
    }

    @Test
    fun `a choice the new server cannot serve falls back to that server's own first shape`() {
        // Switching from bonk to a hard-sub-only server: asking it for a soft sub is a request
        // nothing can answer, and the queue would have walked away from the server for it.
        assertEquals(
            SourceVariant.HARD_SUB,
            effectiveDownloadVariant(
                choice = SourceVariant.SOFT_SUB,
                options = listOf(SourceVariant.HARD_SUB),
                category = Category.SUB,
            ),
        )
    }

    @Test
    fun `with nothing chosen the server's published order decides`() {
        assertEquals(
            SourceVariant.SOFT_SUB,
            effectiveDownloadVariant(
                choice = null,
                options = listOf(SourceVariant.SOFT_SUB, SourceVariant.HARD_SUB),
                category = Category.SUB,
            ),
        )
        assertEquals(
            SourceVariant.HARD_SUB,
            effectiveDownloadVariant(
                choice = null,
                options = listOf(SourceVariant.HARD_SUB, SourceVariant.SOFT_SUB),
                category = Category.SUB,
            ),
        )
    }

    @Test
    fun `a server publishing nothing for this category falls back to the category default`() {
        assertEquals(
            SourceVariant.DUB,
            effectiveDownloadVariant(
                choice = SourceVariant.SOFT_SUB,
                options = emptyList(),
                category = Category.DUB,
            ),
        )
    }
}
