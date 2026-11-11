package com.anilili.playback

/**
 * The hosts a media playlist points at other than its own.
 *
 * Several providers keep their media somewhere else entirely: bonk's playlist is on vivibebe.site
 * and all 143 of its segments are on ByteDance's `p16-ad-sg.ibyteimg.com`, and AniBD's 239 are on
 * `ani9.nukitashi.top`. When those segments are refused the episode ends, and the player's own
 * error names neither the host nor the fact that it was never the playlist's host to begin with.
 *
 * That distinction is the whole value here. A lone stranger among a stream's own segments would be
 * something spliced in; a host carrying the entire episode is simply where the media lives, and
 * treating the two alike would mean deleting a stream to save it.
 *
 * Reported as hosts rather than URLs: the interesting fact is *whose* segments they are, and a
 * segment URL can carry a token.
 */
internal fun playlistForeignHosts(playlist: String, ownHost: String): List<String> =
    segmentHosts(playlist, ownHost).filterNotNull().distinct()

/** How many URI lines a playlist has, and how many of them leave its host. */
internal fun playlistSegmentCensus(playlist: String, ownHost: String): Pair<Int, Int> {
    val hosts = segmentHosts(playlist, ownHost)
    return hosts.size to hosts.count { it != null }
}

/** One entry per segment, in order: the foreign host it points at, or null when it is our own. */
private fun segmentHosts(playlist: String, ownHost: String): List<String?> {
    if (!playlist.startsWith("#EXTM3U")) return emptyList()
    return playlist.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { hostOf(it, ownHost) }
        .toList()
}

/** The foreign host of a segment line, or null when it is relative or on [ownHost]. */
private fun hostOf(line: String, ownHost: String): String? {
    // Only an absolute URL can leave the host; a relative segment is by definition our own.
    val withoutScheme = line.substringAfter("://", missingDelimiterValue = "")
    if (withoutScheme.isEmpty()) return null
    val host = withoutScheme.substringBefore('/')
        .substringAfter('@')
        .substringBefore(':')
        .lowercase()
        .removePrefix("www.")
    val own = ownHost.lowercase().removePrefix("www.")
    return host.takeIf { it.isNotEmpty() && it != own }
}
