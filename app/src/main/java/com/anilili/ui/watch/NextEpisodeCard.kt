package com.anilili.ui.watch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill

/** How long the card counts down before it plays the next episode itself. */
internal const val NEXT_EPISODE_COUNTDOWN_SECONDS = 8

/**
 * How close to the end counts as "ending": the only stretch of a file the next episode is offered in.
 *
 * Kept short because this is an assertion that the episode is over, not an offer the viewer can
 * ignore: the card supersedes the skip button and starts a countdown, so asserting it a whole
 * minute early would put it over the last scene.
 */
internal const val NEXT_EPISODE_TAIL_WINDOW_MS = 30_000L

/**
 * The last stretch of a file in which the next episode is offered even over credits that have not
 * finished. A countdown has to start somewhere short of the last frame: the embed player has no
 * end-of-file event and only ever sees a position, so credits filed to the very end would otherwise
 * never let it advance at all.
 */
internal const val NEXT_EPISODE_FINAL_MS = 5_000L

/**
 * Why the next-episode card is being offered, or [NONE] if it is not. [AFTER_CREDITS] is the tail of
 * a file whose highlighted credits have finished; [ENDING] is the tail of one with no credits filed.
 */
internal enum class NextEpisodeTrigger { AFTER_CREDITS, ENDING, NONE }

/**
 * Whether the episode has reached the point where the next one should be offered.
 *
 * Only ever the end of the file. It used to be the *start* of the highlighted credits, so the card
 * and its countdown sat over the outro — exactly where "Skip Outro" belongs — and took the episode
 * away before whatever follows the credits (a next-episode preview, a post-credits scene) had
 * played. Viewers asked for the next episode "only at the end of the anime, not in the highlighted
 * outro", and that is what this is now.
 *
 * So the tail of the file is the window, as it always was without timings, and a credits window
 * ([outroWindowIsCredits]) can only push it later: while the playhead is still inside the
 * highlighted outro the card waits, until the outro finishes or [NEXT_EPISODE_FINAL_MS] is all that
 * is left. Because an outro can no longer bring the card forward, a wrong one costs nothing worse
 * than a card a few seconds late, which is why this no longer depends on the skip setting.
 */
internal fun nextEpisodeTrigger(
    positionMs: Long,
    durationMs: Long,
    outroStartMs: Long?,
    outroEndMs: Long?,
    tailWindowMs: Long = NEXT_EPISODE_TAIL_WINDOW_MS,
): NextEpisodeTrigger {
    if (durationMs <= 0L || positionMs < 0L) return NextEpisodeTrigger.NONE
    val remainingMs = durationMs - positionMs
    if (remainingMs !in 0L..tailWindowMs) return NextEpisodeTrigger.NONE
    // The same rule every other use of an outro applies: a window has to have both ends, run
    // forwards, and reach the end of the file, or it says nothing about where the credits are.
    if (!outroWindowIsCredits(outroStartMs, outroEndMs, durationMs)) return NextEpisodeTrigger.ENDING
    val creditsRolling = positionMs < outroEndMs!! && remainingMs > NEXT_EPISODE_FINAL_MS
    return if (creditsRolling) NextEpisodeTrigger.NONE else NextEpisodeTrigger.AFTER_CREDITS
}

/** What an embed tick should do about the next episode. */
internal enum class EmbedAutoAdvanceStep {
    /** Take it now. */
    ADVANCE,

    /** Not yet, and remember that the clock was seen short of the ending. */
    ARM,

    /** Not yet, and nothing learned. */
    WAIT,
}

/**
 * Whether an embed player's tick should take the next episode.
 *
 * The embed advances outright, with no card and no countdown to refuse — so unlike the native
 * player it cannot simply act on [trigger]. Someone resuming at 23:50 of a 24:00 file is inside the
 * tail window on the very first tick, and acting there would mean an episode a viewer had just
 * chosen to open was replaced before they saw a frame of it. [armed] is the difference between
 * watching the clock cross into the ending and merely finding it there, and only the first is an
 * episode that has been watched.
 *
 * [durationMs] is what makes [ARM] honest: it is zero until the page reports a video, so a tick
 * that has not reached one yet cannot claim to have seen a position before the credits.
 */
internal fun embedAutoAdvanceStep(
    autoplay: Boolean,
    hasNextEpisode: Boolean,
    armed: Boolean,
    trigger: NextEpisodeTrigger,
    durationMs: Long,
): EmbedAutoAdvanceStep = when {
    trigger == NextEpisodeTrigger.NONE ->
        if (durationMs > 0L) EmbedAutoAdvanceStep.ARM else EmbedAutoAdvanceStep.WAIT
    // Checked after the arming branch on purpose: a viewer who turns auto-play on midway through an
    // episode should get it at the end of that episode, not the one after.
    !autoplay || !hasNextEpisode || !armed -> EmbedAutoAdvanceStep.WAIT
    else -> EmbedAutoAdvanceStep.ADVANCE
}

/**
 * The card offering the next episode, with the countdown that will otherwise play it.
 *
 * It replaces what used to be an instant jump the moment the outro started: the episode simply
 * changed under the viewer, and there was no way to say no to it short of turning auto-play off
 * altogether. The countdown does the same thing a few seconds later and lets it be refused.
 *
 * [playNowFocusRequester] is how a TV hands the remote to the card as it appears. Without it the
 * ring stayed wherever it was, and refusing took Up to reach "Play now" and then Right to "Stay".
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun NextEpisodeCard(
    visible: Boolean,
    secondsRemaining: Int,
    nextEpisodeLabel: String,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    playNowFocusRequester: FocusRequester? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.86f))
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Up next",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.62f),
            )
            Text(
                text = nextEpisodeLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                // Left and Right only ever choose between the two answers. Let out of the row, they
                // walked on into the control row underneath, which is not an answer to the card.
                modifier = Modifier
                    .focusProperties {
                        exit = { direction ->
                            if (direction == FocusDirection.Left || direction == FocusDirection.Right) {
                                FocusRequester.Cancel
                            } else {
                                FocusRequester.Default
                            }
                        }
                    }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NextEpisodeAction(
                    text = if (secondsRemaining > 0) "Play now · ${secondsRemaining}s" else "Play now",
                    filled = true,
                    onClick = onPlayNow,
                    modifier = playNowFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                )
                NextEpisodeAction(text = "Stay", filled = false, onClick = onCancel)
            }
        }
    }
}

/**
 * One of the card's two actions.
 *
 * Focusable in its own right so a remote can reach the card at all: it is drawn above the control
 * row and a TV viewer has no other way to answer it.
 */
@Composable
private fun NextEpisodeAction(
    text: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val fillFocus1 = rememberFocusFill()
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = fillFocus1.content(Color.White),
        modifier = modifier
            .focusFill(fillFocus1, shape)
            .clip(shape)
            .background(fillFocus1.background(Color.White.copy(alpha = if (filled) 0.24f else 0.12f)), shape)
            .then(
                if (filled) {
                    Modifier
                } else {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.22f), shape)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .semantics { contentDescription = text },
    )
}
