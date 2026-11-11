package com.anilili.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anilili.playback.BulkDownloadOutcome
import com.anilili.playback.BulkEpisodeDownloads
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill

/**
 * Status line for a running batch download.
 *
 * Batches take minutes and queue their episodes one at a time, so without this the only feedback
 * is downloads quietly appearing in My List — easily read as nothing happening.
 *
 * Shared by the player and the detail page because the queue itself is shared: a batch started
 * from one has to be visible, and stoppable, from the other. Renders nothing when no batch has
 * run, so both callers can place it unconditionally.
 */
@Composable
fun BulkDownloadStatus(modifier: Modifier = Modifier) {
    val progress by BulkEpisodeDownloads.progress.collectAsState()
    val state = progress ?: return

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = when (state.outcome) {
                BulkDownloadOutcome.RUNNING -> buildString {
                    append("Downloading ${state.done + 1} of ${state.total}")
                    state.current?.let { append(" · episode $it") }
                }
                BulkDownloadOutcome.FINISHED ->
                    "Queued ${state.queued} of ${state.total}" +
                        if (state.failed > 0) " · ${state.failed} had no downloadable source" else ""
                BulkDownloadOutcome.CANCELLED -> "Stopped after ${state.queued} of ${state.total}"
                BulkDownloadOutcome.OUT_OF_SPACE ->
                    "Out of space after ${state.queued} of ${state.total}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (state.outcome == BulkDownloadOutcome.OUT_OF_SPACE) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        val controlFocus1 = rememberFocusFill()
        TextButton(
            colors = controlFocus1.buttonColors(ButtonDefaults.textButtonColors()),
            onClick = { if (state.isRunning) BulkEpisodeDownloads.cancel() else BulkEpisodeDownloads.dismiss() },
            modifier = Modifier.focusFill(controlFocus1, RoundedCornerShape(20.dp)),
        ) {
            Text(if (state.isRunning) "Stop" else "Dismiss")
        }
    }
}
