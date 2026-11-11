package com.anilili.playback

import android.os.SystemClock
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The player's sleep timer: pause after a set time, or once the episode playing now has ended.
 *
 * It exists for the viewer who falls asleep with auto-play on. Nothing else ever stops an evening
 * of auto-play, so the episodes ran on all night with the screen held awake and the battery ran out.
 *
 * It is process-wide rather than owned by a player surface, because it spans the viewer's evening
 * and not a single episode. Auto-play replaces the episode many times before the timer fires, and a
 * change of provider replaces the whole surface, native for embed or the other way round. Neither
 * may reset the timer. Each player only listens: [PlaybackService] fades and pauses the native
 * player, which keeps working with the app in picture-in-picture, and the embed pauses its own page.
 *
 * Nothing keeps it between launches. A sleep timer that switches itself on is a sleep timer that
 * pauses someone who is wide awake.
 */
object SleepTimer {
    sealed interface State {
        data object Off : State

        /** [minutes] is the choice as made; [extend] moves the deadline but leaves it alone. */
        data class Counting(val minutes: Int, val deadlineElapsedMs: Long) : State

        data object EndOfEpisode : State
    }

    /** The durations the player offers, in minutes. */
    val DURATIONS_MINUTES: List<Int> = listOf(15, 30, 45, 60)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var countdown: Job? = null

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _volume = MutableStateFlow(1f)

    /**
     * A multiplier for the player's volume. It is 1, except over the last [SLEEP_FADE_MS] of a
     * countdown, where it falls to 0. That way the pause arrives as quiet and not as a sudden
     * silence, which is a noise of its own and can wake the sleeper the timer was set for.
     */
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _expiries = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** One event each time a countdown runs out: every player that is playing pauses. */
    val expiries: SharedFlow<Unit> = _expiries.asSharedFlow()

    fun start(minutes: Int) {
        require(minutes > 0) { "Sleep timer needs a positive duration" }
        val deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
        DiagnosticsLog.event("SleepTimer start minutes=$minutes")
        _state.value = State.Counting(minutes, deadline)
        runCountdown(deadline)
    }

    fun pauseAtEndOfEpisode() {
        DiagnosticsLog.event("SleepTimer end-of-episode")
        stopCountdown()
        _state.value = State.EndOfEpisode
    }

    fun cancel() {
        if (_state.value == State.Off) return
        DiagnosticsLog.event("SleepTimer cancelled")
        stopCountdown()
        _state.value = State.Off
    }

    /** Pushes a running countdown back by [minutes]: the "keep watching" answer to the warning. */
    fun extend(minutes: Int) {
        val counting = _state.value as? State.Counting ?: return
        val deadline = counting.deadlineElapsedMs.coerceAtLeast(SystemClock.elapsedRealtime()) +
            minutes * 60_000L
        DiagnosticsLog.event("SleepTimer extended minutes=$minutes")
        _state.value = counting.copy(deadlineElapsedMs = deadline)
        runCountdown(deadline)
    }

    /**
     * Called where an episode reaches its end, and where the next one would start by itself.
     * Returns true when the timer was waiting for that moment. In that case the caller must not
     * start the next episode: this end is where the viewer asked playback to stop. The timer
     * switches off in the same call, so one episode end can never be claimed twice.
     */
    fun consumeEpisodeEnd(): Boolean {
        if (_state.value != State.EndOfEpisode) return false
        DiagnosticsLog.event("SleepTimer end-of-episode reached; holding the next episode back")
        _state.value = State.Off
        return true
    }

    /** Time left on a countdown, or null when the timer is not counting down. */
    fun remainingMs(state: State = _state.value): Long? =
        (state as? State.Counting)?.let {
            (it.deadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }

    private fun stopCountdown() {
        countdown?.cancel()
        countdown = null
        _volume.value = 1f
    }

    private fun runCountdown(deadline: Long) {
        stopCountdown()
        countdown = scope.launch {
            while (true) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0L) break
                _volume.value = sleepFadeLevel(left)
                // One long sleep up to the fade, then short steps through it. The clock is read
                // again on each pass, so a coroutine that wakes late never ends the fade early.
                delay(if (left > SLEEP_FADE_MS) left - SLEEP_FADE_MS else SLEEP_FADE_STEP_MS)
            }
            _volume.value = 0f
            DiagnosticsLog.event("SleepTimer expired; pausing playback")
            _state.value = State.Off
            _expiries.tryEmit(Unit)
            // The players pause on the event. The volume comes back up only after that, so the
            // resume button plays at the viewer's level and no burst of sound gets out first.
            delay(SLEEP_VOLUME_RESTORE_DELAY_MS)
            _volume.value = 1f
        }
    }
}

internal const val SLEEP_FADE_MS = 8_000L
private const val SLEEP_FADE_STEP_MS = 100L
private const val SLEEP_VOLUME_RESTORE_DELAY_MS = 1_500L

/** How long before the pause the player says it is coming, while there is still time to stop it. */
internal const val SLEEP_WARNING_MS = 60_000L

/** The volume multiplier with [remainingMs] left: 1 until the fade, then a straight fall to 0. */
internal fun sleepFadeLevel(remainingMs: Long): Float =
    (remainingMs.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f)

/** "23:41", or "1:02:05" when an hour or more is left, rounded up so 0:00 means it has fired. */
internal fun formatSleepRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(java.util.Locale.ROOT, hours, minutes, seconds)
    } else {
        "%d:%02d".format(java.util.Locale.ROOT, minutes, seconds)
    }
}
