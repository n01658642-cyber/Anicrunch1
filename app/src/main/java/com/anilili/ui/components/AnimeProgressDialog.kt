package com.anilili.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.anilili.data.AnimeListProgress
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
import com.anilili.ui.dialogs.MidnightStepperRow
import com.anilili.ui.rethrowIfCancellation
import kotlinx.coroutines.launch

/**
 * The number of episodes watched for one title, set by hand on the signed-in AniList or MyAnimeList
 * account — lower as well as higher.
 *
 * Unlike [AnimeScoreDialog] this is a stepper with a Save, not one row per value: a count can run to
 * hundreds, and a stepper that wrote on every nudge would send a dozen writes to reach episode 12.
 * Nothing is sent until Save. "By ten" is there because a remote has no keyboard worth typing on.
 *
 * The count is read live from the account when the sheet opens rather than from the library
 * snapshot, which only keeps counts for Watching. Only offered for a title already on the account.
 */
@Composable
internal fun AnimeProgressDialog(
    anilistId: Int,
    title: String,
    cover: String?,
    service: AccountService,
    onDismiss: () -> Unit,
    kicker: String = "Episodes watched",
) {
    val scope = rememberCoroutineScope()
    var loaded by remember(anilistId) { mutableStateOf<AnimeListProgress?>(null) }
    var loading by remember(anilistId) { mutableStateOf(true) }
    var count by remember(anilistId) { mutableStateOf(0) }
    var saving by remember(anilistId) { mutableStateOf(false) }
    var errorMessage by remember(anilistId) { mutableStateOf<String?>(null) }

    LaunchedEffect(anilistId, service) {
        loading = true
        runCatching { AppGraph.repository.animeListProgress(anilistId) }
            .onSuccess { progress ->
                loaded = progress
                count = progress?.progress ?: 0
                if (progress == null) {
                    errorMessage = "This title isn't on your ${service.label} list any more."
                }
            }
            .onFailure { error ->
                error.rethrowIfCancellation()
                errorMessage = error.toFailure(
                    AnililiError.ACCOUNT_PROGRESS_UPDATE_FAILED,
                    service.label,
                    attributes = mapOf("service" to service.name, "anilistId" to anilistId, "stage" to "read"),
                ).withCode()
            }
        loading = false
    }

    val max = loaded?.maxEpisodes
    val ready = !loading && loaded != null
    val busy = loading || saving

    fun nudge(by: Int) {
        val next = (count + by).coerceAtLeast(0)
        count = max?.let { next.coerceAtMost(it) } ?: next
    }

    fun save() {
        val current = loaded ?: return
        if (busy) return
        if (count == current.progress) {
            onDismiss()
            return
        }
        saving = true
        errorMessage = null
        DiagnosticsLog.event(
            "List episode count requested service=${service.label} id=$anilistId " +
                "from=${current.progress} to=$count",
        )
        scope.launch {
            runCatching { AppGraph.repository.setAnimeListProgress(anilistId, count) }
                .onSuccess {
                    // The library's Continue Watching rows follow the account's count on the next
                    // read, forwards only; a lowered count leaves this device's own row alone.
                    LibraryStore.refreshRemoteLibrary(force = true)
                    onDismiss()
                }
                .onFailure { error ->
                    error.rethrowIfCancellation()
                    errorMessage = error.toFailure(
                        AnililiError.ACCOUNT_PROGRESS_UPDATE_FAILED,
                        service.label,
                        attributes = mapOf(
                            "service" to service.name,
                            "anilistId" to anilistId,
                            "progress" to count,
                        ),
                    ).withCode()
                    saving = false
                }
        }
    }

    MidnightDialogHost(
        onDismiss = onDismiss,
        // A network write; closing mid-flight would leave the viewer unsure it landed.
        canDismiss = !saving,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = kicker,
                title = title,
                metadata = when {
                    loading -> "Reading ${service.label}…"
                    loaded == null -> null
                    else -> "${loaded?.progress} watched on ${service.label}"
                },
                artworkUrl = cover,
                onDismiss = onDismiss.takeIf { showClose && !saving },
            )
        },
        footer = {
            MidnightDialogFooter(
                quietText = "Cancel",
                onQuiet = onDismiss,
                enabled = !saving,
            )
        },
    ) {
        if (ready) {
            MidnightHelper("Set the count on ${service.label}. Lowering it is allowed; the status stays as it is.")
            MidnightStepperRow(
                label = "Episodes watched",
                value = max?.let { "$count / $it" } ?: count.toString(),
                onDecrease = { nudge(-1) },
                onIncrease = { nudge(1) },
                decreaseEnabled = !busy && count > 0,
                increaseEnabled = !busy && (max == null || count < max),
            )
            MidnightStepperRow(
                label = "By ten",
                value = "",
                onDecrease = { nudge(-10) },
                onIncrease = { nudge(10) },
                decreaseLabel = "−10",
                increaseLabel = "+10",
                decreaseEnabled = !busy && count > 0,
                increaseEnabled = !busy && (max == null || count < max),
            )
            // The cursor opens on Save, so a remote that only wanted to look can Back out, and one
            // that nudged the stepper is one Down away from sending it.
            MidnightStatusRow(
                label = if (count == loaded?.progress) "No change" else "Save $count episodes",
                subtitle = "Sent to your ${service.label} list",
                selected = false,
                busy = saving,
                enabled = !busy,
                claimInitialFocus = true,
                onSelect = ::save,
            )
        }
        errorMessage?.let { message ->
            MidnightInlineNotice(message, tone = MidnightNoticeTone.ERROR)
        }
    }
}
