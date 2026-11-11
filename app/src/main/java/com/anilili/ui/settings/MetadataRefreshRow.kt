package com.anilili.ui.settings

import android.content.Intent
import android.os.Process
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import com.anilili.data.MetadataRefresh
import com.anilili.data.profiles.ProfileRestartActivity
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.dialogs.MidnightConfirmDialog
import com.anilili.ui.dialogs.MidnightInlineNotice
import com.anilili.ui.dialogs.MidnightNoticeTone
import com.anilili.ui.dialogs.MidnightSettingRow
import com.anilili.ui.theme.LocalMidnightAccent
import com.anilili.ui.watch.findActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings → Data → Re-download metadata; see [MetadataRefresh] for what it fetches and clears.
 *
 * Asks first, because it ends in a restart. While it runs the row fills from the left like a
 * download — a tint over the row as wide as the progress — with what it is fetching underneath and
 * the percentage on the right; then the app restarts so everything reads the new data. A failure
 * leaves the row ready to try again, with the reason beneath it.
 */
@Composable
internal fun MetadataRefreshRow(
    /**
     * Passed in rather than written here: Settings search finds rows by the titles written in
     * SettingsScreen.kt (SettingsSearchIndexTest reads them from there).
     */
    title: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<MetadataRefresh.Progress?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val accent = LocalMidnightAccent.current
    val fill by animateFloatAsState(progress?.fraction ?: 0f, tween(250), label = "metadata-refresh-fill")

    if (confirming) {
        MidnightConfirmDialog(
            kicker = "Data",
            title = "Re-download metadata?",
            body = "Fetches the newest catalogue, title IDs and dub info from the metadata index " +
                "(about 2 MB), clears cached title details, then restarts the app. Your lists, " +
                "history, downloads and settings stay as they are.",
            confirmText = "Re-download",
            destructive = false,
            onConfirm = {
                confirming = false
                failure = null
                scope.launch {
                    try {
                        MetadataRefresh.run(context) { step -> progress = step }
                        // Long enough to read "Restarting…" on a full bar.
                        delay(RESTART_PAUSE_MS)
                        val activity = context.findActivity() ?: return@launch
                        // The same hand-off a profile switch uses: a process of its own kills this
                        // one and starts the app again, so nothing still holds the old data.
                        activity.startActivity(
                            Intent(activity, ProfileRestartActivity::class.java)
                                .putExtra(ProfileRestartActivity.EXTRA_PID, Process.myPid()),
                        )
                        activity.finishAffinity()
                    } catch (error: CancellationException) {
                        progress = null
                        throw error
                    } catch (error: Exception) {
                        DiagnosticsLog.throwable("Re-download metadata failed", error)
                        progress = null
                        failure = "Couldn't re-download the metadata (${error.message ?: error.javaClass.simpleName}). " +
                            "Nothing was changed — try again when the connection is better."
                    }
                }
            },
            onDismiss = { confirming = false },
        )
    }

    Column {
        val running = progress
        Box(
            Modifier.drawWithContent {
                drawContent()
                if (running != null) {
                    drawRect(accent.copy(alpha = .16f), size = Size(size.width * fill, size.height))
                }
            },
        ) {
            MidnightSettingRow(
                title = if (running != null) "Re-downloading metadata" else title,
                description = running?.label
                    ?: "Get the newest catalogue, title IDs and dub info from the index, then restart",
                value = running?.let { "${(fill * 100).toInt()}%" },
                showChevron = running == null,
                onClick = if (running == null) {
                    { confirming = true }
                } else {
                    null
                },
            )
        }
        failure?.let { MidnightInlineNotice(it, tone = MidnightNoticeTone.ERROR) }
    }
}

private const val RESTART_PAUSE_MS = 700L
