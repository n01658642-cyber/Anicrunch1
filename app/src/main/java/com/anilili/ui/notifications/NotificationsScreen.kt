package com.anilili.ui.notifications

import com.anilili.ui.theme.canvasColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.anilili.data.AppGraph
import com.anilili.data.auth.AuthManager
import com.anilili.data.model.AppNotification
import com.anilili.errors.AnililiError
import com.anilili.errors.toFailure
import com.anilili.ui.UiState
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.TvFocusTarget
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.adaptive.rememberTvFocusTarget
import com.anilili.ui.adaptive.tvFocusHandoff
import com.anilili.ui.adaptive.tvFocusTarget
import com.anilili.ui.components.ErrorBox
import com.anilili.ui.components.LoadingBox
import com.anilili.ui.components.ScrollAwareTopBar
import com.anilili.ui.rethrowIfCancellation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NotificationsViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _state = MutableStateFlow<UiState<List<AppNotification>>>(UiState.Loading)
    val state = _state.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount = _unreadCount.asStateFlow()

    init {
        load(markAllRead = false)
    }

    fun refresh() = load(markAllRead = false)

    fun markAllRead() = load(markAllRead = true, keepUnreadHighlight = true)

    private fun load(markAllRead: Boolean, keepUnreadHighlight: Boolean = false) {
        viewModelScope.launch {
            if (_state.value !is UiState.Success) _state.value = UiState.Loading
            try {
                val (items, unread) = repo.notifications(markAllRead)
                // After mark-all-read the highlight stays for this visit so the user still
                // sees what was new; the counter itself is cleared.
                _state.value = UiState.Success(items)
                _unreadCount.value = if (markAllRead) 0 else unread
                if (keepUnreadHighlight) _unreadCount.value = 0
                com.anilili.data.reminder.NotificationCenter.setUnread(_unreadCount.value)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _state.value = UiState.Error(e.toFailure(AnililiError.NOTIFICATIONS_LOAD_FAILED))
            }
        }
    }
}

private enum class Tab(val label: String) {
    ALL("All"),
    AIRING("Airing"),
    SOCIAL("Social"),
    MEDIA("Media");

    fun matches(item: AppNotification): Boolean = when (this) {
        ALL -> true
        AIRING -> item.kind == AppNotification.Kind.AIRING
        SOCIAL -> item.kind == AppNotification.Kind.SOCIAL
        MEDIA -> item.kind == AppNotification.Kind.MEDIA
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onAnimeClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vm: NotificationsViewModel = viewModel(),
) {
    val device = LocalAppDeviceProfile.current
    val token by AuthManager.token.collectAsState()
    val state by vm.state.collectAsState()
    val unreadCount by vm.unreadCount.collectAsState()
    var tab by remember { mutableStateOf(Tab.ALL) }
    val backFocusRequester = remember { FocusRequester() }
    val tvContentFocusTarget = rememberTvFocusTarget()
    // The user is now looking at the same items the tray was advertising: clear the shade so
    // the system notifications never outlive the in-app read state.
    val notifContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.anilili.data.reminder.AniListNotificationPushManager.dismissAll(notifContext)
    }

    Scaffold(
        containerColor = canvasColor(),
        modifier = modifier,
        topBar = {
            ScrollAwareTopBar { TopAppBar(
                title = { Text("Notifications", fontWeight = FontWeight.Black) },
                navigationIcon = {
                    val controlFocus1 = rememberFocusFill()
                    IconButton(
                        colors = controlFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                        onClick = onBack,
                        modifier = Modifier
                            .focusRequester(backFocusRequester)
                            .tvFocusHandoff(
                                Key.DirectionDown,
                                tvContentFocusTarget.takeIf { device.isTv },
                            )
                            .focusFill(controlFocus1, RoundedCornerShape(24.dp)),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val controlFocus2 = rememberFocusFill()
                    IconButton(
                        colors = controlFocus2.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                        onClick = vm::refresh,
                        modifier = Modifier
                            .tvFocusHandoff(
                                Key.DirectionDown,
                                tvContentFocusTarget.takeIf { device.isTv },
                            )
                            .focusFill(controlFocus2, RoundedCornerShape(24.dp)),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    val controlFocus3 = rememberFocusFill()
                    TextButton(
                        colors = controlFocus3.buttonColors(ButtonDefaults.textButtonColors()),
                        onClick = vm::markAllRead,
                        enabled = unreadCount > 0,
                        modifier = Modifier
                            .tvFocusHandoff(
                                Key.DirectionDown,
                                tvContentFocusTarget.takeIf { device.isTv },
                            )
                            .focusFill(controlFocus3, RoundedCornerShape(20.dp)),
                    ) {
                        Text("Mark all read")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = canvasColor()),
            ) }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            if (token == null) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Log in with AniList", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Notifications for new episodes and activity come from your AniList account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                return@Box
            }

            when (val current = state) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(current.failure, vm::refresh)
                is UiState.Success -> NotificationList(
                    items = current.data,
                    tab = tab,
                    unreadTotal = unreadCount,
                    onTab = { tab = it },
                    onAnimeClick = onAnimeClick,
                    pagePadding = device.pagePadding,
                    contentPadding = padding,
                    isTv = device.isTv,
                    backFocusRequester = backFocusRequester,
                    tvContentFocusTarget = tvContentFocusTarget,
                )
            }
        }
    }
}

@Composable
private fun NotificationList(
    items: List<AppNotification>,
    tab: Tab,
    unreadTotal: Int,
    onTab: (Tab) -> Unit,
    onAnimeClick: (Int) -> Unit,
    pagePadding: androidx.compose.ui.unit.Dp,
    contentPadding: PaddingValues = PaddingValues(),
    isTv: Boolean,
    backFocusRequester: FocusRequester,
    tvContentFocusTarget: TvFocusTarget,
) {
    val filtered = items.filter(tab::matches)
    val now = System.currentTimeMillis() / 1000
    val sections = filtered.groupBy { item ->
        val age = now - item.createdAt
        when {
            age < 7L * 24 * 3600 -> "THIS WEEK"
            age < 30L * 24 * 3600 -> "LAST 30 DAYS"
            else -> "OVER 1 MONTH AGO"
        }
    }

    if (isTv) {
        TvNotificationGrid(
            items = items,
            filtered = filtered,
            sections = sections,
            tab = tab,
            unreadTotal = unreadTotal,
            onTab = onTab,
            onAnimeClick = onAnimeClick,
            pagePadding = pagePadding,
            contentPadding = contentPadding,
            backFocusRequester = backFocusRequester,
            tvContentFocusTarget = tvContentFocusTarget,
        )
        return
    }

    LazyColumn(
        // Scroll padding, not layout padding, so rows pass under the bar as it retreats.
        contentPadding = PaddingValues(
            start = pagePadding,
            end = pagePadding,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                items(Tab.entries.size) { index ->
                    val entry = Tab.entries[index]
                    val count = items.count { it.unread && entry.matches(it) }
                    val controlFocus4 = rememberFocusFill()
                    FilterChip(
                        colors = controlFocus4.chipColors(FilterChipDefaults.filterChipColors()),
                        selected = tab == entry,
                        onClick = { onTab(entry) },
                        label = { Text(if (unreadTotal > 0) "${entry.label}  $count" else entry.label) },
                        modifier = Modifier.focusFill(controlFocus4, RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                Text(
                    "Nothing here yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 32.dp).fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        sections.forEach { (section, sectionItems) ->
            item(key = "header-$section") {
                Text(
                    section,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            sectionItems.forEach { item ->
                item(key = item.id) {
                    NotificationCard(item, onClick = { item.mediaId?.let(onAnimeClick) })
                }
            }
        }
    }
}

@Composable
private fun TvNotificationGrid(
    items: List<AppNotification>,
    filtered: List<AppNotification>,
    sections: Map<String, List<AppNotification>>,
    tab: Tab,
    unreadTotal: Int,
    onTab: (Tab) -> Unit,
    onAnimeClick: (Int) -> Unit,
    pagePadding: androidx.compose.ui.unit.Dp,
    contentPadding: PaddingValues,
    backFocusRequester: FocusRequester,
    tvContentFocusTarget: TvFocusTarget,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(notificationColumnCount(isTv = true)),
        // Down out of the top bar lands on the grid, not on the first filter chip inside it. The
        // chips are the grid's first item, so they exist only while they are on screen, and coming
        // back from an anime page restores the scroll the grid was left at: the hand-off then had
        // nothing attached and gave the press back to ordinary focus search. That search cannot
        // reach the grid either, because the grid runs under the bar from y=0 and Compose's 2-D
        // search rejects a Down candidate that begins above the focused button. The remote was
        // stuck on the top bar until the page was left and opened again.
        //
        // Naming the grid keeps the hand-off pointed at something that exists while there is
        // content; focus-enter then picks whichever child is on screen.
        modifier = Modifier
            .fillMaxSize()
            .tvFocusTarget(tvContentFocusTarget)
            .focusGroup(),
        contentPadding = PaddingValues(
            start = pagePadding,
            end = pagePadding,
            top = contentPadding.calculateTopPadding() + 6.dp,
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth().focusGroup().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { entry ->
                    val count = items.count { it.unread && entry.matches(it) }
                    val controlFocus5 = rememberFocusFill()
                    FilterChip(
                        colors = controlFocus5.chipColors(FilterChipDefaults.filterChipColors()),
                        selected = tab == entry,
                        onClick = { onTab(entry) },
                        label = { Text(if (unreadTotal > 0) "${entry.label}  $count" else entry.label) },
                        modifier = Modifier
                            .focusProperties { up = backFocusRequester }
                            .focusFill(controlFocus5, RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Nothing here yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 32.dp).fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        sections.forEach { (section, sectionItems) ->
            item(key = "header-$section", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    section,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
            }
            sectionItems.forEach { item ->
                item(key = item.id) {
                    NotificationCard(
                        item = item,
                        onClick = { item.mediaId?.let(onAnimeClick) },
                        compactTv = true,
                    )
                }
            }
        }
    }
}

internal fun notificationColumnCount(isTv: Boolean): Int = if (isTv) 2 else 1

@Composable
private fun NotificationCard(
    item: AppNotification,
    onClick: () -> Unit,
    compactTv: Boolean = false,
) {
    val isTv = LocalAppDeviceProfile.current.isTv
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .focusHighlight(shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (item.unread) 1.5.dp else 1.dp,
                color = when {
                    // Grey, not the accent, on a television: the accent is white in the default
                    // theme, which is the focus ring's colour too, so a page of unread cards read as
                    // a page of focused ones. A lighter grey than a read card's still tells them apart.
                    item.unread && isTv -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    item.unread -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                },
                shape = shape,
            )
            .then(
                when {
                    item.mediaId != null -> Modifier.clickable(onClick = onClick)
                    compactTv -> Modifier.focusable()
                    else -> Modifier.clickable(enabled = false, onClick = onClick)
                },
            )
            .then(if (compactTv) Modifier.height(112.dp) else Modifier),
    ) {
        item.banner?.let { banner ->
            AsyncImage(
                model = banner,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.18f,
                modifier = Modifier.matchParentSize(),
            )
        }
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = item.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(
                        width = if (compactTv) 64.dp else 58.dp,
                        height = if (compactTv) 86.dp else 76.dp,
                    )
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.detail?.let { detail ->
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (compactTv) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (compactTv) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item.badge?.let { badge -> NotificationBadge(badge) }
                        Spacer(Modifier.weight(1f))
                        NotificationDate(item.createdAt)
                    }
                } else {
                    item.badge?.let { badge ->
                        NotificationBadge(badge, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            if (!compactTv) NotificationDate(item.createdAt, Modifier.align(Alignment.Bottom))
        }
    }
}

@Composable
private fun NotificationBadge(badge: String, modifier: Modifier = Modifier) {
    Text(
        badge,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun NotificationDate(epochSeconds: Long, modifier: Modifier = Modifier) {
    Text(
        formatDay(epochSeconds),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private val dayFormatter = DateTimeFormatter.ofPattern("d MMM")

private fun formatDay(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(dayFormatter)
