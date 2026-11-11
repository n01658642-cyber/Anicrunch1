package com.anilili.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anilili.playback.EpisodeDownloadStage
import com.anilili.playback.EpisodeDownloadUi
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill

/** What a press on an episode's download control should do, given what that episode already has. */
enum class EpisodeDownloadAction {
    /** The press has been taken and the source is still being resolved; another does nothing. */
    WAIT,
    START,
    RETRY,
    CANCEL,
    REMOVE,
}

/**
 * The single meaning of a download control, derived once rather than re-decided by each screen.
 *
 * [pending] is the gap this exists to cover: between the press and a provider answering there is
 * no download to describe, and for a slow source that is twenty seconds of a button that would
 * otherwise still read "download".
 */
fun episodeDownloadAction(state: EpisodeDownloadUi?, pending: Boolean): EpisodeDownloadAction = when {
    pending -> EpisodeDownloadAction.WAIT
    state == null -> EpisodeDownloadAction.START
    state.stage == EpisodeDownloadStage.FAILED -> EpisodeDownloadAction.RETRY
    state.stage == EpisodeDownloadStage.SAVED -> EpisodeDownloadAction.REMOVE
    else -> EpisodeDownloadAction.CANCEL
}

/**
 * The download control at the end of an episode row, beside the watched eye.
 *
 * Quiet by default for the same reason [DownloadCoverBadge] is: a list of a hundred rows each
 * carrying a loud arrow is unreadable, so the untouched state is the outline arrow at the same
 * half strength the eye uses, and only an episode with something happening to it takes colour.
 *
 * Progress is drawn here as well as on the cover because the cover is a thumbnail the viewer may
 * have scrolled past — the row's own control is what stays in the same place down the list.
 */
@Composable
fun EpisodeDownloadButton(
    state: EpisodeDownloadUi?,
    pending: Boolean,
    onAction: (EpisodeDownloadAction) -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
) {
    val action = episodeDownloadAction(state, pending)
    val colors = MaterialTheme.colorScheme
    val fill = rememberFocusFill()
    IconButton(
        colors = fill.iconButtonColors(),
        onClick = { if (action != EpisodeDownloadAction.WAIT) onAction(action) },
        modifier = modifier.focusFill(fill, CircleShape),
    ) {
        when (action) {
            EpisodeDownloadAction.WAIT -> CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                strokeWidth = 2.dp,
                color = fill.content(colors.primary),
            )

            EpisodeDownloadAction.START -> Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Download episode",
                tint = fill.content(colors.onSurfaceVariant.copy(alpha = 0.7f)),
                modifier = Modifier.size(iconSize),
            )

            EpisodeDownloadAction.RETRY -> Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = "Download failed, try again",
                tint = fill.content(colors.error),
                modifier = Modifier.size(iconSize),
            )

            EpisodeDownloadAction.REMOVE -> Icon(
                imageVector = Icons.Default.DownloadDone,
                contentDescription = "Downloaded — remove from this device",
                tint = fill.content(colors.primary),
                modifier = Modifier.size(iconSize),
            )

            // A cross inside the progress ring: the ring alone says how far along the episode is
            // but not that the control still does anything.
            EpisodeDownloadAction.CANCEL -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(iconSize + 6.dp),
            ) {
                val progress = state?.progress
                if (progress != null) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(iconSize + 6.dp),
                        strokeWidth = 2.dp,
                        color = fill.content(colors.primary),
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(iconSize + 6.dp),
                        strokeWidth = 2.dp,
                        color = fill.content(colors.primary),
                    )
                }
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Stop downloading this episode",
                    tint = fill.content(colors.onSurfaceVariant),
                    modifier = Modifier.size(iconSize - 6.dp),
                )
            }
        }
    }
}
