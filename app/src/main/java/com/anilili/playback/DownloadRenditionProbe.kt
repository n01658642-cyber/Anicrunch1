package com.anilili.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.TrackGroupArray
import com.anilili.data.AppGraph
import com.anilili.data.ProviderCatalog
import com.anilili.data.model.Category
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.SourceVariant
import com.anilili.data.model.StreamItem
import com.anilili.data.settings.DownloadQuality
import com.anilili.diagnostics.DiagnosticsLog
import com.google.common.util.concurrent.ListenableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What resolutions a server really has for one episode, read before anything is downloaded.
 *
 * The Quality setting is a ceiling, and most servers publish a single rendition (see
 * `download-quality-is-the-server`): anikoto hands over one 1080p media playlist, reanime one rung
 * that changes per episode. Asking such a server for 480p downloads its 1080p without a word, which
 * reads as the setting being ignored. Knowing the ladder up front is what lets the dialog say so and
 * the queue go looking elsewhere.
 */
internal object DownloadRenditionProbe {

    /**
     * Heights on offer for [chosen], lowest first, or empty when they could not be read.
     *
     * A provider that lists each rendition as its own stream answers from the list alone. An HLS
     * stream is prepared the way the player would: a multivariant playlist yields its ladder from
     * the playlist itself, and a lone media playlist costs one segment read, since nothing else in
     * it says how tall the picture is. A direct file with no declared height costs a header read.
     */
    suspend fun offeredHeights(
        context: Context,
        chosen: StreamItem,
        available: List<StreamItem>,
    ): List<Int> {
        val published = publishedHeights(chosen, available)
        if (published.size >= 2) return published
        // A file that declares its height has told us everything; one that does not (xfdm's bare
        // mirrors) costs only a header read to find out.
        if (!chosen.isHls && published.isNotEmpty()) return published
        val probed = runCatching { probedHeights(context, chosen) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                DiagnosticsLog.throwable("Download rendition probe failed host=${hostOf(chosen)}", error)
            }
            .getOrNull()
        return probed?.takeIf(List<Int>::isNotEmpty) ?: published
    }

    private suspend fun probedHeights(context: Context, stream: StreamItem): List<Int>? {
        val dataSourceFactory = EpisodeDownloads.probeDataSourceFactory(context, stream)
        val factory = if (stream.isHls) {
            HlsMediaSource.Factory(dataSourceFactory)
        } else {
            DefaultMediaSourceFactory(dataSourceFactory)
        }
        val item = MediaItem.Builder()
            .setUri(stream.url)
            .apply { if (stream.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        val groups = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            MetadataRetriever.retrieveMetadata(factory, item).awaitResult()
        } ?: return null
        return videoHeights(groups)
    }

    private fun videoHeights(groups: TrackGroupArray): List<Int> =
        (0 until groups.length)
            .asSequence()
            .map(groups::get)
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group -> (0 until group.length).asSequence().map { group.getFormat(it).height } }
            .filter { it != Format.NO_VALUE && it > 0 }
            .distinct()
            .sorted()
            .toList()

    private fun hostOf(stream: StreamItem): String =
        runCatching { java.net.URI(stream.url).host }.getOrNull() ?: "unknown"

    /** Awaits a Media3 future without pulling in a coroutines-guava dependency for one call. */
    private suspend fun <T> ListenableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addListener(
            {
                runCatching { get() }
                    .onSuccess { cont.resume(it) }
                    .onFailure { cont.resumeWithException(it) }
            },
            Runnable::run,
        )
        cont.invokeOnCancellation { cancel(false) }
    }

    /** A segment read on a slow CDN; past this the answer is "could not tell", not an error. */
    private const val PROBE_TIMEOUT_MS = 20_000L
}

/** Another server that can meet the requested resolution for the episode that was checked. */
internal data class QualityAlternative(val provider: String, val height: Int)

/** Where the download dialog's resolution check stands for the chosen server. */
internal sealed interface BatchQualityCheck {
    data object Checking : BatchQualityCheck

    /** Nothing the check could say: no source answered, or its ladder was unreadable. */
    data object Unknown : BatchQualityCheck

    /** [provider] is the server that answered, which "Auto" or a failed first choice can change. */
    data class Fits(val provider: String, val height: Int) : BatchQualityCheck

    /** The chosen server would come back at [delivered], not at the resolution asked for. */
    data class Misses(
        val provider: String,
        val episodeLabel: String,
        val offered: List<Int>,
        val delivered: Int,
        /** Still resolving other servers for one that fits. */
        val searching: Boolean,
        val alternative: QualityAlternative?,
    ) : BatchQualityCheck
}

/**
 * Checks one sample episode of a batch against the requested resolution, and when the chosen
 * server would miss it, walks the other servers for one that would not.
 *
 * One episode stands for the batch. That is a sample, not a guarantee — reanime's ladder changes
 * per episode — which is why the queue can also check every episode as it goes (see
 * [BulkEpisodeDownloads.start]'s `matchQuality`). The walk stops at the first server that fits and
 * after [MAX_ALTERNATIVES] resolves, because this runs while the viewer is still deciding and
 * every step is a provider request.
 */
internal fun checkBatchQuality(
    context: Context,
    anilistId: Int,
    episode: EpisodeItem,
    catalog: EpisodesResult,
    category: Category,
    variant: SourceVariant,
    preferredProvider: String,
    quality: DownloadQuality,
    /** The batch's "Hard subs only": the check samples the server the batch would really use. */
    hardSubsOnly: Boolean = false,
): Flow<BatchQualityCheck> = flow {
    emit(BatchQualityCheck.Checking)
    val excluded = mutableSetOf<String>()
    val first = sampleHeights(
        context, anilistId, episode, catalog, category, variant, preferredProvider, excluded,
        hardSubsOnly,
    )
    val tag = "id=$anilistId episode=${episode.displayNumber} quality=${quality.label}"
    if (first == null || first.second.isEmpty()) {
        DiagnosticsLog.event("Download quality check unknown $tag provider=${first?.first}")
        emit(BatchQualityCheck.Unknown)
        return@flow
    }
    val (provider, offered) = first
    excluded += provider
    val delivered = deliveredHeight(offered, quality) ?: 0
    if (fitsQuality(offered, quality)) {
        DiagnosticsLog.event("Download quality check fits $tag provider=$provider offered=$offered")
        emit(BatchQualityCheck.Fits(provider, delivered))
        return@flow
    }
    fun misses(searching: Boolean, alternative: QualityAlternative?) = BatchQualityCheck.Misses(
        provider = provider,
        episodeLabel = episode.displayNumber,
        offered = offered,
        delivered = delivered,
        searching = searching,
        alternative = alternative,
    )
    emit(misses(searching = true, alternative = null))
    var alternative: QualityAlternative? = null
    for (attempt in 1..MAX_ALTERNATIVES) {
        val (other, otherOffered) = sampleHeights(
            context, anilistId, episode, catalog, category, variant, preferredProvider, excluded,
            hardSubsOnly,
        ) ?: break
        excluded += other
        if (otherOffered.isNotEmpty() && fitsQuality(otherOffered, quality)) {
            alternative = QualityAlternative(other, deliveredHeight(otherOffered, quality) ?: 0)
            break
        }
    }
    DiagnosticsLog.event(
        "Download quality check misses $tag provider=$provider offered=$offered " +
            "alternative=${alternative?.provider}:${alternative?.height} tried=$excluded",
    )
    emit(misses(searching = false, alternative = alternative))
}.flowOn(Dispatchers.IO)

/**
 * Resolves [episode] on the next server not in [excluded] that has a downloadable stream, and reads
 * its ladder. Null once every server has been tried.
 *
 * Interactive challenges are off: this runs behind a dialog, and a Cloudflare page popping up over
 * it for a question the viewer never asked would be worse than not knowing.
 */
private suspend fun sampleHeights(
    context: Context,
    anilistId: Int,
    episode: EpisodeItem,
    catalog: EpisodesResult,
    category: Category,
    variant: SourceVariant,
    preferredProvider: String,
    excluded: MutableSet<String>,
    hardSubsOnly: Boolean,
): Pair<String, List<Int>>? {
    val hardSubs = HardSubDownloads.appliesTo(hardSubsOnly, category)
    val excludedSources = if (hardSubs) HardSubDownloads.excludedSources(catalog) else emptySet()
    while (true) {
        val resolved = AppGraph.repository.resolveSources(
            anilistId = anilistId,
            number = episode.number,
            preferred = preferredProvider,
            category = category,
            sourceVariant = variant,
            episodes = catalog,
            excludedProviders = excluded,
            excludedSources = excludedSources,
            allowInteractiveChallenges = false,
        ).resolved ?: return null
        val stream = downloadSeedStream(resolved.provider, resolved.sources)
        if (
            stream == null ||
            !ProviderCatalog.supportsOfflineDownload(resolved.provider) ||
            (hardSubs && !HardSubDownloads.delivers(resolved.variant, resolved.sources.subtitles))
        ) {
            excluded += resolved.provider
            continue
        }
        val offered = DownloadRenditionProbe.offeredHeights(context, stream, resolved.sources.streams)
        return resolved.provider to offered
    }
}

/** Other servers the dialog's check may resolve after the chosen one, at most. */
private const val MAX_ALTERNATIVES = 5
