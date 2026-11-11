package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.diagnostics.DiagnosticsLog
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * ToonHub4u's Hindi/multi-audio anime catalogue.
 *
 * Each post is one season (or movie) and groups a ToonStream player plus its download mirrors by
 * episode. ToonStream's players come first and the post's own mirrors follow them, because
 * GDMirror's old domain redirects to an advertising site the guarded embed browser blocks — but
 * they do follow, since ToonStream reaches this app through rotating domains and not all of them
 * answer. The watch link itself may be direct or wrapped in ToonHub's base64 redirector, and
 * ToonHub sometimes repeats episode one's player URL in every block, so the parser decodes the hop
 * and repairs the episode suffix from the surrounding heading.
 */
internal class ToonHubProvider(
    private val client: OkHttpClient,
    private val base: String = BASE,
) {
    private data class Catalog(val label: String, val pageUrl: String, val episodes: List<ToonHubParser.Episode>)

    private val catalogs = boundedMap<Int, Catalog>(64)
    private val misses = boundedMap<Int, Long>(128)

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val episodes = catalog(media).episodes.map { it.number }.toSet()
        return EpisodeAvailability(sub = emptySet(), dub = episodes)
    }

    /** @param audio unused: every accepted ToonHub post explicitly carries Hindi audio. */
    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val catalog = catalog(media)
        val entry = catalog.episodes.firstOrNull { it.number == episode }
            ?: error("ToonHub episode $episode is not in the catalog")
        if (entry.links.isEmpty()) error("ToonHub episode $episode has no playable mirrors")
        val watchLinks = entry.links.filter { ToonHubParser.isWatchLink(it.url) }
        val players = watchLinks.flatMap {
            runCatching { resolveWatchStreams(it.url, catalog.pageUrl) }
                .onFailure { failure -> DiagnosticsLog.throwable("ToonHub watch page failed ep=$episode", failure) }
                .getOrDefault(emptyList())
        }
        // ToonStream is preferred, not required. Its domains rotate, and one of them currently
        // answers a JS interstitial no plain HTTP client can pass; treating that as fatal threw
        // away an episode's other mirrors — which play — to report the server had nothing.
        val mirrors = entry.links.asSequence()
            .filterNot { ToonHubParser.isWatchLink(it.url) }
            .filterNot { ToonHubParser.redirectsToAds(it.url) }
            .map { it.toStreamItem(referer = catalog.pageUrl) }
        val streams = (players.asSequence() + mirrors)
            .distinctBy(StreamItem::url)
            .toList()
            .mapIndexed { index, stream -> stream.copy(isActive = index == 0) }
            .ifEmpty { error("ToonHub episode $episode has no playable mirrors") }
        return SourcesResult(
            streams = streams,
            subtitles = emptyList(),
            skip = null,
            download = null,
        )
    }

    private fun catalog(media: Media): Catalog {
        catalogs[media.id]?.let { return it }
        misses[media.id]?.let { seenAt ->
            if (System.currentTimeMillis() - seenAt < MISS_TTL_MS) {
                error("ToonHub has no entry for this title")
            }
            misses.remove(media.id)
        }
        val built = build(media)
        if (built == null) {
            misses[media.id] = System.currentTimeMillis()
            error("ToonHub has no entry for this title")
        }
        catalogs[media.id] = built
        return built
    }

    /**
     * Returns null only for a title this site was actually asked about and did not have.
     *
     * A pass that never got an answer — the catalogue budget expired and interrupted the worker,
     * or the socket timed out — used to fall out of here as null, which [catalog] then remembered
     * as "no entry" for six hours. On a TV box that is most passes: one slow moment took ToonHub
     * out of every title until the process restarted, which is what "the server isn't there"
     * looked like from the sofa. Transport failures are rethrown so nothing is learned from them.
     */
    private fun build(media: Media): Catalog? {
        var unanswered: Throwable? = null
        val candidates = searchQueries(media).flatMap { query ->
            runCatching {
                ToonHubParser.parseSearchJson(get(searchUrl(query), "$base/"))
                    ?: error("ToonHub search for \"$query\" did not answer with JSON")
            }.onFailure { unanswered = it }.getOrDefault(emptyList())
        }
        if (candidates.isEmpty()) unanswered?.let { throw it }
        val ranked = candidates.distinctBy { it.url }
            .map { it to candidateScore(media, it) }
            .filter { (_, score) -> score >= MIN_TITLE_SCORE }
            .sortedByDescending { (_, score) -> score }

        ranked.take(MAX_PROBE_PAGES).forEach { (candidate, _) ->
            val html = runCatching { get(candidate.url, "$base/") }
                .onFailure { unanswered = it }
                .getOrDefault("")
            var episodes = ToonHubParser.parseEpisodes(html, base)
            if (episodes.isEmpty() && isSingleVideo(media)) {
                ToonHubParser.parseLinks(html, base).takeIf { it.isNotEmpty() }?.let { links ->
                    episodes = listOf(ToonHubParser.Episode(1, links))
                }
            }
            if (episodes.isNotEmpty()) return Catalog(candidate.title, candidate.url, episodes)
        }
        unanswered?.let { throw it }
        return null
    }

    private fun candidateScore(media: Media, candidate: ToonHubParser.SearchResult): Double {
        val mediaTitles = titles(media)
        var score = mediaTitles.maxOfOrNull { query ->
            maxOf(
                NativeProviderParsers.titleSelectionScore(query, candidate.title),
                NativeProviderParsers.titleSelectionScore(query, ToonHubParser.seriesTitle(candidate.title)),
            )
        } ?: 0.0

        if (!isSingleVideo(media)) {
            val requestedSeason = AnimeDekhoParser.seasonHint(mediaTitles) ?: 1
            ToonHubParser.seasonNumber(candidate.title)?.let { candidateSeason ->
                score *= if (candidateSeason == requestedSeason) 1.2 else 0.45
            }
        } else if (candidate.title.contains("movie", ignoreCase = true)) {
            score *= 1.1
        }
        return score.coerceAtMost(1.0)
    }

    private fun expectedEpisodeCount(media: Media): Int? = media.episodes?.takeIf { it > 0 }
        ?: media.nextAiringEpisode?.episode?.minus(1)?.takeIf { it > 0 }

    private fun isSingleVideo(media: Media): Boolean =
        media.format.equals("MOVIE", ignoreCase = true) || expectedEpisodeCount(media) == 1

    /**
     * The season-stripped name leads here too, for the reason it does on the other two scrapers:
     * this search is literal and the post is named after the site's own numbering, not AniList's.
     *
     * "Solo Leveling Season 2 -Arise from the Shadow-" and its romaji both returned **0** posts
     * (measured 2026-09-14) while "Solo Leveling" returned the season 1 and season 2 posts; "Attack
     * on Titan Final Season" reached 2 of the 11 posts the base name reaches. [candidateScore]
     * already weights the season the entry asked for, so widening the search does not cost accuracy.
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

    private fun get(url: String, referer: String?): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .apply { referer?.let { header("Referer", it) } }
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("ToonHub HTTP ${response.code} for $url")
            return response.body?.string().orEmpty()
        }
    }

    private fun resolveWatchStreams(url: String, referer: String): List<StreamItem> {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .header("Referer", referer)
            .get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("ToonHub player HTTP ${response.code} for $url")
            val pageUrl = response.request.url.toString()
            val wrappers = ToonHubParser.parsePlayerEmbeds(response.body?.string().orEmpty(), pageUrl)
            mapParallel(wrappers, PLAYER_RESOLVE_CONCURRENCY, PLAYER_RESOLVE_BUDGET_MS) { link ->
                // Only the same-host chrome hides a player behind another page. Fetching a real
                // player to look for an iframe costs a request per mirror and risks unwrapping the
                // page's own advertising frame in place of the video.
                if (ToonHubParser.isWrappedPlayer(link.url, pageUrl)) unwrapPlayer(link, pageUrl) else null
            }.mapIndexed { index, resolved ->
                resolved ?: wrappers[index].toStreamItem(referer = pageUrl)
            }
        }
    }

    /**
     * ToonStream puts a full-screen fake play button above every real player. Loading the iframe
     * inside that wrapper means Anilili's touch shield correctly blocks the fake click, but also
     * leaves the viewer staring at a button that can never receive the tap. Resolve the one small
     * wrapper page here and hand the WebView the actual player instead.
     */
    private fun unwrapPlayer(link: ToonHubParser.Link, pageUrl: String): StreamItem? = runCatching {
        val request = Request.Builder().url(link.url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .header("Referer", pageUrl)
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("ToonHub embed HTTP ${response.code}")
            val wrapperUrl = response.request.url.toString()
            val playerUrl = ToonHubParser.parseWrappedPlayer(
                response.body?.string().orEmpty(),
                wrapperUrl,
            ) ?: error("ToonHub embed has no player iframe")
            link.copy(url = playerUrl).toStreamItem(referer = wrapperUrl)
        }
    }.onFailure {
        DiagnosticsLog.throwable("ToonHub player unwrap failed server=${link.label}", it)
    }.getOrNull()

    private fun ToonHubParser.Link.toStreamItem(referer: String): StreamItem = StreamItem(
        url = url,
        type = "embed",
        quality = label,
        audio = "Hindi",
        referer = referer,
        isActive = false,
        width = null,
        height = height,
    )

    /** Preserve source order while keeping all wrapper probes inside the provider's watch budget. */
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
            List(items.size) { null }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * WordPress's own search REST route, not `/?s=`.
     *
     * The rendered search page is 106-149 KB and every byte of it is read by a regex walk over
     * *every* `<a>` on the page; this route answers the same question in about 1 KB of JSON
     * (measured over ten titles 2026-09-14: 1224 KB of HTML against 68 KB of JSON, 18x). That is
     * the whole reason [build] can now afford to run every query instead of stopping at the first
     * one that answered — see [searchQueries].
     *
     * It is also the more accurate answer. `/?s=` renders the theme's sidebar and related-posts
     * cards with the same `post-thumb` class as the results, so a search for "Dandadan" returned
     * 13 candidates of which 11 were chrome — and, because that chrome is present whatever was
     * searched for, the old "first query with any results wins" could never reach a second query.
     * This route returned the 2 real posts and nothing else.
     */
    private fun searchUrl(query: String): String =
        "$base/wp-json/wp/v2/search?search=${enc(query)}&per_page=$SEARCH_PAGE_SIZE"

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun <K, V> boundedMap(maxEntries: Int): MutableMap<K, V> = Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > maxEntries
        },
    )

    companion object {
        private const val BASE = "https://toonhub4u.co"
        private const val MIN_TITLE_SCORE = 0.28
        private const val MAX_SEARCH_QUERIES = 3
        /** Deep enough to hold every season post of a long-running series (Naruto returns 20). */
        private const val SEARCH_PAGE_SIZE = 20
        private const val MAX_PROBE_PAGES = 5
        private const val PLAYER_RESOLVE_CONCURRENCY = 4
        private const val PLAYER_RESOLVE_BUDGET_MS = 6_000L
        private const val MISS_TTL_MS = 6L * 60L * 60L * 1000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/** Pure HTML parsing for [ToonHubProvider], kept network-free for unit tests. */
internal object ToonHubParser {
    data class SearchResult(val url: String, val title: String)
    data class Link(val url: String, val label: String, val height: Int?)
    data class Episode(val number: Int, val links: List<Link>)

    private data class EpisodeHead(val start: Int, val end: Int, val number: Int)

    private val A_TAG = Regex("""<a\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val IFRAME_TAG = Regex("""<iframe\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val P_TAG = Regex("""<p\b[^>]*>[\s\S]*?</p>""", RegexOption.IGNORE_CASE)
    private val SPAN_TAG = Regex("""<span\b[^>]*>[\s\S]*?</span>""", RegexOption.IGNORE_CASE)
    private val EPISODE_TEXT = Regex("""^Episode\s*0*(\d+)\b""", RegexOption.IGNORE_CASE)
    private val QUALITY = Regex("""\b(2160p|1080p|720p|480p|360p|4k)\b""", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")
    private val FILE_PATH = Regex("/file/", RegexOption.IGNORE_CASE)
    private val EPISODE_SUFFIX = Regex("""(-\d+x)\d+(?=/|$)""", RegexOption.IGNORE_CASE)
    private val REDIRECT_TARGET = Regex("""[?&]url=([A-Za-z0-9+/=_-]+)""")
    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val TRAILER_HOSTS = setOf("youtube.com", "youtu.be", "youtube-nocookie.com")
    private val SERIES_TITLE_CUT = Regex(
        """\s*(?:[-–—:|]\s*)?\b(?:Season\s*\d+|Episodes?|Hindi|Tamil|Telugu|Multi\s*Audio|Dual\s*Audio|Download|Watch|Movie|Film)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * One `wp/v2/search` response, or null if the body was not one.
     *
     * The route answers with a flat array of `{id, title, url, type, subtype}`. Only posts carry
     * episodes, so pages — the site's contact and DMCA entries, which match a surprising number of
     * queries — are dropped here rather than given a probe slot. Titles arrive HTML-escaped
     * (`Really, Really &#038; Really`), so they are decoded to match what the ranker compares.
     *
     * **Null and empty are not the same answer.** An empty array means the site was asked and has
     * nothing, which [ToonHubProvider.catalog] is entitled to remember for six hours. A body that
     * is not an array means the question never really got through — a cache layer answering a JSON
     * request with HTML is a measured failure of exactly this shape on a sibling provider — and
     * folding that into "no results" would take ToonHub out of the title until the process
     * restarts. That is the trap [ToonHubProvider.build] documents, reached one level lower down.
     */
    fun parseSearchJson(body: String): List<SearchResult>? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: return null
        return root.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val subtype = (entry["subtype"] as? JsonPrimitive)?.contentOrNull
            if (subtype != null && !subtype.equals("post", ignoreCase = true)) return@mapNotNull null
            val url = (entry["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = (entry["title"] as? JsonPrimitive)?.contentOrNull
                ?.let(NativeProviderParsers::decodeEntities)
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            SearchResult(url, title)
        }.distinctBy { it.url }
    }

    fun parseEpisodes(html: String, base: String): List<Episode> {
        val heads = P_TAG.findAll(html).mapNotNull { paragraph ->
            val number = EPISODE_TEXT.find(NativeProviderParsers.stripTags(paragraph.value))
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            EpisodeHead(paragraph.range.first, paragraph.range.last + 1, number)
        }.toList()
        return heads.mapIndexedNotNull { index, head ->
            val end = heads.getOrNull(index + 1)?.start ?: html.length
            val segment = html.substring(head.end, end)
            val links = parseWatchLinks(segment, base, head.number) + parseLinks(segment, base)
            Episode(head.number, links).takeIf { links.isNotEmpty() }
        }.distinctBy { it.number }
    }

    fun parseLinks(html: String, base: String): List<Link> = P_TAG.findAll(html).flatMap { paragraph ->
        val text = NativeProviderParsers.stripTags(paragraph.value)
        val quality = QUALITY.find(text)?.value?.uppercase()?.replace("P", "p")
        val height = quality?.removeSuffix("p")?.toIntOrNull()
            ?: if (quality.equals("4K", ignoreCase = true)) 2160 else null
        // `text` is already `stripTags(paragraph.value)`, and stripping is four pattern matches
        // over the paragraph. Recomputing it per anchor made this quadratic in a page's link
        // count for a label that is thrown away whenever the paragraph names a quality.
        val anchorLabel by lazy(LazyThreadSafetyMode.NONE) {
            text.substringBefore('–').substringBefore('-').trim()
        }
        A_TAG.findAll(paragraph.value).mapNotNull { anchor ->
            val raw = NativeProviderParsers.attr(anchor.value, "href").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val absolute = NativeProviderParsers.absoluteUrl(base, raw)
            val embed = when {
                absolute.contains("/file/", ignoreCase = true) ->
                    absolute.replace(FILE_PATH, "/embed/")
                absolute.contains("/embed/", ignoreCase = true) -> absolute
                else -> return@mapNotNull null
            }
            Link(
                url = embed,
                label = quality ?: anchorLabel.takeIf { it.isNotBlank() } ?: "Mirror",
                height = height,
            )
        }
    }.distinctBy { it.url }.toList()

    fun isWatchLink(url: String): Boolean = runCatching {
        val host = java.net.URI(url).host.orEmpty()
        host.contains("toonstream", ignoreCase = true) ||
            host.contains("toon-stream", ignoreCase = true)
    }.getOrDefault(false)

    /**
     * GDMirror's old domain answers every embed with a redirect to an advertising site. The guarded
     * embed browser blocks that correctly and then has nothing to play, so it is not offered.
     */
    fun redirectsToAds(url: String): Boolean = runCatching {
        java.net.URI(url).host.orEmpty().contains("gdmirrorbot", ignoreCase = true)
    }.getOrDefault(false)

    /**
     * Reads a watch link out of ToonHub's own `/redirect/mainN.php?url=<base64>` hop.
     *
     * [isWatchLink] judges the host, and the redirector's host is ToonHub's. Newer posts publish
     * only the wrapped form, so those titles looked like they had no player at all and fell back
     * to their download mirrors — the watch row was gone before anything was fetched.
     */
    fun followRedirect(url: String): String {
        if (!url.contains("/redirect/", ignoreCase = true)) return url
        val encoded = REDIRECT_TARGET.find(url)?.groupValues?.get(1) ?: return url
        val decoded = decodeBase64(encoded) ?: return url
        return decoded.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        } ?: url
    }

    /**
     * `android.util.Base64` answers null under the unit tests' default-value stubs and
     * `java.util.Base64` is outside the desugared API set this app's minSdk relies on, so the one
     * place that needs base64 carries its own decoder rather than giving up either the tests or
     * the older boxes. Both alphabets are accepted because the redirector uses whichever its
     * encoder produced, and padding is optional for the same reason.
     */
    private fun decodeBase64(value: String): String? {
        val payload = value.trimEnd('=')
        val out = StringBuilder(payload.length)
        var buffer = 0
        var bits = 0
        payload.forEach { char ->
            val symbol = when (char) {
                '-' -> '+'
                '_' -> '/'
                else -> char
            }
            val digit = BASE64_ALPHABET.indexOf(symbol)
            if (digit < 0) return null
            buffer = (buffer shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.append(((buffer shr bits) and 0xFF).toChar())
            }
        }
        return out.takeIf { it.isNotEmpty() }?.toString()
    }

    /**
     * Every player one ToonStream episode page offers, in the order its own server list names them.
     *
     * ToonStream used to iframe one same-host `/embed/` wrapper per server, each holding the real
     * player, which is what [parseWrappedPlayer] peels. It now iframes the players themselves —
     * rubystm, vidmoly, AbyssPlayer, emturbovid and the rest — so a same-host filter matched
     * nothing on a live page and every episode raised "no ToonStream player mirrors". Both shapes
     * are kept: the provider unwraps a wrapper and uses a player as it stands.
     *
     * The trailer is excluded because it is an iframe and a video and not this episode, and
     * because keeping it would shift every server label one place along.
     */
    fun parsePlayerEmbeds(html: String, pageUrl: String): List<Link> {
        val labels = SPAN_TAG.findAll(html).mapNotNull { span ->
            val isServer = NativeProviderParsers.attr(span.value, "class")
                .split(WHITESPACE)
                .any { it.equals("server", ignoreCase = true) }
            NativeProviderParsers.stripTags(span.value).takeIf { isServer && it.isNotBlank() }
        }.toList()
        return IFRAME_TAG.findAll(html).mapNotNull { iframe ->
            val raw = NativeProviderParsers.attr(iframe.value, "src")
                .ifBlank { NativeProviderParsers.attr(iframe.value, "data-src") }
                .takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val absolute = NativeProviderParsers.absoluteUrl(pageUrl, raw)
            val uri = runCatching { java.net.URI(absolute) }.getOrNull() ?: return@mapNotNull null
            val host = uri.host.orEmpty().removePrefix("www.")
            val isHttp = uri.scheme.equals("http", ignoreCase = true) ||
                uri.scheme.equals("https", ignoreCase = true)
            if (!isHttp || host.isBlank() || absolute.equals(pageUrl, ignoreCase = true)) {
                return@mapNotNull null
            }
            if (TRAILER_HOSTS.any { host == it || host.endsWith(".$it") }) return@mapNotNull null
            Link(absolute, "", null)
        }.mapIndexed { index, link ->
            link.copy(label = labels.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "Server ${index + 1}")
        }.distinctBy { it.url }.sortedBy { playerRank(it.label) }.toList()
    }

    /** True for the same-host `/embed/` chrome that [parseWrappedPlayer] can peel. */
    fun isWrappedPlayer(url: String, pageUrl: String): Boolean = runCatching {
        val target = java.net.URI(url)
        val page = java.net.URI(pageUrl)
        target.host.orEmpty().equals(page.host.orEmpty(), ignoreCase = true) &&
            target.path.orEmpty().startsWith("/embed/", ignoreCase = true)
    }.getOrDefault(false)

    /** Returns the real HTTP(S) player iframe inside one ToonStream `/embed/` wrapper page. */
    fun parseWrappedPlayer(html: String, pageUrl: String): String? = IFRAME_TAG.findAll(html)
        .mapNotNull { iframe ->
            val raw = NativeProviderParsers.attr(iframe.value, "src")
                .ifBlank { NativeProviderParsers.attr(iframe.value, "data-src") }
                .takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val absolute = NativeProviderParsers.absoluteUrl(pageUrl, raw)
            val uri = runCatching { java.net.URI(absolute) }.getOrNull() ?: return@mapNotNull null
            absolute.takeIf {
                (uri.scheme.equals("http", ignoreCase = true) ||
                    uri.scheme.equals("https", ignoreCase = true)) &&
                    !uri.host.isNullOrBlank() &&
                    !absolute.equals(pageUrl, ignoreCase = true)
            }
        }.firstOrNull()

    private fun playerRank(label: String): Int = when {
        label.contains("short", ignoreCase = true) -> 0 // currently resolves to AbyssPlayer
        label.contains("moly", ignoreCase = true) -> 1
        label.contains("turbo", ignoreCase = true) -> 2
        label.contains("ruby", ignoreCase = true) -> 3
        label.contains("multi", ignoreCase = true) -> 4
        label.equals("play", ignoreCase = true) -> 5
        label.contains("cloud", ignoreCase = true) -> 6
        label.contains("strm", ignoreCase = true) -> 7
        label.contains("watch", ignoreCase = true) -> 20 // GDMirror currently redirects to ads
        else -> 10
    }

    private fun parseWatchLinks(html: String, base: String, episode: Int): List<Link> =
        A_TAG.findAll(html).mapNotNull { anchor ->
            val raw = NativeProviderParsers.attr(anchor.value, "href").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val absolute = followRedirect(NativeProviderParsers.absoluteUrl(base, raw))
            if (!isWatchLink(absolute)) return@mapNotNull null
            val corrected = absolute.replace(EPISODE_SUFFIX, "$1$episode")
            Link(corrected, "Hindi Stream", null)
        }.distinctBy { it.url }.toList()

    fun seriesTitle(title: String): String {
        val cut = SERIES_TITLE_CUT.find(title)?.range?.first ?: title.length
        return title.substring(0, cut).trim().trim('-', '–', '—', ':', '|', '(', '[').trim()
    }

    fun seasonNumber(title: String): Int? = AnimeDekhoParser.seasonNumber(title)
}
