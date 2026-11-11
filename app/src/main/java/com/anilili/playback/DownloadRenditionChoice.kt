package com.anilili.playback

import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.settings.DownloadQuality
import com.anilili.ui.watch.declaredVideoHeight
import com.anilili.ui.watch.providerStreamOrder

/**
 * Which of a provider's streams a download should take.
 *
 * This used to exist only on the watch screen, where the viewer's chosen stream is already in hand
 * and [selectDownloadStream] merely refines it. The queue behind the anime page had nothing of the
 * sort: it took `streams.firstOrNull(canDownload)`, which is the provider's *list order* and has no
 * opinion about resolution at all. On a server that publishes each rendition as its own playlist —
 * Kwik does, and it is the shape [selectDownloadStream] was written for — that silently ignored the
 * resolution the viewer asked for, because on those servers picking the URL is the only thing that
 * decides.
 *
 * Lives in `playback` rather than in the watch screen because both roads to the downloader need it,
 * and only one of them has a UI.
 */
internal fun selectDownloadStream(
    current: StreamItem,
    available: List<StreamItem>,
    quality: DownloadQuality,
): StreamItem {
    if (current.isHls) {
        // One HLS entry is, or may be, a multivariant manifest with the renditions inside it, and
        // track selection can pick from those — so leave it alone. But providers like Kwik publish
        // each rendition as its own single-rendition playlist, and there track selection has
        // nothing to choose between: asking for 360p downloaded 1080p, because picking the URL is
        // the only thing that decides. Two or more distinct heights means it is that second shape.
        val renditions = renditionCandidates(current, available)
        if (renditions.distinctBy { it.second }.size < 2) return current
        return pickByHeight(renditions, quality) ?: current
    }
    val directCandidates = renditionCandidates(current, available)
    if (directCandidates.isEmpty()) return current
    return pickByHeight(directCandidates, quality) ?: current
}

/**
 * The stream a download should take from a provider that has just resolved, or null when the
 * provider offered nothing this app can save.
 *
 * The seed is the provider's own first choice among the streams that can be downloaded, read from
 * [providerStreamOrder] — the same order the player would try them in, so a server whose policy
 * prefers its direct files to its HLS mirror (ally) or its native playlist to its embed (kiwi) is
 * downloaded the way it is played. Taking the list's first downloadable entry instead, as the
 * queue did, is an order the provider never promised anything about.
 *
 * [selectDownloadStream] then has the final word on resolution, which is what the seed cannot
 * decide: the provider's preference is about which *source* to use, not which rendition of it.
 */
internal fun downloadStreamFor(
    provider: String,
    sources: SourcesResult,
    quality: DownloadQuality,
): StreamItem? {
    val seed = downloadSeedStream(provider, sources) ?: return null
    return selectDownloadStream(seed, sources.streams, quality)
}

/**
 * The provider's own first choice among the streams this app can save, with no opinion yet about
 * resolution — see [downloadStreamFor], which is this plus the viewer's ceiling.
 *
 * Separate because the watch screen needs the answer before a resolution has been chosen: it is
 * what decides whether the download control appears at all when the stream on screen is an embed
 * nothing can save.
 */
internal fun downloadSeedStream(provider: String, sources: SourcesResult): StreamItem? =
    providerStreamOrder(provider, sources).firstOrNull(EpisodeDownloads::canDownload)
        ?: sources.streams.firstOrNull(EpisodeDownloads::canDownload)

/**
 * The streams [selectDownloadStream] chooses between, each with the height it declares: the same
 * delivery (HLS or not) as [current], saveable, and not in a different audio language.
 */
private fun renditionCandidates(
    current: StreamItem,
    available: List<StreamItem>,
): List<Pair<StreamItem, Int>> = (listOf(current) + available)
    .filter { !current.isHls || it.isHls }
    .filter(EpisodeDownloads::canSaveToDevice)
    .filter { candidate ->
        current.audio == null || candidate.audio == null || candidate.audio == current.audio
    }
    .distinctBy(StreamItem::url)
    .mapNotNull { candidate ->
        val height = candidate.height ?: declaredVideoHeight(candidate.quality)
        height?.let { candidate to it }
    }

/**
 * Heights the provider publishes as separate streams alongside [current], lowest first.
 *
 * Two or more means the provider's own list is the ladder (Kwik's shape) and nothing needs to be
 * fetched to know what is on offer. One or none says nothing about what sits *inside* an HLS
 * playlist — that takes [DownloadRenditionProbe].
 */
internal fun publishedHeights(current: StreamItem, available: List<StreamItem>): List<Int> =
    renditionCandidates(current, available).map { it.second }.distinct().sorted()

/**
 * The height a download will come back at from a server offering [offered], or null when nothing
 * is known about what it offers.
 *
 * Mirrors both deciders: [pickByHeight] here, and Media3's track selection under
 * `setMaxVideoSize`, which with nothing under the ceiling exceeds it on the smallest rendition.
 */
internal fun deliveredHeight(offered: List<Int>, quality: DownloadQuality): Int? {
    if (offered.isEmpty()) return null
    val maxHeight = quality.maxHeight ?: return offered.max()
    return offered.filter { it <= maxHeight }.maxOrNull() ?: offered.min()
}

/**
 * Whether a server offering [offered] would hand back what the viewer asked for: the chosen
 * resolution or a rung just under it. Both misses surprise someone — 480p asked of a 1080p-only
 * server comes back 1080p, and 720p asked of a 360/1080 ladder comes back 360p. An unknown ladder
 * is not evidence of anything, and "Best" asks for nothing in particular.
 *
 * The floor is a fraction rather than an exact match so a letterboxed or oddly-encoded rung
 * (1080p stored 1072 tall) still counts as the resolution it is.
 */
internal fun fitsQuality(offered: List<Int>, quality: DownloadQuality): Boolean {
    val maxHeight = quality.maxHeight ?: return true
    val delivered = deliveredHeight(offered, quality) ?: return true
    return delivered <= maxHeight && delivered >= maxHeight * FIT_FLOOR
}

private const val FIT_FLOOR = 0.85

/** Best rendition at or below the requested ceiling, or the smallest when none fits. */
private fun pickByHeight(
    candidates: List<Pair<StreamItem, Int>>,
    quality: DownloadQuality,
): StreamItem? {
    val maxHeight = quality.maxHeight ?: return candidates.maxByOrNull { it.second }?.first
    return candidates.filter { it.second <= maxHeight }.maxByOrNull { it.second }?.first
        ?: candidates.minByOrNull { it.second }?.first
}
