package com.anilili.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.anilili.data.AppGraph
import com.anilili.data.auth.AccountService
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.errors.AnililiError
import com.anilili.errors.toFailure
import com.anilili.ui.dialogs.MidnightDialogFooter
import com.anilili.ui.dialogs.MidnightDialogHost
import com.anilili.ui.dialogs.MidnightSectionLabel
import com.anilili.ui.dialogs.MidnightSettingRow
import com.anilili.ui.dialogs.midnightInitialFocus
import com.anilili.ui.dialogs.MidnightInlineNotice
import com.anilili.ui.dialogs.MidnightMediaHeader
import com.anilili.ui.dialogs.MidnightNoticeTone
import com.anilili.ui.dialogs.MidnightStatusRow
import kotlinx.coroutines.launch

internal data class ListDestination(
    val status: String,
    val label: String,
    val icon: ImageVector,
    /** What the status means, for the one menu with the room to say it. */
    val hint: String,
)

/** Shared with [AnimeListActionsDialog] so every long-press menu offers the same destinations. */
internal val listDestinations = listOf(
    ListDestination("CURRENT", "Watching", Icons.Filled.PlayArrow, "Working through it now"),
    ListDestination("REPEATING", "Rewatching", Icons.Filled.Replay, "Seen it; going again"),
    ListDestination("PLANNING", "Plan to watch", Icons.Filled.BookmarkBorder, "Saved for later"),
    ListDestination("PAUSED", "Paused", Icons.Filled.PauseCircleOutline, "On hold for now"),
    ListDestination("COMPLETED", "Completed", Icons.Filled.CheckCircleOutline, "Finished every episode"),
    ListDestination("DROPPED", "Dropped", Icons.Filled.RemoveCircleOutline, "Given up on"),
)

/**
 * Long-press menu shared by Home and My List Continue Watching cards.
 *
 * Local removal deliberately leaves the remote list untouched. Moving to a non-watching status
 * updates the active account first, then removes the local row so a failed network call never
 * makes the UI claim that AniList or MAL was changed.
 *
 * The destinations are the same status list My List uses (§44), so the two workflows cannot drift
 * into two different-looking answers to the same question.
 */
@Composable
fun ContinueWatchingActionsDialog(
    entry: HistoryEntry,
    onDismiss: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val service = remember { AccountService.active }
    val remoteStatuses by LibraryStore.remoteStatuses.collectAsState()
    val currentStatus = remoteStatuses[entry.anilistId]
    val scope = rememberCoroutineScope()
    var busyStatus by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun moveTo(status: String) {
        if (busyStatus != null || status == currentStatus) return
        busyStatus = status
        errorMessage = null
        scope.launch {
            runCatching { AppGraph.repository.updateAnimeListStatus(entry.anilistId, status) }
                .onSuccess {
                    LibraryStore.updateRemoteStatus(entry.anilistId, status)
                    if (status != "CURRENT" && status != "REPEATING") {
                        LibraryStore.removeHistory(entry.anilistId, dismissRemoteSeed = true)
                    }
                    LibraryStore.refreshRemoteLibrary(force = true)
                    onDismiss()
                }
                .onFailure { error ->
                    errorMessage = error.toFailure(
                        AnililiError.ACCOUNT_LIST_UPDATE_FAILED,
                        service?.label ?: "your list",
                        attributes = mapOf(
                            "from" to "continue_watching",
                            "service" to (service?.name ?: "none"),
                            "anilistId" to entry.anilistId,
                            "status" to status,
                        ),
                    ).withCode()
                    busyStatus = null
                }
        }
    }

    MidnightDialogHost(
        onDismiss = onDismiss,
        canDismiss = busyStatus == null,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = "Continue watching",
                title = entry.title,
                metadata = "Episode ${entry.episodeNumber.toEpisodeLabel()}",
                artworkUrl = entry.cover,
                onDismiss = onDismiss.takeIf { showClose && busyStatus == null },
            )
        },
        footer = {
            MidnightDialogFooter(
                quietText = "Close",
                onQuiet = onDismiss,
                enabled = busyStatus == null,
            )
        },
    ) {
        // Laid out as a Settings page: each action a row that says what it does, the list moves a
        // labelled section of their own. Details and Remove used to be two words in the footer,
        // beside a Close of the same weight, under a paragraph about signing in.
        MidnightSectionLabel("This title")
        MidnightSettingRow(
            title = "Details",
            description = "Synopsis, seasons and every episode",
            enabled = busyStatus == null,
            // The remote starts at the top, not on whichever list status happens to be current.
            modifier = Modifier.midnightInitialFocus(),
            onClick = {
                onOpenDetails()
                onDismiss()
            },
        )
        MidnightSettingRow(
            title = "Remove from Continue watching",
            description = service?.let { "Only from this row. Your ${it.label} list stays as it is" }
                ?: "Only from this row",
            danger = true,
            enabled = busyStatus == null,
            onClick = {
                LibraryStore.removeHistory(entry.anilistId, dismissRemoteSeed = true)
                onDismiss()
            },
        )
        if (service == null) {
            MidnightSectionLabel("Your list")
            MidnightInlineNotice("Sign in to AniList or MyAnimeList to move this title between lists.")
            return@MidnightDialogHost
        }
        MidnightSectionLabel("Move on ${service.label}")
        listDestinations.forEach { destination ->
            MidnightStatusRow(
                label = destination.label,
                subtitle = destination.hint,
                selected = destination.status == currentStatus,
                busy = destination.status == busyStatus,
                enabled = busyStatus == null,
                destructive = destination.status == "DROPPED",
                icon = destination.icon,
                claimInitialFocus = false,
                onSelect = { moveTo(destination.status) },
            )
        }
        errorMessage?.let { message ->
            MidnightInlineNotice(message, tone = MidnightNoticeTone.ERROR)
        }
    }
}

/** Episode numbers are stored as doubles for the half-episode recaps; 12.0 should read as 12. */
private fun Double.toEpisodeLabel(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()
