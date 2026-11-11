package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.util.cached
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * AnimeDekho's Hindi/multi-audio catalogue.
 *
 * The site is a plain WordPress catalogue. A series page contains every season and points at one
 * page per episode; the episode page exposes its mirrors through the small `trdekho` endpoints.
 * Those mirrors are deliberately returned as embeds. Several hosts perform their own browser
 * handshake before video starts, so extracting a URL early produces a short-lived or rejected
 * stream while letting the existing embed player load the page works reliably.
 */
internal class AnimeDekhoProvider(private val client: OkHttpClient) {
    private data class Catalog(val label: String, val episodes: List<AnimeDekhoParser.Episode>)

    private val catalogs = boundedMap<Int, Catalog>(64)
    private val misses = boundedMap<Int, Long>(128)

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val episodes = catalog(media).episodes.map { it.number }.toSet()
        // AnimeDekho's Japanese track, when present, is part of the same multi-audio Hindi file.
        // The provider belongs in the dub row so a Hindi request can find it.
        return EpisodeAvailability(sub = emptySet(), dub = episodes)
    }

    /** @param audio unused: the catalogue is intentionally exposed through the dub category. */
    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val entry = catalog(media).episodes.firstOrNull { it.number == episode }
            ?: error("AnimeDekho episode $episode is not in the catalog")
        val page = get(entry.url, referer = "$BASE/")
        val term = AnimeDekhoParser.termId(page)
            ?: error("AnimeDekho episode $episode has no resolver id")

        val embeds = AnimeDekhoParser.orderMirrors(
            mapParallel(SERVER_INDEXES, RESOLVE_CONCURRENCY, RESOLVE_BUDGET_MS) { index ->
            runCatching {
                val endpoint = "$BASE/?trdekho=$index&trid=$term&trtype=${entry.mediaType}"
                AnimeDekhoParser.iframeUrls(get(endpoint, referer = entry.url), BASE)
            }.onFailure {
                DiagnosticsLog.throwable("AnimeDekho mirror failed ep=$episode index=$index", it)
            }.getOrDefault(emptyList())
            }.filterNotNull().flatten(),
            BASE,
        )

        if (embeds.isEmpty()) error("AnimeDekho episode $episode has no playable mirrors")
        return SourcesResult(
            streams = embeds.mapIndexed { index, url ->
                StreamItem(
                    url = url,
                    type = "embed",
                    quality = mirrorLabel(url, index),
                    audio = "Hindi",
                    referer = entry.url,
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
                error("AnimeDekho has no entry for this title")
            }
            misses.remove(media.id)
        }
        val built = build(media)
        if (built == null) {
            misses[media.id] = System.currentTimeMillis()
            error("AnimeDekho has no entry for this title")
        }
        catalogs[media.id] = built
        return built
    }

    /**
     * Returns null only for a title this site was actually asked about and did not have.
     *
     * A pass that never got an answer — the catalogue budget expired and interrupted the worker,
     * or the socket timed out — used to fall out of here as null, which [catalog] then remembered
     * as "no entry" for six hours. On a TV box that is most passes: one slow moment took AnimeDekho
     * out of every title until the process restarted. Transport failures are rethrown so nothing is
     * learned from them.
     */
    private fun build(media: Media): Catalog? {
        var unanswered: Throwable? = null
        val queries = searchQueries(media)
        val candidates = queries.firstNotNullOfOrNull { query ->
            runCatching { AnimeDekhoParser.parseSearch(get("$BASE/?s=${enc(query)}", "$BASE/")) }
                .onFailure { unanswered = it }
                .getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
        }.orEmpty()
        if (candidates.isEmpty()) unanswered?.let { throw it }
        val ranked = candidates.distinctBy { it.url }
            .map { it to candidateScore(media, it) }
            .filter { (_, score) -> score >= MIN_TITLE_SCORE }
            .sortedByDescending { (_, score) -> score }

        val expected = expectedEpisodeCount(media)
        val hint = AnimeDekhoParser.seasonHint(titles(media))
        ranked.take(MAX_PROBE_PAGES).forEach { (candidate, _) ->
            val html = runCatching { get(candidate.url, "$BASE/") }
                .onFailure { unanswered = it }
                .getOrDefault("")
            val seasons = AnimeDekhoParser.parseSeasons(html)
            val selected = AnimeDekhoParser.selectSeason(
                seasons = seasons,
                seasonHint = hint,
                expectedEpisodes = expected,
                seasonYear = media.seasonYear,
            )
            if (selected != null && selected.episodes.isNotEmpty()) {
                val episodes = expected?.let(selected.episodes::take) ?: selected.episodes
                return Catalog(candidate.title, episodes)
            }
            if (seasons.isEmpty() && isSingleVideo(media) && AnimeDekhoParser.termId(html) != null) {
                return Catalog(
                    candidate.title,
                    listOf(AnimeDekhoParser.Episode(1, candidate.url, mediaType = 1)),
                )
            }
        }
        unanswered?.let { throw it }
        return null
    }

    private fun candidateScore(media: Media, candidate: AnimeDekhoParser.SearchResult): Double {
        var score = titles(media).maxOfOrNull {
            NativeProviderParsers.titleSelectionScore(it, candidate.title)
        } ?: 0.0
        val movie = isSingleVideo(media)
        val pathLooksLikeMovie = candidate.url.contains("/movie", ignoreCase = true)
        val pathLooksLikeSeries = candidate.url.contains("/series", ignoreCase = true)
        score *= when {
            movie && pathLooksLikeMovie -> 1.12
            !movie && pathLooksLikeSeries -> 1.12
            movie && pathLooksLikeSeries -> 0.65
            !movie && pathLooksLikeMovie -> 0.65
            else -> 1.0
        }
        return score.coerceAtMost(1.0)
    }

    private fun expectedEpisodeCount(media: Media): Int? = media.episodes?.takeIf { it > 0 }
        ?: media.nextAiringEpisode?.episode?.minus(1)?.takeIf { it > 0 }

    private fun isSingleVideo(media: Media): Boolean =
        media.format.equals("MOVIE", ignoreCase = true) || expectedEpisodeCount(media) == 1

    /**
     * The season-stripped name leads, because on this site it is the only one a sequel is findable
     * by at all.
     *
     * Every season of a series lives in one post here — [AnimeDekhoParser.selectSeason] picks the
     * right one out of it from the same AniList name — so the post is filed under the base series
     * and the search is literal. Measured live 2026-09-14: "That Time I Got Reincarnated as a Slime
     * Season 2" returned **0** results and "That Time I Got Reincarnated as a Slime" returned the
     * post; likewise "Skeleton Knight in Another World Season 2" and "Demon Slayer Season 3". Every
     * sequel was therefore missing this provider outright, and then cached as missing for six hours.
     * A title with no season marker is unchanged by the strip, so it costs no extra query.
     */
    private fun searchQueries(media: Media): List<String> = titles(media)
        .flatMap {
            listOf(NativeProviderParsers.baseSeriesTitle(it), it.substringBefore(':').trim(), it)
        }
        .filter { it.length >= 3 }
        .distinctBy { it.lowercase() }
        .take(MAX_SEARCH_QUERIES)

    private fun titles(media: Media): List<String> = listOfNotNull(
        media.title.english,
        media.title.romaji,
        media.title.native,
    ).filter { it.isNotBlank() }.distinct()

    private fun mirrorLabel(url: String, index: Int): String {
        val host = runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
        return when {
            host.contains("abyssplayer") -> "AbyssPlayer"
            host.contains("vidmoly") -> "VidMoly"
            host.contains("vidcloud") -> "VidCloud"
            host.contains("emturbovid") -> "TurboVid"
            host.contains("gdmirror") -> "GDMirror"
            host.isNotBlank() -> host.substringBefore('.')
                .replaceFirstChar { it.uppercase() }
            else -> "Mirror ${index + 1}"
        }
    }

    private fun get(url: String, referer: String?): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .apply { referer?.let { header("Referer", it) } }
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("AnimeDekho HTTP ${response.code} for $url")
            return response.body?.string().orEmpty()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun <T, R> mapParallel(
        items: List<T>,
        concurrency: Int,
        timeoutMs: Long,
        block: (T) -> R,
    ): List<R?> {
        if (items.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(concurrency, items.size))
        return try {
            val tasks = items.map { item -> Callable { runCatching { block(item) }.getOrNull() } }
            pool.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS)
                .map { future -> runCatching { future.get() }.getOrNull() }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyList()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun <K, V> boundedMap(maxEntries: Int): MutableMap<K, V> = Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > maxEntries
        },
    )

    companion object {
        private const val BASE = "https://animedekho.app"
        private const val MIN_TITLE_SCORE = 0.28
        private const val MAX_SEARCH_QUERIES = 3
        private const val MAX_PROBE_PAGES = 4
        private const val RESOLVE_CONCURRENCY = 4
        private const val RESOLVE_BUDGET_MS = 6_000L
        private const val MISS_TTL_MS = 6L * 60L * 60L * 1000L
        private val SERVER_INDEXES = (0..10).toList()
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/** Pure HTML parsing for [AnimeDekhoProvider], kept network-free for unit tests. */
internal object AnimeDekhoParser {
    data class SearchResult(val url: String, val title: String)
    data class Episode(val number: Int, val url: String, val mediaType: Int = 2)
    data class Season(val number: Int?, val year: Int?, val episodes: List<Episode>)

    private val ARTICLE = Regex("""<article\b[^>]*>([\s\S]*?)</article>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""", RegexOption.IGNORE_CASE)
    private val SEASON_START = Regex(
        """<div\b[^>]*class=["'][^"']*\bseasons-bx\b[^"']*["'][^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val LIST_START = Regex(
        """<ul\b[^>]*class=["'][^"']*\bseasons-lst\b[^"']*["'][^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val LI = Regex("""<li\b[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
    private val A_TAG = Regex("""<a\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val H2 = Regex("""<h2\b[^>]*>([\s\S]*?)</h2>""", RegexOption.IGNORE_CASE)
    private val IFRAME = Regex("""<iframe\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val EPISODE_LABEL = Regex("""\bS(\d+)\s*[-–]\s*E(\d+)\b""", RegexOption.IGNORE_CASE)
    private val EPISODE_PATH = Regex("""-(\d+)x(\d+)(?:/|$)""", RegexOption.IGNORE_CASE)

    fun parseSearch(html: String): List<SearchResult> = ARTICLE.findAll(html).mapNotNull { article ->
        val block = article.groupValues[1]
        val link = A_TAG.findAll(block).firstOrNull {
            NativeProviderParsers.attr(it.value, "class").split(Regex.cached("\\s+")).any { name ->
                name.equals("lnk-blk", ignoreCase = true)
            }
        } ?: return@mapNotNull null
        val url = NativeProviderParsers.attr(link.value, "href").takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val title = H2.find(block)?.groupValues?.get(1)
            ?.let(NativeProviderParsers::stripTags)
            ?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        SearchResult(url, title)
    }.distinctBy { it.url }.toList()

    fun parseSeasons(html: String): List<Season> {
        val starts = SEASON_START.findAll(html).toList()
        return starts.mapNotNull { start ->
            val next = starts.firstOrNull { it.range.first > start.range.first }?.range?.first ?: html.length
            val segment = html.substring(start.range.first, next)
            val listStart = LIST_START.find(segment) ?: return@mapNotNull null
            val listEnd = segment.indexOf("</ul>", listStart.range.last + 1, ignoreCase = true)
                .takeIf { it >= 0 } ?: segment.length
            val header = segment.substring(0, listStart.range.first)
            val list = segment.substring(listStart.range.last + 1, listEnd)
            val number = Regex.cached(
                """\bSeason\s*(?:<[^>]+>\s*)*(\d+)""",
                RegexOption.IGNORE_CASE,
            ).find(header)?.groupValues?.get(1)?.toIntOrNull()
            val year = Regex.cached("""\b(?:19|20)\d{2}\b""").find(header)?.value?.toIntOrNull()
            val episodes = LI.mapMatches(list) { item ->
                val block = item.group(1).orEmpty()
                val url = A_TAG.firstMatchNotNullOf(block) { anchor ->
                    NativeProviderParsers.attr(anchor.group(), "href")
                        .takeIf { it.contains("/epi/", ignoreCase = true) }
                } ?: return@mapMatches null
                val label = EPISODE_LABEL.find(NativeProviderParsers.stripTags(block))
                val path = EPISODE_PATH.find(url)
                val season = label?.groupValues?.get(1)?.toIntOrNull()
                    ?: path?.groupValues?.get(1)?.toIntOrNull()
                val episode = label?.groupValues?.get(2)?.toIntOrNull()
                    ?: path?.groupValues?.get(2)?.toIntOrNull()
                    ?: return@mapMatches null
                if (number != null && season != null && number != season) return@mapMatches null
                Episode(episode, url)
            }.distinctBy { it.number }.sortedBy { it.number }
            Season(number, year, episodes).takeIf { episodes.isNotEmpty() }
        }
    }

    fun selectSeason(
        seasons: List<Season>,
        seasonHint: Int?,
        expectedEpisodes: Int?,
        seasonYear: Int?,
    ): Season? {
        if (seasons.isEmpty()) return null
        seasonHint?.let { hint -> seasons.firstOrNull { it.number == hint }?.let { return it } }
        seasonYear?.let { year ->
            seasons.filter { it.year == year }.singleOrNull()?.let { return it }
        }
        expectedEpisodes?.let { expected ->
            seasons.filter { it.episodes.size == expected }.singleOrNull()?.let { return it }
        }
        if (seasons.size == 1) return seasons.first()
        return seasons.firstOrNull { it.number == 1 } ?: seasons.first()
    }

    fun seasonHint(titles: List<String>): Int? = NativeProviderParsers.seasonHint(titles)

    /**
     * Shared now, and two bugs lighter for it. This copy built four `Regex` objects on every call —
     * the cost [NativeProviderParsers] exists to hold — and its trailing-digit rule had no guard,
     * so "Kaiju No. 8" asked this provider, and ToonHub through it, for an eighth season.
     */
    fun seasonNumber(title: String): Int? = NativeProviderParsers.seasonNumber(title)

    fun termId(html: String): String? {
        val body = Regex.cached("""<body\b[^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value ?: return null
        return Regex.cached("""(?:term|postid)-(\d+)""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)
    }

    fun iframeUrls(html: String, base: String): List<String> = IFRAME.findAll(html).mapNotNull { iframe ->
        val src = NativeProviderParsers.attr(iframe.value, "src")
            .ifBlank { NativeProviderParsers.attr(iframe.value, "data-src") }
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        NativeProviderParsers.absoluteUrl(base, src)
    }.distinct().toList()

    /**
     * AnimeDekho's numeric mirror order is an upload order, not a health order. In particular its
     * own `/aaa/down/` placeholder answers 200 while rendering a dead-server message, so putting
     * that URL first makes a healthy episode look broken. Prefer established player hosts while
     * retaining every mirror in the quality menu as a manual fallback.
     */
    fun orderMirrors(urls: List<String>, base: String): List<String> {
        val baseHost = runCatching { URI(base).host.orEmpty() }.getOrDefault("")
        return urls.distinct().withIndex().sortedWith(
            compareBy<IndexedValue<String>>(
                { mirrorPriority(it.value, baseHost) },
                { it.index },
            ),
        ).map { it.value }
    }

    private fun mirrorPriority(url: String, baseHost: String): Int {
        val uri = runCatching { URI(url) }.getOrNull() ?: return 80
        val host = uri.host.orEmpty().removePrefix("www.")
        val path = uri.path.orEmpty()
        return when {
            host.contains("abyssplayer") -> 0
            host.contains("vidmoly") -> 1
            host.contains("gdmirror") -> 2
            host.contains("vidcloud") -> 3
            host.contains("emturbovid") -> 4
            host != baseHost -> 20
            path.contains("/aaa/pixel/", ignoreCase = true) -> 40
            path.contains("/aaa/down/", ignoreCase = true) -> 100
            else -> 50
        }
    }
}
