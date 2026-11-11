package com.anilili.errors

import android.media.MediaCodec
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.transformer.ExportException

/**
 * Borrowed error codes, rather than invented ones.
 *
 * Where the platform already numbers a failure, that number is better than anything this catalog
 * could make up: Media3's `ERROR_CODE_IO_BAD_HTTP_STATUS` (2004) and an HTTP 403 are precise,
 * documented, and stable across app versions. So the app's own code says *which path gave up* and
 * the platform code says *why*, and a report carries both: `PLAY-02/h403`.
 *
 * What the platform cannot do is cover the rest. There is no Android or Kotlin code for "no server
 * offered a downloadable copy" or "the MP4 has no usable video configuration" — those are this
 * app's own failures. And falling back to an exception's class name only looks free: R8 minifies
 * release builds here, so `javaClass.simpleName` reaches you as `a.b.c` and has to be deobfuscated
 * against the mapping archive before it means anything. Hence a borrowed code where one exists, and
 * a catalogued one always.
 *
 * The one-letter prefix keeps the numbering spaces apart, because `403` and `2004` come from
 * different vocabularies and a bare number would not say which:
 *
 * | Prefix | Source |
 * | --- | --- |
 * | `h` | HTTP response status |
 * | `m` | `PlaybackException.errorCode` |
 * | `x` | `ExportException.errorCode` |
 * | `c` | `MediaCodec.CodecException.errorCode` |
 */
@OptIn(UnstableApi::class)
fun Throwable.platformErrorCode(): String? = causeChain().firstNotNullOfOrNull { error ->
    when {
        // Most specific first. A bad status arrives wrapped in a PlaybackException whose own code
        // is the generic ERROR_CODE_IO_BAD_HTTP_STATUS, so reading the outer one loses the status
        // that actually explains the refusal.
        error is HttpDataSource.InvalidResponseCodeException -> "h${error.responseCode}"
        error is MediaCodec.CodecException && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
            "c${error.errorCode}"
        error is ExportException -> "x${error.errorCode}"
        error is PlaybackException -> "m${error.errorCode}"
        else -> null
    }
}

/**
 * The platform's own name for that code, for the detail sheet and the diagnostics attributes.
 *
 * `ERROR_CODE_IO_BAD_HTTP_STATUS` is worth far more than `2004` to whoever reads the bundle, and it
 * costs nothing to record both.
 */
@OptIn(UnstableApi::class)
fun Throwable.platformErrorName(): String? = causeChain().firstNotNullOfOrNull { error ->
    when {
        error is HttpDataSource.InvalidResponseCodeException -> "HTTP ${error.responseCode}"
        error is MediaCodec.CodecException -> error.diagnosticInfo
        error is ExportException -> error.errorCodeName
        error is PlaybackException -> error.errorCodeName
        else -> null
    }
}

/**
 * A short technical line for the detail sheet: the exception type and its message, deepest cause
 * first, deduplicated.
 *
 * Types are printed as written even though R8 renames them — inside a diagnostics bundle the
 * mapping archive resolves them, and this string is never the primary identity of anything. The
 * code is.
 */
fun Throwable.technicalDetail(limit: Int = 3): String = causeChain()
    .map { error ->
        val type = error.javaClass.simpleName.ifBlank { error.javaClass.name }
        error.message?.takeIf(String::isNotBlank)?.let { "$type: $it" } ?: type
    }
    .distinct()
    .take(limit)
    .joinToString(" ← ")

/** Bounded so a self-referential cause chain cannot hang the caller. */
internal fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { current ->
    current.cause?.takeIf { it !== current }
}.take(8)
