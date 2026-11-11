package com.anilili.playback

import com.anilili.data.ProviderSourceIdentity
import com.anilili.data.model.Category
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.ProviderData
import com.anilili.data.model.SourceVariant
import com.anilili.data.model.SubtitleItem
import com.anilili.ui.detail.effectiveDownloadVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Hard subs only" in the download dialog: which servers count, and what the batch asks for. */
class HardSubDownloadsTest {

    private val track = SubtitleItem(url = "https://example.test/en.vtt", label = "English", language = "en")

    @Test
    fun `a Miruro source says its shape up front`() {
        assertTrue(HardSubDownloads.delivers(SourceVariant.HARD_SUB, emptyList()))
        // A hard sub that also carries a track is still burned in.
        assertTrue(HardSubDownloads.delivers(SourceVariant.HARD_SUB, listOf(track)))
        assertFalse(HardSubDownloads.delivers(SourceVariant.SOFT_SUB, emptyList()))
    }

    @Test
    fun `a native source is soft sub exactly when it hands over subtitle files`() {
        assertTrue(HardSubDownloads.delivers(SourceVariant.GENERIC_SUB, emptyList()))
        assertFalse(HardSubDownloads.delivers(SourceVariant.GENERIC_SUB, listOf(track)))
    }

    @Test
    fun `dub is never filtered`() {
        assertFalse(HardSubDownloads.appliesTo(hardSubsOnly = true, category = Category.DUB))
        assertTrue(HardSubDownloads.appliesTo(hardSubsOnly = true, category = Category.SUB))
        assertFalse(HardSubDownloads.appliesTo(hardSubsOnly = false, category = Category.SUB))
    }

    @Test
    fun `every server's soft sub is ruled out before it is asked`() {
        val catalog = EpisodesResult(
            providers = listOf(
                ProviderData("bonk", emptyList(), emptyList()),
                ProviderData("hop", emptyList(), emptyList()),
            ),
        )
        assertEquals(
            setOf(
                ProviderSourceIdentity("bonk", SourceVariant.SOFT_SUB),
                ProviderSourceIdentity("hop", SourceVariant.SOFT_SUB),
            ),
            HardSubDownloads.excludedSources(catalog),
        )
    }

    @Test
    fun `a soft-sub-only server cannot be a first choice`() {
        assertFalse(HardSubDownloads.mayOffer("hop"))
        assertTrue(HardSubDownloads.mayOffer("kiwi"))
        // Publishes both: its hard sub is what gets asked for.
        assertTrue(HardSubDownloads.mayOffer("bonk"))
    }

    @Test
    fun `the batch asks a two-shape server for its hard sub whatever was picked before`() {
        assertEquals(
            SourceVariant.HARD_SUB,
            effectiveDownloadVariant(
                choice = SourceVariant.SOFT_SUB,
                options = listOf(SourceVariant.SOFT_SUB, SourceVariant.HARD_SUB),
                category = Category.SUB,
                hardSubsOnly = true,
            ),
        )
        // Off, the earlier choice stands.
        assertEquals(
            SourceVariant.SOFT_SUB,
            effectiveDownloadVariant(
                choice = SourceVariant.SOFT_SUB,
                options = listOf(SourceVariant.SOFT_SUB, SourceVariant.HARD_SUB),
                category = Category.SUB,
                hardSubsOnly = false,
            ),
        )
    }
}
