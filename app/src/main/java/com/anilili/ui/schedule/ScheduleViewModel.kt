package com.anilili.ui.schedule

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anilili.data.AppGraph
import com.anilili.data.model.AiringSchedule
import com.anilili.data.model.ScheduleDayCount
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.toFailure
import com.anilili.ui.UiState
import com.anilili.ui.rethrowIfCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class ScheduleViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _state = MutableStateFlow<UiState<List<AiringSchedule>>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    /** The day the list below the picker is showing. Both pickers write it; only they move it. */
    var selectedDate by mutableStateOf(LocalDate.now(ZoneId.systemDefault()))
        private set

    /** The month the calendar grid is drawing, which is not always [selectedDate]'s own month. */
    var visibleMonth by mutableStateOf(YearMonth.from(LocalDate.now(ZoneId.systemDefault())))
        private set

    /**
     * Episode counts for the days the grid is currently drawing. Kept apart from [state] because
     * the two fail independently: a month whose badges never arrive is still a usable date picker,
     * and a day that fails to load must not blank the calendar the viewer needs to leave it by.
     */
    private val _dayCounts = MutableStateFlow<Map<LocalDate, ScheduleDayCount>>(emptyMap())
    val dayCounts = _dayCounts.asStateFlow()
    private val _countsLoading = MutableStateFlow(false)
    val countsLoading = _countsLoading.asStateFlow()

    private var countsJob: Job? = null
    /** The last grid asked for, so a pull-to-refresh can renew its badges as well as the day. */
    private var countDays: List<LocalDate> = emptyList()
    private var revalidateJob: Job? = null

    init { load() }

    fun selectDate(date: LocalDate) {
        if (date == selectedDate) {
            // Re-tapping the selected day is still how you follow it out of the month it is in,
            // which is what a grid's leading and trailing cells are for.
            visibleMonth = YearMonth.from(date)
            return
        }
        selectedDate = date
        visibleMonth = YearMonth.from(date)
        load()
    }

    fun showMonth(month: YearMonth) {
        if (month == visibleMonth) return
        visibleMonth = month
    }

    fun stepMonth(months: Long) = showMonth(visibleMonth.plusMonths(months))

    /**
     * Loads the badge for each of [days], which the calendar passes in because the run of dates a
     * grid covers depends on where the viewer's week starts — a locale question the screen answers
     * and this does not.
     */
    fun loadCounts(days: List<LocalDate>, force: Boolean = false) {
        if (days.isEmpty()) return
        countDays = days
        countsJob?.cancel()
        countsJob = viewModelScope.launch {
            _countsLoading.value = true
            try {
                _dayCounts.value = repo.scheduleCounts(days, force = force)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                // Nothing is shown for this: the grid without its numbers is the date picker the
                // viewer asked for, and an error banner over a working calendar is noise.
                DiagnosticsLog.throwable("Schedule calendar counts unavailable", e)
                _dayCounts.value = emptyMap()
            } finally {
                _countsLoading.value = false
            }
        }
    }

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            if (force && _state.value is UiState.Success) _isRefreshing.value = true else _state.value = UiState.Loading
            try {
                _state.value = UiState.Success(repo.schedule(selectedDate, force = force))
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _state.value = UiState.Error(
                    e.toFailure(
                        AnililiError.SCHEDULE_LOAD_FAILED,
                        attributes = mapOf("date" to selectedDate.toString()),
                    ),
                )
            } finally {
                _isRefreshing.value = false
            }
            confirmToday(selectedDate, force)
        }
    }

    /**
     * Checks today against AniList behind the render that already happened.
     *
     * Only today, and only when the published month was what answered: every other day of the month
     * stays free, and the one people are about to set a reminder on is the one where an airing time
     * moved since the nightly build would be felt. A failure here leaves the day that is already on
     * screen exactly where it is — this confirms a good answer rather than producing one.
     */
    private fun confirmToday(date: LocalDate, force: Boolean) {
        if (date != LocalDate.now(ZoneId.systemDefault())) return
        revalidateJob?.cancel()
        revalidateJob = viewModelScope.launch {
            try {
                val live = repo.scheduleRevalidated(date, force = force) ?: return@launch
                // The viewer may have moved on while this was in flight; their choice wins.
                if (selectedDate == date) _state.value = UiState.Success(live)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                DiagnosticsLog.throwable("Live check for today unavailable, keeping published day", e)
            }
        }
    }

    fun refresh() {
        load(force = true)
        if (countDays.isNotEmpty()) loadCounts(countDays, force = true)
    }
}
