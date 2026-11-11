package com.anilili.data.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import com.anilili.data.profiles.PhoneUploadKind
import com.anilili.data.profiles.ProfilePictures
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.rethrowIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Pictures the viewer chose as the app's background, from this device's storage or sent from a
 * phone to a television.
 *
 * Each is decoded, shrunk to at most [MAX_LONG_SIDE_PX] on its long side and re-encoded as a JPEG,
 * so what is kept is small and always decodable. They live device-wide under `files/app_backgrounds`
 * and a profile's settings name one by its `file://` URL.
 *
 * Television profiles copy settings from each other, so two profiles can name the same file; that
 * is why a replaced picture is not deleted on the spot. The newest [KEEP] are kept instead, which
 * covers every profile's current choice unless several have changed theirs in quick succession —
 * and a picture that has gone missing just draws the plain canvas.
 */
object AppBackdropImages {
    private const val DIR = "app_backgrounds"

    /** Enough for a 1440p phone held either way; the background is drawn at half this or less. */
    private const val MAX_LONG_SIDE_PX = 2560
    private const val JPEG_QUALITY = 88
    private const val KEEP = 6

    /** A camera original is fine from local storage; this only turns away what is not a photo at all. */
    private const val MAX_LOCAL_BYTES = 40L * 1024 * 1024

    /** From a phone, which shrinks the photo before sending it (see [phoneUpload]). */
    private const val MAX_PHONE_BYTES = 4L * 1024 * 1024

    private const val MIN_SIDE_PX = 320

    /** What a television shows the phone when it asks for a background picture. */
    val phoneUpload = PhoneUploadKind(
        heading = "Background for your TV",
        intro = "Pick a photo. It fills the app's background, darkened so everything stays readable.",
        maxBytes = MAX_PHONE_BYTES,
        minSide = MIN_SIDE_PX,
        shrinkShortSide = 1440,
        wide = true,
        tooLargeMessage = "That photo is too large. Pick one under ${MAX_PHONE_BYTES / (1024 * 1024)} MB.",
        import = { context, input -> import(context, input, MAX_PHONE_BYTES) },
    )

    /** From this device's own storage, through the system picker. */
    suspend fun import(context: Context, uri: Uri): ProfilePictures.Result {
        val stream = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        } ?: return ProfilePictures.Result.Rejected("That file could not be opened. Try another.", 400)
        return import(context, stream, MAX_LOCAL_BYTES)
    }

    /** Checks and stores the picture read from [source], which is closed afterwards. */
    suspend fun import(context: Context, source: InputStream, maxBytes: Long): ProfilePictures.Result =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val staged = File(app.cacheDir, "app-background-${UUID.randomUUID()}.upload")
            try {
                val copied = source.use { input -> staged.outputStream().use { copyCapped(input, it, maxBytes) } }
                    ?: return@withContext ProfilePictures.Result.Rejected(
                        "That photo is too large. Pick one under ${maxBytes / (1024 * 1024)} MB.",
                        413,
                    )
                if (copied == 0L) {
                    return@withContext ProfilePictures.Result.Rejected("That file was empty. Try another photo.", 400)
                }
                store(app, staged)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                DiagnosticsLog.throwable("App background import failed", e)
                ProfilePictures.Result.Rejected("The photo could not be read. Try again, or pick another.", 500)
            } finally {
                staged.delete()
            }
        }

    private suspend fun store(context: Context, staged: File): ProfilePictures.Result {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(staged.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return ProfilePictures.Result.Rejected("That isn't a picture the app can read. Try a JPEG or PNG photo.", 415)
        }
        if (minOf(bounds.outWidth, bounds.outHeight) < MIN_SIDE_PX) {
            return ProfilePictures.Result.Rejected(
                "That picture is too small (${bounds.outWidth}×${bounds.outHeight}). " +
                    "Pick one at least $MIN_SIDE_PX pixels on each side.",
                422,
            )
        }
        // Coil rather than BitmapFactory: it turns a phone photo the way its EXIF says, and samples
        // a 50-megapixel original down while decoding instead of allocating all of it.
        val request = ImageRequest.Builder(context)
            .data(staged)
            .size(MAX_LONG_SIDE_PX)
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
        val decoded = (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
            ?: return ProfilePictures.Result.Rejected("That isn't a picture the app can read. Try a JPEG or PNG photo.", 415)
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { decoded.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        DiagnosticsLog.event(
            "App background stored from ${bounds.outWidth}x${bounds.outHeight} ${bounds.outMimeType} " +
                "bytes=${staged.length()} -> ${decoded.width}x${decoded.height} ${file.length()}",
        )
        prune(dir, keep = file)
        return ProfilePictures.Result.Saved(Uri.fromFile(file).toString())
    }

    /** Keeps the newest [KEEP] pictures, [keep] always among them. */
    private fun prune(dir: File, keep: File) {
        dir.listFiles()
            ?.filter { it != keep }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP - 1)
            ?.forEach { it.delete() }
    }

    /** Copies at most [maxBytes]; null when the source had more. */
    private fun copyCapped(input: InputStream, output: java.io.OutputStream, maxBytes: Long): Long? {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
    }
}
