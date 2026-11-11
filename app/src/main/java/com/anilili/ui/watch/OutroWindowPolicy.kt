package com.anilili.ui.watch

/**
 * How far into the file an outro has to reach before it is treated as the credits.
 *
 * Deliberately loose, because a real ending frequently stops well short of the last frame: a
 * next-episode preview, a sponsor card or a post-credits scene all legitimately sit after the
 * credits. A quarter of the file is more room than any of those need, and still nowhere near enough
 * to admit a window from the first half.
 */
internal const val OUTRO_CREDITS_TAIL_FRACTION = 0.75

/**
 * Whether an outro window describes the credits of the file actually being played.
 *
 * Every use of an outro is an assertion that the episode is nearly over: it offers "Skip Outro",
 * auto-skip jumps it, and the next episode is held back until it has finished. So a window that
 * closes long before the file does cannot be acted on — credits run to the end of an episode, and
 * one that does not reach the end is either a submission made against a different encode or simply
 * a wrong one.
 *
 * Both exist. AniSkip files Bleach episode 274 — a 24:05 encode, which is the length it was asked
 * for — with an `ed` of 4:46–6:16, alongside a `recap` of 2:40–4:10 and an `op` of 0:24–1:54: three
 * ninety-second windows evenly spaced across the first six minutes. Believed, that ed put "Next
 * Episode" over the fifth minute of the episode (what the button said then), and with auto-play on
 * it took the episode away.
 * Only the providers that carry timings of their own hid it, which is what made it look like one
 * source's fault.
 *
 * Rejecting a window costs little: both players fall back to [NEXT_EPISODE_TAIL_WINDOW_MS], the
 * tail of the file, which is a fact about the file rather than a claim about it.
 */
internal fun outroWindowIsCredits(
    outroStartMs: Long?,
    outroEndMs: Long?,
    durationMs: Long,
): Boolean {
    if (outroStartMs == null || outroEndMs == null || outroStartMs < 0L) return false
    // A window has to run forwards; a zero-length or inverted one locates nothing.
    if (outroEndMs <= outroStartMs) return false
    // An unknown duration is a stream that has not loaded yet, and nothing can be judged against
    // it. Waiting is safe: the players re-read this as the duration arrives.
    if (durationMs <= 0L) return false
    return outroEndMs >= durationMs * OUTRO_CREDITS_TAIL_FRACTION
}

/**
 * Where "Skip Outro" lands: the end of the highlighted credits, or the end of the file for a window
 * filed past it. Credits that run to the last frame therefore end the episode, and the next one
 * follows the way it does at any other ending — the skip itself never jumps episodes.
 */
internal fun outroSkipTargetMs(outroEndMs: Long, durationMs: Long): Long =
    if (durationMs > 0L) outroEndMs.coerceAtMost(durationMs) else outroEndMs
