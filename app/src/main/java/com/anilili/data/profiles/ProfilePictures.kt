package com.anilili.data.profiles

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
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.rethrowIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Pictures people chose themselves for their profile, sent from their phone or picked from the
 * TV's own storage.
 *
 * Every picture is checked, cropped to a centred square and re-encoded as a [SIZE_PX] JPEG, so what
 * is kept is small and always decodable whatever was sent. They live device-wide under
 * `files/profile_pictures` — not with a profile's own files, which move aside on every switch — and
 * a profile refers to one by its `file://` URL in [LocalProfile.avatarUrl], so everything that draws
 * a profile's picture loads it exactly as it loads an AniList portrait.
 */
object ProfilePictures {
    private const val DIR = "profile_pictures"

    /** The stored square's side. Twice the largest place a profile picture is drawn (128dp at 2x). */
    private const val SIZE_PX = 512
    private const val JPEG_QUALITY = 88

    /**
     * The largest file accepted, from the phone or the TV's storage. The phone page shrinks a camera
     * original to fit before sending, so this mostly refuses what a browser could not shrink.
     */
    const val MAX_BYTES = 1L * 1024 * 1024

    /** Below this on the shorter side the picture would be visibly blurred even at the smallest size. */
    const val MIN_SIDE_PX = 128

    /**
     * How long a stored picture that no profile uses yet is kept. One just received is not saved to
     * its profile until the viewer presses Save, and must survive a prune in between.
     */
    private const val UNSAVED_GRACE_MS = 30L * 60 * 1000

    sealed interface Result {
        data class Saved(val url: String) : Result

        /** [message] is shown as it stands, on the TV and on the phone that sent the file. */
        data class Rejected(val message: String, val status: Int) : Result
    }

    val tooLargeMessage = "That photo is too large. Pick one under ${MAX_BYTES / (1024 * 1024)} MB."

    /** Whether [url] is one of these pictures rather than a character portrait. */
    fun isOwn(url: String?): Boolean = url != null && url.startsWith("file:") && url.contains("/$DIR/")

    /** Checks and stores the picture read from [source], which is closed afterwards. */
    suspend fun import(context: Context, source: InputStream): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val staged = File(app.cacheDir, "profile-picture-${UUID.randomUUID()}.upload")
        try {
            val copied = source.use { input ->
                staged.outputStream().use { output -> copyCapped(input, output) }
            }
            if (copied == null) return@withContext Result.Rejected(tooLargeMessage, 413)
            if (copied == 0L) return@withContext Result.Rejected("That file was empty. Try another photo.", 400)
            store(app, staged)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            DiagnosticsLog.throwable("Profile picture import failed", e)
            Result.Rejected("The photo could not be read. Try again, or pick another.", 500)
        } finally {
            staged.delete()
        }
    }

    /** From the TV's own storage, through the system picker. */
    suspend fun import(context: Context, uri: Uri): Result {
        val stream = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        } ?: return Result.Rejected("That file could not be opened. Try another.", 400)
        return import(context, stream)
    }

    private suspend fun store(context: Context, staged: File): Result {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(staged.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return Result.Rejected("That isn't a picture this TV can read. Try a JPEG or PNG photo.", 415)
        }
        if (minOf(bounds.outWidth, bounds.outHeight) < MIN_SIDE_PX) {
            return Result.Rejected(
                "That picture is too small (${bounds.outWidth}×${bounds.outHeight}). " +
                    "Pick one at least $MIN_SIDE_PX pixels on each side.",
                422,
            )
        }
        // Coil rather than BitmapFactory: it turns a phone photo the way its EXIF says, and samples
        // a 50-megapixel original down while decoding instead of allocating all of it.
        val request = ImageRequest.Builder(context)
            .data(staged)
            .size(SIZE_PX)
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
        val decoded = (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
            ?: return Result.Rejected("That isn't a picture this TV can read. Try a JPEG or PNG photo.", 415)
        val square = squareOf(decoded)
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        DiagnosticsLog.event(
            "Profile picture stored from ${bounds.outWidth}x${bounds.outHeight} ${bounds.outMimeType} " +
                "bytes=${staged.length()} -> ${file.length()}",
        )
        return Result.Saved(Uri.fromFile(file).toString())
    }

    /**
     * A centred square scaled to [SIZE_PX]. A tall picture is cut a little above centre, where a
     * face in a portrait photo usually is.
     */
    private fun squareOf(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        val x = (source.width - side) / 2
        val y = (source.height - side) / 3
        val cropped = Bitmap.createBitmap(source, x, y, side, side)
        return if (side == SIZE_PX) cropped else Bitmap.createScaledBitmap(cropped, SIZE_PX, SIZE_PX, true)
    }

    /** Copies at most [MAX_BYTES]; null when the source had more. */
    private fun copyCapped(input: InputStream, output: java.io.OutputStream): Long? {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > MAX_BYTES) return null
            output.write(buffer, 0, read)
        }
    }

    /**
     * Deletes pictures no profile uses any more: replaced ones, deleted profiles' ones, and ones
     * received and never saved once [UNSAVED_GRACE_MS] has passed.
     */
    fun prune(context: Context, inUse: Collection<String?>) {
        val dir = File(context.filesDir, DIR)
        val keep = inUse.filterNotNull().toSet()
        thread(name = "profile-picture-prune") {
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { file ->
                if (Uri.fromFile(file).toString() !in keep && now - file.lastModified() > UNSAVED_GRACE_MS) {
                    file.delete()
                }
            }
        }
    }
}
