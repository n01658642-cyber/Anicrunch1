package com.anilili.ui.detail

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.anilili.data.ProviderCatalog
import com.anilili.data.model.Category
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.Media
import com.anilili.data.model.SourceVariant
import com.anilili.data.settings.DownloadQuality
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.playback.BatchQualityCheck
import com.anilili.playback.BulkEpisodeDownloads
import com.anilili.playback.EpisodeDownloadStage
import com.anilili.playback.EpisodeDownloadUi
import com.anilili.playback.EpisodeDownloads
import com.anilili.playback.EpisodeExport
import com.anilili.playback.EpisodeQueueOutcome
import com.anilili.playback.HardSubDownloads
import com.anilili.playback.OfflineEpisode
import com.anilili.playback.checkBatchQuality
import com.anilili.playback.episodeDownloadBadges
import com.anilili.playback.offlineEpisodes
import com.anilili.ui.components.EpisodeDownloadAction
import com.anilili.ui.dialogs.MidnightConfirmDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * One episode's offline copy, resolved once per row: which download id it is, and what that
 * download is currently doing. The id has to travel with the state because the row may be
 * speaking for the other audio track's copy.
 */
internal data class EpisodeDownloadTarget(
    val episode: EpisodeItem,
    val downloadId: String,
    val state: EpisodeDownloadUi?,
)

/**
 * The anime page's downloads — the per-episode controls and the batch — as one piece both page
 * layouts share.
 *
 * It lived inside the phone layout, below the point where the television page had already
 * returned, which is how TV came to have no download on the anime page at all. The phone uses all
 * of it; the television page offers only the batch, whose episode list is where a single episode
 * is picked as well.
 */
internal class SeasonDownloads(
    /** The download a row speaks for, id included so the control acts on the copy it is showing. */
    val targetFor: (EpisodeItem) -> EpisodeDownloadTarget,
    /** Whether the episode is still waiting for its provider listing or a source. */
    val isPending: (EpisodeItem) -> Boolean,
    val onEpisodeAction: (EpisodeDownloadTarget, EpisodeDownloadAction) -> Unit,
    /** Opens the batch dialog over the episodes on show. */
    val openBatch: () -> Unit,
    /** Episodes on show that are not already saved — what the batch would offer. */
    val batchCandidateCount: Int,
    /** True while a confirmed batch waits for its provider listing. */
    val batchPreparing: Boolean,
)

/**
 * State for [SeasonDownloads], and the dialogs it opens — which it draws itself, wherever it is
 * called, so neither layout can forget one.
 *
 * [shown] is the batch's scope: exactly the episodes the list is displaying, for the reasons given
 * on [SeasonDownloadDialog].
 */
@Composable
internal fun rememberSeasonDownloads(
    data: DetailData,
    seasonMedia: Media,
    shown: List<EpisodeItem>,
    scopeLabel: String,
    /** The providers' episode listing for a season, fetched only when a download needs it. */
    loadProviderCatalog: suspend (Int) -> EpisodesResult,
    /**
     * Whether the dialog opens with every episode on show ticked. The phone's list has its own
     * arrow on every row, so its batch control is only ever asked for a run. On television the
     * batch dialog is the one way to download at all — one episode as often as a season — and a
     * season ticked up front on a stick with a few GB free opened on "Not enough space".
     */
    preselectAll: Boolean = true,
): SeasonDownloads {
    val context = LocalContext.current
    val seasonId = data.selectedSeasonId
    val downloadIndex by EpisodeDownloads.downloads(context).collectAsState()
    val exportStatuses by EpisodeExport.statuses(context).collectAsState()
    val exportedEpisodes by EpisodeExport.exported(context).collectAsState()
    val downloadBadges = remember(downloadIndex, exportStatuses, exportedEpisodes) {
        episodeDownloadBadges(downloadIndex, exportStatuses, exportedEpisodes)
    }

    // Everything the resolver needs is season-scoped, because a download belongs to the season
    // whose row was pressed rather than to the page it was pressed on.
    val downloadCategory = data.preferredCategory
    val preferredProvider by SettingsStore.preferredProvider.collectAsState()
    val downloadQuality by SettingsStore.downloadQuality.collectAsState()
    val hardSubsOnly by SettingsStore.downloadHardSubsOnly.collectAsState()
    val resolvingDownloadIds by BulkEpisodeDownloads.pendingIds.collectAsState()
    // The provider listing has to be fetched before the episode can even be queued, and that is
    // its own wait. Held here so the row spins from the press rather than from the queue.
    var awaitingCatalogIds by remember(seasonId) { mutableStateOf(emptySet<String>()) }
    var downloadToRemove by remember(seasonId) { mutableStateOf<EpisodeDownloadTarget?>(null) }
    var downloadToCancel by remember(seasonId) { mutableStateOf<EpisodeDownloadTarget?>(null) }
    val downloadScope = rememberCoroutineScope()

    val downloadIdFor: (EpisodeItem) -> String = { episode ->
        EpisodeDownloads.idFor(seasonId, downloadCategory.api, episode.displayNumber)
    }

    // The other audio track only counts when it is finished: a viewer who saved the dub has the
    // episode however this page is set, but a dub download that is queued or has failed is not
    // something a row set to sub should offer to stop or retry — pressing it would act on the sub,
    // which is not what the row was reporting.
    val targetFor: (EpisodeItem) -> EpisodeDownloadTarget = { episode ->
        val preferredId = downloadIdFor(episode)
        val badge = downloadBadges[preferredId]
        if (badge != null) {
            EpisodeDownloadTarget(episode, preferredId, badge)
        } else {
            val otherId = EpisodeDownloads.idFor(
                seasonId,
                if (downloadCategory == Category.DUB) "sub" else "dub",
                episode.displayNumber,
            )
            downloadBadges[otherId]
                ?.takeIf { it.stage == EpisodeDownloadStage.SAVED }
                ?.let { EpisodeDownloadTarget(episode, otherId, it) }
                ?: EpisodeDownloadTarget(episode, preferredId, null)
        }
    }

    val startEpisodeDownload: (EpisodeItem) -> Unit = { episode ->
        val id = downloadIdFor(episode)
        awaitingCatalogIds = awaitingCatalogIds + id
        downloadScope.launch {
            val catalog = runCatching { loadProviderCatalog(seasonId) }
                .onFailure { error ->
                    DiagnosticsLog.throwable("Detail download catalog failed id=$seasonId", error)
                }
                .getOrNull()
            if (catalog == null || catalog.isEmpty) {
                awaitingCatalogIds = awaitingCatalogIds - id
                Toast.makeText(context, "No source lists this episode yet.", Toast.LENGTH_LONG).show()
                return@launch
            }
            BulkEpisodeDownloads.enqueueEpisode(
                context = context,
                anilistId = seasonId,
                seriesTitle = seasonMedia.title.preferred,
                artworkUrl = seasonMedia.coverImage.best,
                category = downloadCategory,
                preferredProvider = preferredProvider,
                variant = effectiveDownloadVariant(
                    choice = null,
                    options = ProviderCatalog.sourceVariants(preferredProvider, downloadCategory),
                    category = downloadCategory,
                    hardSubsOnly = hardSubsOnly,
                ),
                episode = episode,
                catalog = catalog,
                quality = downloadQuality,
                hardSubsOnly = hardSubsOnly,
            ) { outcome ->
                // Silence is the success case: the row's own control becomes the progress ring,
                // which says more than a toast would.
                val message = when (outcome) {
                    EpisodeQueueOutcome.QUEUED, EpisodeQueueOutcome.DROPPED -> null
                    EpisodeQueueOutcome.NO_SOURCE -> if (
                        HardSubDownloads.appliesTo(hardSubsOnly, downloadCategory)
                    ) {
                        "No hard-sub server offered a downloadable copy of episode " +
                            "${episode.displayNumber}. Turn off \"Hard subs only\" in the " +
                            "download dialog to allow soft subs."
                    } else {
                        "No server offered a downloadable copy of episode ${episode.displayNumber}."
                    }
                    EpisodeQueueOutcome.OUT_OF_SPACE ->
                        "Not enough free space to download episode ${episode.displayNumber}."
                }
                message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
            // Handed over: the queue's own pending set covers the episode from here.
            awaitingCatalogIds = awaitingCatalogIds - id
        }
        Unit
    }

    // Batch download of the rows on screen. "Already held" counts an exported MP4 too: with the
    // cache copy dropped after conversion, going by the download index alone would offer to
    // re-fetch a run the viewer already has.
    val heldEpisodeIds = remember(downloadIndex, exportedEpisodes) {
        offlineEpisodes(downloadIndex, exportedEpisodes)
            .filter(OfflineEpisode::isPlayable)
            .map(OfflineEpisode::id)
            .toSet()
    }
    var batchCategory by remember(seasonId) { mutableStateOf(downloadCategory) }
    var batchProvider by remember(seasonId) { mutableStateOf(preferredProvider) }
    val batchCandidates = remember(shown, seasonId, batchCategory, heldEpisodeIds) {
        BulkEpisodeDownloads.pendingEpisodes(
            episodes = shown,
            anilistId = seasonId,
            category = batchCategory.api,
            fromNumber = null,
            alreadyHave = heldEpisodeIds,
        )
    }
    // Null until the viewer says otherwise, so changing server or audio re-reads that server's
    // own first choice rather than carrying a shape it may not publish at all.
    var batchVariantChoice by remember(seasonId) { mutableStateOf<SourceVariant?>(null) }
    val batchVariants = remember(batchProvider, batchCategory) {
        ProviderCatalog.sourceVariants(batchProvider, batchCategory)
    }
    val batchVariant = effectiveDownloadVariant(batchVariantChoice, batchVariants, batchCategory, hardSubsOnly)
    var batchDialogVisible by remember(seasonId) { mutableStateOf(false) }
    var batchQuality by remember { mutableStateOf(downloadQuality) }
    var batchSelectedIds by remember(seasonId) { mutableStateOf(emptySet<String>()) }
    var batchSkipFillers by remember(seasonId) { mutableStateOf(false) }
    var batchPreparing by remember(seasonId) { mutableStateOf(false) }
    var batchMatchQuality by remember(seasonId) { mutableStateOf(false) }
    var batchQualityCheck by remember { mutableStateOf<BatchQualityCheck?>(null) }
    // The first episode the batch would fetch stands in for the rest; see [checkBatchQuality].
    val batchSampleEpisode = remember(batchCandidates, batchSelectedIds, batchSkipFillers) {
        selectedDownloadEpisodes(batchCandidates, batchSelectedIds, batchSkipFillers).firstOrNull()
    }
    LaunchedEffect(
        batchDialogVisible,
        seasonId,
        batchProvider,
        batchCategory,
        batchVariant,
        batchQuality,
        hardSubsOnly,
        batchSampleEpisode?.pipeId,
    ) {
        batchQualityCheck = null
        val sample = batchSampleEpisode
        if (!batchDialogVisible || batchQuality == DownloadQuality.BEST || sample == null) {
            return@LaunchedEffect
        }
        // Stepping through the resolution tabs would otherwise resolve a server per tab.
        delay(QUALITY_CHECK_DEBOUNCE_MS)
        val catalog = runCatching { loadProviderCatalog(seasonId) }.getOrNull()
            ?.takeUnless { it.isEmpty }
            ?: return@LaunchedEffect
        checkBatchQuality(
            context = context,
            anilistId = seasonId,
            episode = sample,
            catalog = catalog,
            category = batchCategory,
            variant = batchVariant,
            preferredProvider = batchProvider,
            quality = batchQuality,
            hardSubsOnly = hardSubsOnly,
        ).catch { error ->
            DiagnosticsLog.throwable("Download quality check failed id=$seasonId", error)
            emit(BatchQualityCheck.Unknown)
        }.collect { batchQualityCheck = it }
    }

    val deleteEpisodeDownload: (EpisodeDownloadTarget) -> Unit = { target ->
        EpisodeDownloads.remove(context, target.downloadId)
        EpisodeExport.deleteExported(context, target.downloadId)
    }

    val onEpisodeAction: (EpisodeDownloadTarget, EpisodeDownloadAction) -> Unit = { target, action ->
        when (action) {
            EpisodeDownloadAction.START, EpisodeDownloadAction.RETRY -> startEpisodeDownload(target.episode)
            EpisodeDownloadAction.CANCEL -> downloadToCancel = target
            EpisodeDownloadAction.REMOVE -> downloadToRemove = target
            EpisodeDownloadAction.WAIT -> Unit
        }
    }

    if (batchDialogVisible) {
        SeasonDownloadDialog(
            seriesTitle = seasonMedia.title.preferred,
            scopeLabel = scopeLabel,
            artworkUrl = seasonMedia.coverImage.best,
            episodes = batchCandidates,
            selectedEpisodeIds = batchSelectedIds,
            skipFillers = batchSkipFillers,
            alreadyHeldCount = shown.size - batchCandidates.size,
            category = batchCategory,
            onCategoryChange = { batchCategory = it },
            preferredProvider = batchProvider,
            onProviderChange = { batchProvider = it },
            variant = batchVariant,
            variantOptions = batchVariants,
            onVariantChange = { batchVariantChoice = it },
            hardSubsOnly = hardSubsOnly,
            onHardSubsOnlyChange = SettingsStore::setDownloadHardSubsOnly,
            quality = batchQuality,
            onQualityChange = { batchQuality = it },
            qualityCheck = batchQualityCheck,
            onUseProvider = { batchProvider = it },
            matchQuality = batchMatchQuality,
            onMatchQualityChange = { batchMatchQuality = it },
            onEpisodeSelected = { episode, checked ->
                batchSelectedIds = if (checked) {
                    batchSelectedIds + episode.pipeId
                } else {
                    batchSelectedIds - episode.pipeId
                }
            },
            onSelectAll = { checked ->
                batchSelectedIds = if (checked) {
                    batchCandidates
                        .filterNot { batchSkipFillers && it.filler }
                        .mapTo(linkedSetOf(), EpisodeItem::pipeId)
                } else {
                    emptySet()
                }
            },
            onSkipFillersChange = { batchSkipFillers = it },
            onDismiss = { batchDialogVisible = false },
            onConfirm = {
                val chosen = selectedDownloadEpisodes(
                    episodes = batchCandidates,
                    selectedEpisodeIds = batchSelectedIds,
                    skipFillers = batchSkipFillers,
                )
                val quality = batchQuality
                val matchQuality = batchMatchQuality
                val category = batchCategory
                val provider = batchProvider
                val variant = batchVariant
                val hardSubs = hardSubsOnly
                batchDialogVisible = false
                batchPreparing = true
                downloadScope.launch {
                    val catalog = runCatching { loadProviderCatalog(seasonId) }
                        .onFailure { error ->
                            DiagnosticsLog.throwable("Detail batch catalog failed id=$seasonId", error)
                        }
                        .getOrNull()
                    batchPreparing = false
                    if (catalog == null || catalog.isEmpty) {
                        Toast.makeText(
                            context,
                            "No source lists these episodes yet.",
                            Toast.LENGTH_LONG,
                        ).show()
                        return@launch
                    }
                    BulkEpisodeDownloads.start(
                        context = context,
                        anilistId = seasonId,
                        seriesTitle = seasonMedia.title.preferred,
                        artworkUrl = seasonMedia.coverImage.best,
                        category = category,
                        preferredProvider = provider,
                        variant = variant,
                        episodes = chosen,
                        catalog = catalog,
                        quality = quality,
                        matchQuality = matchQuality,
                        hardSubsOnly = hardSubs,
                    )
                }
            },
        )
    }

    downloadToRemove?.let { target ->
        MidnightConfirmDialog(
            kicker = "Remove download",
            title = "Remove episode ${target.episode.displayNumber}?",
            body = "${seasonMedia.title.preferred} · Episode ${target.episode.displayNumber} goes " +
                "from this device, along with its MP4 copy in Downloads when one exists. Your " +
                "watch progress is untouched.",
            confirmText = "Remove episode",
            onConfirm = {
                deleteEpisodeDownload(target)
                downloadToRemove = null
            },
            onDismiss = { downloadToRemove = null },
        )
    }
    downloadToCancel?.let { target ->
        MidnightConfirmDialog(
            kicker = "Stop download",
            title = "Stop downloading episode ${target.episode.displayNumber}?",
            body = "The part already fetched is discarded. Nothing else about the episode changes.",
            confirmText = "Stop download",
            onConfirm = {
                deleteEpisodeDownload(target)
                downloadToCancel = null
            },
            onDismiss = { downloadToCancel = null },
        )
    }

    return SeasonDownloads(
        targetFor = targetFor,
        isPending = { episode ->
            val id = downloadIdFor(episode)
            id in awaitingCatalogIds || id in resolvingDownloadIds
        },
        onEpisodeAction = onEpisodeAction,
        openBatch = {
            batchCategory = if (SettingsStore.preferDub.value) Category.DUB else Category.SUB
            batchProvider = preferredProvider
            batchQuality = downloadQuality
            // Default to the whole visible list where this is only ever asked for a run. Keep picks
            // for both audio choices. The candidate list skips copies already saved in the category
            // selected in the dialog.
            batchSelectedIds = if (preselectAll) {
                shown.mapTo(linkedSetOf(), EpisodeItem::pipeId)
            } else {
                emptySet()
            }
            batchDialogVisible = true
        },
        batchCandidateCount = batchCandidates.size,
        batchPreparing = batchPreparing,
    )
}

private const val QUALITY_CHECK_DEBOUNCE_MS = 600L
