package com.anilili.ui.schedule


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anilili.data.model.ScheduleDayCount
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")

/**
 * The days a month's grid draws, including the neighbouring days that fill out its first and last
 * weeks. Five rows for most months, six when a long month starts late in the week — the run is
 * whatever it takes, rather than a fixed 42, so a month that fits in five weeks is not fetched or
 * drawn as six.
 *
 * [firstDayOfWeek] is the viewer's, not Monday: the grid has to line up with the week they read.
 */
internal fun monthGridDays(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate> {
    val first = month.atDay(1)
    val lead = (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val cells = ((lead + month.lengthOfMonth() + 6) / 7) * 7
    val start = first.minusDays(lead.toLong())
    return (0 until cells).map { start.plusDays(it.toLong()) }
}

@Composable
internal fun MonthCalendar(
    month: YearMonth,
    days: List<LocalDate>,
    selected: LocalDate,
    today: LocalDate,
    counts: Map<LocalDate, ScheduleDayCount>,
    countsLoading: Boolean,
    firstDayOfWeek: DayOfWeek,
    onStepMonth: (Long) -> Unit,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = device.pagePadding, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .padding(vertical = 6.dp)
            // One group for the whole grid, so a remote leaving it does not have to walk every
            // remaining cell on the way out.
            .focusGroup(),
    ) {
        MonthHeader(month, countsLoading, onStepMonth)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
            repeat(7) { index ->
                val day = firstDayOfWeek.plus(index.toLong())
                Text(
                    text = day.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        days.chunked(7).forEach { week ->
            // The week holding focus draws over the others: a focused cell's ring reaches past its
            // own edge, and the week below, drawn later, covered the ring's bottom. A cell's own
            // lift only ranks it among the days of its week.
            var weekFocused by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .onFocusChanged { weekFocused = it.hasFocus }
                    .zIndex(if (weekFocused) 1f else 0f),
            ) {
                week.forEach { date ->
                    DayCell(
                        date = date,
                        count = counts[date],
                        inMonth = YearMonth.from(date) == month,
                        selected = date == selected,
                        today = date == today,
                        onSelect = onSelect,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(month: YearMonth, countsLoading: Boolean, onStepMonth: (Long) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val controlFocus1 = rememberFocusFill()
        IconButton(
            colors = controlFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
            onClick = { onStepMonth(-1) },
            modifier = Modifier.focusFill(controlFocus1, CircleShape),
        ) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = month.format(monthFmt),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            // Sits beside the month rather than over the grid: the badges arrive after the cells
            // do, and a spinner across the dates would hide the picker while it waits.
            if (countsLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterEnd).size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val controlFocus2 = rememberFocusFill()
        IconButton(
            colors = controlFocus2.iconButtonColors(IconButtonDefaults.iconButtonColors()),
            onClick = { onStepMonth(1) },
            modifier = Modifier.focusFill(controlFocus2, CircleShape),
        ) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    count: ScheduleDayCount?,
    inMonth: Boolean,
    selected: Boolean,
    today: Boolean,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val shape = RoundedCornerShape(10.dp)
    val dimmed = if (inMonth) 1f else 0.45f
    val dayColor = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        today -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val fillFocus1 = rememberFocusFill()
    Column(
        modifier = modifier
            .height(if (device.isTv) 66.dp else 54.dp)
            .padding(2.dp)
            .focusFill(fillFocus1, shape)
            .clip(shape)
            .background(fillFocus1.background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface))
            .border(
                width = 1.dp,
                color = fillFocus1.content(when {
                    selected -> MaterialTheme.colorScheme.primary
                    today -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                }),
                shape = shape,
            )
            .clickable { onSelect(date) }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (today || selected) FontWeight.Bold else FontWeight.Normal,
            color = fillFocus1.content(dayColor.copy(alpha = dayColor.alpha * dimmed)),
            maxLines = 1,
        )
        // A day with nothing on it leaves the space empty rather than printing a nought, so a
        // glance down the grid reads as the shape of the week.
        val episodes = count?.episodes ?: 0
        if (episodes > 0) {
            Text(
                text = if (count?.atLeast == true) "$episodes+" else episodes.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = fillFocus1.content((
                    if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                    ).let { it.copy(alpha = it.alpha * dimmed) }),
                maxLines = 1,
            )
        }
    }
}
