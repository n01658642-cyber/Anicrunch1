package com.anilili.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anilili.ui.theme.Midnight
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * How long until the next episode airs, counted down a second at a time.
 *
 * A date and time is the honest answer to "when", and a poor answer to "how long" — reading
 * "Wed, Aug 27 at 1:30 AM" means working out what day it is now and how far off that is, in a
 * timezone the viewer has to trust was converted. A running clock answers the question that was
 * actually being asked.
 */
@Composable
fun NextEpisodeCountdown(
    airingAtEpochSeconds: Long,
    episode: Int?,
    modifier: Modifier = Modifier,
) {
    val nowSeconds = rememberTickingEpochSeconds(until = airingAtEpochSeconds)
    val remaining = (airingAtEpochSeconds - nowSeconds).coerceAtLeast(0L)

    val heading = when {
        remaining <= 0L -> episode?.let { "EPISODE $it IS OUT" } ?: "THE NEXT EPISODE IS OUT"
        episode != null -> "EPISODE $episode WILL BE RELEASED IN"
        else -> "THE NEXT EPISODE WILL BE RELEASED IN"
    }
    val value = if (remaining <= 0L) "AVAILABLE NOW" else formatAiringCountdown(remaining)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Midnight.Panel2)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            // One announcement rather than five, and phrased the way it would be said aloud: a
            // screen reader walking "EPISODE 20 WILL BE RELEASED IN" then "1 DAY 3 HRS…" reads the
            // abbreviations letter by letter.
            .clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = spokenAiringCountdown(remaining, episode)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = heading,
            style = MaterialTheme.typography.labelSmall,
            color = Midnight.Text3,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = Midnight.Text,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The wall clock in epoch seconds, ticking once a second until [until] and then standing still.
 *
 * Seconds, because that is the resolution any countdown here shows; a millisecond clock would wake
 * the frame scheduler sixty times more often for a digit that changes once. Cancelled with the
 * composition, so a page scrolled away from — or a card scrolled off a strip — stops ticking.
 * Aligning to the next whole second keeps the display from sitting a fraction behind the wall
 * clock, which is visible as a digit that occasionally appears to skip.
 */
@Composable
fun rememberTickingEpochSeconds(until: Long): Long {
    var nowSeconds by remember(until) { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(until) {
        while (true) {
            val nowMillis = System.currentTimeMillis()
            nowSeconds = nowMillis / 1000L
            if (until - nowSeconds <= 0L) return@LaunchedEffect
            delay(1_000L - (nowMillis % 1_000L))
        }
    }
    return nowSeconds
}

/**
 * `02:15:08`, or `6d 04:15` once a day or more is left — the countdown in the width of an episode
 * card.
 *
 * The spelled-out form ([formatAiringCountdown]) is twice as wide as a 204dp card at a size legible
 * from a sofa. Seconds are dropped while days are shown: at that distance they are motion without
 * information, and the line stays short enough to sit on one row.
 */
internal fun formatCompactCountdown(remainingSeconds: Long): String {
    val total = remainingSeconds.coerceAtLeast(0L)
    val days = total / 86_400L
    val hours = (total % 86_400L) / 3_600L
    val minutes = (total % 3_600L) / 60L
    val seconds = total % 60L
    return if (days > 0L) {
        "%dd %02d:%02d".format(Locale.US, days, hours, minutes)
    } else {
        "%02d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    }
}

/**
 * `1 DAY 3 HRS 28 MINS 5 SECS`, dropping units larger than the time left.
 *
 * Leading zeroes are dropped rather than padded: "0 DAYS 0 HRS 4 MINS" spends most of its width
 * saying nothing, and the units that remain are the ones changing. Everything below the largest
 * shown unit stays, including zeroes, so the line keeps a stable width for its final minute rather
 * than shortening under the reader.
 */
internal fun formatAiringCountdown(remainingSeconds: Long): String {
    val total = remainingSeconds.coerceAtLeast(0L)
    val days = total / 86_400L
    val hours = (total % 86_400L) / 3_600L
    val minutes = (total % 3_600L) / 60L
    val seconds = total % 60L

    val parts = buildList {
        if (days > 0L) add(days to if (days == 1L) "DAY" else "DAYS")
        if (days > 0L || hours > 0L) add(hours to if (hours == 1L) "HR" else "HRS")
        if (days > 0L || hours > 0L || minutes > 0L) add(minutes to if (minutes == 1L) "MIN" else "MINS")
        add(seconds to if (seconds == 1L) "SEC" else "SECS")
    }
    return parts.joinToString(" ") { (amount, unit) -> "$amount $unit" }
}

/** The same countdown as a sentence, for anything that reads the screen aloud. */
internal fun spokenAiringCountdown(remainingSeconds: Long, episode: Int?): String {
    val subject = episode?.let { "Episode $it" } ?: "The next episode"
    if (remainingSeconds <= 0L) return "$subject is available now"
    return "$subject airs in ${spokenDuration(remainingSeconds)}"
}

/**
 * `1 day, 3 hours, 28 minutes`, or `less than a minute`.
 *
 * Seconds are deliberately left out: a polite live region re-announces on change, and a spoken
 * clock that repeats every second talks over everything else on the page.
 */
internal fun spokenDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0L)
    val days = total / 86_400L
    val hours = (total % 86_400L) / 3_600L
    val minutes = (total % 3_600L) / 60L
    val parts = buildList {
        if (days > 0L) add("$days ${if (days == 1L) "day" else "days"}")
        if (hours > 0L) add("$hours ${if (hours == 1L) "hour" else "hours"}")
        if (minutes > 0L) add("$minutes ${if (minutes == 1L) "minute" else "minutes"}")
    }
    return if (parts.isEmpty()) "less than a minute" else parts.joinToString(", ")
}
