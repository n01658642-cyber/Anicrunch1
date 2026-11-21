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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.anilili.data.model.EpisodeItem
import com.anilili.ui.UiState
import com.anilili.ui.detail.DetailViewModel

@Composable
fun CrDetailScreen(
    animeId: Int,
    onBack: () -> Unit,
    onPlay: (animeId: Int, provider: String, category: String, episode: String) -> Unit,
    onAnimeClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vm: DetailViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val accent = CrColors.accent
    LaunchedEffect(animeId) { vm.load(animeId) }

    Box(modifier.fillMaxSize().background(CrColors.Black)) {
        when (val s = state) {
            is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent)
            }
            is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.failure.message, color = CrColors.TextSecondary)
                    Spacer(Modifier.height(12.dp))
                    Text("Back", color = accent, modifier = Modifier.clickable(onClick = onBack))
                }
            }
            is UiState.Success -> {
                val info = s.data.info
                val episodes = s.data.episodes
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Box(Modifier.fillMaxWidth().height(280.dp)) {
                            AsyncImage(
                                model = info.coverImage.extraLarge ?: info.coverImage.best ?: info.bannerImage,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                alignment = Alignment.TopCenter,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(0.2f),
                                        0.55f to Color.Black.copy(0.5f),
                                        1f to Color.Black,
                                    ),
                                ),
                            )
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.statusBarsPadding().padding(8.dp),
                            ) {
                                Icon(Icons.Default.Close, null, tint = Color.White)
                            }
                        }
                    }
                    item {
                        Column(Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                info.title.english ?: info.title.romaji ?: "",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(8.dp))
                            val genres = info.genres.take(8).joinToString(", ")
                            if (genres.isNotEmpty()) {
                                Text(genres, color = CrColors.TextSecondary, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, null, tint = accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    info.averageScore?.let { "Average: ${it / 10.0}" } ?: "—",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                CrAction(Icons.Outlined.Add, "My List") {}
                                CrAction(Icons.Default.Share, "Share") {}
                                CrAction(Icons.Outlined.MenuBook, "Manga") {}
                            }
                            Spacer(Modifier.height(12.dp))
                            val plain = remember(info.description) {
                                info.description.orEmpty()
                                    .replace(Regex("<[^>]+>"), " ")
                                    .replace(Regex("\\s+"), " ")
                                    .trim()
                            }
                            if (plain.isNotEmpty()) {
                                Text(
                                    plain,
                                    color = CrColors.TextSecondary,
                                    fontSize = 14.sp,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text("More Details", color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("Episodes", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    items(episodes, key = { it.number }) { ep ->
                        CrEpisodeRow(
                            episode = ep,
                            coverFallback = info.coverImage.best,
                            accent = accent,
                            onClick = {
                                onPlay(
                                    animeId,
                                    "auto",
                                    "sub",
                                    ep.number.toInt().toString(),
                                )
                            },
                        )
                    }
                    item {
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = {
                                val first = episodes.firstOrNull()?.number?.toInt()?.toString() ?: "1"
                                onPlay(animeId, "auto", "sub", first)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .height(52.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start Watching E1", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Spacer(Modifier.height(48.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CrAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Icon(icon, null, tint = CrColors.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = CrColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun CrEpisodeRow(
    episode: EpisodeItem,
    coverFallback: String?,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(140.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            AsyncImage(
                model = episode.image ?: coverFallback,
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
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${episode.displayNumber}. ${episode.title ?: "Episode ${episode.displayNumber}"}",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            episode.runtime?.let {
                Text("${it}m", color = CrColors.TextMuted, fontSize = 12.sp)
            }
        }
    }
}
