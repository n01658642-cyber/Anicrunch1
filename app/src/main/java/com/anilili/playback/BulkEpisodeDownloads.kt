package com.anilili.playback

import com.anilili.data.model.hasUsableWindow
import android.content.Context
import com.anilili.data.AppGraph
import com.anilili.data.model.Category
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.SourceVariant
import com.anilili.data.settings.DownloadDestination
import com.anilili.data.settings.DownloadQuality
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.report
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Why a batch stopped, for the line the UI shows when it is over. */
enum class BulkDownloadOutcome { RUNNING, FINISHED, CANCELLED, OUT_OF_SPACE }

/**
 * How one episode's turn in the queue ended.
 *
 * Separated because the three failures need three different sentences: a source that has nothing
 * downloadable, a disk with no room, and a queue that was thrown away are not the same news, and
 * a single boolean made the storage refusal read as "no source found".
 */
enum class EpisodeQueueOutcome { QUEUED, NO_SOURCE, OUT_OF_SPACE, DROPPED }

data class BulkDownloadProgress(
    val seriesTitle: String,
    val total: Int,
    val queued: Int = 0,
    val failed: Int = 0,
    /** Episode label currently being resolved, so the UI can say more than a bare count. */
    val current: String? = null,
    val outcome: BulkDownloadOutcome = BulkDownloadOutcome.RUNNING,
) {
    val done: Int get() = queued + failed
    val isRunning: Boolean get() = outcome == BulkDownloadOutcome.RUNNING
}

/**
 * One episode waiting for a source, carrying everything its resolution needs.
 *
 * [countsTowardBatch] separates the two kinds of caller. A batch member moves the counters behind
 * [BulkEpisodeDownloads.progress]; a single episode tapped in a list has no banner of its own and
 * must not move somebody else's — it reports through [onOutcome] instead.
 */
private data class PendingDownload(
    val anilistId: Int,
    val seriesTitle: String,
    val artworkUrl: String?,
    val category: Category,
    val preferredProvider: String,
    /**
     * The subtitle shape asked for, which is not derivable from [category].
     *
     * Two servers publish both — bonk offers soft sub first, twin hard sub first — and the queue
     * used to resolve at [SourceVariant.defaultFor], so an episode queued while watching bonk's
     * soft sub came back as whichever variant the provider happened to list first. The viewer's
     * choice is a property of the request, so it travels with it.
     */
    val variant: SourceVariant,
    val episode: EpisodeItem,
    val catalog: EpisodesResult,
    val quality: DownloadQuality,
    val countsTowardBatch: Boolean,
    /**
     * Only a server with the subtitles burned into the picture will do; see [HardSubDownloads].
     * An episode no such server has is a failed episode, not a soft-sub copy in its place.
     */
    val hardSubsOnly: Boolean = false,
    /**
     * Whether a server that would exceed [quality] sends this episode looking for one that would
     * not. Off for everything but a batch that asked for it: a single tap on a row, or a re-queue
     * after a failed transfer, gets the server it was already going to get.
     */
    val matchQuality: Boolean = false,
    /** Sources this episode has already been through, so a re-queue does not pick one of them. */
    val excludedProviders: Set<String> = emptySet(),
    /** Carried into the download's metadata; see [EpisodeDownloadMetadata.freshResolves]. */
    val freshResolves: Int = 0,
    /**
     * Run once a replacement stream has been resolved and is about to be handed to the downloader.
     *
     * This is where a re-queue drops the failed download it is replacing. Doing it here rather than
     * when the re-queue was decided is what keeps a dead end from becoming a disappearance: if no
     * other source has the episode, nothing is removed and the viewer keeps both the bytes already
     * fetched and the Retry button over them.
     */
    val beforeEnqueue: (() -> Unit)? = null,
    val onOutcome: ((EpisodeQueueOutcome) -> Unit)? = null,
) {
    val downloadId: String
        get() = EpisodeDownloads.idFor(anilistId, category.api, episode.displayNumber)
}

/**
 * Queues episodes for offline download, one at a time.
 *
 * Sources are resolved **as each episode reaches the front of the queue**, never upfront. Several
 * providers hand out URLs that are short-lived, IP-bound or single-use, so resolving forty
 * episodes in advance would leave most of the batch holding links that expired before the
 * downloader reached them. It also means a batch costs one provider request per episode rather
 * than a burst of forty, which is what a scraper would rate-limit.
 *
 * There is one queue and one worker for the whole app, so the download button on a list row and
 * the batch dialog in the player cannot resolve against the same provider at the same time.
 */
object BulkEpisodeDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards [queue], [inFlightIds] and [activeWorkers]; never held across a suspension point. */
    private val queue = ArrayDeque<PendingDownload>()
    private val inFlightIds = mutableSetOf<String>()
    private var worker: Job? = null // legacy unused; activeWorkers tracks parallel slots

    @Volatile private var appContext: Context? = null

    private val _progress = MutableStateFlow<BulkDownloadProgress?>(null)
    val progress = _progress.asStateFlow()

    private val _pendingIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Download ids waiting on, or currently in, source resolution.
     *
     * Nothing exists in Media3's download index until a provider has answered, and on a slow
     * source that is a quiet twenty seconds. A list row needs this to show the press registered;
     * without it the button looks dead and gets pressed again.
     */
    val pendingIds = _pendingIds.asStateFlow()

    /** Episodes after (and including) [fromNumber] that are not already downloaded. */
    fun pendingEpisodes(
        episodes: List<EpisodeItem>,
        anilistId: Int,
        category: String,
        fromNumber: Double?,
        alreadyHave: Set<String>,
    ): List<EpisodeItem> = episodes
        .asSequence()
        .filter { fromNumber == null || it.number >= fromNumber }
        .filterNot { EpisodeDownloads.idFor(anilistId, category, it.displayNumber) in alreadyHave }
        .toList()

    /** Keeps batch order while optionally removing entries the catalog marks as filler. */
    fun eligibleEpisodes(episodes: List<EpisodeItem>, skipFillers: Boolean): List<EpisodeItem> =
        if (skipFillers) episodes.filterNot(EpisodeItem::filler) else episodes

    /**
     * Drops everything still waiting, batch members and single episodes alike.
     *
     * The episode already being resolved is left to finish: its provider request is paid for
     * either way, and abandoning it mid-flight is how a half-registered download used to appear
     * in the index with nothing behind it.
     */
    fun cancel() {
        val dropped = drainQueue()
        _progress.value = _progress.value?.copy(
            current = null,
            outcome = BulkDownloadOutcome.CANCELLED,
        )
        report(dropped, EpisodeQueueOutcome.DROPPED)
    }

    /** Clears a finished batch's status line. */
    fun dismiss() {
        if (_progress.value?.isRunning != true) _progress.value = null
    }

    /**
     * Queues a single episode without disturbing whatever is already waiting.
     *
     * This is the list-row download button. Appending rather than replacing is the whole point:
     * tapping four rows in a row must queue four episodes, not three cancellations and one
     * download. A second tap on an episode already waiting is ignored.
     */
    fun enqueueEpisode(
        context: Context,
        anilistId: Int,
        seriesTitle: String,
        artworkUrl: String?,
        category: Category,
        preferredProvider: String,
        variant: SourceVariant = SourceVariant.defaultFor(category),
        episode: EpisodeItem,
        catalog: EpisodesResult,
        quality: DownloadQuality,
        hardSubsOnly: Boolean = false,
        onOutcome: (EpisodeQueueOutcome) -> Unit = {},
    ) {
        appContext = context.applicationContext
        val item = PendingDownload(
            anilistId = anilistId,
            seriesTitle = seriesTitle,
            artworkUrl = artworkUrl,
            category = category,
            preferredProvider = preferredProvider,
            variant = variant,
            episode = episode,
            catalog = catalog,
            quality = quality,
            countsTowardBatch = false,
            hardSubsOnly = hardSubsOnly,
            onOutcome = onOutcome,
        )
        if (item.downloadId in _pendingIds.value) return
        push(listOf(item), replace = false)
        DiagnosticsLog.event(
            "Episode download queued from list id=${item.downloadId} provider=$preferredProvider",
        )
        ensureWorker()
    }

    /**
     * Puts an episode back in the queue on a source it has not been through yet.
     *
     * Called by [EpisodeDownloads] when a transfer dies partway rather than at resolution, which is
     * the case the queue could not previously see: [queueOne] retries other providers while it is
     * still trying to *start* a download, and then hands the episode to Media3 and forgets it.
     *
     * The catalog is fetched again instead of being remembered from the original request. It is one
     * cached call, the failure this answers can arrive an hour later, and the alternative is
     * holding every queued episode's provider list alive for the length of its download.
     *
     * The failed download is not discarded until there is something to put in its place. Removing
     * it up front would empty the row for however long the catalog took to answer, and would throw
     * the episode away entirely on a lookup that never does.
     *
     * [preferredProvider] names the server to ask first instead of the viewer's global preference.
     * It is how a server with expiring URLs is asked for a fresh one (see
     * [EpisodeDownloads.shouldResolveAgainOnSameProvider]); [freshResolves] counts those asks.
     */
    fun retryOnAnotherProvider(
        context: Context,
        metadata: EpisodeDownloadMetadata,
        excludedProviders: Set<String>,
        preferredProvider: String? = null,
        freshResolves: Int = metadata.freshResolves,
    ) {
        val app = context.applicationContext
        appContext = app
        val downloadId = EpisodeDownloads.idFor(
            metadata.anilistId,
            metadata.category,
            metadata.episodeNumber,
        )
        scope.launch {
            val number = metadata.episodeNumber.trim().toDoubleOrNull() ?: run {
                DiagnosticsLog.event(
                    "Episode download resend has no episode number id=$downloadId " +
                        "episode=${metadata.episodeNumber}",
                )
                EpisodeDownloads.releaseProviderFallback(downloadId)
                return@launch
            }
            val category = Category.from(metadata.category)
            val catalog = runCatching { AppGraph.repository.episodes(metadata.anilistId) }
                .onFailure {
                    DiagnosticsLog.throwable("Episode download resend catalog failed", it)
                }
                .getOrNull()
            if (catalog == null) {
                EpisodeDownloads.releaseProviderFallback(downloadId)
                return@launch
            }
            // Prefer the catalog's own entry so the replacement carries the same pipe id and filler
            // mark as any other download of this episode; fall back to what the failed one knew.
            val episode = catalog.providers
                .asSequence()
                .flatMap { it.episodes(category).asSequence() }
                .firstOrNull { it.number == number }
                ?: EpisodeItem(
                    pipeId = "",
                    number = number,
                    title = metadata.episodeTitle,
                    image = null,
                    filler = metadata.filler,
                )
            val item = PendingDownload(
                anilistId = metadata.anilistId,
                seriesTitle = metadata.seriesTitle,
                artworkUrl = metadata.artworkUrl,
                category = category,
                preferredProvider = preferredProvider ?: SettingsStore.preferredProvider.value,
                // A re-queue is the same request on a different server, so it keeps the subtitle
                // shape the viewer asked for. Downloads queued before the field existed have none
                // recorded and fall back to the category's default, as they did then.
                variant = metadata.variant
                    ?.let(SourceVariant::fromRoute)
                    ?: SourceVariant.defaultFor(category),
                episode = episode,
                catalog = catalog,
                quality = DownloadQuality.fromStored(metadata.requestedQuality),
                countsTowardBatch = false,
                hardSubsOnly = metadata.hardSubsOnly,
                excludedProviders = excludedProviders,
                freshResolves = freshResolves,
                // The dead cache is only worth losing once a replacement stream is in hand; the
                // removal also clears the guard [EpisodeDownloads] holds over this download.
                beforeEnqueue = { EpisodeDownloads.remove(app, downloadId) },
                // Anything other than a fresh download leaves the old one where it is, so the
                // guard has to come off or the viewer's own Retry could never switch source again.
                onOutcome = { outcome ->
                    if (outcome != EpisodeQueueOutcome.QUEUED) {
                        DiagnosticsLog.event(
                            "Episode download resend found no other source id=$downloadId " +
                                "outcome=$outcome",
                        )
                        EpisodeDownloads.releaseProviderFallback(downloadId)
                    }
                },
            )
            if (item.downloadId in _pendingIds.value) {
                EpisodeDownloads.releaseProviderFallback(downloadId)
                return@launch
            }
            push(listOf(item), replace = false)
            DiagnosticsLog.event(
                "Episode download resend queued id=${item.downloadId} excluded=$excludedProviders " +
                    "preferred=${item.preferredProvider} freshResolves=$freshResolves",
            )
            ensureWorker()
        }
    }

    fun start(
        context: Context,
        anilistId: Int,
        seriesTitle: String,
        artworkUrl: String?,
        category: Category,
        preferredProvider: String,
        variant: SourceVariant = SourceVariant.defaultFor(category),
        episodes: List<EpisodeItem>,
        catalog: EpisodesResult,
        quality: DownloadQuality,
        /** See [PendingDownload.matchQuality]. */
        matchQuality: Boolean = false,
        /** See [PendingDownload.hardSubsOnly]. */
        hardSubsOnly: Boolean = false,
    ) {
        if (episodes.isEmpty()) return
        appContext = context.applicationContext
        _progress.value = BulkDownloadProgress(seriesTitle = seriesTitle, total = episodes.size)
        DiagnosticsLog.event(
            "Bulk download start id=$anilistId episodes=${episodes.size} category=${category.api} " +
                "quality=${quality.label} matchQuality=$matchQuality hardSubsOnly=$hardSubsOnly",
        )
        val items = episodes.map { episode ->
            PendingDownload(
                anilistId = anilistId,
                seriesTitle = seriesTitle,
                artworkUrl = artworkUrl,
                category = category,
                preferredProvider = preferredProvider,
                variant = variant,
                episode = episode,
                catalog = catalog,
                quality = quality,
                countsTowardBatch = true,
                matchQuality = matchQuality && quality.maxHeight != null,
                hardSubsOnly = hardSubsOnly,
            )
        }
        // Starting a batch still replaces one already waiting, as it always has.
        report(push(items, replace = true), EpisodeQueueOutcome.DROPPED)
        ensureWorker()
    }

    /** Adds [items] to the queue, returning anything a replacement threw away. */
    private fun push(items: List<PendingDownload>, replace: Boolean): List<PendingDownload> =
        synchronized(queue) {
            val dropped = if (replace) queue.toList() else emptyList()
            if (replace) queue.clear()
            queue.addAll(items)
            syncPendingIds()
            dropped
        }

    private fun drainQueue(): List<PendingDownload> = synchronized(queue) {
        val dropped = queue.toList()
        queue.clear()
        syncPendingIds()
        dropped
    }

    /** Call with [queue] held. */
    private fun syncPendingIds() {
        _pendingIds.value = buildSet {
            addAll(inFlightIds)
            queue.forEach { add(it.downloadId) }
        }
    }

    /**
     * Starts the worker unless one is already running.
     *
     * The restart in the exit path is what keeps an episode queued in the split second a worker
     * spends finishing from being left in the queue with nothing to drain it.
     */
    private fun maxParallelWorkers(): Int =
        if (SettingsStore.parallelDownloads.value) 2 else 1

    private var activeWorkers = 0

    private fun ensureWorker() {
        synchronized(queue) {
            val limit = maxParallelWorkers()
            while (activeWorkers < limit && queue.isNotEmpty()) {
                activeWorkers++
                scope.launch {
                    try {
                        drain()
                    } finally {
                        val restart = synchronized(queue) {
                            activeWorkers = (activeWorkers - 1).coerceAtLeast(0)
                            queue.isNotEmpty() && activeWorkers < maxParallelWorkers()
                        }
                        if (restart) ensureWorker()
                    }
                }
            }
            // Keep legacy worker flag unused; drain loop owns progress.
            worker = null
        }
    }

    private suspend fun drain() {
        val app = appContext ?: return
        // One episode per worker invocation when parallel is on; sequential still drains the queue.
        val parallel = SettingsStore.parallelDownloads.value
        while (true) {
            val next = synchronized(queue) {
                val item = queue.removeFirstOrNull() ?: return@synchronized null
                inFlightIds.add(item.downloadId)
                syncPendingIds()
                item
            } ?: return

            // Checked per episode, not once upfront: the queue itself is what fills the disk, and
            // a stick can run dry three episodes in.
            if (!DownloadStorage.hasRoomFor(app, next.quality.maxHeight)) {
                DiagnosticsLog.event("Download queue stopped: out of space")
                val dropped = synchronized(queue) {
                    val rest = queue.toList()
                    queue.clear()
                    inFlightIds.clear()
                    syncPendingIds()
                    rest
                }
                _progress.value = _progress.value?.copy(
                    current = null,
                    outcome = BulkDownloadOutcome.OUT_OF_SPACE,
                )
                report(dropped + next, EpisodeQueueOutcome.OUT_OF_SPACE)
                return
            }

            if (next.countsTowardBatch) {
                _progress.value = _progress.value?.copy(current = next.episode.displayNumber)
            }

            // Bounded, because the queue is single-file and everything behind an episode waits on
            // it. A viewer's batch of nine queued two episodes and then nothing for eleven minutes:
            // the resolve it was on never came back at all, not even as its own 45-second timeout,
            // and with the worker parked inside it the seven episodes behind it were never
            // attempted. Nothing downstream can be trusted to always answer — a provider bridge
            // that needs a foreground WebView, or Media3's manifest prepare, both simply stop —
            // so the queue keeps its own clock and moves on without them.
            //
            // Generous on purpose: a legitimate walk through five providers with a 45-second
            // timeout apiece, three times over, took a little under four minutes in that same
            // batch. This is the difference between a slow episode and a stuck one, not a deadline
            // for a fast one.
            val ok = withTimeoutOrNull(EPISODE_TURN_TIMEOUT_MS) {
                runCatching { queueOne(app, next) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        DiagnosticsLog.throwable(
                            "Episode download failed episode=${next.episode.displayNumber}",
                            error,
                        )
                    }
                    .getOrDefault(false)
            } ?: run {
                DiagnosticsLog.event(
                    "Episode download gave up after ${EPISODE_TURN_TIMEOUT_MS / 1000}s " +
                        "id=${next.downloadId} episode=${next.episode.displayNumber}",
                )
                false
            }

            record(next, ok)

            synchronized(queue) {
                inFlightIds.remove(next.downloadId)
                syncPendingIds()
            }

            // A short breather between provider hits; a tight loop over a scraper is the fastest
            // way to get everything queued behind it rate-limited.
            delay(if (parallel) BETWEEN_EPISODES_MS / 2 else BETWEEN_EPISODES_MS)
            if (parallel) {
                // One episode per worker; ensureWorker will refill free slots.
                return
            }
        }
    }

    /** Moves the batch counters (when the episode belongs to one) and tells its caller. */
    private suspend fun record(item: PendingDownload, ok: Boolean) {
        val outcome = if (ok) EpisodeQueueOutcome.QUEUED else EpisodeQueueOutcome.NO_SOURCE
        if (item.countsTowardBatch) {
            _progress.value = _progress.value?.let { current ->
                val queued = current.queued + if (ok) 1 else 0
                val failed = current.failed + if (ok) 0 else 1
                val finished = queued + failed >= current.total
                if (finished) {
                    DiagnosticsLog.event("Bulk download finished queued=$queued failed=$failed")
                }
                current.copy(
                    queued = queued,
                    failed = failed,
                    current = if (finished) null else current.current,
                    outcome = if (finished) BulkDownloadOutcome.FINISHED else current.outcome,
                )
            }
        }
        item.onOutcome?.let { callback -> withContext(Dispatchers.Main) { callback(outcome) } }
    }

    /** Tells whoever asked for these episodes how their turn ended. */
    private fun report(items: List<PendingDownload>, outcome: EpisodeQueueOutcome) {
        val callbacks = items.mapNotNull(PendingDownload::onOutcome)
        if (callbacks.isEmpty()) return
        scope.launch(Dispatchers.Main) { callbacks.forEach { it(outcome) } }
    }

    /** Resolves one episode and hands it to the downloader. Returns false if nothing was playable. */
    private suspend fun queueOne(context: Context, item: PendingDownload): Boolean {
        val excludedProviders = item.excludedProviders.toMutableSet()
        var attempts = 0
        // Read before enqueueing, because afterwards there is no telling the two apart: an episode
        // the viewer already had must keep its library copy even when the destination is
        // device-only, exactly as the player's dialog treats it.
        val alreadyHeld = EpisodeDownloads.downloads(context).value
            .any { it.id == item.downloadId && it.isComplete }
        var replaced = false
        while (attempts < 3) {
            attempts++
            val download = resolveDownloadable(context, item, excludedProviders) ?: return false

            if (!replaced) {
                replaced = true
                item.beforeEnqueue?.invoke()
            }
            if (awaitEnqueue(context, download, item.quality)) {
                requestDeviceCopy(context, item.downloadId, alreadyHeld)
                return true
            }

            // HLS prepares are asynchronous and provider URLs are routinely single-use, short-lived,
            // or 403: if prepare fails, exclude this provider and try an alternative source.
            excludedProviders.add(download.metadata.provider)
        }
        return false
    }

    /**
     * Asks for the Downloads-folder MP4 whenever the viewer's chosen destination calls for one.
     *
     * The queue behind the anime page reached this point doing nothing at all, so the same episode
     * came out as an MP4 in `Downloads/Anilili` when downloaded from the player and as cache
     * segments only the app can read when downloaded from its own title page — same button, same
     * setting, different result, and nothing on screen to say which one you were getting. The
     * destination is a preference about where downloads go, not about which screen started them.
     *
     * A batch was excluded for a reason that turns out not to hold: the export runs off the
     * finished download rather than the resolved stream, so having no stream to describe up front
     * costs it nothing. Fifteen at once is safe now too — the transmux gate admits one at a time
     * process-wide and stands down during playback, which is what the parallel workers that used
     * to dominate the mobile crashes were missing.
     */
    private fun requestDeviceCopy(context: Context, downloadId: String, alreadyHeld: Boolean) {
        val destination = SettingsStore.downloadDestination.value
        if (!destination.includesDevice) return
        EpisodeExport.request(
            context = context,
            downloadId = downloadId,
            deleteAppCopyAfter = deleteAppCopyAfterExport(destination, alreadyHeld),
        )
    }

    private class ResolvedDownload(
        val stream: com.anilili.data.model.StreamItem,
        val metadata: EpisodeDownloadMetadata,
    )

    private suspend fun resolveDownloadable(
        context: Context,
        item: PendingDownload,
        excludedProviders: Set<String> = emptySet(),
    ): ResolvedDownload? {
        val currentExcluded = excludedProviders.toMutableSet()
        // Servers passed over, in the order the walk met them. The first is what this episode would
        // have got with no search at all, and it is still the answer when nothing better turns up —
        // the wrong size beats no file.
        val passedOver = mutableListOf<ResolvedDownload>()
        val qualityDeadline = System.currentTimeMillis() + QUALITY_SEARCH_BUDGET_MS
        val hardSubsOnly = HardSubDownloads.appliesTo(item.hardSubsOnly, item.category)
        val excludedSources = if (hardSubsOnly) HardSubDownloads.excludedSources(item.catalog) else emptySet()
        while (true) {
            val resolution = AppGraph.repository.resolveSources(
                anilistId = item.anilistId,
                number = item.episode.number,
                preferred = item.preferredProvider,
                category = item.category,
                sourceVariant = item.variant,
                episodes = item.catalog,
                excludedProviders = currentExcluded,
                excludedSources = excludedSources,
            )
            val resolved = resolution.resolved ?: run {
                passedOver.firstOrNull()?.let { held ->
                    DiagnosticsLog.event(
                        "Episode download found no server with ${item.quality.label} " +
                            "id=${item.downloadId} keeping=${held.metadata.provider}",
                    )
                    return held
                }
                // Every server has now been tried and none offered a copy this app can save — the
                // batch counts it as one failed episode, and SRC-08 is what makes "3 had no
                // downloadable source" answerable afterwards: which episode, on which servers.
                AnililiError.SOURCE_NO_DOWNLOADABLE.report(
                    attributes = mapOf(
                        "anilistId" to item.anilistId,
                        "episode" to item.episode.displayNumber,
                        "category" to item.category.api,
                        "variant" to item.variant.route,
                        "excludedProviders" to currentExcluded.joinToString(","),
                        "hardSubsOnly" to hardSubsOnly,
                    ),
                )
                return null
            }
            // A native server's shape only shows once it has answered: subtitle files beside the
            // video mean a clean picture, which is the one thing this download asked not to get.
            if (hardSubsOnly && !HardSubDownloads.delivers(resolved.variant, resolved.sources.subtitles)) {
                DiagnosticsLog.event(
                    "Episode download skipped a soft-sub server id=${item.downloadId} " +
                        "provider=${resolved.provider} variant=${resolved.variant.route}",
                )
                currentExcluded.add(resolved.provider)
                continue
            }
            // The provider's own order, then the requested resolution — see [downloadStreamFor].
            // Taking the list's first downloadable entry ignored both.
            val stream = downloadStreamFor(resolved.provider, resolved.sources, item.quality)
            if (stream != null) {
                val metadata = EpisodeDownloadMetadata(
                    anilistId = item.anilistId,
                    seriesTitle = item.seriesTitle,
                    episodeNumber = item.episode.displayNumber,
                    episodeTitle = item.episode.title,
                    filler = item.episode.filler,
                    artworkUrl = item.episode.image?.takeIf { it.isNotBlank() } ?: item.artworkUrl,
                    seriesArtworkUrl = item.artworkUrl,
                    provider = resolved.provider,
                    category = item.category.api,
                    // What the server actually answered with, which may not be what was asked for:
                    // a hard-sub-only server reached through the fallback ladder has no soft sub to
                    // give, and the download should say so rather than repeat the request back.
                    variant = resolved.variant.route,
                    hardSubsOnly = item.hardSubsOnly,
                    referer = stream.referer,
                    headers = stream.headers,
                    subtitles = resolved.sources.subtitles.map {
                        EpisodeDownloadSubtitle(
                            url = it.url,
                            label = it.label,
                            language = it.language,
                            referer = it.referer,
                            headers = it.headers,
                            mimeType = it.mimeType,
                        )
                    },
                    skip = resolved.sources.skip?.takeIf { it.hasUsableWindow() },
                    // Everything already ruled out, plus the source about to be tried. A download
                    // that dies mid-transfer reads this to know what not to fall back to.
                    attemptedProviders = (currentExcluded + resolved.provider).toList(),
                    // Only a fresh resolve that landed on the server it was asked of counts; a walk
                    // that moved on to another server starts that one's allowance from nothing.
                    freshResolves = if (resolved.provider == item.preferredProvider) item.freshResolves else 0,
                )
                val download = ResolvedDownload(stream, metadata)
                if (!item.matchQuality) return download
                val offered = DownloadRenditionProbe.offeredHeights(
                    context,
                    stream,
                    resolved.sources.streams,
                )
                // Only a ladder that was actually read and fits is worth switching to. An unreadable
                // one is passed over too: on a Galaxy S25 the walk took bee (its CDN 403'd the probe
                // and then the download) and then xfdm (a bare file declaring no height), while the
                // dialog's check — which skips unknowns — had already found hop with 720p.
                if (offered.isNotEmpty() && fitsQuality(offered, item.quality)) {
                    if (passedOver.isNotEmpty()) {
                        DiagnosticsLog.event(
                            "Episode download switched server for ${item.quality.label} " +
                                "id=${item.downloadId} from=${passedOver.first().metadata.provider} " +
                                "to=${resolved.provider} offered=$offered",
                        )
                    }
                    return download
                }
                passedOver += download
                DiagnosticsLog.event(
                    "Episode download server misses ${item.quality.label} id=${item.downloadId} " +
                        "provider=${resolved.provider} offered=$offered",
                )
                if (passedOver.size > MAX_QUALITY_ALTERNATIVES ||
                    System.currentTimeMillis() > qualityDeadline
                ) {
                    return passedOver.first()
                }
            }
            // Resolved provider returned only embeds / un-downloadable streams.
            // Exclude it and loop to try the next provider in priority order.
            currentExcluded.add(resolved.provider)
        }
    }

    /**
     * Suspends until the enqueue actually succeeds or fails. HLS downloads prepare their manifest
     * asynchronously, so counting the episode the moment [EpisodeDownloads.enqueue] returns is how
     * a batch of 403s used to report "queued=5 failed=0" and leave no trace in the library.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun awaitEnqueue(
        context: Context,
        download: ResolvedDownload,
        quality: DownloadQuality,
    ): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        EpisodeDownloads.enqueue(context, download.metadata, download.stream, quality) { result ->
            if (continuation.isActive) continuation.resume(result.isSuccess) {}
        }
    }

    /**
     * Whether the cached copy goes once the MP4 is written.
     *
     * "Device Downloads" means don't leave behind a library copy *this download* created — not
     * delete one the viewer already had. Getting this backwards is unrecoverable in the way that
     * matters: the episode is gone from the library and the viewer never asked for that.
     */
    internal fun deleteAppCopyAfterExport(destination: DownloadDestination, alreadyHeld: Boolean): Boolean =
        !destination.includesApp && !alreadyHeld

    private const val BETWEEN_EPISODES_MS = 1_200L

    /**
     * How many servers past the first one episode may try for the requested rendition, and for how
     * long. Both well inside [EPISODE_TURN_TIMEOUT_MS]: a larger file on time beats the right size
     * never, and the first server's URL is ageing the whole while.
     */
    private const val MAX_QUALITY_ALTERNATIVES = 4
    private const val QUALITY_SEARCH_BUDGET_MS = 90_000L

    /** How long one episode may hold the queue before it is written off and the next one starts. */
    private const val EPISODE_TURN_TIMEOUT_MS = 5L * 60 * 1000

    /**
     * Batch sizes offered in the download dialog.
     *
     * Deliberately bounded rather than "all of them". A long-runner like Naruto Shippuden has 500
     * episodes; queueing the lot is roughly 200 GB, hours of provider requests, and nothing a
     * viewer can meaningfully supervise. A viewer who wants more simply starts another batch.
     */
    val BATCH_SIZES = listOf(5, 10, 25)
}
