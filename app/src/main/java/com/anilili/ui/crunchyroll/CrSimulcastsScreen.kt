package com.anilili.ui.crunchyroll

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.anilili.data.AppGraph
import com.anilili.data.model.DiscoverFilters
import com.anilili.data.model.Media
import java.util.Calendar

private val SEASONS = listOf("WINTER", "SPRING", "SUMMER", "FALL")
private val SEASON_LABEL = mapOf(
    "WINTER" to "Winter",
    "SPRING" to "Spring",
    "SUMMER" to "Summer",
    "FALL" to "Fall",
)

@Composable
fun CrSimulcastsScreen(
    onAnimeClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = remember { Calendar.getInstance() }
    val currentYear = now.get(Calendar.YEAR)
    val currentSeason = remember {
        when (now.get(Calendar.MONTH)) {
            in 0..1 -> "WINTER"
            in 2..4 -> "SPRING"
            in 5..7 -> "SUMMER"
            else -> "FALL"
        }
    }
    var year by remember { mutableIntStateOf(currentYear) }
    var season by remember { mutableStateOf(currentSeason) }
    var items by remember { mutableStateOf<List<Media>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val accent = CrColors.accent
    val years = remember { (currentYear downTo 2010).toList() }

    LaunchedEffect(year, season) {
        loading = true
        error = null
        runCatching {
            AppGraph.aniList.discover(
                filters = DiscoverFilters(
                    year = year,
                    season = season,
                    sort = "SCORE_DESC",
                    minimumScore = 70,
                    format = "TV",
                ),
                page = 1,
                perPage = 50,
                hideAdult = true,
            )
        }.onSuccess {
            items = it.items
            loading = false
        }.onFailure {
            error = it.message ?: "Failed to load"
            loading = false
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(CrColors.Black)
            .statusBarsPadding(),
    ) {
        Text(
            "Simulcasts",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Text(
            "Top-rated TV anime by season",
            color = CrColors.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            years.forEach { y ->
                FilterChip(
                    selected = year == y,
                    onClick = { year = y },
                    label = { Text("$y") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = accent,
                        selectedLabelColor = Color.Black,
                        containerColor = CrColors.Card,
                        labelColor = Color.White,
                    ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SEASONS.forEach { s ->
                FilterChip(
                    selected = season == s,
                    onClick = { season = s },
                    label = { Text(SEASON_LABEL[s] ?: s) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = accent,
                        selectedLabelColor = Color.Black,
                        containerColor = CrColors.Card,
                        labelColor = Color.White,
                    ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${SEASON_LABEL[season]} $year",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error!!, color = CrColors.TextSecondary)
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No titles for this season", color = CrColors.TextSecondary)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.id }) { media ->
                    Column(Modifier.clickable { onAnimeClick(media.id) }) {
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
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        media.averageScore?.let {
                            Text(
                                String.format("★ %.1f", it / 10.0),
                                color = accent,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}
