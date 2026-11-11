package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * AnimeHeaven's back catalogue, served as progressive MP4.
 *
 * Three plain GETs and no player page: `search.php` finds the entry, `anime.php?<id>` lists the
 * episodes, and `gate.php` — asked with the episode's hash as a `key` cookie — answers with
 * `<source>` tags naming the file on the CDN directly.
 *
 * **What makes this provider worth having is what it does *not* need.** Measured against
 * `ax.animeheaven.me` on 2026-09-13, the MP4 answers `206 Partial Content` with
 * `Accept-Ranges: bytes` and `Content-Type: video/mp4` under curl's own User-Agent with no Referer
 * at all, and still answers 206 to a deliberately hostile `Referer: example.com`. Nothing else the
 * app carries is reachable that way, and three separate limitations fall out of it:
 *
 *  - [StreamItem.referer] is left null on purpose, which is this app's signal for "not gated" —
 *    `CastStreamRouting` then hands the receiver the real URL instead of detouring it through
 *    `StreamCorsProxy`, and an external player, which cannot send headers at all, gets a
 *    `video/mp4` it can actually route.
 *  - A saved episode is a byte copy. There is no HLS to transmux, so none of the export pipeline's
 *    failure modes — the parallel-Transformer OOM, the dropped-PPS `avcC` repair — are reachable
 *    from here.
 *  - The origin is plain Apache with no Cloudflare in front, so no clearance is ever spent on it.
 *
 * Its limits are equally worth stating. The catalogue is **sub only** and the subtitles are burned
 * in: there is no `<track>` on the gate page and no separate dubbed entry in search, so this
 * provider publishes nothing under [EpisodeAvailability.dub] and offers no selectable subtitle.
 * One quality per episode (720p h264 on the titles measured), and the three `<source>` mirrors are
 * the same file on different hosts rather than a quality ladder — they are offered as manual
 * failover, the way [AnimeDekhoProvider] offers its mirrors, not as a resolution menu.
 */
internal class AnimeHeavenProvider(
    private val client: OkHttpClient,
    /** Overridden only by tests, matching [ToonHubProvider] and [RareAnimesProvider]. */
    private val base: String = BASE,
) {
    private data class Catalog(val title: String, val path: String, val episodes: List<AnimeHeavenParser.Episode>)

    private val catalogs = boundedMap<Int, Catalog>(64)
    private val misses = boundedMap<Int, Long>(128)

    fun episodeAvailability(media: Media): EpisodeAvailability = EpisodeAvailability(
        sub = catalog(media).episodes.map { it.number }.toSet(),
        dub = emptySet(),
    )

    /** @param audio unused: AnimeHeaven publishes one hard-subbed track and no dub. */
    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val catalog = catalog(media)
        val entry = catalog.episodes.firstOrNull { it.number == episode }
            ?: error("AnimeHeaven episode $episode is not in the catalog")

        // The gate hands out a second, short-lived token in the file URL, so it is asked at resolve
        // time rather than cached with the episode list.
        val page = get(
            url = "$base/gate.php",
            referer = "$base/${catalog.path}",
            cookie = "key=${entry.gateKey}",
        )
        val urls = AnimeHeavenParser.videoUrls(page, entry.gateKey)
        if (urls.isEmpty()) error("AnimeHeaven episode $episode returned no video source")

        return SourcesResult(
            streams = urls.mapIndexed { index, url ->
                StreamItem(
                    url = url,
                    type = "mp4",
                    quality = mirrorLabel(url, index),
                    audio = null,
                    // Deliberately null; see the class comment. The CDN was measured refusing
                    // nothing, and naming a referer here would route every cast through the proxy
                    // and strip the one advantage this provider has.
                    referer = null,
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
                error("AnimeHeaven has no entry for this title")
            }
            misses.remove(media.id)
        }
        val built = build(media)
        if (built == null) {
            misses[media.id] = System.currentTimeMillis()
            error("AnimeHeaven has no entry for this title")
        }
        catalogs[media.id] = built
        return built
    }

    /**
     * Returns null only for a title this site was asked about and did not have.
     *
     * A lookup that never got an answer — the catalogue budget interrupted the worker, or the
     * socket timed out — must not fall out of here as null, because [catalog] would remember that
     * as "no entry" for six hours and take the provider out of the title until the process
     * restarts. Transport failures are rethrown so nothing is learned from them; this is the same
     * trap [AnimeDekhoProvider.build] documents, and it costs a whole provider on a slow TV box.
     */
    private fun build(media: Media): Catalog? {
        var unanswered: Throwable? = null
        val candidates = searchQueries(media).firstNotNullOfOrNull { query ->
            runCatching { AnimeHeavenParser.parseSearch(get("$base/search.php?s=${enc(query)}", "$base/")) }
                .onFailure { unanswered = it }
                .getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
        }.orEmpty()
        if (candidates.isEmpty()) unanswered?.let { throw it }

        val ranked = candidates.distinctBy { it.path }
            .map { it to titleScore(media, it.title) }
            .filter { (_, score) -> score >= MIN_TITLE_SCORE }
            .sortedWith(
                compareByDescending<Pair<AnimeHeavenParser.SearchResult, Double>> { (candidate, _) ->
                    seasonPriority(media, candidate.title)
                }.thenByDescending { (_, score) -> score },
            )

        ranked.take(MAX_PROBE_PAGES).forEach { (candidate, _) ->
            val html = runCatching { get("$base/${candidate.path}", "$base/") }
                .onFailure { unanswered = it }
                .getOrNull()
                ?: return@forEach
            val episodes = AnimeHeavenParser.parseEpisodes(html)
            if (episodes.isNotEmpty()) return Catalog(candidate.title, candidate.path, episodes)
        }
        unanswered?.let { throw it }
        return null
    }

    private fun titleScore(media: Media, candidate: String): Double = titles(media)
        .flatMap { listOf(it, NativeProviderParsers.baseSeriesTitle(it)) }
        .maxOfOrNull { NativeProviderParsers.titleSelectionScore(it, candidate) } ?: 0.0

    /**
     * Whether a candidate is the season that was asked for: 2 yes, 1 no opinion, 0 wrong season.
     *
     * Ranking on the title score alone cannot answer this, and gets it backwards where it matters.
     * Asking for "Saga of Tanya the Evil Season 2" against this site's posts, "Saga of Tanya the
     * Evil" scores **0.764** and "Saga of Tanya the Evil II" scores **0.727** — the season markers
     * are a couple of characters against a shared name, so the *shorter, wronger* post wins and
     * season one plays under season two's episode numbers. That is worse than the miss it replaces,
     * which is why [searchQueries] and this have to land together.
     *
     * A candidate with no season marker counts as the first, which is what an unnumbered post
     * usually is. A wrong season is ordered last rather than dropped: it is still better than
     * offering nothing when a site only carries the one post.
     */
    private fun seasonPriority(media: Media, candidate: String): Int {
        val wanted = NativeProviderParsers.seasonHint(titles(media)) ?: return 1
        val found = NativeProviderParsers.seasonNumber(candidate) ?: 1
        return if (found == wanted) 2 else 0
    }

    /**
     * The season-stripped name leads, because AnimeHeaven's search is literal and files sequels
     * under their own numbering.
     *
     * "Saga of Tanya the Evil Season 2" and "Skeleton Knight in Another World Season 2" both
     * returned nothing on every one of the three names AniList carries, while the site had them as
     * "… II" and "… 2" the whole time (measured 2026-09-14). The provider then cached both as
     * missing titles for six hours. A name with no season marker is unchanged by the strip and so
     * costs no extra request — and for "One-Punch Man Season 3" it saves one, by answering on the
     * first query instead of the second.
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

    /**
     * The mirror's own hostname, which is the only thing that distinguishes these entries.
     *
     * They are the same encode on `ax`/`ct`/`ck`, so labelling them by resolution would print the
     * same number three times, and claiming a resolution this code never measured would be worse:
     * the height is left null so the player reports what it actually opened.
     */
    private fun mirrorLabel(url: String, index: Int): String {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        return host.substringBefore('.').takeIf { it.isNotBlank() }
            ?.let { "Mirror ${index + 1} ($it)" }
            ?: "Mirror ${index + 1}"
    }

    private fun get(url: String, referer: String?, cookie: String? = null): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .apply {
                referer?.let { header("Referer", it) }
                cookie?.let { header("Cookie", it) }
            }
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("AnimeHeaven HTTP ${response.code} for $url")
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
        private const val BASE = "https://animeheaven.me"
        private const val MIN_TITLE_SCORE = 0.28
        private const val MAX_SEARCH_QUERIES = 3
        private const val MAX_PROBE_PAGES = 3
        private const val MISS_TTL_MS = 6L * 60L * 60L * 1000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/**
 * Pure HTML parsing for [AnimeHeavenProvider], kept network-free for unit tests.
 *
 * Every pattern is compiled once and held for the reason [NativeProviderParsers] spells out: these
 * run per element over a whole page, and an inline `Regex` costs a `Pattern.compile` each time —
 * unnoticeable on a phone, tens of times slower on a TV box, where it is the difference between a
 * provider appearing in the picker and timing out of it.
 *
 * The markup is hand-written and quoted with apostrophes throughout (`class='similarimg'`), with
 * stray whitespace inside tags (`<div  class= ' watch2 bc ' >`). Both are why parsing goes through
 * [NativeProviderParsers.attr] rather than matching on `class="..."` directly.
 */
internal object AnimeHeavenParser {
    data class SearchResult(val path: String, val title: String)
    data class Episode(val number: Int, val gateKey: String)

    /** Search renders `similarimg` cards; the front page and `popular.php` render `chart` rows. */
    private val cardStart = Regex(
        """<div\b[^>]*\bclass\s*=\s*['"][^'"]*\b(?:similarimg|chart)\b[^'"]*['"][^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val animeAnchor = Regex("""<a\b[^>]*\bhref\s*=\s*['"]?(?:/)?(anime\.php\?[^'"\s>]+)""", RegexOption.IGNORE_CASE)
    private val nameAnchor = Regex(
        """<div\b[^>]*\bclass\s*=\s*['"][^'"]*\b(?:similarname|charttitle)\b[^'"]*['"][^>]*>\s*<a\b[^>]*>([\s\S]*?)</a>""",
        RegexOption.IGNORE_CASE,
    )
    private val imgTag = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val gateAnchor = Regex(
        """gatea\(\s*(?:"|&quot;|')([0-9a-f]+)(?:"|&quot;|')\s*\)""",
        RegexOption.IGNORE_CASE,
    )
    private val watchNumber = Regex(
        """<div\b[^>]*\bwatch2\b[^>]*>\s*(\d+(?:\.\d+)?)\s*</div>""",
        RegexOption.IGNORE_CASE,
    )
    private val sourceTag = Regex("""<source\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val htmlComment = Regex("""<!--[\s\S]*?-->""")

    /**
     * The entries on a `search.php` page.
     *
     * Search renders `similarimg` cards; the front page and `popular.php` render `chart` rows with
     * the same anchor and a `charttitle` instead. Both are read here so one parser covers every
     * listing the provider might land on.
     */
    fun parseSearch(html: String): List<SearchResult> =
        segments(html, cardStart.findAll(html).map { it.range.last }.toList()).mapNotNull { block ->
            val path = animeAnchor.find(block)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            // The card's own caption is the reliable title; the cover's `alt` is the fallback for a
            // layout that renders the image but no name element.
            val title = nameAnchor.find(block)?.groupValues?.get(1)
                ?.let(NativeProviderParsers::stripTags)
                ?.takeIf { it.isNotBlank() }
                ?: imgTag.find(block)?.value
                    ?.let { NativeProviderParsers.attr(it, "alt") }
                    ?.let(NativeProviderParsers::decodeEntities)
                    ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            SearchResult(path, title.trim())
        }.distinctBy { it.path }

    /**
     * The episodes on an `anime.php` page, ascending.
     *
     * Each row is an `<a onclick='gatea("<hash>")'>` wrapping a `watch2` div holding the episode
     * number. The number is looked for only between one anchor and the next rather than with a
     * lazy match across the page: a row that renders without a `watch2` div would otherwise borrow
     * the *following* episode's number and silently shift every episode after it by one.
     */
    fun parseEpisodes(html: String): List<Episode> {
        val anchors = gateAnchor.findAll(html)
            .mapNotNull { match -> match.groupValues[1].takeIf { it.isNotBlank() }?.let { it to match.range.last } }
            .toList()
        return anchors.mapIndexedNotNull { index, (gateKey, start) ->
            val end = anchors.getOrNull(index + 1)?.second ?: html.length
            val number = watchNumber.find(html.substring(start, end))
                ?.groupValues?.get(1)
                ?.toFloatOrNull()
                ?.toInt()
                ?: return@mapIndexedNotNull null
            Episode(number, gateKey)
        }.distinctBy { it.number }.sortedBy { it.number }
    }

    /**
     * Every mirror on a `gate.php` page, primary first.
     *
     * The page orders them `ax`, then `ct` marked `&error`, then `ck` marked `&error2` — its own
     * player only reaches the later ones from an `onerror` handler. That order is kept as-is, so
     * the fallbacks stay fallbacks, and duplicates are dropped because the same host is emitted
     * more than once per page.
     *
     * **The page plants a decoy for scrapers.** Since about 2026-10 every gate page opens with a
     * `<source>` wrapped in an HTML comment, naming a 3.7-second clip ("Watch episode on
     * AnimeHeaven.Me", 88KB, the same `2c523c8c…` hash on every episode). A browser never sees it;
     * a regex over the raw page took it as the first mirror and played it as the episode. So
     * comments are stripped before matching, and when [gateKey] is known only files named by it are
     * kept: every real mirror's query opens with the episode's own gate key, and the decoy's does
     * not.
     */
    fun videoUrls(html: String, gateKey: String? = null): List<String> {
        val urls = sourceTag.findAll(htmlComment.replace(html, "")).mapNotNull { tag ->
            NativeProviderParsers.attr(tag.value, "src")
                .trim()
                .takeIf { it.contains(".mp4", ignoreCase = true) }
        }.distinct().toList()
        if (gateKey.isNullOrBlank()) return urls
        return urls.filter { it.contains("?$gateKey", ignoreCase = true) }
    }

    /**
     * [html] cut into one slice per entry in [starts], each running to the next start.
     *
     * Slicing on the card boundaries rather than matching each card with a lazy `[\s\S]*?` keeps
     * the final card from swallowing the whole page footer, and keeps every inner pattern searching
     * one card's worth of text instead of an unbounded tail.
     */
    private fun segments(html: String, starts: List<Int>): List<String> =
        starts.mapIndexed { index, start ->
            html.substring(start, starts.getOrNull(index + 1) ?: html.length)
        }
}
