package com.anilili.ui.nav

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.URLEncoder
import kotlin.math.floor

/**
 * `anilili://` links, the format both Anilili apps open.
 *
 * ```text
 * anilili://anime/<anilist id>                        the title's page
 * anilili://watch/<anilist id>/<episode>[?t=<secs>]   play an episode, from `t` if given
 * anilili://search?q=<text>                           search
 * ```
 *
 * The desktop app defined the format (`../anilili-desktop/docs/LINKS.md`) and its reader is
 * `crates/core/src/links.rs`. This is a port of that reader, not a second design: the cases both
 * must answer alike are `fixtures/parity/links.json` there, which the `syncParityCorpus` task
 * copies into this module's test resources and `AnililiLinkTest` runs. A case the two disagree on is settled in that file
 * first, then in both readers.
 *
 * Pure Kotlin on purpose. `android.net.Uri` would do the splitting, but it is a stub under unit
 * tests, and its idea of a host differs from the `url` crate's in exactly the forms the desktop
 * accepts (`anilili:anime/1` has no host for either, `anilili:///anime/1` an empty one).
 */
sealed interface AnililiLink {
    data class Anime(val id: Int) : AnililiLink

    /** [episode] is AniList's number, fractional for a recap; [startSeconds] is null for "from the saved position". */
    data class Watch(val id: Int, val episode: Double, val startSeconds: Double? = null) : AnililiLink {
        /** The episode as the routes and both apps write it: `6.5`, `3` (never `3.0`). */
        val episodeLabel: String get() = number(episode)
    }

    data class Search(val query: String) : AnililiLink

    /** The canonical form, the one to hand to anyone else. */
    fun toUrl(): String = when (this) {
        is Anime -> "$SCHEME://anime/$id"
        is Watch -> "$SCHEME://watch/$id/$episodeLabel" +
            (startSeconds?.let { "?t=${floor(it).toLong()}" } ?: "")
        // URLEncoder is application/x-www-form-urlencoded, as the desktop's `append_pair` is:
        // the same unreserved set, and a space written as `+`.
        is Search -> "$SCHEME://search?q=${URLEncoder.encode(query, "UTF-8")}"
    }

    sealed interface Reading {
        data class Read(val link: AnililiLink) : Reading

        /** [why] is for diagnostics only; the viewer is told `DLINK-01` and nothing more. */
        data class Refused(val why: String) : Reading
    }

    companion object {
        const val SCHEME = "anilili"

        fun parse(text: String): AnililiLink? = (read(text) as? Reading.Read)?.link

        /**
         * Reads a link. Also accepted, as on desktop: `anilili:///anime/1`, `anilili:anime/1`,
         * surrounding whitespace, a trailing `/`, any letter case in the scheme, and query
         * parameters it doesn't know (a newer app can add some). Anything else is refused,
         * including a known page with a missing or invalid id or episode.
         */
        fun read(text: String): Reading {
            val link = text.trim()
            fun refuse(why: String) = Reading.Refused("$why: ${shorten(link)}")

            val colon = link.indexOf(':')
            if (colon <= 0) return refuse("not a link")
            if (!link.substring(0, colon).equals(SCHEME, ignoreCase = true)) return refuse("not an anilili:// link")
            val rest = link.substring(colon + 1).substringBefore('#')
            val query = rest.substringAfter('?', missingDelimiterValue = "")
            var path = rest.substringBefore('?')
            // `anilili://anime/1` carries the page as its host; the other two forms carry it in the
            // path. Joined, all three read the same.
            val parts = mutableListOf<String>()
            if (path.startsWith("//")) {
                val hostEnd = path.indexOf('/', startIndex = 2).let { if (it < 0) path.length else it }
                parts += path.substring(2, hostEnd)
                path = path.substring(hostEnd)
            }
            parts += path.split('/')
            val segments = parts.filter { it.isNotEmpty() }

            return when {
                segments.size == 2 && segments[0] == "anime" ->
                    id(segments[1])?.let { Reading.Read(Anime(it)) } ?: refuse("no AniList id")
                segments.size == 3 && segments[0] == "watch" -> {
                    val id = id(segments[1]) ?: return refuse("no AniList id")
                    val episode = decimal(segments[2])?.takeIf { it.isFinite() && it >= 0.0 }
                        ?: return refuse("no episode")
                    // A `t` that isn't a positive number is dropped, not refused: the episode is
                    // still worth opening.
                    val start = queryValue(query, "t")?.let(::decimal)?.takeIf { it.isFinite() && it > 0.0 }
                    // -0 reads as an episode (it is >= 0) but must equal episode 0.
                    Reading.Read(Watch(id, if (episode == 0.0) 0.0 else episode, start))
                }
                segments == listOf("search") ->
                    queryValue(query, "q")?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { Reading.Read(Search(it)) }
                        ?: refuse("nothing to search for")
                else -> refuse("not a page this app has")
            }
        }
    }
}

/**
 * Rust's `i64` grammar, not Kotlin's: `toLongOrNull` alone also takes non-ASCII digits. AniList ids
 * fit in an Int, which is what every route here carries; one that doesn't is no title anyone has.
 */
private val Integer = Regex("[+-]?[0-9]+")

/** Rust's `f64` grammar minus `inf`/`nan`, which the callers refuse anyway. Kotlin's would add `1d`, hex and padding. */
private val Decimal = Regex("[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?")

private fun id(text: String): Int? =
    text.takeIf { Integer.matches(it) }?.toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()

private fun decimal(text: String): Double? = text.takeIf { Decimal.matches(it) }?.toDoubleOrNull()

/** `6.5`, and `3` rather than `3.0`, as the desktop's `number()` writes them. */
private fun number(n: Double): String =
    if (n % 1.0 == 0.0) n.toLong().toString() else BigDecimal.valueOf(n).stripTrailingZeros().toPlainString()

/** The first value under [key], decoded as a form: `+` is a space, and a stray `%` stays a `%`. */
private fun queryValue(query: String, key: String): String? =
    query.split('&').asSequence()
        .filter { it.isNotEmpty() }
        .map { formDecode(it.substringBefore('=')) to formDecode(it.substringAfter('=', missingDelimiterValue = "")) }
        .firstOrNull { it.first == key }
        ?.second

private fun formDecode(text: String): String {
    val bytes = text.replace('+', ' ').toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream(bytes.size)
    var i = 0
    while (i < bytes.size) {
        val hi = if (bytes[i] == '%'.code.toByte() && i + 2 < bytes.size) hex(bytes[i + 1]) else -1
        val lo = if (hi >= 0) hex(bytes[i + 2]) else -1
        if (lo >= 0) {
            out.write(hi * 16 + lo)
            i += 3
        } else {
            out.write(bytes[i].toInt())
            i++
        }
    }
    // Invalid UTF-8 becomes U+FFFD, as the desktop's lossy decode does.
    return String(out.toByteArray(), Charsets.UTF_8)
}

private fun hex(b: Byte): Int = when (val c = b.toInt().toChar()) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/** Links end up in diagnostics: keep a pasted wall of text short. */
private fun shorten(text: String): String = if (text.length > 120) text.take(120) + "…" else text
