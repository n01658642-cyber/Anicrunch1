package com.anilili.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.anilili.errors.AnililiFailure
import com.anilili.errors.ErrorAction
import com.anilili.ui.dialogs.MidnightModal
import com.anilili.ui.dialogs.MidnightModalTitle
import com.anilili.ui.dialogs.MidnightQuietAction
import com.anilili.ui.theme.Midnight

/**
 * How a failure looks, everywhere.
 *
 * The rule the app got wrong before: an error in a list row is a *label*, not an explanation. A
 * downloads screen showing eleven episodes that failed the same way was eleven copies of a
 * fifteen-word sentence, which is both unreadable and no more informative than one copy. So the row
 * gets one short line and a code, and the explanation waits behind [ErrorDetailsDialog] for
 * whoever actually wants it.
 *
 * The code is the part that travels. It is what someone types into Discord, and the only thing in
 * the UI that can be matched against a diagnostics bundle, so it is always present and always
 * selectable — never abbreviated away to save a few pixels.
 */

/** `Couldn't save this episode as an MP4. · EXP-04`, with the code dimmed. */
@Composable
@ReadOnlyComposable
fun failureAnnotated(
    failure: AnililiFailure,
    messageColor: Color = Midnight.ErrorBody,
    codeColor: Color = Midnight.Text3,
): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = messageColor)) { append(failure.message) }
    withStyle(SpanStyle(color = codeColor, fontFamily = FontFamily.Monospace)) {
        append("  ")
        append(failure.code)
    }
}

/**
 * The code as a tappable chip. Tapping copies it, because the alternative is someone transcribing
 * `PLAY-02/h403` from a television screen by hand.
 */
@Composable
fun ErrorCodeChip(
    failure: AnililiFailure,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Midnight.RedSoft)
            .clickable(role = Role.Button) { clipboard.setText(AnnotatedString(failure.code)) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = failure.code,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.W600,
            color = Midnight.ErrorBody,
            maxLines = 1,
        )
    }
}

/**
 * An inline failure: one line, the code, and at most one thing to do about it.
 *
 * [onAction] is offered only when the catalog entry names an [ErrorAction]. An entry with no action
 * is making a claim — that nothing the viewer does from here helps — and putting a button there
 * anyway would be a lie that costs them a pointless retry.
 */
@Composable
fun MidnightErrorNotice(
    failure: AnililiFailure,
    modifier: Modifier = Modifier,
    onAction: ((ErrorAction) -> Unit)? = null,
    onDetails: (() -> Unit)? = null,
) {
    Column(modifier = modifier.padding(top = 10.dp)) {
        Row {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(width = 2.dp, height = 14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Midnight.Red),
            )
            Text(
                text = failureAnnotated(failure),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 9.dp),
            )
        }
        val action = failure.action
        if ((action != null && onAction != null) || onDetails != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 5.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (action != null && onAction != null) {
                    MidnightQuietAction(text = action.label, onClick = { onAction(action) })
                }
                if (onDetails != null) {
                    MidnightQuietAction(text = "Details", onClick = onDetails)
                }
            }
        }
    }
}

/**
 * Everything the one-liner left out, on request.
 *
 * This is where the prose that used to be in the list row lives: the underlying exception, the
 * platform's own error name, whatever the call site attached. Ending in **Send diagnostics** is the
 * point of the whole screen — it is the moment someone is most willing to send a report, and the
 * code shown above it is what makes that report findable afterwards.
 */
@Composable
fun ErrorDetailsDialog(
    failure: AnililiFailure,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onSendDiagnostics: (() -> Unit)? = null,
) {
    MidnightModal(onDismiss = onDismiss, modifier = modifier) {
        MidnightModalTitle(
            kicker = failure.error.domain.label,
            title = failure.message,
            body = "Quote this code if you report it.",
        )
        Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text(
                text = "CODE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.W700,
                color = Midnight.Text3,
            )
            // A chip rather than a line, because this is the one value someone has to reproduce
            // exactly, and tapping it beats transcribing `PLAY-02/h403` by hand.
            ErrorCodeChip(failure = failure, modifier = Modifier.padding(top = 4.dp))
        }
        failure.detail?.let { DetailLine(label = "Detail", value = it, monospace = true) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onSendDiagnostics != null) {
                MidnightQuietAction(text = ErrorAction.SEND_DIAGNOSTICS.label, onClick = onSendDiagnostics)
            }
            MidnightQuietAction(text = "Close", onClick = onDismiss)
        }
    }
}

@Composable
private fun ColumnScope.DetailLine(label: String, value: String, monospace: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.W700,
            color = Midnight.Text3,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            color = Midnight.Text2,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
