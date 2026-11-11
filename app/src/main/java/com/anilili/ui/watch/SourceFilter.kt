package com.anilili.ui.watch

import com.anilili.data.ProviderCatalog
import com.anilili.data.model.Category
import com.anilili.data.model.SourceVariant
import com.anilili.data.settings.SourceFilterLanguage

/** Audio axis of the source picker's filter: everything, or just one category. */
enum class AudioFilter(val label: String, val category: Category?) {
    ANY("All", null),
    SUB("Sub", Category.SUB),
    DUB("Dub", Category.DUB),
}

/**
 * Applies the Settings shortlist to the languages discovered for this episode.
 *
 * No preferences means the legacy behaviour: every discovered language remains available. The
 * result keeps discovery order, which is already alphabetical for the server picker.
 */
fun visibleSourceLanguages(
    discovered: Collection<String>,
    preferred: Set<String>,
): List<String> = discovered.distinct().filter { preferred.isEmpty() || it in preferred }

/** Compact visual tag for a server card; the filter chips continue to use the full language. */
fun sourceLanguageTag(language: String): String =
    SourceFilterLanguage.fromLabel(language)?.tag
        ?: language.filter(Char::isLetter).take(2).uppercase().ifBlank { "?" }

/**
 * Narrows the server list to what the viewer actually wants.
 *
 * The two axes cost very different things to answer. Sub vs dub comes straight from the episode
 * catalog, so it filters instantly and exactly. Language only exists inside a *resolved* source,
 * and most resolves name none at all: hardsubbed English (AnimeGG) carries no subtitle track, and
 * almost no provider labels a dub's audio.
 *
 * So a server that has not said otherwise is taken to be [ASSUMED_LANGUAGE] — whether it has not
 * been checked yet or was checked and named nothing. That is the prior the English filter always
 * relied on, and it is right: these sources are English unless they say so. It used to be applied
 * to every language, which made "French" match every unchecked or unlabelled server — most of the
 * list — and read as a picker full of French sources that had none. Any other language now needs a
 * resolve that actually named it. The "checking servers" line under the chips already says the
 * list is still growing, so a French server not yet inspected is late rather than denied.
 */
fun filterSourceOptions(
    options: List<WatchSourceOption>,
    capabilities: Map<Pair<String, SourceVariant>, SourceCapabilities>,
    audio: AudioFilter,
    language: String?,
): List<WatchSourceOption> = options.filter { option ->
    val matchesAudio = audio.category == null || option.category == audio.category
    if (!matchesAudio) return@filter false
    if (language == null) return@filter true
    // A server that declares its language is that language whatever a resolve reports, and
    // never falls under the English assumption below.
    ProviderCatalog.declaredLanguage(option.provider)?.let { return@filter language == it }
    val capability = capabilities[option.provider to option.variant] ?: SourceCapabilities()
    val spoken = capability.languagesFor(option.category)
    if (!capability.known || spoken.isEmpty()) language == ASSUMED_LANGUAGE else language in spoken
}

/**
 * The languages to show for [option]: its server's declared language when it has one (burned-in
 * subtitles report nothing a resolve can read), otherwise what its resolve reported.
 */
fun sourceLanguages(option: WatchSourceOption, capability: SourceCapabilities): Set<String> =
    ProviderCatalog.declaredLanguage(option.provider)?.let(::setOf) ?: capability.languagesFor(option.category)

/**
 * What a server is taken to carry until a resolve names something else — see [filterSourceOptions].
 */
internal val ASSUMED_LANGUAGE = SourceFilterLanguage.ENGLISH.label

/**
 * Whether a server should be badged as saveable into the offline library.
 *
 * Answered by the resolve where there has been one, and only forecast from the provider's name
 * where there has not. [ProviderCatalog.supportsOfflineDownload] is a claim about how a provider is
 * *plumbed* — it has a native extractor, so it should yield a fetchable stream — and that claim
 * survives the provider breaking. MegaPlay rotating its source route on 2026-09-09 sent AniKoto
 * embed-only, and the glyph went on promising a download that the screen below then had no button
 * for, because nothing consulted what actually came back.
 *
 * The forecast is kept for unresolved servers rather than replaced with a bare "no": most of the
 * picker is unresolved when it first opens, and answering "not downloadable" there would be the
 * same overconfidence pointing the other way. [SourceCapabilities.known] is the seam, exactly as it
 * is for the language filter above.
 */
fun sourceOffersDownload(
    provider: String,
    variant: SourceVariant,
    capabilities: Map<Pair<String, SourceVariant>, SourceCapabilities>,
): Boolean {
    val capability = capabilities[provider to variant] ?: SourceCapabilities()
    return if (capability.known) {
        capability.downloadable
    } else {
        ProviderCatalog.supportsOfflineDownload(provider)
    }
}

/**
 * Whether a server should be marked as carrying a signs-only subtitle track.
 *
 * Unlike [sourceOffersDownload] there is no forecast for a server not yet resolved: no provider's
 * name says whether its release splits the signs out, so an unchecked server shows nothing rather
 * than a guess. The list fills in as servers are played or validated, and opening the picker starts
 * the validation of the rest.
 */
fun sourceOffersSigns(
    provider: String,
    variant: SourceVariant,
    capabilities: Map<Pair<String, SourceVariant>, SourceCapabilities>,
): Boolean = capabilities[provider to variant]?.let { it.known && it.signsTrack } == true

/**
 * Whether the language filter can be trusted yet. While options are still unchecked the row is
 * necessarily incomplete, and the picker should say so rather than imply the list is final.
 */
fun languageFilterIsComplete(
    options: List<WatchSourceOption>,
    capabilities: Map<Pair<String, SourceVariant>, SourceCapabilities>,
): Boolean = options.all { capabilities[it.provider to it.variant]?.known == true }
