package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.util.Base64Compat
import com.anilili.util.cached
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * AnimeKhor's donghua catalogue.
 *
 * The site runs the usual WordPress anime theme: `/?s=` search, a series page listing one page per
 * episode, and an episode page whose mirrors sit in a `<select>` as base64-encoded `<iframe>` HTML.
 * Every mirror is a third-party player (Dailymotion, ok.ru, Rumble, emturbovid, abyssplayer …), so
 * they are handed back as embeds rather than extracted — the same reasoning as [AnimeDekhoProvider],
 * and here there is not even a same-origin stream to extract.
 *
 * The catalogue is **donghua only**; a search for a Japanese title returns "Not Found". The provider
 * is therefore gated to `countryOfOrigin == "CN"` in
 * [com.anilili.data.ProviderCatalog.providersForCountry] so it never takes a lane in the
 * sixteen-provider fan-out on a title it cannot possibly serve.
 */
internal class AnimekhorProvider(private val client: OkHttpClient) {
    private data class Catalog(val label: String, val episodes: Map<Int, String>)

    private val catalogs = boundedMap<Int, Catalog>(64)
    private val misses = boundedMap<Int, Long>(128)

    /**
     * The site tags every entry "Sub" and carries Chinese audio with burned or selectable
     * subtitles; there is no dub catalogue to report.
     */
    fun episodeAvailability(media: Media): EpisodeAvailability =
        EpisodeAvailability(sub = catalog(media).episodes.keys, dub = emptySet())

    /** @param audio unused: the catalogue only ever has subtitled entries. */
    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val page = catalog(media).episodes[episode]
            ?: error("AnimeKhor episode $episode is not in the catalog")
        val mirrors = AnimekhorParser.parseMirrors(get(page, referer = "$BASE/"))
        if (mirrors.isEmpty()) error("AnimeKhor episode $episode has no playable mirrors")
        return SourcesResult(
            streams = mirrors.mapIndexed { index, mirror ->
                StreamItem(
                    url = mirror.url,
                    type = "embed",
                    quality = mirror.label,
                    audio = null,
                    referer = page,
                    isActive = index == 0,
                    width = null,
                    height = null,
                )
            },
            subtitles = emptyList(),
            skip = null,
            download = null,
        )
    }

    private fun catalog(media: Media): Catalog {
        catalogs[media.id]?.let { return it }
        misses[media.id]?.let { seenAt ->
            if (System.currentTimeMillis() - seenAt < MISS_TTL_MS) {
                error("AnimeKhor has no entry for this title")
            }
            misses.remove(media.id)
        }
        val built = build(media)
        if (built == null) {
            misses[media.id] = System.currentTimeMillis()
            error("AnimeKhor has no entry for this title")
        }
        catalogs[media.id] = built
        return built
    }

    /**
     * Returns null only for a title this site was actually asked about and did not have.
     *
     * A pass that never got an answer — the catalogue budget expired and interrupted the worker,
     * or the socket timed out — used to fall out of here as null, which [catalog] then remembered
     * as "no entry" for six hours. On a TV box that is most passes, so one slow moment took
     * AnimeKhor out of every title until the process restarted. Transport failures are rethrown so
     * nothing is learned from them; the same fix ToonHub and AnimeDekho already carry.
     */
    private fun build(media: Media): Catalog? {
        var unanswered: Throwable? = null
        val candidates = searchQueries(media).firstNotNullOfOrNull { query ->
            runCatching { AnimekhorParser.parseSearch(get("$BASE/?s=${enc(query)}", "$BASE/")) }
                .onFailure { unanswered = it }
                .getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
        }.orEmpty()
        if (candidates.isEmpty()) unanswered?.let { throw it }

        val ranked = candidates.distinctBy { it.url }
            .map { it to titleScore(media, it) }
            .filter { (_, score) -> score >= MIN_TITLE_SCORE }
            .sortedByDescending { (_, score) -> score }
            .take(MAX_PROBE_PAGES)

        // Probe the best-matching pages and keep whichever *series* page agrees best with what
        // AniList says the title is, rather than stopping at the first that parses. AnimeKhor
        // numbers many franchises absolutely on a single page — "Battle Through The Heavens 5th
        // Season" holds episodes 1-207 — while AniList often splits the same franchise into
        // 12-to-26-episode seasons. Preferring the page whose episode count is closest to the
        // expected one is what stops a 26-episode AniList season resolving against a 207-episode
        // page and playing the wrong episode under the right number.
        val expected = expectedEpisodeCount(media)
        val probed = ranked.mapNotNull { (candidate, titleScore) ->
            val html = runCatching { get(candidate.url, "$BASE/") }
                .onFailure {
                    unanswered = it
                    DiagnosticsLog.throwable("AnimeKhor page failed ${candidate.url}", it)
                }
                .getOrNull() ?: return@mapNotNull null
            val episodes = AnimekhorParser.parseEpisodes(html)
            if (episodes.isEmpty()) return@mapNotNull null
            Triple(candidate, episodes, titleScore * countAgreement(episodes.size, expected))
        }
        val best = probed.maxByOrNull { (_, _, score) -> score }
            ?: run {
                unanswered?.let { throw it }
                return null
            }
        return Catalog(best.first.title, best.second)
    }

    /**
     * How much an episode count corroborates a candidate, in `[COUNT_PENALTY, 1.0]`.
     *
     * Deliberately a soft multiplier rather than a filter: the site is usually a few episodes
     * behind or ahead of AniList on a running series, and donghua AniList entries are themselves
     * inconsistent about whether they count a season or the whole run. A mismatch should lose to a
     * better-matching page, not disqualify the only page there is.
     */
    private fun countAgreement(found: Int, expected: Int?): Double {
        if (expected == null || expected <= 0 || found <= 0) return 1.0
        val ratio = minOf(found, expected).toDouble() / maxOf(found, expected).toDouble()
        return COUNT_PENALTY + (1.0 - COUNT_PENALTY) * ratio
    }

    private fun titleScore(media: Media, candidate: AnimekhorParser.SearchResult): Double {
        if (candidate.isComic) return 0.0
        val score = titles(media).maxOfOrNull {
            NativeProviderParsers.titleSelectionScore(it, candidate.title)
        } ?: 0.0
        val movie = isSingleVideo(media)
        // The site's own badge is a better format signal than the slug, and it is always present.
        return when {
            movie == candidate.isMovie -> score
            else -> score * FORMAT_MISMATCH_PENALTY
        }.coerceAtMost(1.0)
    }

    private fun expectedEpisodeCount(media: Media): Int? = media.episodes?.takeIf { it > 0 }
        ?: media.nextAiringEpisode?.episode?.minus(1)?.takeIf { it > 0 }

    private fun isSingleVideo(media: Media): Boolean =
        media.format.equals("MOVIE", ignoreCase = true) || expectedEpisodeCount(media) == 1

    private fun searchQueries(media: Media): List<String> = titles(media)
        .flatMap { listOf(it, it.substringBefore(':').trim()) }
        .filter { it.length >= 3 }
        .distinctBy { it.lowercase() }
        .take(MAX_SEARCH_QUERIES)

    private fun titles(media: Media): List<String> = listOfNotNull(
        media.title.english,
        media.title.romaji,
        media.title.native,
    ).filter { it.isNotBlank() }.distinct()

    private fun get(url: String, referer: String?): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .apply { referer?.let { header("Referer", it) } }
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("AnimeKhor HTTP ${response.code} for $url")
            return response.body?.string().orEmpty()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun <K, V> boundedMap(maxEntries: Int): MutableMap<K, V> = Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > maxEntries
        },
    )

    companion object {
        private const val BASE = "https://animekhor.org"
        private const val MIN_TITLE_SCORE = 0.28
        private const val MAX_SEARCH_QUERIES = 3
        private const val MAX_PROBE_PAGES = 3
        private const val COUNT_PENALTY = 0.45
        private const val FORMAT_MISMATCH_PENALTY = 0.65
        private const val MISS_TTL_MS = 6L * 60L * 60L * 1000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/** Pure HTML parsing for [AnimekhorProvider], kept network-free for unit tests. */
internal object AnimekhorParser {
    data class SearchResult(val url: String, val title: String, val type: String) {
        val isComic: Boolean get() = type.equals("Comic", ignoreCase = true)
        val isMovie: Boolean get() = type.equals("Movie", ignoreCase = true)
    }

    data class Mirror(val label: String, val url: String)

    private val ARTICLE = Regex("""<article\b[^>]*>([\s\S]*?)</article>""", RegexOption.IGNORE_CASE)
    private val A_TAG = Regex("""<a\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TYPE_BADGE = Regex(
        """<div\b[^>]*class=["'][^"']*\btypez\b\s*([^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val EPISODE_LIST = Regex(
        """<div\b[^>]*class=["'][^"']*\beplister\b[^"']*["'][^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val LI = Regex("""<li\b[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
    private val EPISODE_NUMBER = Regex(
        """<div\b[^>]*class=["'][^"']*\bepl-num\b[^"']*["'][^>]*>([\s\S]*?)</div>""",
        RegexOption.IGNORE_CASE,
    )
    private val MIRROR_LIST = Regex(
        """<div\b[^>]*class=["'][^"']*\bmobius\b[^"']*["'][^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val OPTION = Regex(
        """<option\b[^>]*value=["']([^"']*)["'][^>]*>([\s\S]*?)</option>""",
        RegexOption.IGNORE_CASE,
    )
    private val IFRAME = Regex("""<iframe\b[^>]*>""", RegexOption.IGNORE_CASE)

    fun parseSearch(html: String): List<SearchResult> = ARTICLE.findAll(html).mapNotNull { article ->
        val block = article.groupValues[1]
        val link = A_TAG.find(block) ?: return@mapNotNull null
        val url = NativeProviderParsers.attr(link.value, "href").takeIf { it.contains("/anime/") }
            ?: return@mapNotNull null
        val title = NativeProviderParsers.attr(link.value, "title")
            .let(NativeProviderParsers::decodeEntities)
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        SearchResult(url, title, TYPE_BADGE.find(block)?.groupValues?.get(1)?.trim().orEmpty())
    }.distinctBy { it.url }.toList()

    /**
     * Every episode number the series page offers, mapped to the page that plays it.
     *
     * A single page can carry several episodes: AnimeKhor batches them freely and writes the label
     * as a range ("637 to 638", "1 to 12") or with a season prefix ("S4 E378-379"). Both endpoints
     * of a range point at the same page, so each number in it is mapped to that page — asking for
     * 638 has to reach the "637 to 638" entry rather than miss.
     */
    fun parseEpisodes(html: String): Map<Int, String> {
        val listStart = EPISODE_LIST.find(html) ?: return emptyMap()
        val listEnd = html.indexOf("</ul>", listStart.range.last + 1, ignoreCase = true)
            .takeIf { it >= 0 } ?: html.length
        val list = html.substring(listStart.range.last + 1, listEnd)
        // The list is newest-first, and a batch page can claim a number an individual entry also
        // claims. First writer wins, so the newest page for a number is the one kept.
        val episodes = sortedMapOf<Int, String>()
        LI.findAll(list).forEach { item ->
            val block = item.groupValues[1]
            val link = A_TAG.find(block) ?: return@forEach
            val url = NativeProviderParsers.attr(link.value, "href").takeIf { it.isNotBlank() }
                ?: return@forEach
            val label = EPISODE_NUMBER.find(block)?.groupValues?.get(1)
                ?.let(NativeProviderParsers::stripTags)
                ?: return@forEach
            episodeNumbers(label).forEach { number -> episodes.putIfAbsent(number, url) }
        }
        return episodes
    }

    /**
     * The episode numbers an `epl-num` label stands for.
     *
     * Observed formats across the catalogue: `207`, `637 to 638`, `1 to 12`, `S4 E380`,
     * `S4 E378-379` and `S2E290`. A season prefix is dropped — the episode number that follows it
     * is already absolute, and the series page is the season.
     */
    fun episodeNumbers(label: String): List<Int> {
        val body = label.trim()
            .replace(Regex.cached("""^S\d+\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex.cached("""\bE(?=\d)""", RegexOption.IGNORE_CASE), "")
        val numbers = Regex.cached("""\d+""").findAll(body).mapNotNull { it.value.toIntOrNull() }.toList()
        return when {
            numbers.isEmpty() -> emptyList()
            numbers.size == 1 -> listOf(numbers.first())
            else -> {
                val first = numbers.first()
                val last = numbers.last()
                // Guard against a stray year or a label that is not a range at all; a batch of
                // more than a season is not something this site publishes on one page.
                if (last in first..(first + MAX_BATCH_SPAN)) (first..last).toList() else listOf(first)
            }
        }
    }

    /**
     * The episode's mirrors, best first.
     *
     * Each `<option>` in the mirror `<select>` holds base64-encoded `<iframe>` HTML rather than a
     * URL, and the page's inline player is a copy of one of them; the options are the complete set,
     * so the inline iframe is only a fallback for a page that ships without the select.
     */
    fun parseMirrors(html: String): List<Mirror> {
        val listStart = MIRROR_LIST.find(html)
        val segment = if (listStart != null) {
            val end = html.indexOf("</select>", listStart.range.last + 1, ignoreCase = true)
                .takeIf { it >= 0 } ?: html.length
            html.substring(listStart.range.last + 1, end)
        } else {
            ""
        }
        val mirrors = OPTION.findAll(segment).mapNotNull { option ->
            val encoded = option.groupValues[1].trim().takeIf { it.length >= MIN_ENCODED_LENGTH }
                ?: return@mapNotNull null
            val decoded = runCatching { String(Base64Compat.decode(encoded)) }.getOrNull()
                ?: return@mapNotNull null
            val url = iframeSrc(decoded) ?: return@mapNotNull null
            val label = NativeProviderParsers.stripTags(option.groupValues[2]).trim()
            Mirror(label.ifBlank { hostLabel(url) }, url)
        }.distinctBy { it.url }.toList()

        if (mirrors.isNotEmpty()) return mirrors
        return iframeSrc(html)?.let { listOf(Mirror(hostLabel(it), it)) }.orEmpty()
    }

    private fun iframeSrc(html: String): String? = IFRAME.findAll(html).firstNotNullOfOrNull { tag ->
        val src = NativeProviderParsers.attr(tag.value, "src")
            .ifBlank { NativeProviderParsers.attr(tag.value, "data-src") }
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: return@firstNotNullOfOrNull null
        // Several mirrors are protocol-relative ("//ok.ru/videoembed/…"), which no player can open.
        when {
            src.startsWith("//") -> "https:$src"
            else -> NativeProviderParsers.absoluteUrl(BASE, src)
        }
    }

    private fun hostLabel(url: String): String {
        val host = runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
        return host.substringBefore('.').replaceFirstChar { it.uppercase() }.ifBlank { "Mirror" }
    }

    private const val BASE = "https://animekhor.org"
    private const val MIN_ENCODED_LENGTH = 16
    private const val MAX_BATCH_SPAN = 26
}
