package com.anilili.data.remote

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal data class NativeEpisodeRequest(
    val provider: String,
    val anilistId: Int,
    val audio: String,
    val episode: Int,
)

internal data class EpisodeAvailability(
    val sub: Set<Int>,
    val dub: Set<Int>,
) {
    companion object {
        fun counts(sub: Int, dub: Int): EpisodeAvailability = EpisodeAvailability(
            sub = if (sub > 0) (1..sub).toSet() else emptySet(),
            dub = if (dub > 0) (1..dub).toSet() else emptySet(),
        )
    }
}

/**
 * Every pattern in this file is compiled once and held.
 *
 * These parsers used to build their `Regex` objects inline, which meant one or more
 * `Pattern.compile` per call — and the callers run them per element over a whole page.
 * `ToonHubParser.parseSearch` asked every `<a>` on a search page for its class before it could
 * filter — it is gone now, because ToonHub reads `wp/v2/search` instead, but `parseLinks` and
 * `AnimeHeavenParser.parseSearch` still walk whole pages this way — and each `attr` was two
 * compiles plus two more inside `decodeEntities`; some loops here compiled
 * a fresh pattern per match. On a phone the whole page still parsed in ~60-110ms, so it never
 * looked like a problem. On a TV box (measured 2026-08-23: MTK 32G20, armeabi-v7a, Android 7,
 * already at 187% CPU decoding 1080p) the same 23KB page took **3.4 seconds** — 32-60x — which ate
 * most of the provider's 15s catalogue budget and dropped it from the picker entirely. Providers
 * late in the fan-out order lost this way on every single build, and because a timed-out lookup
 * caches nothing they stayed cold forever.
 */
internal object NativeProviderParsers {
    private val episodePath = Regex(
        // The id is signed: catalogue-native titles (hanime, One Pace) live under negative ids,
        // and an unsigned pattern refused every one of their episodes as an invalid request.
        "^watch/([a-z0-9]+)/(-?\\d+)/(sub|dub)/[a-z0-9]+-(\\d+)$",
        RegexOption.IGNORE_CASE,
    )

    // Keyed by attribute name, which always comes from a literal in this codebase, so the key set
    // is closed and needs no eviction.
    private val quotedAttr = ConcurrentHashMap<String, Regex>()
    private val unquotedAttr = ConcurrentHashMap<String, Regex>()

    private val SEASON = Regex("""\bSeason\s*0*(\d+)\b""", RegexOption.IGNORE_CASE)
    private val ORDINAL_SEASON = Regex("""\b0*(\d+)(?:st|nd|rd|th)\s+Season\b""", RegexOption.IGNORE_CASE)
    private val ROMAN_SEASON = Regex("""\b(II|III|IV|V|VI)\s*$""", RegexOption.IGNORE_CASE)

    /**
     * "Gintama 2" is a second season; "Kaiju No. 8" is not an eighth one.
     *
     * The only thing separating them is the word in front of the digit, so a trailing number that
     * follows an abbreviation or a number sign is not read as a season. Without that, the Kaiju
     * entry asked for a season the site has never posted, both real posts were ranked as the wrong
     * season, and the tie fell to whichever WordPress had published most recently — season 2, under
     * season 1's episode numbers.
     */
    private val TRAILING_SEASON = Regex("""(?<![.#])\s([2-9])\s*$""")

    /** Everything from a season marker onwards, for turning an AniList sequel name into its series. */
    private val SEASON_SUFFIX = Regex(
        """\s*[-–—:|(\[]?\s*\b(?:\d+(?:st|nd|rd|th)\s+)?(?:the\s+)?(?:final\s+)?season\b.*$""",
        RegexOption.IGNORE_CASE,
    )

    private val htmlTag = Regex("<[^>]*>")
    private val whitespaceRun = Regex("\\s+")
    private val decimalEntity = Regex("&#(\\d+);")
    private val hexEntity = Regex("&#x([0-9a-f]+);", RegexOption.IGNORE_CASE)
    private val nonAlphanumeric = Regex("[^a-z0-9]")
    private val hlsUrl = Regex(
        "(?:https?:)?(?:\\\\/|/)[^\"'\\s<>]+?\\.m3u8[^\"'\\s<>]*",
        RegexOption.IGNORE_CASE,
    )
    private val dataAudioAnchor = Regex(
        """<a\b[^>]*\bdata-(?:num|slug)\s*=\s*(["'])\d+\1[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val listItem = Regex("""<li\b[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
    private val strongNumber = Regex("""<strong[^>]*>[\s\S]*?(\d+)\s*</strong>""", RegexOption.IGNORE_CASE)
    private val aniNekoEpisodeItem = Regex(
        """<article\b[^>]*class=["'][^"']*nv-info-episode-item[^"']*["'][^>]*>([\s\S]*?)</article>""",
        RegexOption.IGNORE_CASE,
    )
    private val aniNekoEpisodeNumber = Regex("""/ep-(\d+)""", RegexOption.IGNORE_CASE)
    private val subBadge = Regex(""">\s*(?:SUB|HSUB)\s*<""", RegexOption.IGNORE_CASE)
    private val dubBadge = Regex(""">\s*DUB\s*<""", RegexOption.IGNORE_CASE)
    private val episodeCountLabel = Regex("""\b(\d+)\s+Episodes\b""", RegexOption.IGNORE_CASE)

    fun episodeRequest(path: String): NativeEpisodeRequest? {
        val match = episodePath.matchEntire(path.trim('/')) ?: return null
        return NativeEpisodeRequest(
            provider = match.groupValues[1].lowercase(),
            anilistId = match.groupValues[2].toIntOrNull() ?: return null,
            audio = match.groupValues[3].lowercase(),
            episode = match.groupValues[4].toIntOrNull() ?: return null,
        )
    }

    fun attr(tag: String, name: String): String {
        val quoted = quotedAttr.getOrPut(name) {
            Regex(
                "\\b${Regex.escape(name)}\\s*=\\s*([\"'])(.*?)\\1",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
        }.find(tag)?.groupValues?.get(2)
        // Some providers (e.g. AniZone) emit unquoted attribute values.
        val raw = quoted ?: unquotedAttr.getOrPut(name) {
            Regex("\\b${Regex.escape(name)}\\s*=\\s*([^\"'\\s>]+)", RegexOption.IGNORE_CASE)
        }.find(tag)?.groupValues?.get(1)
        return raw?.let(::decodeEntities).orEmpty()
    }

    fun stripTags(html: String): String = decodeEntities(
        html.replace(htmlTag, " ").replace(whitespaceRun, " ").trim(),
    )

    fun decodeEntities(value: String): String {
        // Nine passes over the string follow, and every one of them looks for a run starting with
        // '&' or the escaped slash. Most attribute values and text runs contain neither, so this
        // single scan skips all of them.
        if (!value.contains('&') && !value.contains('\\')) return value
        var decoded = value
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
        decoded = decimalEntity.replace(decoded) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
        decoded = hexEntity.replace(decoded) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        }
        return decoded.replace("\\/", "/")
    }

    fun normalize(value: String): String = value.lowercase().replace(nonAlphanumeric, "")

    fun titleScore(query: String, candidate: String): Double =
        normalizedTitleScore(normalize(query), normalize(candidate))

    /** The comparison itself, so a caller that already normalized does not do it twice. */
    private fun normalizedTitleScore(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (a in b || b in a) return 0.86
        if (a.length < 2 || b.length < 2) return 0.0
        val counts = HashMap<String, Int>()
        for (i in 0 until a.length - 1) {
            val pair = a.substring(i, i + 2)
            counts[pair] = (counts[pair] ?: 0) + 1
        }
        var hits = 0
        for (i in 0 until b.length - 1) {
            val pair = b.substring(i, i + 2)
            val remaining = counts[pair] ?: 0
            if (remaining > 0) {
                hits++
                counts[pair] = remaining - 1
            }
        }
        return (2.0 * hits) / ((a.length - 1) + (b.length - 1))
    }

    /** Adds a specificity tie-breaker so a base series beats similarly named sequels/spinoffs. */
    fun titleSelectionScore(query: String, candidate: String): Double {
        val a = normalize(query)
        val b = normalize(candidate)
        val similarity = normalizedTitleScore(a, b)
        if (a.isBlank() || b.isBlank() || a == b) return similarity
        val lengthRatio = minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length)
        return similarity * (0.6 + 0.4 * lengthRatio)
    }

    /**
     * The season a title says it covers, in the forms AniList and these sites actually write.
     *
     * "Season 2", "2nd Season", "II", and a bare trailing "2" are all the same claim. Null means
     * the title makes no claim, which for a series usually means the first — but that reading
     * belongs to the caller, because a movie post makes no claim either.
     *
     * Lived in `RareAnimesParser`, which is why the trailing-digit rule is as careful as it is;
     * `AnimeDekhoParser` had a second copy that read "Kaiju No. 8" as an eighth season.
     */
    fun seasonNumber(title: String): Int? {
        SEASON.find(title)?.let { return it.groupValues[1].toIntOrNull() }
        ORDINAL_SEASON.find(title)?.let { return it.groupValues[1].toIntOrNull() }
        ROMAN_SEASON.find(title.trim())?.let {
            return when (it.groupValues[1].uppercase()) {
                "II" -> 2
                "III" -> 3
                "IV" -> 4
                "V" -> 5
                "VI" -> 6
                else -> null
            }
        }
        return TRAILING_SEASON.find(title.trim())?.groupValues?.get(1)?.toIntOrNull()
    }

    /** The season the AniList entry is asking for, from whichever of its names numbers one. */
    fun seasonHint(titles: List<String>): Int? = titles.firstNotNullOfOrNull(::seasonNumber)

    /**
     * An AniList sequel name reduced to the series a site files it under.
     *
     * "Solo Leveling Season 2 -Arise from the Shadow-" → "Solo Leveling"; "Attack on Titan Final
     * Season" → "Attack on Titan". A name carrying no season marker comes back unchanged, so a
     * caller can offer this as a search query unconditionally and pay nothing for a first season.
     *
     * Searching a site for the name AniList uses is how a sequel goes missing: WordPress and
     * AnimeHeaven both match literally, so "Saga of Tanya the Evil Season 2" found nothing while
     * the site had it as "Saga of Tanya the Evil II" all along.
     */
    fun baseSeriesTitle(title: String): String =
        SEASON_SUFFIX.replace(title, "").trim().trim('-', '–', '—', ':', '|', '(', '[').trim()

    fun absoluteUrl(base: String, value: String): String {
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
    }

    fun hlsUrls(html: String): List<String> {
        val urls = linkedSetOf<String>()
        hlsUrl.forEachMatch(html) { match ->
            val url = decodeEntities(match.group()).replace("\\/", "/")
            urls += if (url.startsWith("//")) "https:$url" else url
        }
        return urls.toList()
    }

    fun dataAudioEpisodes(html: String): EpisodeAvailability {
        val sub = linkedSetOf<Int>()
        val dub = linkedSetOf<Int>()
        dataAudioAnchor.forEachMatch(html) { match ->
            val tag = match.group()
            val number = attr(tag, "data-num").toIntOrNull()
                ?: attr(tag, "data-slug").toIntOrNull()
                ?: return@forEachMatch
            if (attr(tag, "data-sub") == "1" || attr(tag, "data-hsub") == "1") sub += number
            if (attr(tag, "data-dub") == "1") dub += number
        }
        return EpisodeAvailability(sub, dub)
    }

    fun animeGgEpisodes(html: String): EpisodeAvailability {
        val sub = linkedSetOf<Int>()
        val dub = linkedSetOf<Int>()
        listItem.forEachMatch(html) { match ->
            val block = match.group(1).orEmpty()
            if (!block.contains("anm_det_pop", ignoreCase = true)) return@forEachMatch
            val number = strongNumber.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEachMatch
            if (block.contains("btn-subbed", ignoreCase = true)) sub += number
            if (block.contains("btn-dubbed", ignoreCase = true)) dub += number
        }
        return EpisodeAvailability(sub, dub)
    }

    fun aniNekoEpisodes(html: String): EpisodeAvailability {
        val sub = linkedSetOf<Int>()
        val dub = linkedSetOf<Int>()
        aniNekoEpisodeItem.forEachMatch(html) { match ->
            val block = match.group(1).orEmpty()
            val number = aniNekoEpisodeNumber.find(block)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEachMatch
            if (subBadge.containsMatchIn(block)) sub += number
            if (dubBadge.containsMatchIn(block)) dub += number
        }
        return EpisodeAvailability(sub, dub)
    }

    fun labelledEpisodeCount(html: String): Int? =
        episodeCountLabel.find(html)?.groupValues?.get(1)?.toIntOrNull()
}
