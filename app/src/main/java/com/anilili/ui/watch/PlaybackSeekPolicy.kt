package com.anilili.ui.watch

import android.view.KeyEvent

internal const val TV_SEEK_STEP_MS = 10_000L
internal const val TV_SEEK_COALESCE_MS = 180L
internal const val SEEK_ERROR_RECOVERY_WINDOW_MS = 8_000L

/**
 * How long after a Left/Right press the next one still counts as part of the same run.
 *
 * Long enough to cover the pause between deliberate presses, short enough that coming back to the
 * remote minutes later starts again at the smallest step rather than a minute a press.
 */
internal const val TV_DPAD_SEEK_RUN_GAP_MS = 1_200L

/** How long the seek readout stays up after the last press before the picture is left alone. */
internal const val TV_SEEK_INDICATOR_LINGER_MS = 1_400L

/**
 * The fastest the timeline will accept presses, which is what makes holding the key safe.
 *
 * Android repeats a held key roughly every 50ms. Counting every one of those as a press climbs the
 * acceleration almost instantly: a single second of holding would have travelled something like
 * fourteen minutes, most of the way through an episode, from what felt to the viewer like a firm
 * press. Throttled to roughly the rate a person can tap, holding travels quickly but stays
 * somewhere a viewer would recognise, and deliberate taps are unaffected.
 */
internal const val TV_DPAD_SEEK_MIN_GAP_MS = 200L

/** Whether a press this soon after the last accepted one should move the timeline at all. */
internal fun tvDpadSeekAccepts(msSinceLastPress: Long): Boolean =
    msSinceLastPress >= TV_DPAD_SEEK_MIN_GAP_MS

/**
 * The ordinal of a Left/Right press within its run, which is what decides how far it travels.
 *
 * Kept apart from the player so the acceleration can be reasoned about without a remote: presses
 * that keep coming build on each other, and a gap ends the run.
 */
internal fun tvDpadSeekPresses(previousPresses: Int, msSinceLastPress: Long): Int =
    if (previousPresses <= 0 || msSinceLastPress > TV_DPAD_SEEK_RUN_GAP_MS) 1 else previousPresses + 1

/**
 * How many places the number keys divide an episode into. Ten, so the digit is the tenth it lands on.
 */
internal const val TV_NUMBER_SEEK_STOPS = 10L

/**
 * Where a number key asks the episode to go: 1 a tenth of the way in, 9 nine tenths, 0 the start.
 *
 * The convention every desktop and browser player already carries, and the only seek on a remote
 * that names a spot outright. Left/Right nudge from wherever the picture is — accelerating, but
 * still a run of presses to cross a 24-minute episode — so "back to the beginning", "somewhere in
 * the middle" and "near the end" had no single press behind them.
 *
 * Null for anything that is not a digit, and for a stream whose duration is not known yet: every
 * fraction of an unknown episode is zero, so the whole keypad would jump to the start.
 */
internal fun tvNumberSeekTargetMs(keyCode: Int, durationMs: Long): Long? {
    if (durationMs <= 0L) return null
    val digit = numberKeyDigit(keyCode) ?: return null
    return (durationMs * digit / TV_NUMBER_SEEK_STOPS).coerceIn(0L, durationMs)
}

/**
 * The digit a key carries, or null if it carries none.
 *
 * Both ranges are contiguous in [KeyEvent]. The keypad half is not redundant: a TV is not only ever
 * driven by its own remote — keyboard-style remotes and air-mouse dongles are common on boxes, and
 * those report their number pad as `NUMPAD_*`.
 */
private fun numberKeyDigit(keyCode: Int): Int? = when (keyCode) {
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
    else -> null
}

/**
 * Repeated remote presses build on the last pending target instead of the player's stale position.
 * The duration is optional because some streams do not expose it until their first segment loads.
 */
internal fun tvSeekTargetMs(
    currentPositionMs: Long,
    durationMs: Long,
    offsetMs: Long,
): Long {
    val current = currentPositionMs.coerceAtLeast(0L)
    val unboundedTarget = when {
        offsetMs > 0L && current > Long.MAX_VALUE - offsetMs -> Long.MAX_VALUE
        offsetMs < 0L && current < -offsetMs -> 0L
        else -> current + offsetMs
    }
    val maximum = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
    return unboundedTarget.coerceIn(0L, maximum)
}

/**
 * Media3 reserves 2xxx error codes for I/O failures. A single one directly after a user seek is
 * commonly a cancelled/stale segment request, so retry the same media item before changing server.
 */
internal fun shouldRecoverSeekError(
    errorCode: Int,
    elapsedSinceSeekMs: Long,
    recoveryAlreadyAttempted: Boolean,
): Boolean = !recoveryAlreadyAttempted &&
    elapsedSinceSeekMs in 0L..SEEK_ERROR_RECOVERY_WINDOW_MS &&
    errorCode in 2_000..2_999

// The status a bad-status PlaybackException hides used to be pasted onto its message here, because
// SourceFailureCooldowns.record could only find it by searching that message for "429" — its rate-
// limit and refusal branches were otherwise unreachable from playback, and a stream host that
// rate-limited every episode of every title earned the same flat five minutes as one bad file. (A
// tester's whole session on 2026-09-10 went that way: `pewe` resolved perfectly each time while
// `hls.anidb.app` answered 429 to all of it.) The status now travels as the failure's platform code
// — `PLAY-02/h429` — so the cooldown reads it structurally and no wording can lose it again.
