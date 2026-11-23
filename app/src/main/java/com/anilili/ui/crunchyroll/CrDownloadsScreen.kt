package com.anilili.ui.crunchyroll

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.anilili.playback.EpisodeExport
import com.anilili.playback.EpisodeDownloads
import com.anilili.playback.OfflineEpisode
import com.anilili.playback.OfflineSeries
import com.anilili.playback.formatDownloadBytes
import com.anilili.playback.offlineEpisodes
import com.anilili.playback.offlineSeries
import com.anilili.ui.adaptive.LocalAppChromeBottomInset
import com.anilili.ui.dialogs.MidnightConfirmDialog

/**
 * Crunchyroll-shell Downloads: black canvas, poster grid, series detail with episode rows,
 * file sizes kept on every card.
 */
@Composable
fun CrDownloadsScreen(
    onPlayDownload: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val downloadIndex by EpisodeDownloads.downloads(context).collectAsState()
    val exported by EpisodeExport.exported(context).collectAsState()
    val episodes = remember(downloadIndex, exported) {
        offlineEpisodes(downloadIndex, exported)
    }
    val seriesList = remember(episodes) { offlineSeries(episodes) }
    var openSeriesId by remember { mutableStateOf<Int?>(null) }
    val openSeries = seriesList.firstOrNull { it.anilistId == openSeriesId }
    var pendingRemoveSeries by remember { mutableStateOf<OfflineSeries?>(null) }
    var pendingRemoveEpisode by remember { mutableStateOf<OfflineEpisode?>(null) }
    val accent = CrColors.accent

    BackHandler(enabled = openSeriesId != null) { openSeriesId = null }

    pendingRemoveSeries?.let { series ->
        MidnightConfirmDialog(
            kicker = "Downloads",
            title = "Remove all episodes?",
            body = "Delete offline copies of “${series.title}” from this device.",
            confirmText = "Remove",
            onConfirm = {
                series.episodes.forEach { ep ->
                    EpisodeDownloads.remove(context, ep.id)
                    EpisodeExport.deleteExported(context, ep.id)
                }
                pendingRemoveSeries = null
                if (openSeriesId == series.anilistId) openSeriesId = null
            },
            onDismiss = { pendingRemoveSeries = null },
        )
    }
    pendingRemoveEpisode?.let { ep ->
        MidnightConfirmDialog(
            kicker = "Downloads",
            title = "Remove episode?",
            body = "Delete offline copy of episode ${ep.metadata.episodeNumber}.",
            confirmText = "Remove",
            onConfirm = {
                EpisodeDownloads.remove(context, ep.id)
                EpisodeExport.deleteExported(context, ep.id)
                pendingRemoveEpisode = null
            },
            onDismiss = { pendingRemoveEpisode = null },
        )
    }

    Box(
        modifier
            .fillMaxSize()
            .background(CrColors.Black),
    ) {
        if (openSeries != null) {
            CrSeriesDownloads(
                series = openSeries,
                accent = accent,
                onBack = { openSeriesId = null },
                onPlay = onPlayDownload,
                onRemoveAll = { pendingRemoveSeries = openSeries },
                onRemoveEpisode = { pendingRemoveEpisode = it },
            )
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Text(
                    "Downloads",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                val totalEps = seriesList.sumOf { it.episodes.size }
                val totalBytes = seriesList.sumOf { it.totalBytes }
                Text(
                    buildString {
                        append("${seriesList.size} anime")
                        append(" · $totalEps episode${if (totalEps == 1) "" else "s"}")
                        if (totalBytes > 0) append(" · ${formatDownloadBytes(totalBytes)}")
                    },
                    color = CrColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(12.dp))
                if (seriesList.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No downloads yet", color = CrColors.TextMuted)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(140.dp),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = LocalAppChromeBottomInset.current + 24.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        gridItems(seriesList, key = { it.anilistId }) { series ->
                            CrDownloadSeriesTile(
                                series = series,
                                accent = accent,
                                onOpen = { openSeriesId = series.anilistId },
                                onRemove = { pendingRemoveSeries = series },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrDownloadSeriesTile(
    series: OfflineSeries,
    accent: Color,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.clickable(onClick = onOpen)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            AsyncImage(
                model = series.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(0.85f),
                        ),
                    ),
            )
            Text(
                "${series.playableCount} ep",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd).size(36.dp),
            ) {
                Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            if (series.totalBytes > 0) {
                Text(
                    formatDownloadBytes(series.totalBytes),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            series.title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CrSeriesDownloads(
    series: OfflineSeries,
    accent: Color,
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    onRemoveAll: () -> Unit,
    onRemoveEpisode: (OfflineEpisode) -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    series.title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        val n = series.episodes.size
                        append("$n episode${if (n == 1) "" else "s"}")
                        if (series.totalBytes > 0) append(" · ${formatDownloadBytes(series.totalBytes)}")
                    },
                    color = accent,
                    fontSize = 13.sp,
                )
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = LocalAppChromeBottomInset.current + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(series.episodes, key = { it.id }) { ep ->
                CrOfflineEpisodeRow(
                    episode = ep,
                    seriesArt = series.artworkUrl,
                    accent = accent,
                    onPlay = { if (ep.isPlayable) onPlay(ep.id) },
                    onRemove = { onRemoveEpisode(ep) },
                )
            }
            item {
                TextButton(onClick = onRemoveAll) {
                    Icon(Icons.Default.Delete, null, tint = accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Remove all episodes", color = accent)
                }
            }
        }
    }
}

@Composable
private fun CrOfflineEpisodeRow(
    episode: OfflineEpisode,
    seriesArt: String?,
    accent: Color,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    val meta = episode.metadata
    val thumb = meta.artworkUrl ?: meta.seriesArtworkUrl ?: seriesArt
    val cat = meta.category.uppercase()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CrColors.Card)
            .clickable(enabled = episode.isPlayable, onClick = onPlay)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(120.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            AsyncImage(
                model = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (episode.isPlayable) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(32.dp)
                        .background(Color.Black.copy(0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Episode ${meta.episodeNumber} · $cat",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            meta.episodeTitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    color = CrColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val status = when {
                episode.isPlayable -> "Available offline"
                episode.isDownloading -> "Downloading…"
                else -> "Not ready"
            }
            Text(
                buildString {
                    append(status)
                    if (episode.sizeBytes > 0) append(" · ${formatDownloadBytes(episode.sizeBytes)}")
                },
                color = if (episode.isPlayable) accent else CrColors.TextMuted,
                fontSize = 12.sp,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, null, tint = CrColors.TextMuted)
        }
    }
}
