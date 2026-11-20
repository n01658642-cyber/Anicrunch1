package com.anilili.ui.crunchyroll

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
    modifier: Modifier = Modifier,
    vm: HomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val personal by vm.personal.collectAsState()
    LaunchedEffect(Unit) { vm.load() }

    Box(modifier = modifier.fillMaxSize().background(CrColors.Black)) {
        when (val s = state) {
            is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CrColors.Orange)
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
                            onSearch = onSearchClick,
                        )
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
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
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
                AsyncImage(
                    model = media.bannerImage ?: media.coverImage.extraLarge ?: media.coverImage.best,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
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
                                containerColor = CrColors.Orange,
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
                                brush = Brush.linearGradient(listOf(CrColors.Orange, CrColors.Orange)),
                            ),
                        ) {
                            Icon(Icons.Outlined.BookmarkBorder, null, tint = CrColors.Orange)
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
                                        if (i == pager.currentPage) CrColors.Orange
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
                .padding(top = 12.dp, end = 8.dp),
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
                    if (media.averageScore != null) append("★ ${media.averageScore}%")
                }
                if (audio.isNotEmpty()) {
                    Text(audio, color = CrColors.TextMuted, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}
