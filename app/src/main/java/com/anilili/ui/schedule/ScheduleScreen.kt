package com.anilili.ui.schedule

import com.anilili.ui.theme.canvasColor
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.anilili.data.model.AiringSchedule
import com.anilili.data.model.ScheduleDayCount
import com.anilili.data.reminder.ReminderManager
import com.anilili.data.settings.SettingsStore
import com.anilili.ui.UiState
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.components.AppChromeListBottomGap
import com.anilili.ui.components.ErrorBox
import com.anilili.ui.components.LoadingBox
import com.anilili.ui.components.LocalAppChromeBottomInset
import com.anilili.ui.components.LocalTimeOfDayFormatter
import com.anilili.ui.components.PageTitleBar
import com.anilili.ui.components.PullRefreshContainer
import com.anilili.ui.components.ScrollAwareTopBar
import com.anilili.ui.home.TvCentredScrollSpec
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.flow.first

private val dateFmt = DateTimeFormatter.ofPattern("EEEE, MMM d")
private val dayFmt = DateTimeFormatter.ofPattern("EEE")
private val shortDateFmt = DateTimeFormatter.ofPattern("MMM d")

/** The strip's first day relative to today, and how many it shows. Yesterday through next week. */
private const val DAY_STRIP_FIRST = -2L
private const val DAY_STRIP_LENGTH = 9

/** How tall the loading and error panels stand when they are items in the calendar's own list. */
private val CalendarStatusHeight = 220.dp

/** Height of the phone's floating day stepper; both lists reserve it below their last row. */
private val DayStepperHeight = 56.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onAnimeClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vm: ScheduleViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val scheduled by ReminderManager.scheduled.collectAsState()
    val calendarView by SettingsStore.scheduleCalendarView.collectAsState()
    val dayCounts by vm.dayCounts.collectAsState()
    val countsLoading by vm.countsLoading.collectAsState()
    val context = LocalContext.current
    var pendingReminder by remember { mutableStateOf<AiringSchedule?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingReminder?.let(ReminderManager::toggle)
        pendingReminder = null
    }
    val toggleReminder: (AiringSchedule) -> Unit = { item ->
        val alreadyScheduled = ReminderManager.isScheduled(item)
        if (!alreadyScheduled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingReminder = item
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ReminderManager.toggle(item)
        }
    }

    val today = LocalDate.now(ZoneId.systemDefault())
    val firstDayOfWeek = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    val gridDays = remember(vm.visibleMonth, firstDayOfWeek) {
        monthGridDays(vm.visibleMonth, firstDayOfWeek)
    }
    // Only the calendar pays for the badges, and only for the month on screen. Nobody who leaves
    // the strip alone spends a request on this.
    LaunchedEffect(calendarView, gridDays) {
        if (calendarView) vm.loadCounts(gridDays)
    }
    // Coming back to the strip from a day it does not carry would leave the list showing a date
    // with nothing selected above it, so the selection follows to the nearest day it does.
    val stripStart = today.plusDays(DAY_STRIP_FIRST)
    val stripEnd = today.plusDays(DAY_STRIP_FIRST + DAY_STRIP_LENGTH - 1)
    LaunchedEffect(calendarView) {
        if (!calendarView) vm.selectDate(vm.selectedDate.coerceIn(stripStart, stripEnd))
    }
    val device = LocalAppDeviceProfile.current
    // A television changes the day from the pickers at the top, a D-pad press away; the stepper is
    // for a thumb at the bottom of a phone that has scrolled the pickers out of reach.
    val showDayStepper = !device.isTv
    val stepperClearance = if (showDayStepper) DayStepperHeight + 12.dp else 0.dp
    // Fresh for each visit to the month view, the way it was when the calendar owned it.
    val calendarListState = remember(calendarView) { LazyListState() }
    // The stepper changes the day from wherever the month view has been scrolled to, and left alone
    // the new day would open part-way down — on its last rows, if the old one was read to the end.
    // A list already past the calendar comes back to the new day's first slot instead; one still
    // showing the grid stays where it is. Both halves are timed: whether the list was past the
    // calendar is read at the tap, because the moment the day starts loading the list shrinks to
    // the grid and a spinner and every position reads as the top; and the jump waits for the day
    // to arrive, because made against that short list it clamps straight back onto the grid.
    var jumpToDay by remember { mutableStateOf(false) }
    LaunchedEffect(jumpToDay, state) {
        if (jumpToDay && state !is UiState.Loading) {
            jumpToDay = false
            calendarListState.scrollToItem(1)
        }
    }

    Scaffold(
        containerColor = canvasColor(),
        modifier = modifier,
        topBar = {
            ScrollAwareTopBar {
                PageTitleBar(
                    title = "Schedule",
                    trailing = {
                        val controlFocus1 = rememberFocusFill()
                        IconButton(
                            colors = controlFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                            onClick = { SettingsStore.setScheduleCalendarView(!calendarView) },
                            modifier = Modifier.focusFill(controlFocus1, CircleShape),
                        ) {
                            Icon(
                                imageVector = if (calendarView) Icons.AutoMirrored.Filled.ViewList
                                else Icons.Default.CalendarMonth,
                                contentDescription = if (calendarView) "Show the days around today"
                                else "Show the whole month",
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (calendarView) {
                    CalendarSchedule(
                        vm = vm,
                        state = state,
                        isRefreshing = isRefreshing,
                        today = today,
                        gridDays = gridDays,
                        firstDayOfWeek = firstDayOfWeek,
                        dayCounts = dayCounts,
                        countsLoading = countsLoading,
                        scheduled = scheduled,
                        onAnimeClick = onAnimeClick,
                        onToggleReminder = toggleReminder,
                        listState = calendarListState,
                        bottomClearance = stepperClearance,
                    )
                } else {
                    DayTabs(today, vm.selectedDate, vm::selectDate)
                    SelectedDateLabel(vm.selectedDate)
                    when (val s = state) {
                        is UiState.Loading -> LoadingBox()
                        is UiState.Error -> ErrorBox(s.failure, vm::load)
                        is UiState.Success -> PullRefreshContainer(
                            isRefreshing = isRefreshing,
                            onRefresh = vm::refresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            ScheduleList(s.data, scheduled, onAnimeClick, toggleReminder, stepperClearance)
                        }
                    }
                }
            }
            if (showDayStepper) {
                val selected = vm.selectedDate
                DayStepper(
                    selected = selected,
                    today = today,
                    // The strip only carries its nine days, and stepping past either end would leave
                    // the list on a date with no chip selected above it.
                    canGoBack = calendarView || selected > stripStart,
                    canGoForward = calendarView || selected < stripEnd,
                    onSelect = { date ->
                        if (calendarView && calendarListState.firstVisibleItemIndex > 0) jumpToDay = true
                        vm.selectDate(date)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = device.pagePadding)
                        .padding(bottom = LocalAppChromeBottomInset.current + 12.dp)
                        .widthIn(max = 520.dp),
                )
            }
        }
    }
}

/**
 * The month grid and the selected day's episodes, as one scrolling page.
 *
 * The calendar scrolls with the list rather than sitting pinned above it: six weeks of cells and a
 * day's worth of cards do not both fit on a phone, and a grid that cannot be scrolled away leaves
 * two or three episodes visible underneath it. It stays the first thing in the list through
 * loading and failure alike, so a day that will not load is still a day you can change.
 */
@Composable
private fun CalendarSchedule(
    vm: ScheduleViewModel,
    state: UiState<List<AiringSchedule>>,
    isRefreshing: Boolean,
    today: LocalDate,
    gridDays: List<LocalDate>,
    firstDayOfWeek: DayOfWeek,
    dayCounts: Map<LocalDate, ScheduleDayCount>,
    countsLoading: Boolean,
    scheduled: Set<String>,
    onAnimeClick: (Int) -> Unit,
    onToggleReminder: (AiringSchedule) -> Unit,
    listState: LazyListState,
    bottomClearance: Dp,
) {
    val zone = ZoneId.systemDefault()
    val items = (state as? UiState.Success)?.data.orEmpty()
    val groups = remember(items) {
        items.sortedBy(AiringSchedule::airingAt).groupBy(AiringSchedule::airingAt)
    }
    PullRefreshContainer(
        isRefreshing = isRefreshing,
        onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        // On TV the focused card comes to rest mid-screen, going up as well as down.
        TvCentredScrollSpec {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = AppChromeListBottomGap + LocalAppChromeBottomInset.current + bottomClearance,
                ),
            ) {
                item(key = "calendar") {
                    Column {
                        MonthCalendar(
                            month = vm.visibleMonth,
                            days = gridDays,
                            selected = vm.selectedDate,
                            today = today,
                            counts = dayCounts,
                            countsLoading = countsLoading,
                            firstDayOfWeek = firstDayOfWeek,
                            onStepMonth = vm::stepMonth,
                            onSelect = vm::selectDate,
                        )
                        SelectedDateLabel(vm.selectedDate)
                    }
                }
                when (state) {
                    is UiState.Loading -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().height(CalendarStatusHeight)) { LoadingBox() }
                    }
                    is UiState.Error -> item(key = "error") {
                        Box(Modifier.fillMaxWidth().height(CalendarStatusHeight)) {
                            ErrorBox(state.failure, vm::load)
                        }
                    }
                    is UiState.Success -> if (groups.isEmpty()) {
                        item(key = "empty") { EmptyDayNotice() }
                    } else {
                        timeSlots(groups, zone, scheduled, onAnimeClick, onToggleReminder)
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedDateLabel(date: LocalDate) {
    val device = LocalAppDeviceProfile.current
    Text(
        text = date.format(dateFmt),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = device.pagePadding, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyDayNotice() {
    Box(
        Modifier.fillMaxWidth().height(CalendarStatusHeight),
        contentAlignment = Alignment.Center,
    ) {
        Text("Nothing scheduled", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayTabs(today: LocalDate, selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val device = LocalAppDeviceProfile.current
    val listState = rememberLazyListState()
    val selectedIndex = (ChronoUnit.DAYS.between(today, selected) - DAY_STRIP_FIRST).toInt()
    // Coming back from the month on one of the last days the strip carries leaves the selected
    // chip off the right-hand edge, which reads as nothing being selected at all — the very state
    // the coercion above exists to avoid. Nothing can be measured before the first layout pass, so
    // wait for it, then scroll only if the chip is not already whole on screen: opening on today
    // should still start at the left with yesterday in view rather than jumping.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex !in 0 until DAY_STRIP_LENGTH) return@LaunchedEffect
        val layout = snapshotFlow { listState.layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
        val chip = layout.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        val whollyVisible = chip != null &&
            chip.offset >= layout.viewportStartOffset &&
            chip.offset + chip.size <= layout.viewportEndOffset
        if (!whollyVisible) listState.animateScrollToItem(selectedIndex)
    }
    LazyRow(
        state = listState,
        modifier = Modifier.focusGroup(),
        contentPadding = PaddingValues(horizontal = device.pagePadding, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(DAY_STRIP_LENGTH) { index ->
            val date = today.plusDays(DAY_STRIP_FIRST + index)
            val active = date == selected
            val fillFocus1 = rememberFocusFill()
            Column(
                modifier = Modifier
                    .width(88.dp)
                    .height(68.dp)
                    .focusFill(fillFocus1, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        fillFocus1.background(if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface),
                    )
                    .border(
                        width = 1.dp,
                        color = fillFocus1.content(if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline),
                        shape = RoundedCornerShape(14.dp),
                    )
                    .clickable { onSelect(date) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = relativeDayName(date, today) ?: date.format(dayFmt),
                    style = MaterialTheme.typography.labelLarge,
                    color = fillFocus1.content(if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface),
                    maxLines = 1,
                )
                Text(
                    text = date.format(shortDateFmt),
                    style = MaterialTheme.typography.labelSmall,
                    color = fillFocus1.content(if (active) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f)
                    else MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
    }
}

/** "Today", "Tomorrow" or "Yesterday" for the days either side of [today]; null for the rest. */
private fun relativeDayName(date: LocalDate, today: LocalDate): String? =
    when (ChronoUnit.DAYS.between(today, date)) {
        0L -> "Today"
        1L -> "Tomorrow"
        -1L -> "Yesterday"
        else -> null
    }

/**
 * A day back, a day on, and the way home to today, floating at the bottom of a phone screen.
 *
 * Both pickers sit at the top of the page, and in the month view they scroll away with the list —
 * finishing one day's episodes meant scrolling all the way back up to reach the next. The middle
 * names the day on screen, whose label has scrolled away by then too, and takes you back to today.
 */
@Composable
private fun DayStepper(
    selected: LocalDate,
    today: LocalDate,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val onToday = selected == today
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        shadowElevation = 6.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().height(DayStepperHeight).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dayStepFocus1 = rememberFocusFill()
            IconButton(
                colors = dayStepFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                onClick = { onSelect(selected.minusDays(1)) },
                enabled = canGoBack,
                modifier = Modifier.focusFill(dayStepFocus1, CircleShape),
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous day")
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(enabled = !onToday, onClickLabel = "Go to today") { onSelect(today) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "${relativeDayName(selected, today) ?: selected.format(dayFmt)}, " +
                        selected.format(shortDateFmt),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!onToday) {
                    Text(
                        text = "Back to today",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onPrimaryContainer.copy(alpha = 0.72f),
                        maxLines = 1,
                    )
                }
            }
            val dayStepFocus2 = rememberFocusFill()
            IconButton(
                colors = dayStepFocus2.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                onClick = { onSelect(selected.plusDays(1)) },
                enabled = canGoForward,
                modifier = Modifier.focusFill(dayStepFocus2, CircleShape),
            ) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Next day")
            }
        }
    }
}

@Composable
private fun ScheduleList(
    items: List<AiringSchedule>,
    scheduled: Set<String>,
    onAnimeClick: (Int) -> Unit,
    onToggleReminder: (AiringSchedule) -> Unit,
    bottomClearance: Dp,
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing scheduled", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val zone = ZoneId.systemDefault()
    val groups = remember(items) { items.sortedBy(AiringSchedule::airingAt).groupBy(AiringSchedule::airingAt) }
    // On TV the focused card comes to rest mid-screen, going up as well as down.
    TvCentredScrollSpec {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // 28dp of breathing room plus whatever the floating navigation capsule covers — it draws
            // over this list rather than displacing it, so the last slot has to reserve its own space.
            // The day stepper floats the same way, above the capsule.
            contentPadding = PaddingValues(
                bottom = AppChromeListBottomGap + LocalAppChromeBottomInset.current + bottomClearance,
            ),
        ) {
            timeSlots(groups, zone, scheduled, onAnimeClick, onToggleReminder)
        }
    }
}

/** The day's episodes, grouped by air time. Shared so both pickers list a day the same way. */
private fun LazyListScope.timeSlots(
    groups: Map<Long, List<AiringSchedule>>,
    zone: ZoneId,
    scheduled: Set<String>,
    onAnimeClick: (Int) -> Unit,
    onToggleReminder: (AiringSchedule) -> Unit,
) {
    groups.forEach { (airingAt, entries) ->
        item(key = airingAt) {
            TimeSlot(
                airingAt = airingAt,
                entries = entries,
                zone = zone,
                scheduled = scheduled,
                onAnimeClick = onAnimeClick,
                onToggleReminder = onToggleReminder,
            )
        }
    }
}

@Composable
private fun TimeSlot(
    airingAt: Long,
    entries: List<AiringSchedule>,
    zone: ZoneId,
    scheduled: Set<String>,
    onAnimeClick: (Int) -> Unit,
    onToggleReminder: (AiringSchedule) -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    val lineColor = MaterialTheme.colorScheme.outline
    val canvasColor = MaterialTheme.colorScheme.background
    val dotColor = if (airingAt * 1000L > System.currentTimeMillis()) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = device.pagePadding.toPx() + 8.dp.toPx()
                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                drawCircle(dotColor, 5.dp.toPx(), Offset(x, 20.dp.toPx()))
                drawCircle(
                    color = canvasColor,
                    radius = 2.dp.toPx(),
                    center = Offset(x, 20.dp.toPx()),
                )
            },
    ) {
        Text(
            text = Instant.ofEpochSecond(airingAt).atZone(zone).format(LocalTimeOfDayFormatter.current),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(
                start = device.pagePadding + 28.dp,
                top = 10.dp,
                bottom = 4.dp,
            ),
        )
        entries.forEach { item ->
            ScheduleCard(
                item = item,
                zone = zone,
                reminded = ReminderManager.id(item) in scheduled,
                onAnimeClick = onAnimeClick,
                onToggleReminder = onToggleReminder,
            )
        }
    }
}

@Composable
private fun ScheduleCard(
    item: AiringSchedule,
    zone: ZoneId,
    reminded: Boolean,
    onAnimeClick: (Int) -> Unit,
    onToggleReminder: (AiringSchedule) -> Unit,
) {
    val media = item.media ?: return
    val device = LocalAppDeviceProfile.current
    val upcoming = item.airingAt * 1000L > System.currentTimeMillis()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = device.pagePadding + 28.dp, end = device.pagePadding, top = 5.dp, bottom = 7.dp)
            .focusHighlight(RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .clickable { onAnimeClick(media.id) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = media.coverImage.best,
            contentDescription = media.title.preferred,
            modifier = Modifier
                .size(width = 62.dp, height = 82.dp)
                .clip(RoundedCornerShape(9.dp)),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = media.title.preferred,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Episode ${item.episode}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                media.format?.let { ScheduleTag(it.replace('_', ' ')) }
                media.seasonYear?.let { ScheduleTag(it.toString()) }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = Instant.ofEpochSecond(item.airingAt).atZone(zone).format(LocalTimeOfDayFormatter.current),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (upcoming) "UPCOMING" else "AIRED",
                style = MaterialTheme.typography.labelSmall,
                color = if (upcoming) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (upcoming) {
                val controlFocus4 = rememberFocusFill()
                IconButton(
                    colors = controlFocus4.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                    onClick = { onToggleReminder(item) },
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(36.dp)
                        .focusFill(controlFocus4, CircleShape),
                ) {
                    Icon(
                        imageVector = if (reminded) Icons.Filled.Notifications else Icons.Outlined.NotificationsNone,
                        contentDescription = if (reminded) "Remove reminder" else "Remind me",
                        tint = controlFocus4.content(if (reminded) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleTag(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}
