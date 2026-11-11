package com.anilili.ui.announce

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anilili.data.announce.AnnouncementManager
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.report
import com.anilili.ui.dialogs.MidnightConfirmDialog

/**
 * The message behind an announcement notification, opened by tapping it.
 *
 * A notification is a summary — one line in the shade, and a link that is only text and cannot be
 * tapped — so the shade says there is something to read and this is where it is actually read. It
 * sits beside the other always-present hosts at the root of the activity and shows nothing until
 * [AnnouncementManager.pending] is set, which only a notification tap does: a broadcast arriving
 * mid-episode waits in the shade rather than seizing the screen.
 *
 * The whole URL is printed, selectable, above the button that opens it. That is the point of
 * routing the tap through here rather than into a browser: the viewer sees exactly where they are
 * going and can copy it instead of following it. The URL is already known to be plain https —
 * `sanitizeAnnouncementUrl` refuses every other scheme before an announcement is ever shown — so
 * what is left to guard is not a hostile scheme but an unread destination.
 */
@Composable
fun AnnouncementHost() {
    val context = LocalContext.current
    val announcement by AnnouncementManager.pending.collectAsState()
    val live = announcement ?: return
    val link = live.url

    MidnightConfirmDialog(
        kicker = "Announcement",
        title = live.title,
        body = live.message,
        // With nothing to open, the dialog *is* the message and the only action is closing it.
        confirmText = if (link == null) "Close" else live.urlLabel ?: "Open link",
        destructive = false,
        dismissText = if (link == null) null else "Close",
        // Skipped when the publisher already wrote the URL into the message, which is the natural
        // thing to do when composing a line like "Join for updates https://…". Printing it again
        // underneath reads as two different links rather than one shown twice.
        details = if (link == null || live.message.contains(link)) {
            null
        } else {
            {
                SelectionContainer {
                    Text(
                        link,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }
        },
        onConfirm = {
            if (link != null) {
                // A missing browser must not take the app down: a television often has none at
                // all, and the URL is on screen to be copied in that case.
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(link))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { error ->
                    // Recorded, not shown: the dialog is closing and the URL was already on screen
                    // to be copied. PLAY-09 is here so "nobody followed the link" can be told apart
                    // from "nobody could", which on a television is usually the second one.
                    AnililiError.BROWSER_MISSING.report(
                        cause = error,
                        attributes = mapOf("surface" to "announcement"),
                    )
                }
            }
            AnnouncementManager.dismissPending()
        },
        onDismiss = AnnouncementManager::dismissPending,
    )
}
