package com.anilili.ui.crunchyroll

import com.anilili.ui.theme.appCanvas

import androidx.compose.foundation.ExperimentalFoundationApi

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.statusBarsPadding
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.data.model.Media
import com.anilili.ui.UiState
import com.anilili.ui.home.HomeViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CrHomeScreen(
    onAnimeClick: (Int) -> Unit,
    onWatchNow: (Int) -> Unit,
    onSearchClick: () -> Unit,
    onResume: (HistoryEntry) -> Unit = {},
    modifier: Modifier = Modifier,
    vm: HomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val personal by vm.personal.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val continueWatching = remember(history) {
        history.filter { it.positionMs > 0 || it.episodeNumber > 0 }
            .sortedByDescending { it.updatedAt }
            .take(20)
    }
    var showSearch by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.load(); vm.loadPersonal() }

    Box(modifier = modifier.fillMaxSize().appCanvas()) {
        when (val s = state) {
            is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CrColors.accent)
            }
            is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.failure.message, color = CrColors.TextSecondary)
            }
            is UiState.Success -> {
                val heroes = s.data.spotlight.ifEmpty { s.data.popular }.take(8)
                val popular = s.data.popular
                val inspired = personal.forYou.ifEmpty { s.data.newest }
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        CrHeroCarousel(
                            items = heroes,
                            onWatch = onWatchNow,
                            onOpen = onAnimeClick,
                            onSearch = { showSearch = true },
                        )
                    }
                    if (continueWatching.isNotEmpty()) {
                        item { CrSectionHeader("Continue Watching") }
                        item {
                            CrContinueRow(
                                entries = continueWatching,
                                onResume = onResume,
                                onOpen = onAnimeClick,
                            )
                        }
                    }
                    item { CrSectionHeader("Most Popular") }
                    item {
                        CrPosterRow(items = popular, onClick = onAnimeClick)
                    }
                    if (inspired.isNotEmpty()) {
                        item { CrSectionHeader("Inspired by You") }
                        item {
                            CrPosterRow(items = inspired, onClick = onAnimeClick)
                        }
                    }
                    item { CrSectionHeader("Top Rated") }
                    item {
                        CrPosterRow(items = s.data.topRated, onClick = onAnimeClick)
                    }
                    item { CrSectionHeader("New This Season") }
                    item {
                        CrPosterRow(items = s.data.newest, onClick = onAnimeClick)
                    }
                    item {
                        LaunchedEffect(Unit) { vm.loadMore(com.anilili.ui.home.HomeSection.POPULAR) }
                        Spacer(Modifier.height(96.dp))
                    }
                }
            }
        }
        if (showSearch) {
            CrSearchOverlay(
                onDismiss = { showSearch = false },
                onAnimeClick = onAnimeClick,
            )
        }
    }
}

@Composable
private fun CrHeroCarousel(
    items: List<Media>,
    onWatch: (Int) -> Unit,
    onOpen: (Int) -> Unit,
    onSearch: () -> Unit,
) {
    if (items.isEmpty()) return
    val pager = rememberPagerState(pageCount = { items.size })
    LaunchedEffect(items.size) {
        while (true) {
            delay(5000)
            val next = (pager.currentPage + 1) % items.size
            pager.animateScrollToPage(next)
        }
    }
    Box(Modifier.fillMaxWidth().height(520.dp)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val media = items[page]
            Box(Modifier.fillMaxSize().clickable { onOpen(media.id) }) {
                // Crunchyroll-style: full key art (cover), not a washed-out wide banner crop.
                AsyncImage(
                    model = media.coverImage.extraLarge ?: media.coverImage.best ?: media.bannerImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.15f),
                            0.45f to Color.Black.copy(alpha = 0.35f),
                            1f to Color.Black,
                        ),
                    ),
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .fillMaxWidth(),
                ) {
                    val genres = media.genres.take(6).joinToString(", ")
                    if (genres.isNotEmpty()) {
                        Text(
                            genres,
                            color = CrColors.TextSecondary,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        media.title.english ?: media.title.romaji ?: "",
                        color = CrColors.TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    media.description?.let { raw ->
                        val plain = remember(raw) {
                            raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                        }
                        if (plain.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                plain,
                                color = CrColors.TextSecondary,
                                fontSize = 13.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { onWatch(media.id) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CrColors.accent,
                                contentColor = Color.Black,
                            ),
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.height(48.dp).weight(1f),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Start Watching E1", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(
                            onClick = { onOpen(media.id) },
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp),
                            contentPadding = PaddingValues(0.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = Brush.linearGradient(listOf(CrColors.accent, CrColors.accent)),
                            ),
                        ) {
                            Icon(Icons.Outlined.BookmarkBorder, null, tint = CrColors.accent)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        repeat(items.size.coerceAtMost(8)) { i ->
                            Box(
                                Modifier
                                    .padding(horizontal = 3.dp)
                                    .height(3.dp)
                                    .width(if (i == pager.currentPage) 18.dp else 10.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (i == pager.currentPage) CrColors.accent
                                        else Color.White.copy(alpha = 0.35f),
                                    ),
                            )
                        }
                    }
                }
            }
        }
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { }) {
                Icon(Icons.Outlined.Cast, null, tint = Color.White)
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Outlined.Search, null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun CrSectionHeader(title: String) {
    Text(
        title,
        color = CrColors.TextPrimary,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun CrPosterRow(items: List<Media>, onClick: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.id }) { media ->
            Column(
                Modifier
                    .width(120.dp)
                    .clickable { onClick(media.id) },
            ) {
                AsyncImage(
                    model = media.coverImage.extraLarge ?: media.coverImage.best,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    media.title.english ?: media.title.romaji ?: "",
                    color = CrColors.TextPrimary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
                val audio = buildString {
                    if (media.averageScore != null) append(String.format("★ %.1f", media.averageScore / 10.0))
                }
                if (audio.isNotEmpty()) {
                    Text(audio, color = CrColors.TextMuted, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}


@Composable
private fun CrContinueRow(
    entries: List<HistoryEntry>,
    onResume: (HistoryEntry) -> Unit,
    onOpen: (Int) -> Unit,
) {
    val accent = CrColors.accent
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(entries, key = { it.anilistId }) { entry ->
            val watched = entry.progressFraction >= 0.9f
            val ep = if (entry.episodeNumber % 1.0 == 0.0) {
                "EP ${entry.episodeNumber.toInt()}"
            } else {
                "EP ${entry.episodeNumber}"
            }
            Column(
                Modifier
                    .width(160.dp)
                    .clickable { onResume(entry) },
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
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(36.dp)
                            .background(Color.Black.copy(0.55f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.White.copy(0.25f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(if (watched) 1f else entry.progressFraction.coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(accent),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    entry.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(ep)
                        if (entry.progressFraction > 0f && !watched) {
                            append(" · ${(entry.progressFraction * 100).toInt()}%")
                        } else if (watched) {
                            append(" · Watched")
                        }
                    },
                    color = CrColors.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}
