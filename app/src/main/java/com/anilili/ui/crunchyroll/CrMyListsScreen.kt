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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

@Composable
fun CrMyListsScreen(
    onAnimeClick: (Int) -> Unit,
    onSearchClick: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val watchlist by LibraryStore.watchlist.collectAsState()
    val history by LibraryStore.history.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Watchlist", "Crunchylists", "History", "Downloads")

    Column(modifier.fillMaxSize().background(CrColors.Black)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
            selectedTabIndex = tab.coerceIn(0, 2),
            containerColor = CrColors.Black,
            contentColor = CrColors.Orange,
            edgePadding = 8.dp,
            indicator = { positions ->
                val idx = tab.coerceIn(0, positions.lastIndex.coerceAtLeast(0))
                if (positions.isNotEmpty()) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(positions[idx]),
                        color = CrColors.Orange,
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
                        if (i == 3) onOpenDownloads() else tab = i
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
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text("Manage", color = CrColors.Orange, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        when (tab) {
            2 -> CrHistoryGrid(history, onAnimeClick)
            1 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Custom lists coming soon", color = CrColors.TextSecondary)
            }
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
                        .clip(RoundedCornerShape(4.dp)),
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
private fun CrHistoryGrid(entries: List<HistoryEntry>, onAnimeClick: (Int) -> Unit) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No watch history yet", color = CrColors.TextSecondary)
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
                        .clip(RoundedCornerShape(4.dp)),
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
                Text(
                    entry.episodeLabel,
                    color = CrColors.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}
