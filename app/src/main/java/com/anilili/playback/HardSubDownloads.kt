package com.anilili.playback

import com.anilili.data.ProviderCatalog
import com.anilili.data.ProviderSourceIdentity
import com.anilili.data.model.Category
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.SourceVariant
import com.anilili.data.model.SubtitleItem

/**
 * "Hard subs only" for downloads: subtitles burned into the picture, so the saved file shows them
 * in any player, on any device, with nothing beside it to keep track of.
 *
 * Two kinds of server answer that differently. Miruro's say so up front — `sub` is burned in and
 * `ssub` is a clean picture with tracks — so a soft-sub source is ruled out before it is ever asked.
 * The native providers publish one shape under a generic `sub` and only the resolve shows which:
 * one that hands over subtitle files has a clean picture, and one that hands over none has its
 * subtitles in the video. That is the same test the desktop applies to its plugin servers.
 *
 * Dub is untouched: it has no subtitle shape to filter on.
 */
internal object HardSubDownloads {

    /** Whether the filter means anything for [category]. */
    fun appliesTo(hardSubsOnly: Boolean, category: Category): Boolean =
        hardSubsOnly && category == Category.SUB

    /**
     * Every Miruro soft-sub source in [catalog], for the resolver to skip. Cheaper than resolving
     * each one only to turn it away, and it keeps bonk and twin — which publish both shapes — in
     * the running on their hard sub.
     */
    fun excludedSources(catalog: EpisodesResult): Set<ProviderSourceIdentity> =
        catalog.providerNames.mapTo(linkedSetOf()) { ProviderSourceIdentity(it, SourceVariant.SOFT_SUB) }

    /** Whether a resolved source has its subtitles in the picture; see the class comment. */
    fun delivers(variant: SourceVariant, subtitles: List<SubtitleItem>): Boolean = when (variant) {
        SourceVariant.HARD_SUB -> true
        SourceVariant.SOFT_SUB, SourceVariant.DUB -> false
        SourceVariant.GENERIC_SUB -> subtitles.isEmpty()
    }

    /**
     * Whether [provider] can be a first choice for a hard-sub download at all. A Miruro server that
     * publishes only soft sub cannot; a native one might, which only its resolve can tell.
     */
    fun mayOffer(provider: String): Boolean =
        ProviderCatalog.sourceVariants(provider, Category.SUB).any { it != SourceVariant.SOFT_SUB }
}
