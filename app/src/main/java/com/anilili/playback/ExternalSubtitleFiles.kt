package com.anilili.playback

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MimeTypes
import com.anilili.data.AppGraph
import com.anilili.data.model.SubtitleItem
import com.anilili.data.remote.OpenSubtitleSearchResult
import com.anilili.data.remote.OpenSubtitlesClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import okhttp3.Request
import com.anilili.util.cached

data class AttachedSubtitle(
    val subtitle: SubtitleItem,
    val remainingDownloads: Int? = null,
)

/** Copies a document-provider URI into the app cache so playback does not depend on picker grants. */
fun importSubtitleFile(context: Context, uri: Uri): AttachedSubtitle {
    val displayName = context.displayName(uri).ifBlank { "subtitle" }
    val reportedSize = context.reportedSize(uri)
    if (reportedSize != null && reportedSize > MAX_SUBTITLE_BYTES) {
        throw IOException("Subtitle files must be smaller than 8 MB.")
    }
    val mimeType = subtitleMimeTypeFor(displayName, context.contentResolver.getType(uri))
        ?: throw IOException("Choose an SRT, VTT, ASS, SSA, TTML, or XML subtitle file.")
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readSubtitleBytes() }
        ?: throw IOException("The selected subtitle file could not be opened.")
    // Decode before the file exists, so a rejected import leaves nothing behind in the cache.
    val target = context.subtitleCacheFile(displayName, mimeType)
    target.writeText(decodeSubtitleBytes(bytes, languageHint = null))
    return AttachedSubtitle(
        SubtitleItem(
            url = Uri.fromFile(target).toString(),
            label = "Imported · $displayName",
            language = "und",
            mimeType = mimeType,
        ),
    )
}

/** Requests a free OpenSubtitles download and stores the temporary response in the app cache. */
fun downloadOpenSubtitle(
    context: Context,
    openSubtitles: OpenSubtitlesClient,
    result: OpenSubtitleSearchResult,
): AttachedSubtitle {
    val download = openSubtitles.requestDownload(result.fileId)
    val displayName = download.fileName.ifBlank { result.fileName }.ifBlank { "subtitle.srt" }
    val mimeType = subtitleMimeTypeFor(displayName, null) ?: MimeTypes.APPLICATION_SUBRIP
    val request = Request.Builder().url(download.url).get().build()
    val bytes = AppGraph.httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Subtitle download HTTP ${response.code}")
        val length = response.body?.contentLength()?.takeIf { it >= 0 }
        if (length != null && length > MAX_SUBTITLE_BYTES) {
            throw IOException("The subtitle download is larger than 8 MB.")
        }
        response.body?.byteStream()?.use { it.readSubtitleBytes() }
            ?: throw IOException("OpenSubtitles returned an empty file.")
    }
    val target = context.subtitleCacheFile(displayName, mimeType)
    target.writeText(decodeSubtitleBytes(bytes, languageHint = result.language))
    return AttachedSubtitle(
        subtitle = SubtitleItem(
            url = Uri.fromFile(target).toString(),
            label = "OpenSubtitles · ${result.language.uppercase()} · ${result.releaseName}",
            language = result.language,
            mimeType = mimeType,
        ),
        remainingDownloads = download.remainingDownloads,
    )
}

/**
 * The format of an exported sidecar recorded before [ExportedSubtitle.mimeType] existed. Its
 * `content://` URI carries no extension, but the published file's display name does.
 */
fun exportedSubtitleMimeType(context: Context, subtitle: ExportedSubtitle): String? =
    subtitle.mimeType ?: runCatching {
        val uri = Uri.parse(subtitle.uri)
        subtitleMimeTypeFor(context.displayName(uri), context.contentResolver.getType(uri))
    }.getOrNull()

/** The file extension for a stated subtitle MIME type, or null when it names none of ours. */
internal fun subtitleExtensionFor(mimeType: String?): String? = when (mimeType?.lowercase()) {
    null -> null
    MimeTypes.TEXT_VTT -> "vtt"
    in SUBRIP_MIME_TYPES -> "srt"
    in SSA_MIME_TYPES -> "ass"
    in TTML_MIME_TYPES -> "ttml"
    else -> null
}

internal fun subtitleMimeTypeFor(fileName: String, reportedMimeType: String?): String? {
    val path = fileName.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".vtt") || reportedMimeType == MimeTypes.TEXT_VTT -> MimeTypes.TEXT_VTT
        path.endsWith(".srt") || reportedMimeType in SUBRIP_MIME_TYPES -> MimeTypes.APPLICATION_SUBRIP
        path.endsWith(".ass") || path.endsWith(".ssa") || reportedMimeType in SSA_MIME_TYPES -> MimeTypes.TEXT_SSA
        path.endsWith(".ttml") || path.endsWith(".xml") || reportedMimeType in TTML_MIME_TYPES ->
            MimeTypes.APPLICATION_TTML
        else -> null
    }
}

internal fun Context.subtitleCacheFile(displayName: String, mimeType: String): File {
    val directory = File(cacheDir, "external-subtitles").apply { mkdirs() }
    // Every attachment used to stay here for good: up to 8 MB apiece, kept until Android decided
    // to clear the whole cache directory. Only the newest handful can still be attached to a
    // player, so the rest go.
    directory.listFiles()
        ?.filter(File::isFile)
        ?.sortedByDescending(File::lastModified)
        ?.drop(MAX_CACHED_SUBTITLES)
        ?.forEach { it.delete() }
    val extension = displayName.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it in SUPPORTED_EXTENSIONS }
        ?: extensionFor(mimeType)
    return File(directory, "${UUID.randomUUID()}.$extension")
}

private fun Context.displayName(uri: Uri): String = queryValue(uri, OpenableColumns.DISPLAY_NAME)
    ?.take(160)
    ?.replace(Regex.cached("[\\r\\n\\t]"), " ")
    .orEmpty()

private fun Context.reportedSize(uri: Uri): Long? =
    queryValue(uri, OpenableColumns.SIZE)?.toLongOrNull()?.takeIf { it >= 0 }

private fun Context.queryValue(uri: Uri, column: String): String? = runCatching {
    contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor: Cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(column)
        if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
    }
}.getOrNull()

internal fun InputStream.readSubtitleBytes(): ByteArray {
    val collected = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        collected.write(buffer, 0, read)
        if (collected.size() > MAX_SUBTITLE_BYTES) {
            throw IOException("Subtitle files must be smaller than 8 MB.")
        }
    }
    return collected.toByteArray()
}

/**
 * Subtitle text as UTF-8, whatever it arrived as. Media3's parsers read subtitle files as UTF-8,
 * but a large part of the OpenSubtitles catalogue predates it and is written in a legacy
 * single-byte code page — Cyrillic files in windows-1251, western European ones in windows-1252 —
 * and hand-picked files carry whatever the uploader's editor used. Handing those bytes to the
 * player unchanged renders accented and non-Latin text as mojibake, so they are converted here.
 *
 * A byte-order mark decides outright. Otherwise a strict UTF-8 decode is attempted, since valid
 * UTF-8 is not plausibly anything else, and only when that fails does the file's declared language
 * pick the legacy code page.
 */
internal fun decodeSubtitleBytes(bytes: ByteArray, languageHint: String?): String {
    bomCharset(bytes)?.let { (charset, offset) ->
        return String(bytes, offset, bytes.size - offset, charset)
    }
    val strictUtf8 = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    runCatching { strictUtf8.decode(ByteBuffer.wrap(bytes)).toString() }
        .onSuccess { return it }
    return String(bytes, legacyCharsetFor(languageHint))
}

/** The charset a byte-order mark names, with the number of bytes the mark itself occupies. */
private fun bomCharset(bytes: ByteArray): Pair<Charset, Int>? = when {
    bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
        StandardCharsets.UTF_8 to 3
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
        StandardCharsets.UTF_16LE to 2
    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
        StandardCharsets.UTF_16BE to 2
    else -> null
}

/**
 * The code page a non-UTF-8 subtitle in this language is overwhelmingly likely to be in. Western
 * European is the fallback because it is both the most common case and the most forgiving: every
 * byte maps to some character, so a wrong guess mangles accents rather than failing outright.
 */
private fun legacyCharsetFor(languageHint: String?): Charset {
    val language = languageHint.orEmpty().lowercase().substringBefore('-').substringBefore('_')
    val name = when (language) {
        "ru", "uk", "bg", "sr", "mk", "be" -> "windows-1251"
        "pl", "cs", "sk", "hu", "ro", "hr", "sl", "bs" -> "windows-1250"
        "el" -> "windows-1253"
        "tr" -> "windows-1254"
        "he" -> "windows-1255"
        "ar", "fa", "ur" -> "windows-1256"
        "th" -> "windows-874"
        "vi" -> "windows-1258"
        "ja" -> "Shift_JIS"
        "ko" -> "EUC-KR"
        "zh" -> "GBK"
        else -> "windows-1252"
    }
    // Not every code page is present on every device or JVM; ISO-8859-1 always is, and like
    // windows-1252 it decodes any byte sequence rather than throwing.
    return runCatching { Charset.forName(name) }.getOrDefault(StandardCharsets.ISO_8859_1)
}

private fun extensionFor(mimeType: String): String = when (mimeType) {
    MimeTypes.TEXT_VTT -> "vtt"
    MimeTypes.TEXT_SSA -> "ass"
    MimeTypes.APPLICATION_TTML -> "ttml"
    else -> "srt"
}

internal const val MAX_SUBTITLE_BYTES = 8L * 1024 * 1024
private const val MAX_CACHED_SUBTITLES = 20
private val SUPPORTED_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "ttml", "xml")
private val SUBRIP_MIME_TYPES = setOf("application/x-subrip", "application/srt", "text/srt")
private val SSA_MIME_TYPES = setOf("text/x-ssa", "application/x-ssa", "text/ass", "application/ass")
private val TTML_MIME_TYPES = setOf("application/ttml+xml", "text/xml", "application/xml")
