package com.anilili.ui.crunchyroll

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.WatchlistEntry
import com.anilili.ui.dialogs.MidnightConfirmDialog

@Composable
fun CrMyListsScreen(
    onAnimeClick: (Int) -> Unit,
    onSearchClick: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSchedule: () -> Unit = {},
    onResumeHistory: (HistoryEntry) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val watchlist by LibraryStore.watchlist.collectAsState()
    val history by LibraryStore.history.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Watchlist", "History", "Schedule", "Downloads")
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Int>()) }
    var confirmClear by remember { mutableStateOf(false) }
    val accent = CrColors.accent

    if (confirmClear) {
        MidnightConfirmDialog(
            kicker = "History",
            title = "Clear history?",
            body = "Remove all watch history from this device.",
            confirmText = "Clear",
            onConfirm = {
                LibraryStore.clearHistory()
                confirmClear = false
                selecting = false
                selectedIds = emptySet()
            },
            onDismiss = { confirmClear = false },
        )
    }

    Column(
        modifier
            .fillMaxSize()
            .background(CrColors.Black)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "My Lists",
                color = CrColors.TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { }) {
                Icon(Icons.Outlined.Cast, null, tint = Color.White)
            }
            IconButton(onClick = onSearchClick) {
                Icon(Icons.Outlined.Search, null, tint = Color.White)
            }
        }
        ScrollableTabRow(
            selectedTabIndex = tab.coerceIn(0, tabs.lastIndex),
            containerColor = CrColors.Black,
            contentColor = accent,
            edgePadding = 8.dp,
            indicator = { positions ->
                val idx = tab.coerceIn(0, positions.lastIndex.coerceAtLeast(0))
                if (positions.isNotEmpty()) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(positions[idx]),
                        color = accent,
                        height = 3.dp,
                    )
                }
            },
            divider = {},
        ) {
            tabs.forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = {
                        when (i) {
                            2 -> onOpenSchedule()
                            3 -> onOpenDownloads()
                            else -> {
                                tab = i
                                selecting = false
                                selectedIds = emptySet()
                            }
                        }
                    },
                    text = {
                        Text(
                            label,
                            fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal,
                            color = if (tab == i) CrColors.TextPrimary else CrColors.TabInactive,
                        )
                    },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tab == 1) {
                if (selecting) {
                    TextButton(onClick = {
                        selecting = false
                        selectedIds = emptySet()
                    }) {
                        Text("Cancel", color = accent)
                    }
                } else {
                    TextButton(onClick = { selecting = true }) {
                        Text("Manage", color = accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        when (tab) {
            1 -> CrHistoryGrid(
                entries = history,
                selecting = selecting,
                selectedIds = selectedIds,
                onToggleSelect = { id ->
                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                },
                onPlay = onResumeHistory,
                onOpen = onAnimeClick,
                onClearAll = { confirmClear = true },
                onRemoveSelected = {
                    selectedIds.forEach { LibraryStore.removeHistory(it) }
                    selectedIds = emptySet()
                    selecting = false
                },
            )
            else -> CrWatchlistGrid(watchlist, onAnimeClick)
        }
    }
}

@Composable
private fun CrWatchlistGrid(entries: List<WatchlistEntry>, onAnimeClick: (Int) -> Unit) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Your watchlist is empty", color = CrColors.TextSecondary)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { it.anilistId }) { entry ->
            Column(Modifier.clickable { onAnimeClick(entry.anilistId) }) {
                AsyncImage(
                    model = entry.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    entry.title,
                    color = CrColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun CrHistoryGrid(
    entries: List<HistoryEntry>,
    selecting: Boolean,
    selectedIds: Set<Int>,
    onToggleSelect: (Int) -> Unit,
    onPlay: (HistoryEntry) -> Unit,
    onOpen: (Int) -> Unit,
    onClearAll: () -> Unit,
    onRemoveSelected: () -> Unit,
) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No watch history yet", color = CrColors.TextSecondary)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(entries, key = { it.anilistId }) { entry ->
                CrHistoryCard(
                    entry = entry,
                    selecting = selecting,
                    selected = entry.anilistId in selectedIds,
                    onToggleSelect = { onToggleSelect(entry.anilistId) },
                    onPlay = { onPlay(entry) },
                    onOpen = { onOpen(entry.anilistId) },
                    onClearOne = { LibraryStore.removeHistory(entry.anilistId) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        if (selecting) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(CrColors.Card)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (selectedIds.isEmpty()) "Nothing is selected"
                    else "${selectedIds.size} selected",
                    color = CrColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onRemoveSelected,
                    enabled = selectedIds.isNotEmpty(),
                ) {
                    Text("Remove", color = if (selectedIds.isEmpty()) CrColors.TextMuted else CrColors.accent)
                }
            }
        }
    }
}

@Composable
private fun CrHistoryCard(
    entry: HistoryEntry,
    selecting: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onClearOne: () -> Unit,
) {
    val accent = CrColors.accent
    val watched = entry.progressFraction >= 0.9f ||
        entry.episodeNumber in entry.watchedEpisodes
    val audioLabel = when {
        entry.category.equals("dub", true) -> "Dub English"
        else -> "Subtitled English"
    }
    val epNum = if (entry.episodeNumber % 1.0 == 0.0) {
        "E${entry.episodeNumber.toInt()}"
    } else {
        "E${entry.episodeNumber}"
    }
    var menu by remember { mutableStateOf(false) }

    Column(
        Modifier.clickable {
            if (selecting) onToggleSelect() else onPlay()
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            AsyncImage(
                model = entry.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (selecting) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = accent,
                        uncheckedColor = Color.White,
                        checkmarkColor = Color.Black,
                    ),
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                )
            } else {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                        .background(Color.Black.copy(0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (watched) Icons.Default.Replay else Icons.Default.PlayArrow,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (watched) {
                Text(
                    "Watched",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(0.75f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            } else if (entry.durationMs > 0 && entry.positionMs > 0) {
                val minsLeft = ((entry.durationMs - entry.positionMs) / 60000L).coerceAtLeast(0)
                Text(
                    "${minsLeft}m",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(0.75f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // Progress bar
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(0.25f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(entry.progressFraction.coerceIn(0f, 1f).let { if (watched) 1f else it })
                        .height(3.dp)
                        .background(accent),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    color = CrColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.episodeTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = CrColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "$epNum · $audioLabel",
                    color = CrColors.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            if (!selecting) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.MoreVert, null, tint = CrColors.TextMuted, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Open series") },
                            onClick = { menu = false; onOpen() },
                        )
                        DropdownMenuItem(
                            text = { Text("Clear", color = Color(0xFFFF6B6B)) },
                            onClick = { menu = false; onClearOne() },
                        )
                    }
                }
            }
        }
    }
}
