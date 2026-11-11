package com.anilili.ui.watch

import com.anilili.data.ProviderCatalog
import com.anilili.data.model.Category
import com.anilili.data.model.SourceVariant
import com.anilili.data.settings.SourceFilterLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFilterTest {
    private fun option(provider: String, category: Category) =
        WatchSourceOption(provider, category, hasCurrentEpisode = true, episodeCount = 12)

    private val bonkSub = option("bonk", Category.SUB)
    private val bonkDub = option("bonk", Category.DUB)
    private val rareDub = option("rareanimes", Category.DUB)
    private val options = listOf(bonkSub, bonkDub, rareDub)

    private val capabilities = mapOf(
        (bonkSub.provider to bonkSub.variant) to SourceCapabilities(
            subtitleLanguages = setOf("English"),
            known = true,
        ),
        (bonkDub.provider to bonkDub.variant) to SourceCapabilities(
            audioLanguages = setOf("English"),
            known = true,
        ),
        (rareDub.provider to rareDub.variant) to SourceCapabilities(
            audioLanguages = setOf("Hindi", "Tamil"),
            known = true,
        ),
    )

    @Test
    fun `audio filter comes straight from the catalog`() {
        assertEquals(options, filterSourceOptions(options, capabilities, AudioFilter.ANY, null))
        assertEquals(
            listOf(bonkSub),
            filterSourceOptions(options, capabilities, AudioFilter.SUB, null),
        )
        assertEquals(
            listOf(bonkDub, rareDub),
            filterSourceOptions(options, capabilities, AudioFilter.DUB, null),
        )
    }

    @Test
    fun `language filter narrows to sources carrying that track`() {
        assertEquals(
            listOf(rareDub),
            filterSourceOptions(options, capabilities, AudioFilter.ANY, "Hindi"),
        )
        // The headline case: a Hindi dub, found without scrolling every server.
        assertEquals(
            listOf(rareDub),
            filterSourceOptions(options, capabilities, AudioFilter.DUB, "Hindi"),
        )
        assertTrue(filterSourceOptions(options, capabilities, AudioFilter.SUB, "Hindi").isEmpty())
    }

    @Test
    fun `a server that has not been checked yet counts as English and nothing else`() {
        val unchecked = capabilities - (rareDub.provider to rareDub.variant)
        // Still in the English list: these sources are English unless they say otherwise.
        assertTrue(rareDub in filterSourceOptions(options, unchecked, AudioFilter.ANY, "English"))
        // But not in the French one. Keeping every unchecked server there made "French" match most
        // of the picker, none of which had French; the "checking" line says the list is still
        // growing, so a French server not yet inspected turns up when it has been.
        assertTrue(filterSourceOptions(options, unchecked, AudioFilter.ANY, "French").isEmpty())
        // Once a resolve names French, it is in.
        val french = unchecked + ((rareDub.provider to rareDub.variant) to
            SourceCapabilities(audioLanguages = setOf("French"), known = true))
        assertEquals(listOf(rareDub), filterSourceOptions(options, french, AudioFilter.ANY, "French"))
    }

    @Test
    fun `a checked server that named no language is not offered for any other one`() {
        val cogSub = option("cog", Category.SUB)
        val nothingNamed = mapOf((cogSub.provider to cogSub.variant) to SourceCapabilities(known = true))

        assertTrue(filterSourceOptions(listOf(cogSub), nothingNamed, AudioFilter.ANY, "French").isEmpty())
        assertTrue(filterSourceOptions(listOf(cogSub), nothingNamed, AudioFilter.ANY, "Spanish").isEmpty())
        assertEquals(
            listOf(cogSub),
            filterSourceOptions(listOf(cogSub), nothingNamed, AudioFilter.ANY, "English"),
        )
    }

    @Test
    fun `completeness tracks whether every option has been inspected`() {
        assertTrue(languageFilterIsComplete(options, capabilities))
        assertFalse(
            languageFilterIsComplete(options, capabilities - (rareDub.provider to rareDub.variant)),
        )
        assertFalse(
            languageFilterIsComplete(
                options,
                capabilities + ((rareDub.provider to rareDub.variant) to SourceCapabilities()),
            ),
        )
    }

    @Test
    fun `preferred languages keep the filter row compact`() {
        val discovered = listOf("English", "Hindi", "Japanese", "Tamil")

        assertEquals(discovered, visibleSourceLanguages(discovered, emptySet()))
        assertEquals(
            listOf("English", "Hindi", "Japanese"),
            visibleSourceLanguages(discovered, setOf("English", "Japanese", "Hindi")),
        )
        assertEquals(
            listOf("Hindi"),
            visibleSourceLanguages(discovered, setOf("Hindi")),
        )
    }

    @Test
    fun `server language tags are short and stable`() {
        assertEquals("EN", sourceLanguageTag("English"))
        assertEquals("JA", sourceLanguageTag("Japanese"))
        assertEquals("HI", sourceLanguageTag("Hindi"))
        assertEquals("KL", sourceLanguageTag("Klingon"))
    }

    /**
     * AnimeAV1's Spanish is burned into the picture, so a resolve names no language and the
     * English assumption above would have claimed it. Its declared language answers instead.
     */
    @Test
    fun `a server that declares its language is found under it and not under English`() {
        val spanish = option("animeav1", Category.SUB)
        val unchecked = emptyMap<Pair<String, SourceVariant>, SourceCapabilities>()

        assertEquals(listOf(spanish), filterSourceOptions(listOf(spanish), unchecked, AudioFilter.ANY, "Spanish"))
        assertTrue(filterSourceOptions(listOf(spanish), unchecked, AudioFilter.ANY, "English").isEmpty())
        assertEquals(setOf("Spanish"), sourceLanguages(spanish, SourceCapabilities()))
    }

    /**
     * The shortlist matches servers by display name, so every label offered in Settings has to be
     * a name [normalizeLanguage] actually produces. A label the normaliser would spell differently
     * would hide the very servers it was picked to keep, and nothing on screen would say why.
     */
    @Test
    fun `every offered language is a name the normaliser produces`() {
        SourceFilterLanguage.entries.forEach { language ->
            assertEquals(language.label, normalizeLanguage(language.label))
        }
    }

    /**
     * The report this split came from: AniKoto's dub payload carries one English subtitle and no
     * audio label, and the merged view read that as an English dub. The stream behind it holds a
     * single audio track, so there was nothing English about it to find.
     */
    @Test
    fun `a dub is not English merely because its subtitles are`() {
        val anikotoDub = option("anikoto", Category.DUB)
        val subtitlesOnly = SourceCapabilities(subtitleLanguages = setOf("English"), known = true)

        assertTrue(subtitlesOnly.languagesFor(Category.DUB).isEmpty())
        assertEquals(setOf("English"), subtitlesOnly.languagesFor(Category.SUB))
    }

    /**
     * AnimeGG returns no subtitle tracks at all and no audio label, so it reported "checked, and
     * nothing" — and the English filter dropped a dub that is in fact English. Silence about a
     * language is not a denial of it, so an inspected source that named none stays in the list on
     * the same grounds as one still being inspected.
     */
    /**
     * The download glyph used to come straight from [com.anilili.data.ProviderCatalog], which reads
     * it off the provider's name — and an Anivexa provider decides embed-or-not per stream, so the
     * name cannot know. AniKoto is native by that reckoning and was still badged downloadable
     * throughout the 2026-09-09 MegaPlay route rotation, while every resolve came back embed-only
     * and the screen below offered no download button at all.
     */
    @Test
    fun `a resolve that produced only an embed retires the download badge`() {
        val anikoto = option("anikoto", Category.SUB)
        val key = anikoto.provider to anikoto.variant

        assertTrue(ProviderCatalog.supportsOfflineDownload(anikoto.provider))
        assertFalse(
            sourceOffersDownload(
                anikoto.provider,
                anikoto.variant,
                mapOf(key to SourceCapabilities(downloadable = false, known = true)),
            ),
        )
        assertTrue(
            sourceOffersDownload(
                anikoto.provider,
                anikoto.variant,
                mapOf(key to SourceCapabilities(downloadable = true, known = true)),
            ),
        )
    }

    /**
     * Most of the picker is unresolved when it first opens. Answering "not downloadable" there
     * would be the same overconfidence as the old badge, only pointing the other way, so an
     * un-inspected server keeps the catalog's forecast — right down to the embed providers the
     * forecast has always been correct about.
     */
    @Test
    fun `an un-inspected server keeps the catalog forecast`() {
        val anikoto = option("anikoto", Category.SUB)
        val nun = option("nun", Category.SUB)

        assertTrue(sourceOffersDownload(anikoto.provider, anikoto.variant, emptyMap()))
        assertFalse(sourceOffersDownload(nun.provider, nun.variant, emptyMap()))
        // Present but not yet checked is the same state as absent.
        assertTrue(
            sourceOffersDownload(
                anikoto.provider,
                anikoto.variant,
                mapOf((anikoto.provider to anikoto.variant) to SourceCapabilities()),
            ),
        )
    }

    /** The badge is per audio row: a provider can resolve natively for sub and embed-only for dub. */
    @Test
    fun `the badge is answered per server and audio row`() {
        val sub = option("2dhive", Category.SUB)
        val dub = option("2dhive", Category.DUB)
        val capabilities = mapOf(
            (sub.provider to sub.variant) to SourceCapabilities(downloadable = true, known = true),
            (dub.provider to dub.variant) to SourceCapabilities(downloadable = false, known = true),
        )

        assertTrue(sourceOffersDownload(sub.provider, sub.variant, capabilities))
        assertFalse(sourceOffersDownload(dub.provider, dub.variant, capabilities))
    }

    /**
     * "Has signs" is only ever an observation. No provider's name forecasts whether its release
     * splits the signs out, so an unchecked server says nothing rather than guessing either way.
     */
    @Test
    fun `signs are marked only on a server whose resolve had a signs track`() {
        val reanimeDub = option("reanime", Category.DUB)
        val key = reanimeDub.provider to reanimeDub.variant

        assertTrue(
            sourceOffersSigns(
                reanimeDub.provider,
                reanimeDub.variant,
                mapOf(key to SourceCapabilities(signsTrack = true, known = true)),
            ),
        )
        assertFalse(
            sourceOffersSigns(
                reanimeDub.provider,
                reanimeDub.variant,
                mapOf(key to SourceCapabilities(signsTrack = false, known = true)),
            ),
        )
        assertFalse(sourceOffersSigns(reanimeDub.provider, reanimeDub.variant, emptyMap()))
        assertFalse(
            sourceOffersSigns(
                reanimeDub.provider,
                reanimeDub.variant,
                mapOf(key to SourceCapabilities(signsTrack = true, known = false)),
            ),
        )
    }

    /** A provider's sub row and dub row are separate resolves with separate track lists. */
    @Test
    fun `signs are answered per server and audio row`() {
        val sub = option("anipm", Category.SUB)
        val dub = option("anipm", Category.DUB)
        val capabilities = mapOf(
            (sub.provider to sub.variant) to SourceCapabilities(known = true),
            (dub.provider to dub.variant) to SourceCapabilities(signsTrack = true, known = true),
        )

        assertFalse(sourceOffersSigns(sub.provider, sub.variant, capabilities))
        assertTrue(sourceOffersSigns(dub.provider, dub.variant, capabilities))
    }

    @Test
    fun `a checked source that named no language is not ruled out`() {
        val animeggDub = option("animegg", Category.DUB)
        val nothingNamed = mapOf(
            (animeggDub.provider to animeggDub.variant) to SourceCapabilities(known = true),
        )

        assertEquals(
            listOf(animeggDub),
            filterSourceOptions(listOf(animeggDub), nothingNamed, AudioFilter.ANY, "English"),
        )
        // Still complete: it was inspected, so the row is not "still checking".
        assertTrue(languageFilterIsComplete(listOf(animeggDub), nothingNamed))
    }
}
