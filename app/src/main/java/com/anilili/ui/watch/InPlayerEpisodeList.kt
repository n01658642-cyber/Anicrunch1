@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.anilili.ui.watch

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anilili.data.library.HistoryEntry
import com.anilili.data.model.EpisodeItem
import com.anilili.data.settings.EpisodeLayout
import com.anilili.data.settings.SettingsStore
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.components.EpisodeArtwork
import com.anilili.ui.components.EpisodeBlockPicker
import com.anilili.ui.components.EpisodeFillerBadge
import com.anilili.ui.components.EqualizerWaveIndicator
import com.anilili.ui.components.WatchProgressBar
import com.anilili.ui.components.blockIndexContaining
import com.anilili.ui.components.episodeArtworkFallback
import com.anilili.ui.components.episodeArtworkImage
import com.anilili.ui.components.episodeBlocks
import com.anilili.ui.components.episodeWatchFraction

@Composable
internal fun InPlayerEpisodeDrawer(
    episodes: List<EpisodeItem>,
    currentIndex: Int,
    artworkUrl: String?,
    historyEntry: HistoryEntry? = null,
    onSelectEpisode: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val blurEpisodeImages by SettingsStore.blurEpisodeImages.collectAsState()
    // Null until the viewer picks list or grid with the header's toggle: the automatic rule applies.
    val drawerLayout by SettingsStore.episodeDrawerLayout.collectAsState()
    // Vetted against the whole drawer, not the expanded season: a provider either ships episode
    // art or it does not, and blocks of the same show should not disagree about that.
    val rowArtwork = remember(episodes, artworkUrl) { episodeArtworkFallback(episodes, artworkUrl) }

    var chosenBlockIndex by remember(episodes) { mutableStateOf<Int?>(null) }
    val blocks = remember(episodes) { episodeBlocks(episodes) }
    val currentEpisode = episodes.getOrNull(currentIndex)
    val defaultBlockIndex = remember(blocks, currentEpisode) {
        blockIndexContaining(blocks, currentEpisode?.number).coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
    }
    val activeBlockIndex = chosenBlockIndex ?: defaultBlockIndex

    val indexByPipeId = remember(episodes) {
        episodes.withIndex().associate { (index, episode) -> episode.pipeId to index }
    }

    val listState = rememberLazyListState()
    val initialFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }
    var drawerHasFocus by remember { mutableStateOf(false) }

    // Once per opening (and per episode change), not per season toggle: expanding another season
    // from its header must leave the remote on that header rather than yank it back to the
    // current episode.
    LaunchedEffect(blocks, currentIndex) {
        val block = blocks.getOrNull(defaultBlockIndex)
        val shownEpisodes = block?.episodes ?: episodes
        val targetInShown = shownEpisodes.indexOfFirst { indexByPipeId[it.pipeId] == currentIndex }
        if (targetInShown >= 0) {
            listState.scrollToItem(
                episodeDrawerItemIndex(
                    positionInBlock = targetInShown,
                    blockIndex = defaultBlockIndex,
                    blockCount = blocks.size,
                    blockSize = shownEpisodes.size,
                    layout = drawerLayout,
                ),
            )
        }
        // The scroll lands on the next layout pass; the tile has to be composed before it can
        // take focus.
        withFrameNanos { }
        runCatching { initialFocusRequester.requestFocus() }
        withFrameNanos { }
        // Nothing in the drawer took the remote — the current episode is not in the list, or its
        // tile never got composed. Left alone, focus stays on the Episodes button *behind* the
        // drawer, where no press can reach the list. Close is always composed.
        if (!drawerHasFocus) runCatching { closeFocusRequester.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                // The width the other player panels open at, so all four pickers are one shape.
                .width(420.dp)
                .clickable(enabled = false) {}, // Scrim protection
            color = PlayerPanelSurface,
            shape = PlayerPanelShape,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    // Keep the remote inside the drawer; Back and choosing an episode leave it.
                    // Without this, Left or Right from a row walked out through the scrim onto the
                    // transport controls underneath — which the drawer covers, so the cursor was
                    // somewhere the viewer could not see and there was no way back into the list.
                    // Same trap the settings panel uses, for the same reason.
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .onFocusChanged { drawerHasFocus = it.hasFocus }
                    .focusGroup(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The same kicker-and-title header as the captions, settings and server panels.
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "NOW PLAYING",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp),
                            fontWeight = FontWeight.W800,
                            color = PlayerPanelMutedText,
                        )
                        Text(
                            text = "Episodes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.W800,
                            color = Color.White,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    // Offers the layout not on screen: the list icon over a grid, the grid icon over
                    // a list. Choosing one sticks for every series until it is switched back.
                    val shownBlockSize = blocks.getOrNull(activeBlockIndex)?.episodes?.size ?: episodes.size
                    val showingGrid = showsEpisodeGrid(shownBlockSize, drawerLayout)
                    val layoutFocus = rememberFocusFill()
                    IconButton(
                        colors = layoutFocus.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                        onClick = {
                            SettingsStore.setEpisodeDrawerLayout(
                                if (showingGrid) EpisodeLayout.LIST else EpisodeLayout.GRID,
                            )
                        },
                        modifier = Modifier.focusFill(layoutFocus, RoundedCornerShape(20.dp)),
                    ) {
                        Icon(
                            if (showingGrid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = if (showingGrid) "Show episodes as a list" else "Show episodes as a grid",
                            tint = layoutFocus.content(Color.White.copy(alpha = 0.85f)),
                        )
                    }
                    val controlFocus1 = rememberFocusFill()
                    IconButton(
                        colors = controlFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                        onClick = onDismiss,
                        modifier = Modifier
                            .focusRequester(closeFocusRequester)
                            .focusFill(controlFocus1, RoundedCornerShape(20.dp)),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close episodes",
                            tint = controlFocus1.content(Color.White.copy(alpha = 0.85f)),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (blocks.size <= 1) {
                            episodeEntries(
                                episodes = episodes,
                                keyPrefix = "flat",
                                artworkUrl = rowArtwork,
                                blurEpisodeImages = blurEpisodeImages,
                                indexByPipeId = indexByPipeId,
                                currentIndex = currentIndex,
                                historyEntry = historyEntry,
                                initialFocusRequester = initialFocusRequester,
                                onSelectEpisode = onSelectEpisode,
                                onDismiss = onDismiss,
                                layout = drawerLayout,
                            )
                        } else {
                            blocks.forEachIndexed { blockIdx, block ->
                                val isExpanded = (blockIdx == activeBlockIndex)
                                item(key = "season_header_$blockIdx") {
                                    SeasonHeaderRow(
                                        seasonNumber = blockIdx + 1,
                                        rangeLabel = block.label,
                                        episodeCount = block.episodes.size,
                                        isExpanded = isExpanded,
                                        onClick = {
                                            chosenBlockIndex = if (isExpanded) -1 else blockIdx
                                        },
                                    )
                                }

                                if (isExpanded) {
                                    episodeEntries(
                                        episodes = block.episodes,
                                        keyPrefix = "s$blockIdx",
                                        artworkUrl = rowArtwork,
                                        blurEpisodeImages = blurEpisodeImages,
                                        indexByPipeId = indexByPipeId,
                                        currentIndex = currentIndex,
                                        historyEntry = historyEntry,
                                        initialFocusRequester = initialFocusRequester,
                                        onSelectEpisode = onSelectEpisode,
                                        onDismiss = onDismiss,
                                        layout = drawerLayout,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Past this many episodes a season stops being a list of artwork rows and becomes a grid of
 * numbers.
 *
 * A 100-episode season is ~100 screens of scrolling when every entry carries a thumbnail and a
 * title, and none of that helps someone who knows they want episode 74. The numbers-only grid puts
 * a whole season within a couple of flicks. Shorter seasons keep the rows, where the artwork and
 * titles are genuinely the fastest way to recognise an episode.
 */
private const val EPISODE_GRID_THRESHOLD = 25
private const val EPISODE_GRID_COLUMNS = 5

internal fun showsEpisodeGrid(episodeCount: Int, layout: EpisodeLayout? = null): Boolean = when (layout) {
    EpisodeLayout.GRID -> true
    EpisodeLayout.LIST -> false
    null -> episodeCount > EPISODE_GRID_THRESHOLD
}

/**
 * The drawer's LazyColumn item holding an episode, which is not the episode's position: with more
 * than one season every season's header is an item (the headers up to and including its own come
 * first, and only one season is expanded), and a grid season packs [EPISODE_GRID_COLUMNS] episodes
 * into each item. Scrolling to the raw position sent episode 150 of Naruto past the end of the
 * list, so its tile was never composed and could not take focus.
 */
internal fun episodeDrawerItemIndex(
    positionInBlock: Int,
    blockIndex: Int,
    blockCount: Int,
    blockSize: Int,
    layout: EpisodeLayout? = null,
): Int {
    val headers = if (blockCount <= 1) 0 else blockIndex + 1
    val row = if (showsEpisodeGrid(blockSize, layout)) positionInBlock / EPISODE_GRID_COLUMNS else positionInBlock
    return headers + row
}

/** Episodes laid out left-to-right; the last row is short rather than padded. */
internal fun <T> episodeGridRows(episodes: List<T>, columns: Int = EPISODE_GRID_COLUMNS): List<List<T>> =
    if (columns <= 0) listOf(episodes) else episodes.chunked(columns)

/** Emits one season's episodes as artwork rows, or as a number grid once it gets long. */
private fun LazyListScope.episodeEntries(
    episodes: List<EpisodeItem>,
    keyPrefix: String,
    artworkUrl: String?,
    blurEpisodeImages: Boolean,
    indexByPipeId: Map<String, Int>,
    currentIndex: Int,
    historyEntry: HistoryEntry?,
    initialFocusRequester: FocusRequester,
    onSelectEpisode: (Int) -> Unit,
    onDismiss: () -> Unit,
    layout: EpisodeLayout?,
) {
    val select: (EpisodeItem) -> Unit = { episode ->
        val globalIndex = indexByPipeId[episode.pipeId] ?: -1
        if (globalIndex >= 0) {
            onSelectEpisode(globalIndex)
            onDismiss()
        }
    }
    if (showsEpisodeGrid(episodes.size, layout)) {
        val rows = episodeGridRows(episodes)
        items(rows, key = { row -> "${keyPrefix}_grid_${row.first().pipeId}" }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { episode ->
                    val isCurrent = indexByPipeId[episode.pipeId] == currentIndex
                    val watchedFraction = episodeWatchFraction(historyEntry, episode.number)
                    InPlayerEpisodeNumberTile(
                        episode = episode,
                        isCurrent = isCurrent,
                        watchedFraction = watchedFraction,
                        focusRequester = if (isCurrent) initialFocusRequester else remember { FocusRequester() },
                        onClick = { select(episode) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep the final short row's tiles the same width as every other row's.
                repeat(EPISODE_GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    } else {
        items(episodes, key = { ep -> "${keyPrefix}_ep_${ep.pipeId}" }) { episode ->
            val isCurrent = indexByPipeId[episode.pipeId] == currentIndex
            val watchedFraction = episodeWatchFraction(historyEntry, episode.number)
            InPlayerEpisodeItemRow(
                episode = episode,
                artworkUrl = artworkUrl,
                blurred = blurEpisodeImages,
                isCurrent = isCurrent,
                watchedFraction = watchedFraction,
                focusRequester = if (isCurrent) initialFocusRequester else remember { FocusRequester() },
                onClick = { select(episode) },
            )
        }
    }
}

@Composable
private fun InPlayerEpisodeNumberTile(
    episode: EpisodeItem,
    isCurrent: Boolean,
    watchedFraction: Float,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fillFocus1 = rememberFocusFill()
    Box(
        modifier = modifier
            .aspectRatio(1.35f)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                val activate = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                if (!activate) {
                    false
                } else {
                    if (event.type == KeyEventType.KeyUp) onClick()
                    true
                }
            }
            .focusFill(fillFocus1, RoundedCornerShape(10.dp), focusedScale = 1.06f)
            .clip(RoundedCornerShape(10.dp))
            .background(fillFocus1.background(if (isCurrent) Color(0xFF2B2B31) else Color(0xFF17171A)))
            .border(
                1.5.dp,
                if (isCurrent) Color.White.copy(alpha = 0.55f) else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "Episode ${episode.displayNumber}" +
                    if (episode.filler) ", filler" else ""
                if (watchedFraction >= 0.999f) {
                    stateDescription = "Watched"
                } else if (watchedFraction > 0.01f) {
                    stateDescription = "${(watchedFraction * 100).toInt()} percent watched"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = episode.displayNumber,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = fillFocus1.content(if (isCurrent) Color.White else Color.White),
        )
        if (episode.filler) {
            EpisodeFillerBadge(
                compact = true,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
            )
        }
        if (watchedFraction >= 0.999f) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = fillFocus1.content(MaterialTheme.colorScheme.primary),
                modifier = Modifier.align(Alignment.TopStart).padding(5.dp).size(16.dp),
            )
        }
        WatchProgressBar(
            fraction = watchedFraction,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        )
    }
}

@Composable
private fun SeasonHeaderRow(
    seasonNumber: Int,
    rangeLabel: String,
    episodeCount: Int,
    isExpanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fillFocus2 = rememberFocusFill()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .focusFill(fillFocus2, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        color = fillFocus2.background(if (isExpanded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.07f)),
        shape = RoundedCornerShape(10.dp), contentColor = fillFocus2.content(LocalContentColor.current),) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Season $seasonNumber ($rangeLabel)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = fillFocus2.content(Color.White),
                )
                Text(
                    text = "$episodeCount episodes",
                    style = MaterialTheme.typography.bodySmall,
                    color = fillFocus2.content(Color.White.copy(alpha = 0.65f)),
                )
            }
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse season" else "Expand season",
                tint = fillFocus2.content(Color.White.copy(alpha = 0.85f)),
            )
        }
    }
}

@Composable
private fun InPlayerEpisodeItemRow(
    episode: EpisodeItem,
    artworkUrl: String?,
    blurred: Boolean,
    isCurrent: Boolean,
    watchedFraction: Float,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (isCurrent) {
        Color(0xFF2B2B31)
    } else {
        Color(0xFF17171A)
    }
    val borderColor = if (isCurrent) Color.White.copy(alpha = 0.55f) else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                val activate = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                if (!activate) {
                    false
                } else {
                    if (event.type == KeyEventType.KeyUp) onClick()
                    true
                }
            }
            .focusHighlight(RoundedCornerShape(10.dp), focusedScale = 1.03f)
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics {
                if (watchedFraction >= 0.999f) {
                    stateDescription = "Watched"
                } else if (watchedFraction > 0.01f) {
                    stateDescription = "${(watchedFraction * 100).toInt()} percent watched"
                }
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = episode.displayNumber,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.7f),
            modifier = Modifier.width(28.dp),
        )

        Box(
            modifier = Modifier
                .width(80.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            EpisodeArtwork(
                image = episodeArtworkImage(episode.image, artworkUrl),
                blurred = blurred,
                contentScale = ContentScale.Crop,
                compact = true,
                modifier = Modifier.fillMaxSize(),
            )
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    EqualizerWaveIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        barCount = 4,
                        barWidth = 3.5.dp,
                        maxHeight = 18.dp,
                    )
                }
            }
            WatchProgressBar(
                fraction = watchedFraction,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = episode.distinctTitle ?: "Episode ${episode.displayNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = if (isCurrent) Color.White else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isCurrent) {
                    Spacer(Modifier.width(6.dp))
                    EqualizerWaveIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        barCount = 3,
                        barWidth = 2.5.dp,
                        maxHeight = 12.dp,
                    )
                }
            }
            if (episode.filler) {
                EpisodeFillerBadge(compact = true)
            }
            if (watchedFraction >= 0.999f) {
                Text(
                    text = "Watched",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (watchedFraction > 0.01f) {
                Text(
                    text = "${(watchedFraction * 100).toInt()}% watched",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f),
                )
            }
        }
    }
}
