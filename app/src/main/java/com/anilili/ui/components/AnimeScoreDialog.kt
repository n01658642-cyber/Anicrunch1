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
import com.anilili.data.library.formatListScore
import com.anilili.data.library.listScoreLabel
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

/**
 * The viewer's own score for one title, sent to the signed-in AniList or MyAnimeList account.
 *
 * One row per point rather than a stepper: a stepper would write to the account on every nudge, and
 * a remote reaches any row of a list in a few presses anyway. Choosing a row is the write, the way
 * choosing a status is in [AnimeListActionsDialog], and the sheet closes once the account confirms.
 *
 * An entry is one season on both services, so this scores the season on screen. Whole points only:
 * MyAnimeList stores nothing finer, and an AniList score given on its website in half points shows
 * here as it is, with no row ticked, until the viewer picks one.
 */
@Composable
internal fun AnimeScoreDialog(
    anilistId: Int,
    title: String,
    cover: String?,
    service: AccountService,
    onDismiss: () -> Unit,
    kicker: String = "Your score",
) {
    val scores by LibraryStore.remoteScores.collectAsState()
    val current = scores[anilistId]
    val scope = rememberCoroutineScope()
    var busyScore by remember(anilistId) { mutableStateOf<Int?>(null) }
    var errorMessage by remember(anilistId) { mutableStateOf<String?>(null) }
    val busy = busyScore != null

    fun rate(score: Int) {
        if (busy) return
        if (current != null && current == score.toDouble()) return
        busyScore = score
        errorMessage = null
        DiagnosticsLog.event("List score requested service=${service.label} id=$anilistId score=$score")
        scope.launch {
            runCatching { AppGraph.repository.updateAnimeListScore(anilistId, score) }
                .onSuccess {
                    LibraryStore.updateRemoteScore(anilistId, score)
                    onDismiss()
                }
                .onFailure { error ->
                    errorMessage = error.toFailure(
                        AnililiError.ACCOUNT_SCORE_UPDATE_FAILED,
                        service.label,
                        attributes = mapOf(
                            "service" to service.name,
                            "anilistId" to anilistId,
                            "score" to score,
                        ),
                    ).withCode()
                    busyScore = null
                }
        }
    }

    MidnightDialogHost(
        onDismiss = onDismiss,
        // The score is a network write; closing mid-flight would leave the viewer unsure it landed.
        canDismiss = !busy,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = kicker,
                title = title,
                metadata = current?.let { "${formatListScore(it)} / 10 on ${service.label}" }
                    ?: "Not scored on ${service.label} yet",
                artworkUrl = cover,
                onDismiss = onDismiss.takeIf { showClose && !busy },
            )
        },
        footer = {
            MidnightDialogFooter(
                dangerText = "Remove score".takeIf { current != null },
                onDanger = { rate(0) }.takeIf { current != null },
                quietText = "Close",
                onQuiet = onDismiss,
                enabled = !busy,
            )
        },
    ) {
        MidnightHelper("Score this season on ${service.label}")
        // The cursor opens on the score already given, so OK confirms what is on screen; with none,
        // on the middle of the scale rather than the top, which would invite a 10 by default.
        val cursor = current?.takeIf { it % 1.0 == 0.0 }?.toInt() ?: 7
        (10 downTo 1).forEach { score ->
            // Status rows rather than radio rows: a radio row only takes the cursor when it is the
            // chosen one, and a title with no score yet would leave a remote with nowhere to start.
            MidnightStatusRow(
                label = score.toString(),
                subtitle = listScoreLabel(score),
                selected = current == score.toDouble(),
                busy = busyScore == score,
                enabled = !busy,
                claimInitialFocus = score == cursor,
                onSelect = { rate(score) },
            )
        }
        errorMessage?.let { message ->
            MidnightInlineNotice(message, tone = MidnightNoticeTone.ERROR)
        }
    }
}
