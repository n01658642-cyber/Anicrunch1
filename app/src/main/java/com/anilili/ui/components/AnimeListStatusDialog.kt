package com.anilili.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.anilili.data.AppGraph
import com.anilili.data.auth.AccountService
import com.anilili.data.library.LibraryStore
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.toFailure
import com.anilili.ui.dialogs.MidnightDialogFooter
import com.anilili.ui.dialogs.MidnightDialogHost
import com.anilili.ui.dialogs.MidnightHelper
import com.anilili.ui.dialogs.MidnightInlineNotice
import com.anilili.ui.dialogs.MidnightMediaHeader
import com.anilili.ui.dialogs.MidnightNoticeTone
import com.anilili.ui.dialogs.MidnightStatusRow
import kotlinx.coroutines.launch

internal data class AnimeListStatusOption(
    val status: String,
    val label: String,
)

/** The five standard destinations shown by both AniList and MyAnimeList. */
internal val animeListStatusOptions = listOf(
    AnimeListStatusOption("CURRENT", "Watching"),
    AnimeListStatusOption("PLANNING", "Plan to Watch"),
    AnimeListStatusOption("COMPLETED", "Completed"),
    AnimeListStatusOption("PAUSED", "On-Hold"),
    AnimeListStatusOption("DROPPED", "Dropped"),
)

/**
 * Player-facing list picker shared by phone, tablet, and TV.
 *
 * AniList and MAL both use the canonical status keys above inside the app. The repository maps
 * them to the active service's API vocabulary and verifies MAL's returned status before this
 * dialog updates local UI state.
 *
 * Presentation is the shared status list (design/midnight-anime/design.md §14, §44): neutral rows
 * with one violet rail on the stored value, rather than a grid of coloured cards that gave every
 * status its own accent and made the current one no louder than the rest.
 */
@Composable
fun AnimeListStatusDialog(
    anilistId: Int,
    title: String,
    episodeLabel: String?,
    onDismiss: () -> Unit,
    artworkUrl: String? = null,
    onStatusUpdated: (String) -> Unit = {},
) {
    val service = AccountService.active
    val remoteStatuses by LibraryStore.remoteStatuses.collectAsState()
    val currentStatus = remoteStatuses[anilistId]
    val scope = rememberCoroutineScope()
    var busyStatus by remember(anilistId) { mutableStateOf<String?>(null) }
    var errorMessage by remember(anilistId) { mutableStateOf<String?>(null) }

    fun selectStatus(status: String) {
        if (service == null || busyStatus != null || status == currentStatus) return
        busyStatus = status
        errorMessage = null
        DiagnosticsLog.event(
            "Player list status requested service=${service.label} id=$anilistId status=$status",
        )
        scope.launch {
            runCatching { AppGraph.repository.updateAnimeListStatus(anilistId, status) }
                .onSuccess {
                    LibraryStore.updateRemoteStatus(anilistId, status)
                    if (status != "CURRENT" && status != "REPEATING") {
                        LibraryStore.removeHistory(anilistId, dismissRemoteSeed = true)
                    }
                    LibraryStore.refreshRemoteLibrary(force = true)
                    DiagnosticsLog.event(
                        "Player list status confirmed service=${service.label} id=$anilistId status=$status",
                    )
                    onStatusUpdated(status)
                    onDismiss()
                }
                .onFailure { error ->
                    errorMessage = error.toFailure(
                        AnililiError.ACCOUNT_LIST_UPDATE_FAILED,
                        service.label,
                        attributes = mapOf(
                            "from" to "player",
                            "service" to service.name,
                            "anilistId" to anilistId,
                            "status" to status,
                        ),
                    ).withCode()
                    busyStatus = null
                }
        }
    }

    MidnightDialogHost(
        onDismiss = onDismiss,
        // A status change is a network call; dismissing mid-flight would leave the viewer unsure
        // whether it landed.
        canDismiss = busyStatus == null,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = "My list",
                title = title,
                metadata = episodeLabel,
                artworkUrl = artworkUrl,
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
        if (service == null) {
            MidnightInlineNotice(
                "Sign in to AniList or MyAnimeList from Settings to keep a list.",
            )
            return@MidnightDialogHost
        }
        MidnightHelper("Syncing with ${service.label}")
        // Nothing stored yet means no row to open the cursor on, and a dialog with the cursor
        // nowhere is one a remote cannot move inside; the first option stands in.
        val cursorStatus = currentStatus ?: animeListStatusOptions.first().status
        animeListStatusOptions.forEach { option ->
            MidnightStatusRow(
                label = option.label,
                selected = option.status == currentStatus,
                busy = option.status == busyStatus,
                // The current status stays enabled so the D-pad cursor can start on it (§31);
                // choosing it again is a no-op rather than a wasted network call.
                enabled = busyStatus == null,
                destructive = option.status == "DROPPED",
                claimInitialFocus = option.status == cursorStatus,
                icon = listDestinations.firstOrNull { it.status == option.status }?.icon,
                onSelect = { selectStatus(option.status) },
            )
        }
        errorMessage?.let { message ->
            MidnightInlineNotice(message, tone = MidnightNoticeTone.ERROR)
        }
    }
}
