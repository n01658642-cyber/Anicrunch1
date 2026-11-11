package com.anilili.diagnostics

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/** Explicit crash consent. Nothing is prepared until the user chooses Share report. */
@Composable
fun CrashReportDialog(
    report: String,
    onAccepted: () -> Unit,
    onDiscard: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    DiagnosticSubmissionDialog(
        title = "Send crash diagnostics?",
        introduction = "This creates a full crash, device, performance, playback and network timing " +
            "report and opens Android's share sheet. Passwords, cookies, tokens and sensitive links are removed.",
        technicalDetails = report,
        descriptionRequired = false,
        dismissLabel = "Don't send",
        sending = sending,
        errorMessage = errorMessage,
        onDismiss = onDiscard,
        onSend = { submission ->
            sending = true
            errorMessage = null
            scope.launch {
                when (
                    val result = DiagnosticsShareManager.share(
                        context,
                        DiagnosticTrigger.CRASH,
                        submission,
                    )
                ) {
                    is DiagnosticShareResult.Failed,
                    is DiagnosticShareResult.AlreadyPreparing,
                    -> {
                        errorMessage = result.userMessage()
                        sending = false
                    }
                    is DiagnosticShareResult.Started -> {
                        Toast.makeText(context, result.userMessage(), Toast.LENGTH_LONG).show()
                        onAccepted()
                    }
                }
            }
        },
    )
}
