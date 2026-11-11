package com.anilili.playback

/**
 * What a refused media request was, in a form that can go in a diagnostics bundle.
 *
 * A bare "Response code: 403" says nothing that can be acted on. Whether the refusal is the
 * playlist or a segment, whether the URL carries an expiry or a signature, and what the CDN said
 * about itself are the differences between a link that has timed out, one that is bound to another
 * viewer, and a host refusing us on sight — and they need different fixes.
 *
 * Values are deliberately left out. A signed media URL's query carries the token that grants access
 * to it, and these bundles get shared; the parameter *names* answer "is this link signed and does
 * it expire" without carrying the credential itself. The path keeps only its last segment for the
 * same reason.
 */
internal fun httpFailureSummary(
    responseCode: Int,
    url: String,
    headers: Map<String, List<String>> = emptyMap(),
): String {
    // Parsed by hand rather than through android.net.Uri, which is an inert stub under plain JVM
    // unit tests — routing this through it would make the whole thing untestable.
    val withoutScheme = url.substringAfter("://", missingDelimiterValue = "")
    val authority = withoutScheme.substringBefore('/')
    val host = authority.substringAfter('@').substringBefore(':').ifBlank { "unknown" }
    val pathAndRest = withoutScheme.removePrefix(authority)
    val path = pathAndRest.substringBefore('?').substringBefore('#')
    // The kind is decided from the real last segment, before it is given a placeholder for
    // display: defaulting first made an empty URL look like a request for a segment named "/".
    val rawFile = path.trimEnd('/').substringAfterLast('/')
    val file = rawFile.ifBlank { "/" }
    val query = pathAndRest.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
    val params = query.split('&')
        .filter(String::isNotBlank)
        .map { it.substringBefore('=') }
        .filter(String::isNotBlank)

    val kind = when {
        rawFile.endsWith(".m3u8", ignoreCase = true) -> "playlist"
        rawFile.endsWith(".key", ignoreCase = true) || rawFile.endsWith(".bin", ignoreCase = true) -> "key"
        rawFile.isNotBlank() -> "segment"
        else -> "unknown"
    }
    // Which of these is present is the whole question: an expiry means the link timed out, a
    // signature means it was minted for someone else, and neither means the host refused us.
    val signed = params.any { it.lowercase() in SIGNATURE_PARAMS }
    val expiring = params.any { it.lowercase() in EXPIRY_PARAMS }
    val said = INTERESTING_HEADERS.mapNotNull { name ->
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull()?.take(40)?.let { "$name=$it" }
    }

    return buildString {
        append("code=$responseCode kind=$kind host=$host file=${file.take(48)}")
        append(" params=${if (params.isEmpty()) "none" else params.joinToString(",").take(80)}")
        append(" signed=$signed expiring=$expiring")
        if (said.isNotEmpty()) append(" ${said.joinToString(" ")}")
    }
}

private val SIGNATURE_PARAMS = setOf("token", "sig", "signature", "hash", "md5", "key", "s", "st")
private val EXPIRY_PARAMS = setOf("expires", "expiry", "exp", "e", "validto", "ttl", "t")
private val INTERESTING_HEADERS = listOf("server", "cf-ray", "cf-cache-status", "x-cache", "content-type")
