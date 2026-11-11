package com.anilili.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anilili.data.AppGraph
import com.anilili.data.auth.AccountService
import com.anilili.data.library.EpisodeStop
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.LocalWatchedStore
import com.anilili.data.library.WatchedEntry
import com.anilili.data.library.finishesSeries
import com.anilili.data.library.WatchlistEntry
import com.anilili.data.remote.isCatalogueNativeMediaId
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.toFailure
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import kotlinx.coroutines.launch

/**
 * Whether the detail page gets a mark-everything-watched button at all. Signed out it marks the
 * title on this device, which every id can have; signed in it is the account's Completed, which a
 * catalogue-only id cannot.
 */
fun watchedToggleAvailable(anilistId: Int): Boolean =
    AccountService.active == null || !isCatalogueNativeMediaId(anilistId)

/**
 * The per-episode eye toggle shared by the mobile and TV episode lists.
 *
 * The local mark always lands; the account hears about it only when progress sync applies, and
 * only forward — the sync chain refuses regressions by design, so un-marking never lowers the
 * remote episode count.
 *
 * What gets sent is the highest episode now marked, not the one just pressed: ticking episode 3
 * after 12 is already watched describes a viewer filling in a gap, and reporting 3 as their
 * progress would be a claim the rest of their own list disagrees with.
 */
fun toggleEpisodeWatched(
    anilistId: Int,
    episodeNumber: Double,
    currentlyWatched: Boolean,
    title: String,
    cover: String?,
    totalEpisodes: Int?,
    /** The season's aired episodes, so the mark can move Continue Watching on — see [LibraryStore.setEpisodeWatched]. */
    airedEpisodes: List<EpisodeStop> = emptyList(),
) {
    LibraryStore.setEpisodeWatched(anilistId, episodeNumber, !currentlyWatched, title, cover, airedEpisodes)
    // Ticking the last episode finishes the title, as the same tick sent as progress does on an
    // account. Only ever adds the mark: unticking an episode is a correction, not "not finished".
    if (!currentlyWatched) {
        val furthest = LibraryStore.historyFor(anilistId)?.watchedEpisodes?.maxOrNull() ?: episodeNumber
        if (finishesSeries(maxOf(furthest, episodeNumber).toInt(), totalEpisodes)) {
            LocalWatchedStore.markFinished(anilistId, title, cover, totalEpisodes)
        }
    }
    if (!currentlyWatched && LibraryStore.canSyncProgress(anilistId)) {
        val furthest = LibraryStore.historyFor(anilistId)?.watchedEpisodes?.maxOrNull()
        LibraryStore.enqueueProgressSync(
            anilistId,
            maxOf(furthest ?: episodeNumber, episodeNumber).toInt(),
            totalEpisodes,
        )
    }
}

/**
 * The eye sitting at the end of an episode row, or in a television card's corner. Filled when the
 * episode counts as watched — see [episodeMarkedWatched] for why that is the watched set and not
 * the progress fraction.
 *
 * State in, press out: the row already knows whether the episode is watched, and every list that
 * shows one keys the mark to a different id — the page's own for a single-season title, the
 * selected season's for a series. [toggleEpisodeWatched] is what a press should call.
 *
 * Offered signed in or out, and on hanime entries too: the mark itself is local, and it is only
 * the sync that those ids cannot have.
 *
 * A null [onToggle] draws the same eye without the button around it, for a card that shows the
 * mark but hands the press to something else. Both episode pickers pass the real toggle; the
 * television cards simply reach it with Down rather than by stopping on it on the way along the
 * strip.
 */
@Composable
fun EpisodeWatchedToggle(
    watched: Boolean,
    onToggle: (() -> Unit)?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
) {
    if (onToggle == null) {
        WatchedEye(watched, iconSize, modifier)
        return
    }
    val fill = rememberFocusFill()
    IconButton(
        colors = fill.iconButtonColors(),
        onClick = onToggle,
        modifier = modifier.focusFill(fill, CircleShape),
    ) {
        WatchedEye(watched, iconSize, tint = fill.content(watchedEyeColor(watched)))
    }
}

@Composable
private fun watchedEyeColor(watched: Boolean): Color = if (watched) {
    MaterialTheme.colorScheme.primary
} else {
    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
}

@Composable
private fun WatchedEye(
    watched: Boolean,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    tint: Color = watchedEyeColor(watched),
) {
    Icon(
        imageVector = if (watched) Icons.Filled.Visibility else Icons.Outlined.Visibility,
        contentDescription = if (watched) "Mark episode not watched" else "Mark episode watched",
        tint = tint,
        modifier = modifier.size(iconSize),
    )
}

/**
 * The eye button on the detail page: one tap marks the whole title watched, another undoes it.
 *
 * Marking means full progress plus COMPLETED on the signed-in service; unmarking moves the status
 * back to Watching and leaves the episode count alone (see
 * [com.anilili.data.MiruroRepository.setAnimeWatched] for why). The local tail matches what the
 * list-status dialogs do after a move, so Continue Watching and the library cannot disagree with
 * the account.
 *
 * Signed out, the eye is the device's Watched mark instead ([LocalWatchedStore]), filed from
 * [markEntry]; with no [markEntry] there is nothing to file and nothing is rendered. Signed in, a
 * title that cannot sync (a hanime catalogue id) gets no eye: per-episode toggles stay available
 * either way because they are local-first.
 */
@Composable
fun WatchedToggleButton(
    anilistId: Int,
    totalEpisodes: Int?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
    // The television lays this over artwork rather than over a surface, where the theme's own
    // content colour is not guaranteed to be legible.
    tint: Color = LocalContentColor.current,
    markEntry: WatchlistEntry? = null,
) {
    if (!watchedToggleAvailable(anilistId)) return
    val service = AccountService.active
    if (service == null) {
        if (markEntry != null) LocalWatchedEye(anilistId, totalEpisodes, markEntry, modifier, iconSize, tint)
        return
    }
    val remoteStatuses by LibraryStore.remoteStatuses.collectAsState()
    val watched = remoteStatuses[anilistId] == "COMPLETED"
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember(anilistId) { mutableStateOf(false) }

    val fill = rememberFocusFill()
    IconButton(
        colors = fill.iconButtonColors(),
        onClick = {
            if (busy) return@IconButton
            busy = true
            val target = !watched
            DiagnosticsLog.event(
                "Detail watched toggle requested service=${service.label} id=$anilistId watched=$target",
            )
            scope.launch {
                runCatching {
                    AppGraph.repository.setAnimeWatched(anilistId, target, totalEpisodes)
                }.onSuccess {
                    val status = if (target) "COMPLETED" else "CURRENT"
                    LibraryStore.updateRemoteStatus(anilistId, status)
                    if (target) LibraryStore.removeHistory(anilistId, dismissRemoteSeed = true)
                    // The same tail the long-press menu runs, for the same reason: the device's
                    // watchlist is its Planning list, so a title that has left Planning cannot
                    // stay in it wearing a "Completed" caption.
                    if (!savedRowSurvivesMoveTo(status)) {
                        LibraryStore.watchlist.value
                            .firstOrNull { it.anilistId == anilistId }
                            ?.let(LibraryStore::toggleWatchlist)
                    }
                    LibraryStore.refreshRemoteLibrary(force = true)
                    DiagnosticsLog.event(
                        "Detail watched toggle confirmed service=${service.label} id=$anilistId watched=$target",
                    )
                }.onFailure { error ->
                    DiagnosticsLog.throwable(
                        "Detail watched toggle failed service=${service.label} id=$anilistId watched=$target",
                        error,
                    )
                    // The eye reads its state straight from the account, so a failure leaves it
                    // exactly as it was — which on its own is indistinguishable from a press that
                    // never registered. Say so.
                    Toast.makeText(
                        context,
                        error.toFailure(
                            AnililiError.ACCOUNT_LIST_UPDATE_FAILED,
                            service.label,
                            attributes = mapOf(
                                "from" to "watched_toggle",
                                "service" to service.name,
                                "anilistId" to anilistId,
                                "watched" to target,
                            ),
                        ).withCode(),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                busy = false
            }
        },
        enabled = !busy,
        modifier = modifier.focusFill(fill, CircleShape),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                strokeWidth = 2.dp,
                color = fill.content(tint),
            )
        } else {
            Icon(
                imageVector = if (watched) Icons.Filled.Visibility else Icons.Outlined.Visibility,
                contentDescription = if (watched) "Mark as not watched" else "Mark as watched",
                tint = fill.content(tint),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** The signed-out eye: no network, so no busy state and nothing that can fail. */
@Composable
private fun LocalWatchedEye(
    anilistId: Int,
    totalEpisodes: Int?,
    markEntry: WatchlistEntry,
    modifier: Modifier,
    iconSize: Dp,
    tint: Color,
) {
    val entries by LocalWatchedStore.entries.collectAsState()
    val watched = entries.any { it.anilistId == anilistId }
    val fill = rememberFocusFill()
    IconButton(
        colors = fill.iconButtonColors(),
        onClick = {
            LocalWatchedStore.setWatched(
                WatchedEntry(
                    anilistId = anilistId,
                    title = markEntry.title,
                    cover = markEntry.cover,
                    format = markEntry.format,
                    averageScore = markEntry.averageScore,
                    episodes = totalEpisodes,
                ),
                watched = !watched,
            )
        },
        modifier = modifier.focusFill(fill, CircleShape),
    ) {
        Icon(
            imageVector = if (watched) Icons.Filled.Visibility else Icons.Outlined.Visibility,
            contentDescription = if (watched) "Mark as not watched" else "Mark as watched",
            tint = fill.content(tint),
            modifier = Modifier.size(iconSize),
        )
    }
}
