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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.anilili.data.model.Media
import com.anilili.ui.UiState
import com.anilili.ui.home.HomeViewModel

@Composable
fun CrBrowseScreen(
    onAnimeClick: (Int) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    vm: HomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("All Anime", "Simulcasts", "Anime Genres", "Music")
    LaunchedEffect(Unit) { vm.load() }

    Column(modifier.fillMaxSize().background(CrColors.Black)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Browse",
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
            selectedTabIndex = tab,
            containerColor = CrColors.Black,
            contentColor = CrColors.Orange,
            edgePadding = 12.dp,
            indicator = { positions ->
                if (tab < positions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(positions[tab]),
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
                    onClick = { tab = i },
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
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Popular",
                color = CrColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Outlined.Sort, null, tint = CrColors.TextSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(12.dp))
            Icon(Icons.Outlined.FilterList, null, tint = CrColors.TextSecondary, modifier = Modifier.size(22.dp))
        }
        when (val s = state) {
            is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CrColors.Orange)
            }
            is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.failure.message, color = CrColors.TextSecondary)
            }
            is UiState.Success -> {
                val list = when (tab) {
                    1 -> s.data.newest
                    2 -> s.data.topRated
                    else -> s.data.popular
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list, key = { it.id }) { media ->
                        CrBrowseCard(media = media, onClick = { onAnimeClick(media.id) })
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CrBrowseCard(media: Media, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = media.coverImage.extraLarge ?: media.coverImage.best,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            media.title.english ?: media.title.romaji ?: "",
            color = CrColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Dub English | Sub English",
            color = CrColors.TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}
