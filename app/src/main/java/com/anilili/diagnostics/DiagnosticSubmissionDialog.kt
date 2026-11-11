package com.anilili.diagnostics

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill

@Composable
fun DiagnosticSubmissionDialog(
    title: String,
    introduction: String,
    technicalDetails: String? = null,
    descriptionRequired: Boolean,
    dismissLabel: String = "Cancel",
    sending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSend: (DiagnosticSubmissionInput) -> Unit,
) {
    val context = LocalContext.current
    var description by remember { mutableStateOf("") }
    var screenshotUri by remember { mutableStateOf<Uri?>(null) }
    // Fire TV and most other boxes ship nothing that answers GET_CONTENT, and launching it there
    // throws ActivityNotFoundException on the main thread — which is how "Attach screenshot" took
    // the whole app down mid-report. Offer the button only where something can pick an image.
    val canAttachScreenshot = remember {
        Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .resolveActivity(context.packageManager) != null
    }
    val screenshotPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) screenshotUri = uri
    }
    val screenshotName = remember(screenshotUri) {
        screenshotUri?.let { uri ->
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
            }.getOrNull()
        }
    }
    val normalizedDescription = description.trim()
    val canSend = !sending && (!descriptionRequired || normalizedDescription.isNotBlank())

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(introduction, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = description,
                    onValueChange = {
                        description = it.take(DiagnosticSubmissionPolicy.MAX_DESCRIPTION_CHARS)
                    },
                    label = {
                        Text(if (descriptionRequired) "What went wrong?" else "What were you doing? (optional)")
                    },
                    supportingText = {
                        Text(
                            "${description.length}/${DiagnosticSubmissionPolicy.MAX_DESCRIPTION_CHARS} characters",
                        )
                    },
                    minLines = 3,
                    maxLines = 6,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (canAttachScreenshot) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val extraFocus1 = rememberFocusFill()
                    TextButton(
                        colors = extraFocus1.buttonColors(ButtonDefaults.textButtonColors()),
                        enabled = !sending,
                        onClick = {
                            try {
                                screenshotPicker.launch("image/*")
                            } catch (_: ActivityNotFoundException) {
                                // resolveActivity said yes and the launch said no; stay in the dialog.
                            }
                        }, modifier = Modifier.focusFill(extraFocus1, RoundedCornerShape(24.dp)),) {
                        Text(if (screenshotUri == null) "Attach screenshot" else "Change screenshot")
                    }
                    if (screenshotUri != null) {
                        val extraFocus2 = rememberFocusFill()
                        TextButton(
                            colors = extraFocus2.buttonColors(ButtonDefaults.textButtonColors()),
                            enabled = !sending,
                            onClick = { screenshotUri = null }, modifier = Modifier.focusFill(extraFocus2, RoundedCornerShape(24.dp)),) {
                            Text("Remove")
                        }
                    }
                }
                screenshotUri?.let {
                    Text(
                        screenshotName ?: "Screenshot selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "A screenshot is optional and may contain personal information. Attach only what " +
                        "you want to send. Images are limited to 5 MB.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                technicalDetails?.let { report ->
                    Text(
                        report,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.heightIn(max = 180.dp),
                    )
                }
                errorMessage?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            val extraFocus3 = rememberFocusFill()
            TextButton(
                colors = extraFocus3.buttonColors(ButtonDefaults.textButtonColors()),
                enabled = canSend,
                onClick = {
                    onSend(
                        DiagnosticSubmissionInput(
                            description = normalizedDescription,
                            screenshotUri = screenshotUri,
                        ),
                    )
                }, modifier = Modifier.focusFill(extraFocus3, RoundedCornerShape(24.dp)),) {
                Text(if (sending) "Preparing…" else "Share report")
            }
        },
        dismissButton = {
            val extraFocus4 = rememberFocusFill()
            TextButton(
                colors = extraFocus4.buttonColors(ButtonDefaults.textButtonColors()),enabled = !sending, onClick = onDismiss, modifier = Modifier.focusFill(extraFocus4, RoundedCornerShape(24.dp)),) {
                Text(dismissLabel, color = extraFocus4.content(MaterialTheme.colorScheme.onSurfaceVariant))
            }
        },
    )
}
