package com.anilili.data.remote

import kotlinx.coroutines.CancellationException

/** A header alone, an HTML error, or an I-frame-only playlist is not playable video. */
internal fun megaPlayManifestCanPlay(body: String?): Boolean {
    val lines = body?.trimStart()?.lineSequence()?.iterator() ?: return false
    if (!lines.hasNext() || lines.next().trim() != "#EXTM3U") return false
    var expectsUri = false
    for (raw in lines) {
        val line = raw.trim()
        if (expectsUri && line.isNotEmpty() && !line.startsWith('#')) return true
        expectsUri = line.startsWith("#EXT-X-STREAM-INF:") || line.startsWith("#EXTINF:")
    }
    return false
}

/**
 * Masters now include lower qualities (verified on Bleach and Liar Game, 2026-09-22).
 * The old rendition remains a fallback for the historical master-403 failure, never the
 * first choice. A double refusal returns null so the caller can retain its embed fallback.
 */
internal suspend fun resolveMegaPlayManifest(file: String, fetch: suspend (String) -> String): String? {
    suspend fun playable(url: String): Boolean = try {
        megaPlayManifestCanPlay(fetch(url))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
    if (playable(file)) return file
    val legacy = megaPlayRenditionUrl(file) ?: return null
    return legacy.takeIf { playable(it) }
}
