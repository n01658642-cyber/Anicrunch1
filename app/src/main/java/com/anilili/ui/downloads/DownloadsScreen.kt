package com.anilili.ui.downloads

import com.anilili.ui.theme.appCanvas
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.resumeFor
import com.anilili.errors.AnililiError
import com.anilili.errors.asFailure
import com.anilili.playback.DownloadLocationFiles
import com.anilili.playback.DownloadStorage
import com.anilili.playback.EpisodeDownload
import com.anilili.playback.EpisodeDownloadState
import com.anilili.playback.EpisodeDownloads
import com.anilili.playback.EpisodeExport
import com.anilili.playback.EpisodeExportState
import com.anilili.playback.EpisodeExportStatus
import com.anilili.playback.OfflineEpisode
import com.anilili.playback.OfflineSeries
import com.anilili.playback.formatDownloadBytes
import com.anilili.playback.offlineEpisodeBlocks
import com.anilili.playback.offlineEpisodes
import com.anilili.playback.offlineSeries
import com.anilili.playback.transferSizeLabel
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.dpadClickable
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.components.AppChromeListBottomGap
import com.anilili.ui.components.CornerBadge
import com.anilili.ui.components.LocalAppChromeBottomInset
import com.anilili.ui.components.PageFilterOption
import com.anilili.ui.components.PageFilterPill
import com.anilili.ui.components.PageFilterRow
import com.anilili.ui.components.PageMenuChoice
import com.anilili.ui.components.PageMenuSectionLabel
import com.anilili.ui.components.PageSearch
import com.anilili.ui.components.PageSortControl
import com.anilili.ui.components.PageTitleBar
import com.anilili.ui.components.WatchProgressBar
import com.anilili.ui.components.episodeWatchFraction
import com.anilili.ui.components.formatPlaybackPosition
import com.anilili.ui.dialogs.MidnightConfirmDialog
import java.util.Locale

/**
 * Everything saved on this device, on a page of its own.
 *
 * Downloads used to be a section inside the library, sharing that page's poster grid — which put
 * episode cards two to a row on a phone, too narrow for their own action buttons. They are a
 * different kind of object from a watchlist entry (they have a size, a state, and they can be
 * deleted), so they get a list built for them: one row per anime, opening into that anime's
 * episodes. The list shape is what makes a 180-episode long-runner survivable.
 *
 * It is a top-level destination reached from the navigation bar, so the only thing there is ever a
 * back arrow for is the series a viewer has opened — leaving Downloads is the navigation bar's job.
 */
@Composable
fun DownloadsScreen(
    onPlayDownload: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    val downloadIndex by EpisodeDownloads.downloads(context).collectAsState()
    val exportedEpisodes by EpisodeExport.exported(context).collectAsState()
    val exportStatuses by EpisodeExport.statuses(context).collectAsState()
    val history by LibraryStore.history.collectAsState()
    val historyByAnimeId = remember(history) { history.associateBy(HistoryEntry::anilistId) }
    // Read once for the page rather than once per row: it is the same SharedPreferences flag for
    // every episode, and the selection bar has to agree with the rows about who can be exported.
    val exportSupported = EpisodeExport.isSupported && !EpisodeExport.transformerUnsupported(context)

    val episodes = remember(downloadIndex, exportedEpisodes) {
        offlineEpisodes(downloadIndex, exportedEpisodes)
    }
    val allSeries = remember(episodes) { offlineSeries(episodes) }
    var titleFilter by rememberSaveable { mutableStateOf("") }
    var selectedViewName by rememberSaveable { mutableStateOf(DownloadView.ALL.name) }
    var selectedAudio by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSortName by rememberSaveable { mutableStateOf(DownloadSort.RECENT.name) }
    val selectedView = DownloadView.entries.firstOrNull { it.name == selectedViewName } ?: DownloadView.ALL
    val selectedSort = DownloadSort.entries.firstOrNull { it.name == selectedSortName } ?: DownloadSort.RECENT
    val audioOptions = remember(episodes) { downloadAudioOptions(episodes) }

    // The summary line stays about everything saved, not about what survived the filter: it is the
    // answer to "how much of this device am I using", which a search box must not appear to change.
    val series = remember(allSeries, titleFilter, selectedView, selectedAudio, selectedSort, historyByAnimeId) {
        filterAndSortDownloads(
            all = allSeries,
            titleFilter = titleFilter,
            view = selectedView,
            audio = selectedAudio,
            sort = selectedSort,
            historyByAnimeId = historyByAnimeId,
        )
    }

    var openSeriesId by rememberSaveable { mutableStateOf<Int?>(null) }
    // Resolved against everything saved rather than the filtered list. The effect below closes an
    // open series the moment it cannot be found, and a search term that happened not to match the
    // series already on screen would otherwise read as the page throwing you out of it.
    val openSeries = allSeries.firstOrNull { it.anilistId == openSeriesId }
    var selectedCategory by remember(openSeriesId) { mutableStateOf<String?>(null) }
    var selectedBlock by remember(openSeriesId, selectedCategory) { mutableStateOf(0) }
    var episodePendingRemovalId by remember { mutableStateOf<String?>(null) }
    var seriesPendingRemoval by remember { mutableStateOf<OfflineSeries?>(null) }
    val episodePendingRemoval = episodes.firstOrNull { it.id == episodePendingRemovalId }

    // Selection is a mode the open series is in, so it dies with the series rather than surviving
    // into the next one. It is not saved across process death either: a set of ids restored into a
    // list whose episodes have since finished, been exported or been deleted would select ghosts.
    var selectedIds by remember(openSeriesId) { mutableStateOf(emptySet<String>()) }
    var bulkRemovalPending by remember(openSeriesId) { mutableStateOf(false) }
    // Resolved against the series rather than trusted: an episode deleted underneath the selection
    // leaves its id behind, and every count and action here has to be about episodes that exist.
    val selectedEpisodes = openSeries?.episodes.orEmpty().filter { it.id in selectedIds }
    val selecting = selectedEpisodes.isNotEmpty()

    // The open series can vanish underneath the page when its last episode is removed, and a
    // dialog can outlive the episode it asks about.
    LaunchedEffect(openSeriesId, openSeries) {
        if (openSeriesId != null && openSeries == null) openSeriesId = null
    }
    LaunchedEffect(episodePendingRemovalId, episodePendingRemoval) {
        if (episodePendingRemovalId != null && episodePendingRemoval == null) {
            episodePendingRemovalId = null
        }
    }

    // Back leaves the selection before it leaves the series — the two are never both armed, so
    // there is no relying on which order the dispatcher happens to consult them in.
    BackHandler(enabled = selecting) { selectedIds = emptySet() }
    BackHandler(enabled = openSeriesId != null && !selecting) { openSeriesId = null }

    if (episodePendingRemoval != null) {
        MidnightConfirmDialog(
            kicker = "Remove download",
            title = "Remove this episode?",
            body = "${episodePendingRemoval.metadata.seriesTitle} · Episode " +
                "${episodePendingRemoval.metadata.episodeNumber} goes from this device, along " +
                "with its MP4 copy in Downloads when one exists. Your watch progress is untouched.",
            confirmText = "Remove episode",
            onConfirm = {
                removeOfflineEpisode(context, episodePendingRemoval)
                episodePendingRemovalId = null
            },
            onDismiss = { episodePendingRemovalId = null },
        )
    }

    if (bulkRemovalPending && selecting) {
        MidnightConfirmDialog(
            kicker = "Remove downloads",
            title = if (selectedEpisodes.size == 1) {
                "Remove this episode?"
            } else {
                "Remove ${selectedEpisodes.size} episodes?"
            },
            body = "${episodeCountLabel(selectedEpisodes.size)} of " +
                "${openSeries?.title ?: "this anime"} go from this device, along with any MP4 " +
                "copies in the Downloads folder. Your watch progress is untouched.",
            confirmText = "Remove ${episodeCountLabel(selectedEpisodes.size)}",
            onConfirm = {
                selectedEpisodes.forEach { removeOfflineEpisode(context, it) }
                selectedIds = emptySet()
                bulkRemovalPending = false
            },
            onDismiss = { bulkRemovalPending = false },
        )
    }

    seriesPendingRemoval?.let { doomed ->
        MidnightConfirmDialog(
            kicker = "Remove downloads",
            title = "Remove all episodes?",
            body = "Every saved episode of ${doomed.title} goes from this device, along with any " +
                "MP4 copies in the Downloads folder. Your watch progress is untouched.",
            confirmText = "Remove all",
            onConfirm = {
                doomed.episodes.forEach { removeOfflineEpisode(context, it) }
                seriesPendingRemoval = null
                openSeriesId = null
            },
            onDismiss = { seriesPendingRemoval = null },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .appCanvas(),
        // No statusBarsPadding here: PageTitleBar applies its own, and this page has no Scaffold to
        // arbitrate between them, so both would be honoured and the title would sit a status bar
        // lower than every other page's.
    ) {
        PageTitleBar(
            // In selection mode the bar counts the selection instead of naming the series: the
            // count is the one thing that changes with every tap, and the series name is still
            // three lines further down on every card in the list.
            title = when {
                selecting -> "${selectedEpisodes.size} selected"
                else -> openSeries?.title ?: "Downloads"
            },
            subtitle = when {
                selecting -> selectionSummary(selectedEpisodes)
                else -> downloadsSummary(openSeries, allSeries)
            },
            leading = when {
                selecting -> {
                    {
                        val controlFocus1 = rememberFocusFill()
                        IconButton(
                            colors = controlFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                            onClick = { selectedIds = emptySet() },
                            modifier = Modifier.focusFill(controlFocus1, CircleShape),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Leave selection")
                        }
                    }
                }
                openSeriesId == null -> null
                else -> {
                    {
                        val controlFocus2 = rememberFocusFill()
                        IconButton(
                            colors = controlFocus2.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                            onClick = { openSeriesId = null },
                            modifier = Modifier.focusFill(controlFocus2, CircleShape),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            },
        )

        if (allSeries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing downloaded yet. Save an episode from its watch page to keep it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            return@Column
        }

        if (openSeries == null) {
            // Measured rather than taken from the device profile's column count: the cards have to
            // come out the size My List draws its cards, and that is a width, not a number of
            // columns. See [downloadPosterColumns].
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = downloadPosterColumns(
                    available = maxWidth - device.pagePadding * 2,
                    cardWidth = device.posterWidth,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize().focusGroup(),
                    contentPadding = PaddingValues(
                        bottom = LocalAppChromeBottomInset.current + AppChromeListBottomGap,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Column(
                            Modifier.padding(horizontal = device.pagePadding),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PageSearch(
                                value = titleFilter,
                                onValueChange = { titleFilter = it },
                                placeholder = "Search downloads",
                                clearLabel = "Clear search",
                                resultSummary = "${series.size} anime",
                            )
                            PageFilterRow {
                                PageFilterPill(
                                    label = selectedView.label,
                                    options = DownloadView.entries.map { PageFilterOption(it.name, it.label) },
                                    onSelect = { value -> value?.let { selectedViewName = it } },
                                    modifier = Modifier.weight(1f),
                                )
                                PageFilterPill(
                                    label = audioOptions.first { it.value == selectedAudio }.label,
                                    options = audioOptions,
                                    onSelect = { selectedAudio = it },
                                    modifier = Modifier.weight(1f),
                                )
                                PageSortControl(contentDescription = "Sort downloads, ${selectedSort.label}") { dismiss ->
                                    PageMenuSectionLabel("Sort by")
                                    DownloadSort.entries.forEach { sort ->
                                        PageMenuChoice(sort.label, sort == selectedSort) {
                                            dismiss(); selectedSortName = sort.name
                                        }
                                    }
                                }
                            }
                            Text(
                                "${series.size} anime",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (series.isEmpty()) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "No downloaded anime matches these filters.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        val rows = series.chunked(columns)
                        items(rows.size, key = { "dl_row_$it" }) { rowIndex ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = device.pagePadding),
                                horizontalArrangement = Arrangement.spacedBy(DownloadCardGap),
                            ) {
                                for (entry in rows[rowIndex]) {
                                    DownloadSeriesCard(
                                        series = entry,
                                        historyEntry = historyByAnimeId[entry.anilistId],
                                        onOpen = { openSeriesId = entry.anilistId },
                                        onRemoveSeries = { seriesPendingRemoval = entry },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(columns - rows[rowIndex].size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
            return@Column
        }

        val categories = openSeries.episodes.map { it.metadata.category }.distinct()
        val categoryEpisodes = openSeries.episodes.filter {
            selectedCategory == null || it.metadata.category == selectedCategory
        }
        // Long-runners get the same range chunking as the streaming episode browser.
        val blocks = offlineEpisodeBlocks(categoryEpisodes)
        val blockIndex = selectedBlock.coerceAtMost(blocks.lastIndex.coerceAtLeast(0))
        val visibleEpisodes = blocks.getOrNull(blockIndex)?.episodes.orEmpty()

        if (selecting) {
            val exportable = exportableOfflineEpisodes(selectedEpisodes, exportStatuses, exportSupported)
            DownloadSelectionBar(
                // Select all is about the episodes on screen, not the whole series: the category
                // and range chips are what the viewer just used to say which episodes they mean,
                // and a button that quietly reached past them would be a trap on a long-runner.
                allVisibleSelected = visibleEpisodes.isNotEmpty() &&
                    visibleEpisodes.all { it.id in selectedIds },
                exportableCount = exportable.size,
                onSelectAll = { selectedIds = selectedIds + visibleEpisodes.map(OfflineEpisode::id) },
                onClearVisible = { selectedIds = selectedIds - visibleEpisodes.map(OfflineEpisode::id).toSet() },
                onExport = {
                    exportable.forEach { EpisodeExport.request(context, it.id) }
                    selectedIds = emptySet()
                },
                onRemove = { bulkRemovalPending = true },
                modifier = Modifier.padding(horizontal = device.pagePadding, vertical = 4.dp),
            )
        }

        if (categories.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = device.pagePadding, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val controlFocus3 = rememberFocusFill()
                FilterChip(
                    colors = controlFocus3.chipColors(FilterChipDefaults.filterChipColors()),
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("All") },
                    modifier = Modifier.focusFill(controlFocus3, RoundedCornerShape(8.dp)),
                )
                categories.forEach { category ->
                    val controlFocus4 = rememberFocusFill()
                    FilterChip(
                        colors = controlFocus4.chipColors(FilterChipDefaults.filterChipColors()),
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category.uppercase()) },
                        modifier = Modifier.focusFill(controlFocus4, RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
        if (blocks.size > 1) {
            LazyRow(
                modifier = Modifier.focusGroup(),
                contentPadding = PaddingValues(horizontal = device.pagePadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(blocks.size) { index ->
                    val controlFocus5 = rememberFocusFill()
                    FilterChip(
                        colors = controlFocus5.chipColors(FilterChipDefaults.filterChipColors()),
                        selected = blockIndex == index,
                        onClick = { selectedBlock = index },
                        label = { Text(blocks[index].label) },
                        modifier = Modifier.focusFill(controlFocus5, RoundedCornerShape(8.dp)),
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(
                start = device.pagePadding,
                end = device.pagePadding,
                top = 8.dp,
                bottom = LocalAppChromeBottomInset.current + AppChromeListBottomGap,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visibleEpisodes, key = OfflineEpisode::id) { episode ->
                EpisodeRow(
                    episode = episode,
                    artworkUrl = openSeries.artworkUrl,
                    historyEntry = historyByAnimeId[openSeries.anilistId],
                    exportStatus = exportStatuses[episode.id],
                    selectionActive = selecting,
                    selected = episode.id in selectedIds,
                    canExport = isOfflineEpisodeExportable(
                        episode,
                        exportStatuses[episode.id],
                        exportSupported,
                    ),
                    onToggleSelected = {
                        selectedIds = if (episode.id in selectedIds) {
                            selectedIds - episode.id
                        } else {
                            selectedIds + episode.id
                        }
                    },
                    onPlay = { onPlayDownload(episode.id) },
                    onPauseToggle = {
                        episode.download?.let { download ->
                            if (download.isPaused) EpisodeDownloads.resume(context, episode.id)
                            else EpisodeDownloads.pause(context, episode.id)
                        }
                    },
                    onRetry = { EpisodeDownloads.retry(context, episode.id) },
                    onExport = { EpisodeExport.request(context, episode.id) },
                    onRemove = { episodePendingRemovalId = episode.id },
                )
            }
            // Hidden while selecting: the bar at the top is already offering a delete, and two
            // delete buttons on one screen that mean different amounts of deletion is a trap.
            if (!selecting) {
                item(key = "remove_series") {
                    val controlFocus6 = rememberFocusFill()
                    TextButton(
                        colors = controlFocus6.buttonColors(ButtonDefaults.textButtonColors()),
                        onClick = { seriesPendingRemoval = openSeries },
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .focusFill(controlFocus6, RoundedCornerShape(20.dp)),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text("Remove all episodes", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

/** Which saved anime the page is showing. */
private enum class DownloadView(val label: String) {
    ALL("All downloads"),
    UNWATCHED("Not finished"),
    WATCHED("Watched"),
    DOWNLOADING("Downloading"),
}

private enum class DownloadSort(val label: String) {
    RECENT("Recently added"),
    TITLE("Title (A–Z)"),
    LARGEST("Largest first"),
    EPISODES("Most episodes"),
}

/** The gutter between two saved-anime cards, shared by the grid and the count that fills it. */
private val DownloadCardGap = 12.dp

/**
 * How many saved-anime cards fit across [available] at the width My List draws a card.
 *
 * The page used to take `AppDeviceProfile.posterColumns`, which answers a different question: it is
 * the count My List uses for its *expanded* grid, where cards are deliberately packed tighter than
 * the rails the page opens on. Downloads has no compact mode to expand out of, so it inherited the
 * tight number as its only look and its posters came out a third narrower than the ones a viewer
 * had just been looking at one tab over.
 *
 * Asking for a width instead of a count also survives the interface-size setting for free. That
 * setting scales the density every `Dp` is resolved against, so at Large a card is physically wider
 * and [available] — measured in the same scaled units — is narrower; the division drops a column on
 * its own, where a constant count would have needed dividing by the scale by hand.
 */
internal fun downloadPosterColumns(
    available: Dp,
    cardWidth: Dp,
    gap: Dp = DownloadCardGap,
): Int = ((available + gap) / (cardWidth + gap)).toInt().coerceAtLeast(1)

/** How many episodes of [series] have been watched through, per the shared watch history. */
internal fun downloadWatchedCount(series: OfflineSeries, historyEntry: HistoryEntry?): Int =
    series.episodes.count { episode ->
        episode.metadata.episodeNumber.trim().toDoubleOrNull()
            ?.let { episodeWatchFraction(historyEntry, it) >= 0.999f } == true
    }

/**
 * The audio pill's options, built from what is actually saved rather than from a fixed list.
 *
 * Offering "Dub" to someone whose downloads are all subbed is a filter that can only ever empty the
 * page, so the pill carries the categories present and nothing else — and disappears into a single
 * "Any audio" choice when there is only one.
 */
private fun downloadAudioOptions(episodes: List<OfflineEpisode>): List<PageFilterOption> {
    val categories = episodes.map { it.metadata.category }.filter { it.isNotBlank() }.distinct().sorted()
    return listOf(PageFilterOption(null, "Any audio")) +
        categories.map { PageFilterOption(it, it.uppercase(Locale.US)) }
}

private fun filterAndSortDownloads(
    all: List<OfflineSeries>,
    titleFilter: String,
    view: DownloadView,
    audio: String?,
    sort: DownloadSort,
    historyByAnimeId: Map<Int, HistoryEntry>,
): List<OfflineSeries> {
    val needle = titleFilter.trim()
    val filtered = all.filter { series ->
        val matchesTitle = needle.isBlank() || series.title.contains(needle, ignoreCase = true)
        val matchesAudio = audio == null || series.episodes.any { it.metadata.category == audio }
        val watched = downloadWatchedCount(series, historyByAnimeId[series.anilistId])
        val matchesView = when (view) {
            DownloadView.ALL -> true
            DownloadView.WATCHED -> series.playableCount > 0 && watched >= series.playableCount
            DownloadView.UNWATCHED -> watched < series.playableCount
            DownloadView.DOWNLOADING -> series.activeCount > 0
        }
        matchesTitle && matchesAudio && matchesView
    }
    // offlineSeries already orders by most recent episode, so RECENT is the order it arrives in.
    return when (sort) {
        DownloadSort.RECENT -> filtered
        DownloadSort.TITLE -> filtered.sortedBy { it.title.lowercase(Locale.US) }
        DownloadSort.LARGEST -> filtered.sortedByDescending(OfflineSeries::totalBytes)
        DownloadSort.EPISODES -> filtered.sortedByDescending(OfflineSeries::playableCount)
    }
}

/**
 * One saved anime, drawn as My List draws a saved anime.
 *
 * This was a wide row with a 58dp thumbnail, which is a different object to the eye than the
 * posters every other list in the app is made of. The facts a download has and a watchlist entry
 * does not — how many episodes are here, how much of the device they take, how far through them you
 * are — ride as badges on the poster and one caption line, so the shape stays the shared one.
 *
 * The delete disc is on the card rather than behind a long press, for the same reason the Continue
 * Watching cards grew theirs: a long press is a gesture nothing advertises.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadSeriesCard(
    series: OfflineSeries,
    historyEntry: HistoryEntry?,
    onOpen: () -> Unit,
    onRemoveSeries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val watchedCount = downloadWatchedCount(series, historyEntry)
    Column(
        modifier
            .focusHighlight(RoundedCornerShape(9.dp))
            .combinedClickable(
                onClickLabel = "Open ${series.title}",
                onLongClickLabel = "Remove every episode of ${series.title}",
                onClick = onOpen,
                onLongClick = onRemoveSeries,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = series.artworkUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            CornerBadge(
                text = if (series.playableCount == 1) "1 ep" else "${series.playableCount} eps",
                modifier = Modifier.align(Alignment.TopStart).padding(5.dp),
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = .6f))
                    .clickable(
                        role = Role.Button,
                        onClick = onRemoveSeries,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove every episode of ${series.title}",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (series.totalBytes > 0) {
                CornerBadge(
                    text = formatDownloadBytes(series.totalBytes),
                    modifier = Modifier.align(Alignment.BottomStart).padding(5.dp),
                )
            }
            if (series.activeCount > 0) {
                CornerBadge(
                    text = "↓ ${series.activeCount}",
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
                )
            }
        }
        Text(
            series.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
        Text(
            when {
                historyEntry != null -> "Last watched · EP ${historyEntry.episodeLabel}"
                watchedCount > 0 -> "$watchedCount watched"
                else -> "Not started"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One saved episode.
 *
 * The row used to be four icon buttons in a line — save, play, delete, and sometimes pause or retry
 * — with the card itself inert. That put the destructive action the same size and the same distance
 * from the thumb as the one people press every time, and it left the largest target on screen doing
 * nothing at all. Now the card is the play button, the two rare actions live behind the overflow
 * menu, and only the controls that belong to a download in flight (pause, retry) stay on the row,
 * because those are about something happening right now rather than about the episode.
 *
 * The artwork is the series poster rather than a still of this episode: nothing in an offline
 * episode's metadata knows what this episode looks like — the stored artwork is the season cover
 * the download was started from — and a wide crop of it is still what tells one
 * anime's rows apart from another's at a glance in the player's recents.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    episode: OfflineEpisode,
    artworkUrl: String?,
    historyEntry: HistoryEntry?,
    exportStatus: EpisodeExportStatus?,
    selectionActive: Boolean,
    selected: Boolean,
    canExport: Boolean,
    onToggleSelected: () -> Unit,
    onPlay: () -> Unit,
    onPauseToggle: () -> Unit,
    onRetry: () -> Unit,
    onExport: () -> Unit,
    onRemove: () -> Unit,
) {
    val download = episode.download
    val metadata = episode.metadata
    val context = LocalContext.current
    // The same watched rule every other episode picker uses, so an episode watched offline reads
    // as watched here, on the detail screen and in the library without any of them disagreeing.
    val episodeNumber = metadata.episodeNumber.trim().toDoubleOrNull()
    val watchedFraction = episodeNumber?.let { episodeWatchFraction(historyEntry, it) } ?: 0f
    val isWatched = watchedFraction >= 0.999f
    val resumeMs = episodeNumber
        ?.let { historyEntry?.resumeFor(it) }
        ?.positionMs
        ?.takeIf { it > 0L }
    val playbackLabel = when {
        isWatched -> "Watched"
        resumeMs != null -> "Resume at ${formatPlaybackPosition(resumeMs)}"
        else -> null
    }
    // Media3 reports every failure as FAILURE_REASON_UNKNOWN, so a download that died on a full
    // disk cannot be told apart by its reason. What can be checked is whether the device is out of
    // room now — asked only of a download that actually stopped, so a healthy list never stats the
    // filesystem, and never claimed as the cause unless the space really is gone.
    val storageIsFull = remember(download?.state, download?.stalled) {
        val halted = download?.state == EpisodeDownloadState.FAILED ||
            download?.state == EpisodeDownloadState.STOPPED
        halted && DownloadStorage.freeBytes(context) in 0 until DownloadStorage.HEADROOM_BYTES
    }
    var menuOpen by remember { mutableStateOf(false) }
    val activate: () -> Unit = {
        when {
            selectionActive -> onToggleSelected()
            episode.isPlayable -> onPlay()
            // Nothing to play yet. The row deliberately does not fall through to the menu: a card
            // that opens a menu on one episode and starts playback on the next one down is a card
            // you have to read before you can press.
            else -> Unit
        }
    }
    val clickLabel = when {
        selectionActive && selected -> "Deselect episode ${metadata.episodeNumber}"
        selectionActive -> "Select episode ${metadata.episodeNumber}"
        !episode.isPlayable -> null
        isWatched -> "Replay offline"
        resumeMs != null -> "Resume offline"
        else -> "Play offline"
    }
    val shape = RoundedCornerShape(12.dp)
    // Only true when the card itself holds focus, never when one of its buttons does — see the
    // modifier chain below for why the distinction decides whether a remote can press them at all.
    var cardFocused by remember { mutableStateOf(false) }
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusHighlight(shape)
            .onFocusChanged { cardFocused = it.isFocused }
            // A remote has no long press of its own; this is the same gesture with a stopwatch.
            //
            // Gated on the card's own focus because the handler runs in the preview pass, which
            // starts at the root of the focused path: left ungated it would swallow every OK press
            // aimed at the overflow or pause button inside the card, and those buttons are the only
            // way a remote reaches those actions at all.
            .dpadClickable(
                onClick = activate,
                onLongClick = onToggleSelected,
                enabled = cardFocused,
            )
            .clip(shape)
            .combinedClickable(
                onClickLabel = clickLabel,
                onLongClickLabel = "Select episodes",
                onClick = activate,
                onLongClick = onToggleSelected,
            ),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EpisodeRowArtwork(
                artworkUrl = artworkUrl,
                playable = episode.isPlayable,
                selectionActive = selectionActive,
                selected = selected,
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isWatched) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Watched",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(end = 0.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        "Episode ${metadata.episodeNumber} · ${metadata.category.uppercase()}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isWatched) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            Color.Unspecified
                        },
                    )
                }
                metadata.episodeTitle?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (episode.isDownloading) {
                    LinearProgressIndicator(
                        progress = { (download?.percent ?: 0f) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Only for an episode left partway: a finished one already says so in words, and a
                // full bar under every watched episode is the noise the streaming pickers avoid.
                if (!isWatched) {
                    WatchProgressBar(watchedFraction, Modifier.fillMaxWidth())
                }
                Text(
                    listOfNotNull(
                        playbackLabel,
                        // An episode that only exists as an MP4 has no Media3 download state left
                        // to report, so say where it lives instead.
                        download?.stateLabel(storageIsFull) ?: DownloadLocationFiles.describeLocation(
                            context,
                            episode.exported?.uri?.let(Uri::parse),
                        ),
                        download?.offlineSubtitleStatusLabel,
                        // A transfer still running names its total when the source published one,
                        // so the row says how much of the episode is left rather than only how
                        // much has arrived. Everything else — finished, exported, MP4-only — is
                        // already its own final size.
                        download?.takeIf { episode.isDownloading }?.transferSizeLabel()
                            ?: episode.sizeBytes.takeIf { it > 0 }?.let(::formatDownloadBytes),
                        // Once the library copy is gone, the location above already says where the
                        // MP4 went; saying it a second time only doubles the row.
                        exportStatus?.takeUnless {
                            it.state == EpisodeExportState.COMPLETED && download == null
                        }?.let { status ->
                            exportLabel(status) {
                                DownloadLocationFiles.describeSaved(
                                    context,
                                    episode.exported?.uri?.let(Uri::parse),
                                )
                            }
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (
                        download?.state == EpisodeDownloadState.FAILED ||
                        download?.hasMissingOfflineSubtitles == true ||
                        exportStatus?.state == EpisodeExportState.FAILED
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            // Selection mode takes the row's own controls away: every one of them acts on a single
            // episode, which is the opposite of what the viewer has just said they are doing.
            if (selectionActive) return@Row
            // A download in flight keeps its control on the row. It belongs to something happening
            // now, and burying "this has stalled, press retry" two taps deep would hide the one
            // thing that gets the episode moving again.
            // A download the app gave up on offered nothing but Delete, which throws away
            // everything already fetched. Retry keeps the bytes and re-queues the same request.
            if (download?.canRetry == true) {
                val controlFocus9 = rememberFocusFill()
                IconButton(
                    colors = controlFocus9.iconButtonColors(IconButtonDefaults.iconButtonColors()),onClick = onRetry, modifier = Modifier.focusFill(controlFocus9, CircleShape)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Retry this download")
                }
            } else {
                download?.takeIf { it.canPause || it.canResume }?.let { controlled ->
                    val controlFocus10 = rememberFocusFill()
                    IconButton(
                        colors = controlFocus10.iconButtonColors(IconButtonDefaults.iconButtonColors()),onClick = onPauseToggle, modifier = Modifier.focusFill(controlFocus10, CircleShape)) {
                        Icon(
                            if (controlled.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (controlled.isPaused) "Resume download" else "Pause download",
                        )
                    }
                }
            }
            Box {
                val controlFocus11 = rememberFocusFill()
                IconButton(
                    colors = controlFocus11.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                    onClick = { menuOpen = true },
                    modifier = Modifier.focusFill(controlFocus11, CircleShape),
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More for episode ${metadata.episodeNumber}",
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (canExport) {
                        DropdownMenuItem(
                            text = { Text("Save as MP4") },
                            leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                            onClick = { menuOpen = false; onExport() },
                        )
                    }
                    // The way in for anyone who never discovers the long press — a remote, a mouse,
                    // or simply someone who has no reason to try holding a card down.
                    DropdownMenuItem(
                        text = { Text("Select episodes") },
                        leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null) },
                        onClick = { menuOpen = false; onToggleSelected() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text("Remove download", color = MaterialTheme.colorScheme.error)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { menuOpen = false; onRemove() },
                    )
                }
            }
        }
    }
}

/**
 * The thumbnail at the head of an episode row, and everything drawn over it.
 *
 * It carries the row's state in pictures — a play glyph for something that can be watched now, a
 * download glyph for something still arriving, a tick for something selected — because the words
 * beside it are already carrying four other facts and this is the part read at arm's length.
 */
@Composable
private fun EpisodeRowArtwork(
    artworkUrl: String?,
    playable: Boolean,
    selectionActive: Boolean,
    selected: Boolean,
) {
    Box(
        Modifier
            .width(104.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = artworkUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        // One scrim under whichever glyph is showing, so a bright frame can never swallow it.
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = if (selectionActive) .72f else .55f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when {
                    selectionActive && selected -> Icons.Default.CheckCircle
                    selectionActive -> Icons.Default.RadioButtonUnchecked
                    playable -> Icons.Default.PlayArrow
                    else -> Icons.Default.Download
                },
                contentDescription = null,
                tint = if (selectionActive && selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The bar that appears once episodes are selected: what can be done to them, and nothing else.
 *
 * It scrolls sideways rather than wrapping or shrinking its labels. Three buttons fit a phone at
 * the default interface size and do not fit one at the largest, and a delete button that has
 * silently become "Rem…" is worse than one the viewer has to nudge into view.
 */
@Composable
private fun DownloadSelectionBar(
    allVisibleSelected: Boolean,
    exportableCount: Int,
    onSelectAll: () -> Unit,
    onClearVisible: () -> Unit,
    onExport: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val controlFocus12 = rememberFocusFill()
        TextButton(
            colors = controlFocus12.buttonColors(ButtonDefaults.textButtonColors()),
            onClick = if (allVisibleSelected) onClearVisible else onSelectAll,
            modifier = Modifier.focusFill(controlFocus12, RoundedCornerShape(20.dp)),
        ) {
            Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                if (allVisibleSelected) "Select none" else "Select all",
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        // Absent rather than disabled when there is nothing to export: with the whole series
        // already sitting in the Downloads folder, a greyed-out button is a question nobody asked.
        if (exportableCount > 0) {
            val controlFocus13 = rememberFocusFill()
            FilledTonalButton(
                colors = controlFocus13.buttonColors(ButtonDefaults.filledTonalButtonColors()),
                onClick = onExport,
                modifier = Modifier.focusFill(controlFocus13, RoundedCornerShape(20.dp)),
            ) {
                Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Save $exportableCount as MP4", modifier = Modifier.padding(start = 6.dp))
            }
        }
        val controlFocus14 = rememberFocusFill()
        Button(
            onClick = onRemove,
            colors = controlFocus14.buttonColors(
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ),
            modifier = Modifier.focusFill(controlFocus14, RoundedCornerShape(20.dp)),
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Remove", modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/**
 * Which of [episodes] a "save as MP4" action would actually do something to.
 *
 * An episode qualifies only if the app holds a complete copy to rewrap, there is no MP4 in the
 * Downloads folder already, and no export of it is queued or running. Shared by the row menu and
 * the selection bar so the bar cannot count episodes whose own menus refuse to offer the action.
 */
internal fun exportableOfflineEpisodes(
    episodes: List<OfflineEpisode>,
    exportStatuses: Map<String, EpisodeExportStatus>,
    exportSupported: Boolean,
): List<OfflineEpisode> = episodes.filter {
    isOfflineEpisodeExportable(it, exportStatuses[it.id], exportSupported)
}

/** The same rule for a single episode, which is what a row's own menu needs. */
internal fun isOfflineEpisodeExportable(
    episode: OfflineEpisode,
    exportStatus: EpisodeExportStatus?,
    exportSupported: Boolean,
): Boolean = exportSupported &&
    episode.download?.isComplete == true &&
    !episode.isInDownloadsFolder &&
    (exportStatus == null || exportStatus.state == EpisodeExportState.FAILED)

/** The count and weight of a selection, for the title bar that has replaced the series name. */
internal fun selectionSummary(selected: List<OfflineEpisode>): String {
    val bytes = selected.sumOf(OfflineEpisode::sizeBytes)
    return episodeCountLabel(selected.size) +
        if (bytes > 0) " · ${formatDownloadBytes(bytes)}" else ""
}

private fun downloadsSummary(openSeries: OfflineSeries?, all: List<OfflineSeries>): String = when {
    openSeries != null -> buildString {
        append(episodeCountLabel(openSeries.playableCount))
        if (openSeries.totalBytes > 0) append(" · ${formatDownloadBytes(openSeries.totalBytes)}")
    }
    all.isEmpty() -> ""
    else -> {
        val episodes = all.sumOf { it.playableCount }
        val bytes = all.sumOf { it.totalBytes }
        "${all.size} anime · ${episodeCountLabel(episodes)}" +
            if (bytes > 0) " · ${formatDownloadBytes(bytes)}" else ""
    }
}

private fun episodeCountLabel(count: Int): String =
    if (count == 1) "1 episode" else "$count episodes"

private fun exportLabel(
    status: com.anilili.playback.EpisodeExportStatus,
    savedLabel: () -> String,
): String = when (status.state) {
    EpisodeExportState.PENDING -> "Queued for Downloads"
    EpisodeExportState.RUNNING -> status.percent?.let { "Building MP4 · $it%" } ?: "Building MP4…"
    EpisodeExportState.COMPLETED -> savedLabel()
    // One sentence and a code, not the whole explanation. This row is where the old behaviour was
    // worst: a series with eleven failed exports printed eleven copies of the same paragraph.
    EpisodeExportState.FAILED -> status.failure?.rowLabel()
        ?: AnililiError.EXPORT_FAILED.asFailure("this device").rowLabel()
}

/**
 * Says who stopped the download, because "Paused" was a lie in the case people actually hit: the
 * stall watchdog pauses a download that has made no progress for fifteen minutes, and the row then
 * claimed the viewer had paused it themselves and sat there for good.
 */
internal fun EpisodeDownload.stateLabel(storageIsFull: Boolean = false): String = when (state) {
    EpisodeDownloadState.QUEUED -> "Queued"
    EpisodeDownloadState.DOWNLOADING -> "Downloading ${percent?.toInt() ?: 0}%"
    EpisodeDownloadState.STOPPED ->
        // A download the watchdog stopped on a full disk would otherwise read "Stalled · retry to
        // continue", and retrying is precisely what cannot work until space is freed.
        if (storageIsFull) AnililiError.DOWNLOAD_OUT_OF_STORAGE.asFailure().rowLabel()
        else if (stalled) AnililiError.DOWNLOAD_STALLED.asFailure().rowLabel()
        else "Paused"
    EpisodeDownloadState.RESTARTING -> "Restarting"
    EpisodeDownloadState.COMPLETED -> "Available offline"
    EpisodeDownloadState.FAILED ->
        if (storageIsFull) AnililiError.DOWNLOAD_OUT_OF_STORAGE.asFailure().rowLabel()
        else AnililiError.DOWNLOAD_FAILED.asFailure().rowLabel()
    EpisodeDownloadState.REMOVING -> "Removing"
}

/**
 * Removes an episode from the offline library completely, whichever copies of it exist — the
 * cached segments, the MP4 in Downloads, or both. Deleting only half of it would leave a row
 * behind that still looks downloaded.
 */
internal fun removeOfflineEpisode(context: Context, episode: OfflineEpisode) {
    if (episode.download != null) EpisodeDownloads.remove(context, episode.id)
    if (episode.exported != null) EpisodeExport.deleteExported(context, episode.id)
}
