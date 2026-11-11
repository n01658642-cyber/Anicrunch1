package com.anilili.data

import com.anilili.data.model.Category
import com.anilili.data.model.SourceVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceVariantPolicyTest {
    @Test
    fun routeKeysPreserveExactSubtitleChoiceAcrossHistoryAndNotifications() {
        assertEquals(SourceVariant.HARD_SUB, SourceVariant.fromRoute("hsub"))
        assertEquals(SourceVariant.SOFT_SUB, SourceVariant.fromRoute("ssub"))
        assertEquals(SourceVariant.DUB, SourceVariant.fromRoute("dub"))
        assertEquals(SourceVariant.GENERIC_SUB, SourceVariant.fromRoute("sub"))
        assertEquals("sub", SourceVariant.HARD_SUB.api)
        assertEquals("ssub", SourceVariant.SOFT_SUB.api)
    }

    @Test
    fun providerCapabilitiesMatchMiruroHardAndSoftSubChoices() {
        // bonk was soft-sub only from 2026-08-18 (`sub` answered HTTP 444 six times out of six)
        // and serves both again as of 2026-09-06: the live capability map publishes
        // `sub: true, ssub: true` with `variantOrder: ["ssub", "sub"]`, and the site offers
        // `bonkdls` and `bonkdlh` side by side. Soft first, matching that order.
        assertEquals(
            listOf(SourceVariant.SOFT_SUB, SourceVariant.HARD_SUB),
            ProviderCatalog.sourceVariants("bonk", Category.SUB),
        )
        assertEquals(
            listOf(SourceVariant.SOFT_SUB),
            ProviderCatalog.sourceVariants("bee", Category.SUB),
        )
        assertEquals(
            listOf(SourceVariant.HARD_SUB),
            ProviderCatalog.sourceVariants("kiwi", Category.SUB),
        )
        assertEquals(
            listOf(SourceVariant.GENERIC_SUB),
            ProviderCatalog.sourceVariants("anichan", Category.SUB),
        )
    }

    @Test
    fun aOneVariantMiruroServerKeepsTheOtherSubtitleShapeAsALastResort() {
        // Testers on 2026-09-10 reported ally, nun and pewe as "only Dub works" on Android TV.
        // Every one of those resolves is a 444 on the single published hard-sub variant, which is
        // Miruro answering about that episode and not about the server.
        assertEquals(
            SourceVariant.SOFT_SUB,
            ProviderCatalog.unpublishedSubtitleVariant("ally", Category.SUB),
        )
        assertEquals(
            SourceVariant.SOFT_SUB,
            ProviderCatalog.unpublishedSubtitleVariant("nun", Category.SUB),
        )
        assertEquals(
            SourceVariant.HARD_SUB,
            ProviderCatalog.unpublishedSubtitleVariant("bee", Category.SUB),
        )
    }

    @Test
    fun serversPublishingBothSubtitleShapesGainNoExtraAttempt() {
        // bonk and twin already fall through to their own other half — `bonk:hsub:failed:1127,
        // bonk:ssub:ok:66` — so a last resort here would only duplicate a candidate.
        assertNull(ProviderCatalog.unpublishedSubtitleVariant("bonk", Category.SUB))
        assertNull(ProviderCatalog.unpublishedSubtitleVariant("twin", Category.SUB))
    }

    @Test
    fun dubAndNativeScrapersHaveNoSecondSubtitleShapeToTry() {
        // A dub is one request with one answer, and an Anivexa scraper is a page this app parses
        // itself rather than a Miruro variant, so neither has an unpublished half.
        assertNull(ProviderCatalog.unpublishedSubtitleVariant("ally", Category.DUB))
        assertNull(ProviderCatalog.unpublishedSubtitleVariant("anichan", Category.SUB))
    }
}

