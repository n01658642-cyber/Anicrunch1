package com.anilili.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.anilili.errors.AnililiError
import com.anilili.errors.AnililiFailure
import com.anilili.errors.raise
import com.anilili.errors.toFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class DiagnosticTrigger(val logValue: String) {
    MANUAL("manual"),
    CRASH("crash"),
    SLOW_START("slow_start"),
    SHORTCUT("shortcut"),
}

sealed interface DiagnosticShareResult {
    /**
     * [savedPath] is where a viewer can find the report themselves, or null if the copy could not
     * be made. It is named in the message because the share sheet is not reliable on a television
     * and a viewer who cannot get past it still needs to be able to reach the file -- see
     * [DiagnosticsExport].
     */
    data class Started(val savedPath: String?) : DiagnosticShareResult

    data class Failed(
        val failure: AnililiFailure,
    ) : DiagnosticShareResult

    /**
     * A second request arriving while the first is still being built.
     *
     * Held apart from [Failed] because nothing failed and there is nothing to report: the report a
     * viewer asked for is on its way. Saying "couldn't prepare the report" to someone who pressed
     * the button twice would be both wrong and alarming — and this is the diagnostics screen, where
     * a spurious error is the last thing anyone needs.
     */
    data object AlreadyPreparing : DiagnosticShareResult

    fun userMessage(): String = when (this) {
        is Started -> savedPath
            ?.let { "Diagnostic report ready to send. Also saved to $it" }
            ?: "Diagnostic report ready to send"
        is AlreadyPreparing -> "A diagnostic report is already being prepared"
        is Failed -> failure.withCode()
    }
}

/** Builds a redacted report and opens Android's share sheet after an explicit user action. */
object DiagnosticsShareManager {
    private const val LEGACY_PENDING_DIRECTORY = "diagnostic_uploads"
    private const val SCREENSHOT_DIRECTORY = "diagnostic-submission"
    private val sharing = AtomicBoolean(false)

    suspend fun share(
        context: Context,
        trigger: DiagnosticTrigger,
        submission: DiagnosticSubmissionInput = DiagnosticSubmissionInput(),
    ): DiagnosticShareResult {
        if (!sharing.compareAndSet(false, true)) return DiagnosticShareResult.AlreadyPreparing
        val app = context.applicationContext
        var screenshotFile: File? = null
        return try {
            val report = withContext(Dispatchers.IO) {
                discardLegacyPendingReports(app)
                val screenshot = submission.screenshotUri?.let { uri ->
                    val directory = File(app.cacheDir, SCREENSHOT_DIRECTORY).apply { mkdirs() }
                    copyScreenshot(app, uri, directory.resolve("selected-screenshot-${UUID.randomUUID()}"))
                        .also { screenshotFile = it.file }
                }
                DiagnosticsLog.event(
                    "share",
                    "report.requested",
                    mapOf("trigger" to trigger.logValue),
                )
                DiagnosticsLog.createShareSnapshot(app, submission.description, screenshot)
            }
            // Copied before the sheet opens, so the viewer has the file even if the chooser
            // never gets anywhere. Failure here is deliberately not fatal to sharing.
            val savedPath = withContext(Dispatchers.IO) {
                DiagnosticsExport.saveToDownloads(app, report)
            }
            withContext(Dispatchers.Main.immediate) {
                openShareSheet(context, report)
            }
            DiagnosticsLog.event(
                "share",
                "report.share_sheet_opened",
                mapOf("trigger" to trigger.logValue, "bytes" to report.length()),
            )
            DiagnosticShareResult.Started(savedPath)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // A screenshot that could not be read keeps DIAG-02 through this: the fix is to pick a
            // different file, which is not what DIAG-01 would tell them to do.
            DiagnosticShareResult.Failed(
                error.toFailure(
                    AnililiError.DIAGNOSTICS_BUNDLE_FAILED,
                    attributes = mapOf(
                        "trigger" to trigger.logValue,
                        "hasScreenshot" to (submission.screenshotUri != null),
                    ),
                ),
            )
        } finally {
            runCatching { screenshotFile?.delete() }
            runCatching { File(app.cacheDir, SCREENSHOT_DIRECTORY).delete() }
            sharing.set(false)
        }
    }

    /** Deletes reports left by versions that staged private-service uploads for WorkManager. */
    fun discardLegacyPendingReports(context: Context) {
        val directory = File(context.applicationContext.filesDir, LEGACY_PENDING_DIRECTORY)
        if (!directory.exists()) return
        if (directory.deleteRecursively()) {
            DiagnosticsLog.event("Discarded legacy pending diagnostics")
        }
    }

    private fun openShareSheet(context: Context, report: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            report,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, "Anilili diagnostic report")
            putExtra(Intent.EXTRA_TEXT, "Anilili diagnostic report attached.")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "Anilili diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Send diagnostics")
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun copyScreenshot(
        context: Context,
        uri: android.net.Uri,
        destination: File,
    ): DiagnosticScreenshot {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        // Every refusal here is the same one entry, DIAG-02, and every one of them says the same
        // thing to the viewer: pick a different file. What distinguishes them — too big, empty, a
        // PDF — is the detail, which is exactly the split this catalogue is for.
        fun refuse(detail: String): Nothing = AnililiError.DIAGNOSTICS_SCREENSHOT_FAILED.raise(detail = detail)
        try {
            var total = 0L
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val chunk = ByteArray(16 * 1_024)
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        total += read
                        if (total > DiagnosticSubmissionPolicy.MAX_SCREENSHOT_BYTES) {
                            refuse("The screenshot is larger than 5 MB.")
                        }
                        output.write(chunk, 0, read)
                    }
                }
            } ?: refuse("Nothing could be read from the chosen file.")
            if (total <= 0L) refuse("The chosen file is empty.")
            val contentType = temporary.inputStream().buffered().use { input ->
                val header = ByteArray(12)
                val read = input.read(header)
                DiagnosticSubmissionPolicy.screenshotContentType(header.copyOf(maxOf(0, read)))
            } ?: refuse("Choose a JPEG, PNG or WebP screenshot.")
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                if (!temporary.delete()) refuse("The copied screenshot could not be finalised.")
            }
            return DiagnosticScreenshot(destination, contentType)
        } finally {
            runCatching { temporary.delete() }
        }
    }
}
