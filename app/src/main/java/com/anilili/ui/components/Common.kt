package com.anilili.ui.components

import com.anilili.ui.theme.appCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anilili.data.library.resumeFor
import com.anilili.errors.AnililiFailure
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill

@Composable
fun LoadingBox(modifier: Modifier = Modifier, message: String? = null) {
    Box(
        modifier
            .fillMaxSize()
            .appCanvas(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WaterFillLogoIndicator(size = 88.dp)
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                )
            }
        }
    }
}

/**
 * A whole screen that failed to load.
 *
 * The code sits under the message rather than inside it, in the dim monospace the rest of the app
 * uses for codes. It is deliberately always shown: this is a full-screen dead end, which is exactly
 * where someone is about to describe the problem to someone else, and the code is the only part of
 * that description that can be matched against a diagnostics bundle afterwards.
 *
 * The retry button's label comes from the catalogue entry, so an error the app knows is not worth
 * retrying does not grow a Retry button just because the screen has room for one.
 *
 * Anything technical — the exception type, the host, the platform's own error name — waits behind
 * **Details**, and only appears at all when there is something to show. That split is the point: the
 * sentence has to be readable by whoever is holding the device, and the detail has to be reachable by
 * whoever is trying to fix it, and before this the screen printed whichever of the two the exception
 * happened to carry.
 */
@Composable
fun ErrorBox(failure: AnililiFailure, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    var detailsVisible by remember(failure) { mutableStateOf(false) }
    if (detailsVisible) {
        ErrorDetailsDialog(failure = failure, onDismiss = { detailsVisible = false })
    }
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = failure.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = failure.code,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 6.dp),
            )
            if (onRetry != null) {
                val controlFocus1 = rememberFocusFill()
                Button(
                    colors = controlFocus1.buttonColors(ButtonDefaults.buttonColors()),
                    onClick = onRetry,
                    modifier = Modifier.padding(top = 16.dp).focusFill(controlFocus1, RoundedCornerShape(24.dp)),
                ) {
                    Text(failure.action?.label ?: "Try again")
                }
            }
            if (failure.detail != null) {
                val controlFocus2 = rememberFocusFill()
                TextButton(
                    colors = controlFocus2.buttonColors(ButtonDefaults.textButtonColors()),
                    onClick = { detailsVisible = true },
                    modifier = Modifier.padding(top = 4.dp).focusFill(controlFocus2, RoundedCornerShape(20.dp)),
                ) {
                    Text("Details")
                }
            }
        }
    }
}

/**
 * Thin watched-progress bar for episode rows/chips: full for episodes already passed, partial
 * for the one in progress. Draws nothing when there is no progress to show.
 */
@Composable
fun WatchProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    if (fraction <= 0.01f) return
    Box(
        modifier
            .height(3.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(Color.White.copy(alpha = 0.25f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0.04f, 1f))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * How much of [episodeNumber] counts as watched.
 *
 * Only episodes actually played are filled in. This used to answer "1f" for everything numbered
 * below the episode in progress, which meant opening episode 94 of a long series drew a full
 * progress bar under 1–93 and a viewer could not tell what they had skipped.
 *
 * A part-watched episode draws its own share whether or not it is the one the row points at: the
 * clock is kept per episode, so leaving three of them half finished shows three half bars.
 */
fun episodeWatchFraction(entry: com.anilili.data.library.HistoryEntry?, episodeNumber: Double): Float = when {
    entry == null -> 0f
    episodeNumber in entry.watchedEpisodes -> 1f
    else -> entry.resumeFor(episodeNumber)
        ?.let { if (it.durationMs > 0) (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) else 0f }
        ?: 0f
}

/**
 * Whether episode [episodeNumber] carries a watched mark, and so which way a press on its eye moves.
 *
 * Deliberately not `episodeWatchFraction(…) >= 0.999f`, which is what the profile and the in-player
 * list draw with and is the *looser* test: that one also fires on an episode merely resumed to
 * within a second of its end, which is a different thing from a mark. The eye writes through
 * [com.anilili.data.library.historyAfterEpisodeToggled], and that flips membership of
 * [com.anilili.data.library.HistoryEntry.watchedEpisodes] and nothing else — so an eye filled on
 * the fraction would offer to unmark an episode that is not marked, and the press would be dropped
 * as a no-op. The state the eye draws and the state the press flips have to be the same field.
 */
fun episodeMarkedWatched(entry: com.anilili.data.library.HistoryEntry?, episodeNumber: Double): Boolean =
    entry?.watchedEpisodes?.contains(episodeNumber) == true

/**
 * Where a part-watched episode was left, as a clock.
 *
 * Shared by every offline list so "Resume at 12:04" reads identically wherever a downloaded
 * episode is shown.
 */
fun formatPlaybackPosition(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Shared filler marker for every episode picker. A full word is intentional: the old single
 * letter/dot treatment was easy to miss on a television and ambiguous to new viewers.
 */
@Composable
fun EpisodeFillerBadge(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Text(
        text = "FILLER",
        color = MaterialTheme.colorScheme.onTertiary,
        fontSize = if (compact) 8.sp else 9.sp,
        lineHeight = if (compact) 9.sp else 10.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.96f))
            .padding(horizontal = if (compact) 4.dp else 6.dp, vertical = 2.dp),
    )
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (actionLabel != null && onAction != null) {
            val controlFocus3 = rememberFocusFill()
            TextButton(
                colors = controlFocus3.buttonColors(ButtonDefaults.textButtonColors()),
                onClick = onAction,
                modifier = Modifier.focusFill(controlFocus3, RoundedCornerShape(20.dp)),
            ) { Text(actionLabel) }
        }
    }
}

/**
 * AniList's `popularity` as a viewer reads it: 125K, 1.2M. The figure is how many accounts have the
 * title on a list in any status (planning included), which is also what MAL calls "members" — a
 * reach measure beside the score, not a rank, so it must never be printed with a leading "#".
 */
fun compactMemberCount(value: Int): String = when {
    value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000f)
    value >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", value / 1_000f)
    else -> value.toString()
}.replace(".0K", "K").replace(".0M", "M")
