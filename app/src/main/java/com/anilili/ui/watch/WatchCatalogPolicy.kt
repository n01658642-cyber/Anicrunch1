package com.anilili.ui.watch

import com.anilili.data.ProviderCatalog
import com.anilili.data.model.Category
import com.anilili.data.model.EpisodesResult
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A single episode index remains available for jumping and Next. Other providers' full catalogs
 * are optional until playback needs a fallback or the viewer opens the source picker.
 * Use the same boundary as the episode browser's first full range.
 */
internal fun deferWatchSupplementalCatalog(isTv: Boolean, episodeCount: Int): Boolean =
    isTv && episodeCount > com.anilili.ui.components.EPISODE_BLOCK_SIZE

internal data class InitialWatchCatalogs(
    val miruro: Result<EpisodesResult>?,
    val native: EpisodesResult? = null,
    val nativeStartedFirst: Boolean = false,
)

/** Only the selected provider's requested episode/audio can bypass the Miruro wait. */
internal fun nativeCatalogCanStartWatch(
    catalog: EpisodesResult,
    preferred: String,
    category: Category,
    number: Double?,
    preferDub: Boolean,
): Boolean {
    if (ProviderCatalog.sourceOf(preferred) != ProviderCatalog.Source.ANIVEXA) return false
    val wanted = if (preferDub) Category.DUB else category
    val episodes = catalog.provider(preferred)
        ?.episodes(ProviderCatalog.dubCapableCategory(preferred, wanted)).orEmpty()
    // If dub is missing, keep waiting: another provider may have it. The normal category
    // fallback can decide on sub once the initial catalogues have both had their chance.
    return episodes.isNotEmpty() && (number == null || episodes.any { it.number == number })
}

/** Whether any server in [catalog] lists [number] in the category this launch wants. */
internal fun catalogCarriesEpisode(
    catalog: EpisodesResult,
    category: Category,
    number: Double?,
    preferDub: Boolean,
): Boolean {
    val wanted = if (preferDub) Category.DUB else category
    return catalog.providers.any { provider ->
        val episodes = provider.episodes(ProviderCatalog.dubCapableCategory(provider.name, wanted))
        episodes.isNotEmpty() && (number == null || episodes.any { it.number == number })
    }
}

/**
 * The initial wait when no server is pinned: whichever catalogue can play the episode first.
 *
 * With nothing pinned there is no reason to wait for Miruro specifically — measured on the phone
 * emulator, One Piece's pipe answered at 3.7s while the fast native group had it at 1.6s. So this
 * returns on the first of the two to carry the episode. If that was a native server, Miruro gets
 * [graceMs] more to join, because its servers resolve in ~100ms and are worth having in the race
 * when they are only a moment behind; past that, a late Miruro answer merges in behind playback
 * exactly as it does after a timeout.
 *
 * [fastSoFar] is the fast group's running answer, so one quick server is enough to start.
 */
internal suspend fun awaitInitialAutoCatalogs(
    miruro: Deferred<Result<EpisodesResult>>,
    fastSoFar: StateFlow<EpisodesResult>,
    fastDone: Deferred<EpisodesResult>,
    category: Category,
    number: Double?,
    preferDub: Boolean,
    waitMs: Long,
    graceMs: Long,
): InitialWatchCatalogs = withTimeoutOrNull(waitMs) {
    fun carries(catalog: EpisodesResult) = catalogCarriesEpisode(catalog, category, number, preferDub)
    suspend fun nativeFirst(native: EpisodesResult): InitialWatchCatalogs {
        val lateMiruro = withTimeoutOrNull(graceMs) { miruro.await() }
        return InitialWatchCatalogs(lateMiruro, native, nativeStartedFirst = true)
    }
    coroutineScope {
        val firstCarrying = async { fastSoFar.first(::carries) }
        try {
            select {
                miruro.onAwait { result ->
                    val catalog = result.getOrNull()
                    if (catalog != null && carries(catalog)) {
                        InitialWatchCatalogs(result, fastSoFar.value.takeUnless { it.isEmpty })
                    } else {
                        // Miruro answered without the episode: the fast group is all that is left.
                        InitialWatchCatalogs(result, fastDone.await())
                    }
                }
                firstCarrying.onAwait { nativeFirst(fastSoFar.value) }
                // A pass answered wholly from cache delivers no partials, only this.
                fastDone.onAwait { done ->
                    if (carries(done)) nativeFirst(done) else InitialWatchCatalogs(miruro.await(), done)
                }
            }
        } finally {
            firstCarrying.cancel()
        }
    }
} ?: InitialWatchCatalogs(null, fastSoFar.value.takeUnless { it.isEmpty })

/**
 * A ready native selection must not sit behind an unrelated eight-second Miruro wait.
 * Awaiting does not cancel either deferred: the caller merges pending catalogues later.
 */
internal suspend fun awaitInitialWatchCatalogs(
    miruro: Deferred<Result<EpisodesResult>>,
    native: Deferred<EpisodesResult>,
    preferred: String,
    category: Category,
    number: Double?,
    preferDub: Boolean,
    waitMs: Long,
): InitialWatchCatalogs = withTimeoutOrNull(waitMs) {
    if (ProviderCatalog.sourceOf(preferred) != ProviderCatalog.Source.ANIVEXA) {
        InitialWatchCatalogs(miruro.await())
    } else {
        select {
            miruro.onAwait { InitialWatchCatalogs(it) }
            native.onAwait { catalog ->
                if (nativeCatalogCanStartWatch(catalog, preferred, category, number, preferDub)) {
                    InitialWatchCatalogs(null, catalog, nativeStartedFirst = true)
                } else {
                    InitialWatchCatalogs(miruro.await(), catalog)
                }
            }
        }
    }
} ?: InitialWatchCatalogs(null)
