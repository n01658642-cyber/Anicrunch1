package com.anilili.playback

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.anilili.util.cached

/**
 * Points every URL inside a playlist back at [LocalStreamProxy].
 *
 * Lending headers to the manifest alone buys nothing: a CDN that gates the playlist gates the
 * segments and the AES key the same way, and those are fetched from the URLs written *inside* it.
 * So each one is registered with the proxy in turn and replaced by its loopback address, and the
 * other player never makes a request of its own to the CDN.
 *
 * Rewriting is textual and deliberately conservative — an unrecognised tag is copied through
 * untouched — because a playlist this app cannot fully parse is still one the receiving player
 * usually can, and dropping a line it needed would turn a playable stream into a broken one.
 */

/** The tags whose `URI="…"` attribute names something that must also be fetched. */
private val URI_ATTRIBUTE_TAGS = listOf(
    "#EXT-X-KEY",
    "#EXT-X-SESSION-KEY",
    "#EXT-X-MAP",
    "#EXT-X-MEDIA",
    "#EXT-X-I-FRAME-STREAM-INF",
    "#EXT-X-PART",
    "#EXT-X-PRELOAD-HINT",
    "#EXT-X-RENDITION-REPORT",
)

/**
 * Whether a response body is a playlist rather than media.
 *
 * The content type is checked first but not trusted alone: plenty of these CDNs serve a manifest as
 * `application/octet-stream` or `text/plain`, and one served under the wrong type that is not
 * rewritten sends the receiving player straight back to the CDN bare. The body's own first line is
 * the only reliable witness, and `#EXTM3U` is mandatory as the first line of every playlist.
 */
internal fun looksLikeHlsPlaylist(contentType: String?, body: ByteArray): Boolean {
    if (isDeclaredHlsPlaylist(contentType)) return true
    // A few otherwise valid manifests arrive with a UTF-8 BOM or blank lines in front of the
    // marker. Players tolerate that input, so failing to recognise it here would let every URI in
    // the playlist escape the proxy and lose its gated headers.
    return body.decodeToString()
        .removePrefix("\uFEFF")
        .trimStart()
        .startsWith("#EXTM3U")
}

internal fun isDeclaredHlsPlaylist(contentType: String?): Boolean {
    val declared = contentType.orEmpty().substringBefore(';').trim().lowercase()
    return declared in HLS_CONTENT_TYPES
}

private val HLS_CONTENT_TYPES = setOf(
    "application/vnd.apple.mpegurl",
    "application/x-mpegurl",
    "application/mpegurl",
    "audio/mpegurl",
    "audio/x-mpegurl",
)

/** Enough to recognise a mislabelled manifest without delaying an ordinary media response. */
internal const val PLAYLIST_SNIFF_BYTES = 512

/** A manifest larger than this is passed through unchanged instead of being retained in memory. */
internal const val MAX_PLAYLIST_BYTES = 1024 * 1024

/**
 * Rewrites [playlist], resolving every URL it names against [playlistUrl] and handing it to
 * [register], which returns the loopback address to use in its place.
 */
internal fun rewriteHlsPlaylist(
    playlist: String,
    playlistUrl: String,
    register: (String) -> String,
): String {
    val base = playlistUrl.toHttpUrlOrNull()
    val newline = if (playlist.contains("\r\n")) "\r\n" else "\n"
    return playlist.split(Regex.cached("\r\n|\n"))
        .joinToString(newline) { line -> rewriteLine(line, base, register) }
}

private fun rewriteLine(line: String, base: HttpUrl?, register: (String) -> String): String {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return line
    if (!trimmed.startsWith("#")) return replaceUri(line, trimmed, base, register)
    val tag = trimmed.substringBefore(':')
    if (tag !in URI_ATTRIBUTE_TAGS) return line
    return rewriteUriAttribute(line, base, register)
}

/**
 * Swaps the `URI="…"` value of a tag line, leaving every other attribute exactly as it was.
 *
 * Only the quoted form is handled because the specification requires it: an unquoted value here
 * would be a malformed playlist, and guessing where such a value ended would be how a comma inside
 * one silently truncated the URL.
 */
private fun rewriteUriAttribute(line: String, base: HttpUrl?, register: (String) -> String): String {
    val marker = URI_ATTRIBUTE.find(line) ?: return line
    val opening = marker.range.last + 1
    val closing = line.indexOf('"', opening)
    if (closing < 0) return line
    val original = line.substring(opening, closing)
    if (original.isBlank()) return line
    val replacement = resolveAndRegister(original, base, register) ?: return line
    return line.substring(0, opening) + replacement + line.substring(closing)
}

private val URI_ATTRIBUTE = Regex("""(?:^|[,:\s])URI\s*=\s*"""")

private fun replaceUri(line: String, trimmed: String, base: HttpUrl?, register: (String) -> String): String {
    val replacement = resolveAndRegister(trimmed, base, register) ?: return line
    return line.replace(trimmed, replacement)
}

/**
 * The loopback address for one URL named by a playlist, or null when it cannot be resolved.
 *
 * A reference that will not resolve is left alone rather than dropped: it is either already
 * absolute in a scheme this does not handle, or the playlist is malformed, and in both cases the
 * receiving player has a better chance with the original text than with nothing.
 */
private fun resolveAndRegister(reference: String, base: HttpUrl?, register: (String) -> String): String? {
    val resolved = base?.resolve(reference) ?: reference.toHttpUrlOrNull() ?: return null
    return register(resolved.toString())
}
