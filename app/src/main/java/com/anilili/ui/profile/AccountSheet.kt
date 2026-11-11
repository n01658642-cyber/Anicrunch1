package com.anilili.ui.profile

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.anilili.data.library.mergedActivity
import com.anilili.data.library.activityStreak
import com.anilili.data.library.WatchActivityStore
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.anilili.R
import com.anilili.data.auth.AccountService
import com.anilili.data.library.HistoryEntry
import com.anilili.data.model.MediaListEntry
import com.anilili.data.model.Viewer
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.components.RatingBadge
import com.anilili.ui.theme.Midnight
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Where an account's titles sit, in a fixed order with a fixed colour each.
 *
 * The colours are the reference categorical palette's first five dark-mode slots, in its order,
 * validated against the near-black sheet (every adjacent pair clears the colour-vision checks) — so
 * a status keeps its colour however many of the others are empty. Rewatching folds into Watching:
 * a sixth sliver would be too thin to read and says nothing a viewer would act on.
 */
internal enum class ListStatusSlice(val label: String, val color: Color) {
    WATCHING("Watching", Color(0xFF3987E5)),
    COMPLETED("Completed", Color(0xFFD95926)),
    PLANNING("Planned", Color(0xFF199E70)),
    PAUSED("Paused", Color(0xFFC98500)),
    DROPPED("Dropped", Color(0xFFD55181)),
}

/** The single hue the genre bars are drawn in: one series, so no legend and no second colour. */
private val GenreBarColor = Color(0xFF3987E5)

/** Everything the account drawer shows, worked out once from the lists already loaded. */
internal data class AccountInsights(
    val titles: Int,
    val episodes: Int,
    val minutesWatched: Long,
    /** Null when nothing is scored, so the stat can say so rather than print a mean of 0.0. */
    val meanScore: Double?,
    val statusCounts: Map<ListStatusSlice, Int>,
    /** Most-watched genres, most first, at most [MAX_GENRES]. */
    val topGenres: List<Pair<String, Int>>,
    /** Every entry, most recently changed first: the drawer's activity feed. */
    val recent: List<MediaListEntry>,
    val formats: List<Pair<String, Int>> = emptyList(),
    val studios: List<Pair<String, Int>> = emptyList(),
    /** Titles per release year, or per decade once the list spans more than a dozen years. */
    val releaseYears: List<Pair<String, Int>> = emptyList(),
    /** How many titles the viewer gave each whole score, 1 to 10; empty when nothing is scored. */
    val scoreSpread: List<Pair<String, Int>> = emptyList(),
    /** Completed as a share of everything finished one way or the other; null before either. */
    val completionRate: Int? = null,
    /** Episodes left in what is being watched, where the series' length is known. */
    val episodesToGo: Int = 0,
)

private const val MAX_GENRES = 5

internal fun accountInsights(profile: AniListProfile): AccountInsights {
    val stats = profile.viewer.statistics?.anime
    val statusCounts = linkedMapOf(
        ListStatusSlice.WATCHING to profile.watching.size + profile.rewatching.size,
        ListStatusSlice.COMPLETED to profile.completed.size,
        ListStatusSlice.PLANNING to profile.planning.size,
        ListStatusSlice.PAUSED to profile.paused.size,
        ListStatusSlice.DROPPED to profile.dropped.size,
    )
    // Genres of what the viewer has actually started: a Planning list is a wish list, and letting it
    // vote would describe what they mean to watch rather than what they watch.
    val started = listOf(profile.watching, profile.rewatching, profile.completed, profile.paused, profile.dropped)
        .flatten()
    val genreSource: List<MediaListEntry> = started.ifEmpty { profile.planning }
    val topGenres = genreSource
        .mapNotNull { it.media }
        .distinctBy { it.id }
        .flatMap { it.genres }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(MAX_GENRES)
        .map { it.key to it.value }
    val allEntries = started + profile.planning
    val allMedia = allEntries.mapNotNull { it.media }.distinctBy { it.id }
    val startedMedia = started.mapNotNull { it.media }.distinctBy { it.id }
    fun ranked(values: List<String>, limit: Int) = values.groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { it.key to it.value }
    val years = allMedia.mapNotNull { it.seasonYear ?: it.startDate?.year }.filter { it > 0 }
    val releaseYears = when {
        years.isEmpty() -> emptyList()
        years.max() - years.min() <= 11 ->
            (years.min()..years.max()).map { year -> yearLabel(year) to years.count { it == year } }
        else -> {
            val decades = years.map { it / 10 * 10 }
            (decades.min()..decades.max() step 10).map { decade -> decadeLabel(decade) to decades.count { it == decade } }
        }
    }
    val scored = allEntries.filter { it.score > 0.0 }.distinctBy { it.media?.id ?: it.id }
    val finished = profile.completed.size + profile.dropped.size
    return AccountInsights(
        titles = stats?.count?.takeIf { it > 0 } ?: statusCounts.values.sum(),
        episodes = stats?.episodesWatched?.takeIf { it > 0 } ?: allEntries.sumOf { it.progress.coerceAtLeast(0) },
        minutesWatched = stats?.minutesWatched ?: 0L,
        meanScore = stats?.meanScore?.takeIf { it > 0.0 },
        statusCounts = statusCounts,
        topGenres = topGenres,
        recent = allEntries
            .filter { it.media != null && it.updatedAt > 0 }
            .distinctBy { it.media!!.id }
            .sortedByDescending { it.updatedAt },
        formats = ranked(allMedia.mapNotNull { formatLabel(it.format) }, Int.MAX_VALUE),
        studios = ranked(
            startedMedia.flatMap { media -> media.studios.nodes.filter { it.isAnimationStudio }.mapNotNull { it.name } },
            MAX_GENRES,
        ),
        releaseYears = releaseYears,
        scoreSpread = if (scored.isEmpty()) {
            emptyList()
        } else {
            (1..10).map { value -> value.toString() to scored.count { it.score.roundToInt().coerceIn(1, 10) == value } }
        },
        completionRate = if (finished == 0) null else (profile.completed.size * 100f / finished).roundToInt(),
        episodesToGo = (profile.watching + profile.rewatching).sumOf { entry ->
            val total = entry.media?.episodes ?: return@sumOf 0
            (total - entry.progress).coerceAtLeast(0)
        },
    )
}

private fun formatLabel(format: String?): String? = when (format) {
    null -> null
    "TV" -> "TV"
    "TV_SHORT" -> "TV short"
    "MOVIE" -> "Movie"
    "OVA" -> "OVA"
    "ONA" -> "ONA"
    "SPECIAL" -> "Special"
    "MUSIC" -> "Music"
    else -> format.lowercase().replaceFirstChar { it.uppercase() }
}

/** A year as a column label narrow enough for twelve across a phone: 2024 becomes '24. */
internal fun yearLabel(year: Int): String = "\u2019" + (year % 100).toString().padStart(2, '0')

/** A decade as a column label: 1990 becomes 90s, 2000 becomes 00s. */
internal fun decadeLabel(decade: Int): String = (decade % 100).toString().padStart(2, '0') + "s"

/** The account's public page on its own site, for the drawer's "View profile". */
internal fun accountProfileUrl(service: AccountService, name: String): String = when (service) {
    AccountService.ANILIST -> "https://anilist.co/user/${android.net.Uri.encode(name)}/"
    AccountService.MAL -> "https://myanimelist.net/profile/${android.net.Uri.encode(name)}"
}

/**
 * Watch time as the stat row prints it: hours once there are enough of them to count ("1,062 h"),
 * and hours-and-minutes before that, where "0 h" would say nothing.
 */
internal fun statWatchTime(minutes: Long): String =
    if (minutes >= 600) String.format(Locale.US, "%,d h", minutes / 60) else watchTimeLabel(minutes)

/**
 * The drawer's top: banner, a large round picture overlapping it, the name, the connected service,
 * and a way to the account's own page. An account with no banner — every MyAnimeList account —
 * borrows the cover of what it watched last, so the space still carries the viewer's own taste.
 */
@Composable
internal fun AccountHeader(
    viewer: Viewer?,
    service: AccountService?,
    fallbackBanner: String?,
    onSync: () -> Unit,
    onDismiss: (() -> Unit)?,
) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(132.dp),
        ) {
            val banner = viewer?.bannerImage?.takeIf { it.isNotBlank() } ?: fallbackBanner
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Midnight.Text.copy(alpha = 0.06f)),
            ) {
                if (banner != null) {
                    AsyncImage(
                        model = banner,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 1f to Color.Black.copy(alpha = 0.65f))),
                )
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                ) {
                    BannerIconButton(Icons.Default.Refresh, "Sync now", onSync)
                    if (onDismiss != null) BannerIconButton(Icons.Default.Close, "Close", onDismiss)
                }
            }
            AccountAvatar(
                viewer?.avatar?.large,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp)
                    .size(76.dp)
                    .border(3.dp, Midnight.Bg, CircleShape),
                shape = CircleShape,
            )
        }
        Row(
            Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    viewer?.name ?: "Your account",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = Midnight.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (service != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Image(
                            painter = painterResource(
                                if (service == AccountService.ANILIST) R.drawable.ic_brand_anilist else R.drawable.ic_brand_mal,
                            ),
                            contentDescription = null,
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                        Text(
                            "${service.label} connected",
                            style = MaterialTheme.typography.labelMedium,
                            color = Midnight.Text2,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
            val name = viewer?.name
            if (service != null && !name.isNullOrBlank()) {
                val focus = rememberFocusFill()
                val shape = RoundedCornerShape(50)
                Surface(
                    onClick = { runCatching { uriHandler.openUri(accountProfileUrl(service, name)) } },
                    shape = shape,
                    color = focus.background(Color.Transparent),
                    contentColor = focus.content(Midnight.Text),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Midnight.Text.copy(alpha = 0.5f)),
                    modifier = Modifier.focusFill(focus, shape),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("View profile", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BannerIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val focus = rememberFocusFill()
    IconButton(
        onClick = onClick,
        colors = focus.iconButtonColors(
            IconButtonDefaults.iconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.35f),
                contentColor = Color.White,
            ),
        ),
        modifier = Modifier
            .size(40.dp)
            .focusFill(focus, CircleShape),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
    }
}

/**
 * The account's picture, or a drawn anime character when it has none. MyAnimeList hands back no
 * picture at all for an account that never set one, which left an empty dark circle wherever the
 * account appeared.
 */
@Composable
internal fun AccountAvatar(url: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(14.dp)) {
    val placeholder = painterResource(R.drawable.ic_default_avatar)
    AsyncImage(
        model = url?.takeIf { it.isNotBlank() },
        contentDescription = null,
        placeholder = placeholder,
        error = placeholder,
        fallback = placeholder,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

internal enum class DashboardTab(val label: String) { OVERVIEW("Overview"), STATS("Stats"), ACTIVITY("Activity") }

/**
 * The drawer's body: the three headline totals, then Overview / Stats / Activity.
 *
 * [continueWatching] is this device's own Continue Watching, most recent first — the same rows the
 * page below shows — and [onResume] plays one where it was left.
 */
@Composable
internal fun AccountDashboard(
    insights: AccountInsights,
    profile: AniListProfile,
    continueWatching: List<HistoryEntry>,
    onResume: (HistoryEntry) -> Unit,
    onSync: () -> Unit,
    onPair: (() -> Unit)?,
    onLogout: () -> Unit,
    tab: DashboardTab,
    onTabChange: (DashboardTab) -> Unit,
) {
    val localActivity by WatchActivityStore.days.collectAsState()
    val tracked = remember(profile) {
        listOf(profile.watching, profile.rewatching, profile.planning, profile.paused, profile.completed, profile.dropped)
            .flatten()
            .filter { it.media != null }
            .associateBy { it.media!!.id }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        HeadlineStats(insights)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DashboardTab.entries.forEach { entry ->
                TabPill(entry.label, selected = entry == tab, onClick = { onTabChange(entry) }, modifier = Modifier.weight(1f))
            }
        }
        when (tab) {
            DashboardTab.OVERVIEW -> {
                YourAnimeBar(insights.statusCounts, onViewAll = { onTabChange(DashboardTab.STATS) })
                val cards = continueWatching.take(2)
                if (cards.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeading("Continue watching")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            cards.forEach { entry ->
                                ContinueCard(entry, tracked[entry.anilistId], onResume, Modifier.weight(1f))
                            }
                            if (cards.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if (insights.recent.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeading("Recent activity", action = "View all" to { onTabChange(DashboardTab.ACTIVITY) })
                        insights.recent.take(3).forEach { ActivityRow(it) }
                    }
                }
            }
            DashboardTab.STATS -> {
                val activity = remember(localActivity, profile.activityHistory) {
                    mergedActivity(localActivity, profile.activityHistory)
                }
                ActivityHeatmap(activity, fromAccount = profile.service == AccountService.ANILIST)
                HighlightTiles(insights)
                StatusBreakdown(insights.statusCounts)
                if (insights.formats.isNotEmpty()) {
                    HorizontalBars("Formats", "What your list is made of", insights.formats)
                }
                if (insights.topGenres.isNotEmpty()) {
                    val onlyPlanning = insights.statusCounts.filterKeys { it != ListStatusSlice.PLANNING }.values.sum() == 0
                    HorizontalBars(
                        "Top genres",
                        if (onlyPlanning) "Across the titles you plan to watch" else "Across the titles you have started",
                        insights.topGenres,
                    )
                }
                if (insights.studios.isNotEmpty()) {
                    HorizontalBars("Top studios", "Who made what you have started", insights.studios)
                }
                if (insights.releaseYears.isNotEmpty()) {
                    ColumnChart("Release years", "When the titles on your list came out", insights.releaseYears)
                }
                if (insights.scoreSpread.isNotEmpty()) {
                    ColumnChart("Your scores", "How you rate what you have scored", insights.scoreSpread)
                } else {
                    SectionTitle("Your scores", "Score a few titles and your spread shows up here")
                }
            }
            DashboardTab.ACTIVITY -> {
                if (insights.recent.isEmpty()) {
                    Text("Nothing on your list has changed yet.", color = Midnight.Text2)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        insights.recent.take(20).forEach { ActivityRow(it) }
                    }
                }
            }
        }
        AccountActions(profile.service, onSync, onPair, onLogout)
    }
}

/**
 * The account's own controls, as rows at the foot of every tab. They used to be quiet words in the
 * dialog footer — "Logout" in small red type beside "Close" — and a viewer looking for how to sign
 * out could not find it.
 */
@Composable
internal fun AccountActions(
    service: AccountService?,
    onSync: () -> Unit,
    onPair: (() -> Unit)?,
    onLogout: () -> Unit,
) {
    val name = service?.label ?: "your account"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Account", service?.let { "Signed in with ${it.label}" } ?: "Your account")
        AccountActionRow(Icons.Default.Refresh, "Sync now", "Pull your lists from $name again", onSync)
        if (onPair != null) {
            AccountActionRow(Icons.Default.Tv, "Link a TV", "Sign your TV in from this phone", onPair)
        }
        AccountActionRow(Icons.AutoMirrored.Filled.Logout, "Log out", "Sign this device out of $name", onLogout, danger = true)
    }
}

@Composable
internal fun AccountActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val focus = rememberFocusFill()
    val shape = RoundedCornerShape(12.dp)
    val ink = if (danger) Midnight.Red else Midnight.Text
    Surface(
        onClick = onClick,
        shape = shape,
        color = focus.background(Midnight.Text.copy(alpha = 0.05f)),
        contentColor = focus.content(ink),
        modifier = modifier
            .fillMaxWidth()
            .focusFill(focus, shape),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = focus.content(ink), modifier = Modifier.size(22.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = focus.content(ink))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = focus.content(Midnight.Text2))
            }
        }
    }
}

/** Anime, watch time and mean score, three across with hairlines between. */
@Composable
private fun HeadlineStats(insights: AccountInsights) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(52.dp)) {
            HeadlineStat(String.format(Locale.US, "%,d", insights.titles), "Anime", Modifier.weight(1f))
            StatDivider()
            HeadlineStat(statWatchTime(insights.minutesWatched), "Watch time", Modifier.weight(1f))
            StatDivider()
            HeadlineStat(
                insights.meanScore?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                if (insights.meanScore == null) "No scores yet" else "Mean score",
                Modifier.weight(1f),
            )
        }
        Box(
            Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Midnight.Line),
        )
    }
}

@Composable
private fun HeadlineStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Midnight.Text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Midnight.Text2, maxLines = 1)
    }
}

@Composable
private fun StatDivider() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight(0.7f)
            .background(Midnight.Line),
    )
}

@Composable
internal fun TabPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val focus = rememberFocusFill()
    val shape = RoundedCornerShape(50)
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = shape,
        color = focus.background(if (selected) accent else Color.Transparent),
        contentColor = focus.content(if (selected) MaterialTheme.colorScheme.onPrimary else Midnight.Text),
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, Midnight.Text.copy(alpha = 0.3f)),
        modifier = modifier.focusFill(focus, shape),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(vertical = 10.dp),
        )
    }
}

@Composable
internal fun SectionHeading(title: String, action: Pair<String, () -> Unit>? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = Midnight.Text,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            val focus = rememberFocusFill()
            Text(
                action.first,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = focus.content(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .focusFill(focus, RoundedCornerShape(8.dp))
                    .clickable(onClick = action.second)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * "Your anime": the list's shape at a glance — one stacked bar of every status, and the three that
 * describe most accounts named underneath. The Stats tab carries the full table.
 */
@Composable
internal fun YourAnimeBar(counts: Map<ListStatusSlice, Int>, onViewAll: () -> Unit) {
    val total = counts.values.sum()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading("Your anime", action = "View all" to onViewAll)
        StackedStatusBar(counts, total)
        Row {
            listOf(ListStatusSlice.WATCHING, ListStatusSlice.COMPLETED, ListStatusSlice.PLANNING).forEachIndexed { index, slice ->
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = when (index) {
                        0 -> Arrangement.Start
                        1 -> Arrangement.Center
                        else -> Arrangement.End
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(slice.color),
                    )
                    Text(
                        (counts[slice] ?: 0).toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Midnight.Text,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                    Text(
                        " ${slice.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Midnight.Text2,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun StackedStatusBar(counts: Map<ListStatusSlice, Int>, total: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Midnight.Text.copy(alpha = 0.08f)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (total > 0) {
            counts.filterValues { it > 0 }.forEach { (slice, count) ->
                Box(
                    Modifier
                        .weight(count.toFloat())
                        .fillMaxHeight()
                        .background(slice.color),
                )
            }
        }
    }
}

/** A Continue Watching title as a poster card: score, play, which episode, and how far through. */
@Composable
internal fun ContinueCard(
    entry: HistoryEntry,
    tracked: MediaListEntry?,
    onResume: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTv = LocalAppDeviceProfile.current.isTv
    val shape = RoundedCornerShape(12.dp)
    val media = tracked?.media
    val total = media?.episodes?.takeIf { it > 0 }
    val episode = entry.episodeNumber.toInt()
    val fraction = if (total != null) {
        ((episode - 1 + entry.progressFraction) / total).coerceIn(0f, 1f)
    } else {
        entry.progressFraction
    }
    Column(
        modifier
            .focusHighlight(shape, focusedScale = 1.025f)
            .clickable { onResume(entry) },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (isTv) 16f / 9f else 0.72f)
                .clip(shape)
                .background(Midnight.Text.copy(alpha = 0.06f)),
        ) {
            AsyncImage(
                model = media?.coverImage?.best ?: entry.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            media?.averageScore?.takeIf { it > 0 }?.let { score ->
                RatingBadge(score, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Text(
            media?.title?.preferred ?: entry.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Midnight.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            if (total != null) "Episode $episode of $total" else "Episode ${entry.episodeLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = Midnight.Text2,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Midnight.Text.copy(alpha = 0.12f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Text(
                "${(fraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = Midnight.Text2,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** One change to the list, read off the entry itself: what happened, when, and the title's cover. */
@Composable
internal fun ActivityRow(entry: MediaListEntry) {
    val media = entry.media ?: return
    val title = media.title.preferred
    val (icon, headline) = when (entry.status) {
        "COMPLETED" -> Icons.Default.Check to "Completed $title"
        "CURRENT" -> Icons.Default.PlayArrow to
            if (entry.progress > 0) "Watched episode ${entry.progress} of $title" else "Started $title"
        "REPEATING" -> Icons.Default.Replay to "Rewatching $title"
        "PAUSED" -> Icons.Default.Pause to "Paused $title"
        "DROPPED" -> Icons.Default.RemoveCircleOutline to "Dropped $title"
        else -> Icons.Default.Bookmark to "Planned $title"
    }
    val detail = listOfNotNull(
        DateUtils.getRelativeTimeSpanString(
            entry.updatedAt * 1000L,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString(),
        entry.score.takeIf { it > 0.0 }?.let { score ->
            "Rated ${if (score % 1.0 == 0.0) score.toInt().toString() else String.format(Locale.US, "%.1f", score)}/10"
        },
    ).joinToString(" · ")
    Row(Modifier.fillMaxWidth().readingFocus(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Midnight.Text.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                headline,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Midnight.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Midnight.Text2, maxLines = 1)
        }
        AsyncImage(
            model = media.coverImage.best,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 44.dp, height = 60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Midnight.Text.copy(alpha = 0.06f)),
        )
    }
}

@Composable
internal fun SectionTitle(title: String, subtitle: String) {
    val isTv = LocalAppDeviceProfile.current.isTv
    Column {
        Text(title, style = if (isTv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Midnight.Text)
        Text(subtitle, style = if (isTv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelSmall, color = Midnight.Text2)
    }
}

/** Give remote users a stop at each chart or activity row so Down cannot skip the reading matter. */
@Composable
private fun Modifier.readingFocus(): Modifier = if (LocalAppDeviceProfile.current.isTv) {
    this
        .focusHighlight(focusedScale = 1f)
        .focusable()
        .padding(10.dp)
} else {
    this
}

/**
 * Part of a whole, so one stacked bar: each status its share of the width, a 2dp gap between
 * segments, and a legend that doubles as the table — every status, its count and its share, in
 * text, so nothing is carried by colour alone.
 */
@Composable
internal fun StatusBreakdown(counts: Map<ListStatusSlice, Int>) {
    val total = counts.values.sum()
    Column(Modifier.fillMaxWidth().readingFocus(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Your list", "$total titles by status")
        StackedStatusBar(counts, total)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            counts.forEach { (slice, count) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(slice.color),
                    )
                    Text(
                        slice.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Midnight.Text,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                    )
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Midnight.Text,
                    )
                    Text(
                        if (total > 0) "${(count * 100f / total).roundToInt()}%" else "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = Midnight.Text2,
                        modifier = Modifier
                            .width(44.dp)
                            .padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** Magnitude across a handful of categories: horizontal bars against a common track, longest first. */
@Composable
internal fun HorizontalBars(title: String, subtitle: String, items: List<Pair<String, Int>>) {
    val max = items.maxOf { it.second }.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().readingFocus(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title, subtitle)
        items.forEach { (genre, count) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    genre,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Midnight.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(104.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Midnight.Text.copy(alpha = 0.08f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(count.toFloat() / max)
                            .fillMaxHeight()
                            .background(GenreBarColor),
                    )
                }
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Midnight.Text,
                    modifier = Modifier
                        .width(36.dp)
                        .padding(start = 10.dp),
                )
            }
        }
    }
}

/**
 * Counts along an ordered axis — years, scores — as columns on a shared baseline. One series, one
 * hue; only the tallest column carries its number, the rest read against it and the axis labels.
 */
@Composable
internal fun ColumnChart(title: String, subtitle: String, items: List<Pair<String, Int>>) {
    val max = items.maxOf { it.second }.coerceAtLeast(1)
    val peak = items.indexOfFirst { it.second == max }
    Column(Modifier.fillMaxWidth().readingFocus(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title, subtitle)
        Row(
            Modifier
                .fillMaxWidth()
                .height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items.forEachIndexed { index, (_, count) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (index == peak) {
                        Text(count.toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Midnight.Text)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(if (count == 0) 2.dp else (96f * count / max).coerceAtLeast(4f).dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (count == 0) Midnight.Text.copy(alpha = 0.12f) else GenreBarColor),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { (label, _) ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Midnight.Text2,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Days watched, episodes, completion rate and what is left to watch, two by two. */
@Composable
internal fun HighlightTiles(insights: AccountInsights) {
    Column(Modifier.fillMaxWidth().readingFocus(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val hasWholeDay = insights.minutesWatched >= 1440
            HighlightTile(
                if (hasWholeDay) String.format(Locale.US, "%.1f", insights.minutesWatched / 1440.0) else watchTimeLabel(insights.minutesWatched),
                if (hasWholeDay) "Days watched" else "Watch time",
                Modifier.weight(1f),
            )
            HighlightTile(String.format(Locale.US, "%,d", insights.episodes), "Episodes", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HighlightTile(insights.completionRate?.let { "$it%" } ?: "—", "Completion rate", Modifier.weight(1f))
            HighlightTile(String.format(Locale.US, "%,d", insights.episodesToGo), "Episodes to go", Modifier.weight(1f))
        }
    }
}

@Composable
private fun HighlightTile(value: String, label: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .clip(shape)
            .border(1.dp, Midnight.Line, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = Midnight.Text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Midnight.Text2)
    }
}

/**
 * The heat colour: one warm hue, stepped by opacity over the sheet so each level is a brighter step
 * of the same colour — a sequential ramp, never a second hue. Level 0 is the empty cell.
 */
private val HeatColor = Color(0xFFF0BD5C)
private val HeatAlphas = floatArrayOf(0f, 0.3f, 0.5f, 0.75f, 1f)

/**
 * Twenty weeks: across a phone that makes cells about the size of GitHub's, where twelve made
 * squares so large the grid took the whole screen before anything else was seen.
 */
private const val HEATMAP_WEEKS = 20

/**
 * [HEATMAP_WEEKS] weeks of activity, a column per week and a row per weekday, GitHub-style. Brighter is
 * busier, relative to the busiest day on screen; the key underneath says so in words, and the
 * streak and day count put the same picture in numbers.
 */
@Composable
internal fun ActivityHeatmap(days: Map<Long, Int>, fromAccount: Boolean) {
    val today = java.time.LocalDate.now()
    val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val firstDay = weekStart.minusWeeks((HEATMAP_WEEKS - 1).toLong())
    val window = (0 until HEATMAP_WEEKS * 7).map { firstDay.plusDays(it.toLong()) }
    val max = window.maxOf { days[it.toEpochDay()] ?: 0 }.coerceAtLeast(1)
    val activeDays = window.count { !it.isAfter(today) && (days[it.toEpochDay()] ?: 0) > 0 }
    val streak = activityStreak(days, today.toEpochDay())
    fun level(count: Int) = if (count <= 0) 0 else kotlin.math.ceil(4.0 * count / max).toInt().coerceIn(1, 4)
    val empty = Midnight.Text.copy(alpha = 0.07f)
    val labelWidth = 32.dp
    Column(Modifier.fillMaxWidth().readingFocus(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(
            "Your activity",
            if (fromAccount) {
                "Last $HEATMAP_WEEKS weeks · AniList activity and episodes watched here"
            } else {
                "Last $HEATMAP_WEEKS weeks · episodes finished on this device"
            },
        )
        // Month names over the week where each month begins.
        Row(Modifier.padding(start = labelWidth)) {
            (0 until HEATMAP_WEEKS).forEach { week ->
                val monday = firstDay.plusWeeks(week.toLong())
                val startsMonth = week == 0 || monday.month != monday.minusWeeks(1).month
                Box(Modifier.weight(1f)) {
                    if (startsMonth) {
                        Text(
                            monday.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Midnight.Text2,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                        )
                    }
                }
            }
        }
        Row {
            // One row per weekday, so the grid's cell height is set by the week columns and the
            // labels are matched to it by sharing the same row structure.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                (0 until 7).forEach { weekday ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listOf("Mon", "", "Wed", "", "Fri", "", "Sun")[weekday],
                            style = MaterialTheme.typography.labelSmall,
                            color = Midnight.Text2,
                            maxLines = 1,
                            modifier = Modifier.width(labelWidth),
                        )
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            (0 until HEATMAP_WEEKS).forEach { week ->
                                val date = firstDay.plusDays((week * 7 + weekday).toLong())
                                val lvl = level(days[date.toEpochDay()] ?: 0)
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            when {
                                                date.isAfter(today) -> Color.Transparent
                                                lvl == 0 -> empty
                                                else -> HeatColor.copy(alpha = HeatAlphas[lvl])
                                            },
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = if (streak > 0) HeatColor else Midnight.Text3,
                modifier = Modifier.size(18.dp),
            )
            Text(
                when (streak) {
                    0 -> "No streak yet"
                    1 -> "1-day streak"
                    else -> "$streak-day streak"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (streak > 0) HeatColor else Midnight.Text2,
                modifier = Modifier.padding(start = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            Text("Less", style = MaterialTheme.typography.labelSmall, color = Midnight.Text2)
            (0..4).forEach { lvl ->
                Box(
                    Modifier
                        .padding(start = 3.dp)
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (lvl == 0) empty else HeatColor.copy(alpha = HeatAlphas[lvl])),
                )
            }
            Text("More", style = MaterialTheme.typography.labelSmall, color = Midnight.Text2, modifier = Modifier.padding(start = 4.dp))
        }
        Text(
            if (activeDays == 0 && !fromAccount) {
                "Watch an episode here to start your activity history."
            } else {
                "$activeDays active ${if (activeDays == 1) "day" else "days"} in the last $HEATMAP_WEEKS weeks"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Midnight.Text2,
        )
    }
}
