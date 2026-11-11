package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.diagnostics.DiagnosticsLog
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * RareAnimes (rareanimes.mov, "RareToonsIndia") — Hindi/Tamil/Telugu dub source. The site is plain
 * WordPress; every episode's WatchMultiQuality link chains through codedew.com
 * (`/zipper/?url=…` → 302 → `/multiquality/?url=<fid>`) into an `argon.*` JW Player embed whose
 * `_juicycodes` blob decodes client-side to a single HLS master playlist (see [JuicyCodesDecoder]).
 * That master is session-gated, so what this provider hands back is the embed — see [resolveStream].
 *
 * All languages are exposed through the pipe's single `dub` channel with the language name on
 * [StreamItem.audio], until the sub|dub category model grows real per-language rows.
 *
 * **Post selection.** Title similarity alone picks the wrong page surprisingly often: for
 * "Demon Slayer: Kimetsu no Yaiba" the Infinity Castle *movie* post scores 0.64 while the Season 1
 * post carrying all 26 episodes scores 0.20. So candidates are ranked but then *probed* — a post
 * that parses to zero episodes is skipped rather than accepted.
 *
 * **Movies.** That probe is also why every film on the site used to be invisible: a movie post has
 * no `Episode NN` heading to parse, so it looks exactly like the stub pages the probe exists to
 * reject. A single-episode AniList entry has nothing to lose by reading a post whole, so it — and
 * only it — falls back to [RareAnimesParser.parseMovie]; a series must keep skipping such posts or
 * Infinity Castle beats Demon Slayer's Season 1 post again.
 *
 * **Season stitching.** The site splits long series into per-season posts that each restart at
 * episode 1, while AniList numbers them absolutely (Naruto Shippuden = one 500-episode entry).
 * When the AniList entry clearly outruns the best single post, the season posts are fetched and
 * laid end to end so the numbering lines up with the rest of the app.
 */
internal class RareAnimesProvider(
    private val client: OkHttpClient,
    private val base: String = BASE,
) {
    private data class Catalog(val label: String, val episodes: List<RareAnimesParser.Episode>)

    private val catalogs = boundedMap<Int, Catalog>(64)
    /** Titles the site genuinely has nothing for, so a miss costs one search per [MISS_TTL_MS]. */
    private val misses = boundedMap<Int, Long>(128)

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val episodes = catalog(media).episodes
        // Every track is a dub (Hindi/Tamil/Telugu); there is no Japanese-audio "sub" offering.
        return EpisodeAvailability(emptySet(), episodes.map { it.number }.toSet())
    }

    /**
     * @param audio unused — this provider is dub-only, and [episodeAvailability] reports an empty
     *   sub set, so the catalog never hands the app a sub row that could reach here.
     */
    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val entry = catalog(media).episodes.firstOrNull { it.number == episode }
            ?: error("RareAnimes episode $episode is not in the catalog")
        // Hindi first: it is the site's primary track and the one most likely to resolve, so if
        // the batch deadline below cuts things short, that is the track we keep.
        val resolvable = entry.languages.mapNotNull { (language, links) ->
            RareAnimesParser.preferred(links)?.let { language to it }
        }.sortedBy { (language, _) -> LANGUAGE_PRIORITY.indexOf(language).takeIf { it >= 0 } ?: LANGUAGE_PRIORITY.size }
        // Each language is an independent two-hop resolve against a slow host.
        val streams = mapParallel(resolvable, RESOLVE_CONCURRENCY, RESOLVE_BUDGET_MS) { (language, link) ->
            runCatching { resolveStream(link.url, language) }
                .onFailure {
                    DiagnosticsLog.throwable(
                        "RareAnimes resolve failed ep=$episode lang=$language",
                        it,
                    )
                }
                .getOrNull()
        }.filterNotNull()
        if (streams.isEmpty()) error("RareAnimes episode $episode has no playable streams")
        // Hindi is the site's primary track; fall back to whatever resolved so one stream is
        // always marked active for players that key their default off it.
        val active = streams.firstOrNull { it.audio.equals("Hindi", ignoreCase = true) } ?: streams.first()
        return SourcesResult(
            streams.map { it.copy(isActive = it === active) }.distinctBy(StreamItem::url),
            emptyList(),
            null,
            null,
        )
    }

    private fun catalog(media: Media): Catalog {
        catalogs[media.id]?.let { return it }
        misses[media.id]?.let { seenAt ->
            if (System.currentTimeMillis() - seenAt < MISS_TTL_MS) {
                error("RareAnimes has no entry for this title")
            }
            misses.remove(media.id)
        }
        val built = build(media)
        if (built == null) {
            misses[media.id] = System.currentTimeMillis()
            error("RareAnimes has no entry for this title")
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
     * RareAnimes out of every title until the process restarted. Transport failures are rethrown
     * so nothing is learned from them; the same fix ToonHub and AnimeDekho already carry.
     *
     * The sink is atomic rather than a plain var because the searches and the page fetches both
     * run through [mapParallel]: the first failure wins and the rest are dropped, which is all
     * this needs — one is enough to prove the pass never got an answer.
     */
    private fun build(media: Media): Catalog? {
        val unanswered = AtomicReference<Throwable?>(null)
        val candidates = rankedCandidates(media, unanswered = unanswered)
        if (candidates.isEmpty()) {
            unanswered.get()?.let { throw it }
            return null
        }

        // The page and its parse are memoised separately: stitchSeasons asks for a season's
        // episodes twice (once to warm it in parallel, once to lay it out) and these pages are
        // ~450 KB, so neither the fetch nor the regex sweep should run again.
        val pages = HashMap<String, String>()
        fun pageOf(candidate: RareAnimesParser.SearchResult): String =
            synchronized(pages) { pages[candidate.url] } ?: run {
                val html = runCatching { get(candidate.url, referer = "$base/") }
                    .onFailure { unanswered.compareAndSet(null, it) }
                    .getOrDefault("")
                synchronized(pages) { pages[candidate.url] = html }
                html
            }

        val parsed = HashMap<String, List<RareAnimesParser.Episode>>()
        fun episodesOf(candidate: RareAnimesParser.SearchResult): List<RareAnimesParser.Episode> =
            synchronized(parsed) { parsed[candidate.url] } ?: run {
                val episodes = RareAnimesParser.parseEpisodes(pageOf(candidate))
                synchronized(parsed) { parsed[candidate.url] = episodes }
                episodes
            }

        // Best-ranked post that actually carries episodes. Movie posts and stub pages score high
        // on title alone but parse to nothing, so they fall through here instead of winning.
        val single = candidates.take(MAX_PROBE_POSTS)
            .firstNotNullOfOrNull { candidate ->
                episodesOf(candidate).takeIf { it.isNotEmpty() }?.let { Catalog(candidate.title, it) }
            }

        val expected = expectedEpisodeCount(media)
        if (single != null && (expected == null || single.episodes.size >= expected - EPISODE_COUNT_SLACK)) {
            return single
        }

        // Nothing above can match a film, because a film's post has no episode heading to match.
        // Reached only when the entry is one video anyway, so there is no series for a stray movie
        // post to displace — and no stitching to attempt below either.
        if (single == null && isSingleVideo(media)) {
            candidates.take(MAX_PROBE_POSTS).firstNotNullOfOrNull { candidate ->
                RareAnimesParser.parseMovie(pageOf(candidate))?.let {
                    Catalog(candidate.title, listOf(it))
                }
            }?.let { return it }
        }

        // Only stitch posts belonging to the same series as the post we trust, or "Naruto" would
        // absorb the Shippuden season posts that its own search returns.
        val series = RareAnimesParser.seriesTitle((single?.label ?: candidates.first().title))
        fun sameSeries(all: List<RareAnimesParser.SearchResult>) = all.filter {
            RareAnimesParser.seriesTitle(it.title).equals(series, ignoreCase = true)
        }
        // One feed page holds ~6 posts, so a 22-season run is invisible from page 1 alone. Walk
        // deeper only here, on the long series that actually need it — and note the walk restarts
        // at page one, so this re-reads what [rankedCandidates] already saw rather than resuming
        // past the end of a short result set. At 13 KB a page that is the cheap half of the trade.
        val deep = sameSeries(
            candidates + rankedCandidates(media, depth = MAX_SEARCH_PAGES, unanswered = unanswered),
        )
        val stitched = stitchSeasons(deep, ::episodesOf, expected)
        if (stitched != null && stitched.episodes.size > (single?.episodes?.size ?: 0)) {
            DiagnosticsLog.event(
                "RareAnimes stitched ${stitched.episodes.size} episodes for id=${media.id} " +
                    "(best single post had ${single?.episodes?.size ?: 0}, AniList expects $expected)",
            )
            return stitched
        }
        // Nothing matched. Only say so when the site actually answered — otherwise this is a
        // cancelled pass, and [catalog] would remember it as a six-hour absence.
        if (single == null) unanswered.get()?.let { throw it }
        return single
    }

    /**
     * Lays the site's per-season posts end to end so episode numbers match AniList's absolute
     * numbering.
     *
     * Only an unbroken run from season 1 takes part. A missing season in the middle would slide
     * every later season's episodes onto numbers that belong to the gap, so the run stops at the
     * first hole: fewer episodes offered, but every one of them numbered correctly.
     */
    private fun stitchSeasons(
        candidates: List<RareAnimesParser.SearchResult>,
        episodesOf: (RareAnimesParser.SearchResult) -> List<RareAnimesParser.Episode>,
        expected: Int?,
    ): Catalog? {
        val bySeason = candidates
            .mapNotNull { candidate -> RareAnimesParser.seasonNumber(candidate.title)?.let { it to candidate } }
            .distinctBy { it.first }
            .sortedBy { it.first }
        val seasons = mutableListOf<Pair<Int, RareAnimesParser.SearchResult>>()
        bySeason.forEach { entry -> if (entry.first == seasons.size + 1) seasons += entry }
        if (seasons.size < 2) return null
        seasons.subList(minOf(seasons.size, MAX_SEASON_POSTS), seasons.size).clear()

        // Warm every season page in parallel; each is ~500 KB and the caller is on a budget.
        mapParallel(seasons, FETCH_CONCURRENCY) { (_, candidate) -> episodesOf(candidate) }

        var next = 1
        val absolute = mutableListOf<RareAnimesParser.Episode>()
        seasons.forEach { (_, candidate) ->
            episodesOf(candidate).sortedBy { it.number }.forEach { episode ->
                absolute += episode.copy(number = next++)
            }
        }
        if (absolute.isEmpty()) return null
        if (expected != null && absolute.size < expected - EPISODE_COUNT_SLACK) {
            DiagnosticsLog.event(
                "RareAnimes stitched only ${absolute.size}/$expected episodes; numbering may drift",
            )
        }
        // AniList splits some series into per-season entries while the site keeps stitching past
        // them (Jujutsu Kaisen's entry is season 1 only). Trimming to the entry's own length keeps
        // the leading seasons aligned instead of inventing episodes the entry does not have.
        val trimmed = if (expected != null) absolute.take(expected) else absolute
        return Catalog("${seasons.size} season posts", trimmed)
    }

    /** Search hits across the AniList titles, best title match first. */
    private fun rankedCandidates(
        media: Media,
        depth: Int = 1,
        unanswered: AtomicReference<Throwable?>? = null,
    ): List<RareAnimesParser.SearchResult> {
        val titles = listOfNotNull(media.title.english, media.title.romaji, media.title.native)
            .filter { it.isNotBlank() }.distinct()
        if (titles.isEmpty()) return emptyList()
        val hits = mapParallel(searchQueries(titles), FETCH_CONCURRENCY) { query ->
            searchFeed(query, depth, unanswered)
        }.filterNotNull().flatten().distinctBy { it.url }
        // WordPress returns the newest post first. Title matching alone gives every season post
        // the same base-series score, so a season-one AniList entry used to probe season four and
        // then burn the catalogue deadline trying to stitch unrelated seasons. Rank the intended
        // season before any of those large post pages are fetched.
        return RareAnimesParser.rankSearchResults(
            titles = titles,
            results = hits,
            episodic = !isSingleVideo(media),
            minScore = MIN_TITLE_SCORE,
        )
    }

    /**
     * One query's search results, read from the RSS feed rather than the rendered search page.
     *
     * `/?s=` is **~430 KB per page** here and this provider runs on every title, which is the
     * single largest parse in the catalogue — the cost [NativeProviderParsers] exists to contain,
     * and the reason a TV box cannot finish this provider. `/search/{query}/feed/rss2/` answers
     * the same question in 11-15 KB. Measured 2026-09-14 over five series, walking the feed to
     * exhaustion against five pages of HTML: naruto 115 KB vs 2110 KB (51 posts found against 30),
     * dragon ball 136 KB vs 2107 KB (60 against 30), bleach 14 KB vs 2252 KB.
     *
     * It is also the better result set. The rendered search matches loosely across post content,
     * so "One Piece" returned One Punch Man, Dr. STONE and *Ghost in the Shell: Stand Alone*, while
     * the feed returned the One Piece season and film posts and nothing else. Nothing relevant was
     * lost in any series measured — including every `Naruto`/`Naruto Shippuden` season post, which
     * is what [stitchSeasons] needs and what five pages of HTML did *not* reach.
     *
     * **Walked from the first page in order, never fetched in parallel and never resumed.** Asking
     * for a page past the last one does not answer with an empty feed: WordPress falls through to
     * the theme and returns the full **507 KB** HTML page. So this stops at the first page that is
     * not a feed or that comes back short, and a deeper walk restarts at page one rather than
     * picking up where a shallower one left off — measured, a resumed walk paid that 507 KB on
     * every title the site does not have, because page one had already been the last page and only
     * the walk that fetched it knew. Re-reading page one instead costs 13 KB.
     *
     * A query with genuinely no results is not the past-the-end case — it answers with a valid,
     * empty 2 KB feed — so a miss stays distinguishable from a page that does not exist.
     */
    private fun searchFeed(
        query: String,
        depth: Int,
        unanswered: AtomicReference<Throwable?>?,
    ): List<RareAnimesParser.SearchResult> {
        val found = mutableListOf<RareAnimesParser.SearchResult>()
        for (page in 1..depth) {
            val body = runCatching { get(feedUrl(query, page), referer = "$base/") }
                .onFailure { unanswered?.compareAndSet(null, it) }
                .getOrNull()
                ?: return found
            val results = RareAnimesParser.parseSearchFeed(body) ?: return found
            found += results
            // A short page is the last one. Guessing this wrong only ever costs *pages*, while
            // reading one page too many costs the 507 KB that this whole change exists to avoid —
            // so the comparison is against a declared size rather than one inferred from the walk,
            // which on a final page of exactly its own length would have stepped past the end.
            if (results.size < FEED_PAGE_SIZE) return found
        }
        return found
    }

    private fun feedUrl(query: String, page: Int): String {
        val path = "$base/search/${enc(query)}/feed/rss2/"
        return if (page > 1) "$path?paged=$page" else path
    }

    /**
     * WordPress search is literal, so AniList's fuller names miss: searching
     * "Demon Slayer: Kimetsu no Yaiba" returns only the Infinity Castle movie, while
     * "Demon Slayer" returns every season post. Query the part before the colon too, and keep the
     * total capped — each search page is ~430 KB and this provider runs on every title.
     *
     * The season-stripped form leads, because it is the only one a sequel entry can be found by and
     * because the cap would otherwise spend every query on names the site cannot match. AniList
     * files sequels as their own entries — "Solo Leveling Season 2 -Arise from the Shadow-",
     * "Attack on Titan Final Season" — and the site has posts for both, named after the base series.
     * Searching only the full names returned neither, and the ranking then handed the best of the
     * unrelated leftovers to the player: Solo Leveling season 2 played Mushoku Tensei, and Attack on
     * Titan played Oshi no Ko. A title with no season marker is unchanged by the strip and so costs
     * no extra request.
     */
    private fun searchQueries(titles: List<String>): List<String> = titles
        .flatMap { title ->
            listOf(RareAnimesParser.baseSeriesTitle(title), title.substringBefore(':').trim(), title)
        }
        .filter { it.length >= 3 }
        .distinctBy { it.lowercase() }
        .take(MAX_SEARCH_QUERIES)

    /** How many episodes the AniList entry is expected to carry, if it is knowable. */
    private fun expectedEpisodeCount(media: Media): Int? = media.episodes?.takeIf { it > 0 }
        ?: media.nextAiringEpisode?.episode?.minus(1)?.takeIf { it > 0 }

    /**
     * Whether the entry is one video rather than a run of episodes.
     *
     * Format is checked as well as the count because AniList leaves `episodes` null on plenty of
     * films, and a null count would otherwise send a movie down the season-stitching path.
     */
    private fun isSingleVideo(media: Media): Boolean =
        media.format.equals("MOVIE", ignoreCase = true) || expectedEpisodeCount(media) == 1

    /**
     * codedew zipper → (302) multiquality → argon embed. OkHttp follows the zipper's redirect
     * itself, so [get] on the zipper URL returns the multiquality page directly.
     *
     * The result is the **embed page**, not the HLS master inside it. The master decodes fine, but
     * its CDN ("JUICY NGINX") authorises per player session: the embed registers itself through a
     * token and a `/api/videos/…/ping` route, and every request that did not come from that page
     * is answered with a branded 403 — no Referer, Origin, or cookie combination gets past it.
     * Handing the WebView the embed lets the page do its own handshake, which is exactly how kiwi
     * is handled for the same reason (see `pickProviderStream`).
     *
     * The blob is still decoded, because a page whose config does not carry a master playlist has
     * nothing to play and should fail here rather than at the player.
     */
    private fun resolveStream(zipperUrl: String, language: String): StreamItem {
        val multiquality = get(zipperUrl, referer = "$base/")
        val embedUrl = RareAnimesParser.embedUrl(multiquality)
            ?: error("codedew page has no argon embed")
        val embed = get(embedUrl, referer = CODEDEW_ORIGIN)
        val blob = JuicyCodesDecoder.extractBlob(embed) ?: error("argon embed has no juicycodes blob")
        JuicyCodesDecoder.config(JuicyCodesDecoder.decode(blob))
        return StreamItem(
            url = embedUrl,
            type = "embed",
            quality = "MultiQuality",
            audio = language.replaceFirstChar { it.uppercase() },
            referer = CODEDEW_ORIGIN,
            isActive = false,
            width = null,
            height = null,
        )
    }

    private fun get(url: String, referer: String?): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .apply { referer?.let { header("Referer", it) } }
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("RareAnimes HTTP ${response.code} for $url")
            return response.body?.string().orEmpty()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    /**
     * Runs [block] over [items] on a short-lived pool; failures come back as null.
     *
     * [timeoutMs] bounds the whole batch, not each item: whatever has not finished by then is
     * cancelled and reported as null. Callers that are themselves on a deadline need that — the
     * watch screen gives a provider 8 seconds total, and three of this site's 283 KB player pages
     * do not reliably fit, so it is better to return the tracks that did land than to time out
     * with nothing.
     */
    private fun <T, R> mapParallel(
        items: List<T>,
        concurrency: Int,
        timeoutMs: Long = Long.MAX_VALUE,
        block: (T) -> R,
    ): List<R?> {
        if (items.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(concurrency, items.size))
        return try {
            val tasks = items.map { item -> Callable { runCatching { block(item) }.getOrNull() } }
            val futures = if (timeoutMs == Long.MAX_VALUE) {
                pool.invokeAll(tasks)
            } else {
                pool.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS)
            }
            futures.map { future -> runCatching { future.get() }.getOrNull() }
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
        private const val BASE = "https://www.rareanimes.mov"
        private const val CODEDEW_ORIGIN = "https://codedew.com/"

        /**
         * How close a post title has to be before it can be this title at all.
         *
         * At 0.28 this was not a match test, it was a formality: WordPress answers a name it does
         * not carry with whatever shares a word, and bigram similarity puts that noise around 0.3 —
         * comfortably over the bar. The best of the leftovers was then probed, parsed to real
         * episodes, and played, which is how a request for one anime came back as another.
         *
         * Measured over twenty titles against the live site on 2026-09-03, the two populations do
         * not overlap: every correct post scored 0.594-1.000 (the floor being a movie post matched
         * from an arc name) and every wrong one 0.289-0.388. Half sits in the gap with room on both
         * sides. The cost of the bar being slightly too high is the provider reporting it has
         * nothing, which is what it should have said in the first place.
         */
        private const val MIN_TITLE_SCORE = 0.5
        private const val MAX_SEARCH_QUERIES = 4
        /** How deep the feed walk goes, and only when a series needs season stitching. */
        private const val MAX_SEARCH_PAGES = 5
        /** Items the site puts on one feed page; a page holding fewer is the last one. */
        private const val FEED_PAGE_SIZE = 6
        /** Posts probed for episodes before giving up on a title. */
        private const val MAX_PROBE_POSTS = 6
        private const val MAX_SEASON_POSTS = 24
        private const val FETCH_CONCURRENCY = 4
        private const val RESOLVE_CONCURRENCY = 3
        /** Inside the watch screen's 8s per-provider budget, with room to return what resolved. */
        private const val RESOLVE_BUDGET_MS = 6_000L
        private val LANGUAGE_PRIORITY = listOf("hindi", "tamil", "telugu")
        /** AniList and the site disagree by an episode or two often enough to tolerate it. */
        private const val EPISODE_COUNT_SLACK = 2
        private const val MISS_TTL_MS = 6L * 60 * 60 * 1000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/** Pure-HTML parsing for RareAnimes pages, kept separate so unit tests never touch the network. */
internal object RareAnimesParser {
    data class SearchResult(val url: String, val title: String)
    data class ServerLink(val url: String, val anchor: String)

    /** Episode number → per-language server links (`hindi`/`tamil`/`telugu`). */
    data class Episode(val number: Int, val languages: Map<String, List<ServerLink>>)

    private val EPISODE_HEAD = Regex(""">\s*Episode\s+0*(\d+)""", RegexOption.IGNORE_CASE)
    private val LANGUAGE = Regex("""\b(Hindi|Tamil|Telugu)\b""", RegexOption.IGNORE_CASE)
    /** AniList's wordy way of numbering a last season: "Final Season", "The Final Season". */
    private val FINAL_SEASON = Regex("""\bfinal\s+season\b""", RegexOption.IGNORE_CASE)

    private val TITLE_BOILERPLATE = Regex(
        """\s*(?:[-–—:|]\s*)?\b(?:Season|Episodes?|Hindi|Tamil|Telugu|Bengali|Malayalam|""" +
            """Dubbed|Dual\s+Audio|Download|Watch|Complete|Uncut|Movie|Film|Series)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SELECTION_BOILERPLATE = Regex(
        """\s*(?:[-–—:|]\s*)?\b(?:Episodes?|Hindi|Tamil|Telugu|Bengali|Malayalam|""" +
            """Dubbed|Dual\s+Audio|Download|Watch|Complete|Uncut|Film|Series)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val ZIPPER_LINK = Regex(
        """<a\b[^>]*?href="(https?://codedew\.com/zipper/\?url=[^"]+)"[^>]*>([\s\S]*?)</a>""",
        RegexOption.IGNORE_CASE,
    )
    /** The post body, which on a movie post is the only place a server link legitimately lives. */
    private val ENTRY_CONTENT = Regex("""<div\b[^>]*\bentry-content\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ENTRY_CONTENT_END = Regex(
        """herald-related|herald-single-tags|<footer\b""",
        RegexOption.IGNORE_CASE,
    )
    private val IFRAME_EMBED = Regex(
        """<iframe\b[^>]*?\bsrc="(https?://[a-z0-9.-]+/embed/[A-Za-z0-9]+)"""",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_EMBED = Regex("""https://[a-z0-9.-]+/embed/[A-Za-z0-9]+""")
    private val FEED_ITEM = Regex("""<item>([\s\S]*?)</item>""", RegexOption.IGNORE_CASE)
    private val FEED_LINK = Regex("""<link>([\s\S]*?)</link>""", RegexOption.IGNORE_CASE)
    private val FEED_TITLE = Regex("""<title>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
    private val CDATA = Regex("""<!\[CDATA\[|]]>""")

    /**
     * One page of `/search/{query}/feed/rss2/`, or null when the body is not a feed at all.
     *
     * Null means "this page does not exist" — WordPress answers a page past the last one with the
     * rendered HTML search page, half a megabyte of it — and it is the signal
     * [RareAnimesProvider.searchFeed] stops on. It is never "no results": a query the site has
     * nothing for still answers with a well-formed, empty feed.
     *
     * `<title>` is CDATA-wrapped on some items and bare, entity-escaped text on others, so both
     * are unwrapped before decoding. The two fields taken here are exactly the two the rendered
     * page gave: the post URL and its title.
     */
    fun parseSearchFeed(body: String): List<SearchResult>? {
        if (!body.trimStart().startsWith("<?xml")) return null
        return FEED_ITEM.findAll(body).mapNotNull { item ->
            val block = item.groupValues[1]
            val url = FEED_LINK.find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = FEED_TITLE.find(block)?.groupValues?.get(1)
                ?.let { CDATA.replace(it, "") }
                ?.let(NativeProviderParsers::decodeEntities)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            SearchResult(url, title)
        }.distinctBy { it.url }.toList()
    }

    /** The season a title covers, including the common forms AniList uses for sequels. */
    fun seasonNumber(title: String): Int? = NativeProviderParsers.seasonNumber(title)

    /**
     * Orders search results without touching the network.
     *
     * An explicit AniList season marker wins. "Final Season" is read as the last season the site
     * has posted, because that is the only thing the phrase can mean and nothing else in here maps
     * a word to a number. A suffix-less title is treated as season one only when it exactly matches
     * a returned series name; that guard lets titled sequel arcs (for example, "Hashira Training
     * Arc") win by their more specific post title instead.
     */
    fun rankSearchResults(
        titles: List<String>,
        results: List<SearchResult>,
        episodic: Boolean,
        minScore: Double = 0.0,
    ): List<SearchResult> {
        if (titles.isEmpty() || results.isEmpty()) return emptyList()

        fun score(candidate: SearchResult): Double = titles.maxOf { title ->
            maxOf(
                NativeProviderParsers.titleSelectionScore(title, seriesTitle(candidate.title)),
                NativeProviderParsers.titleSelectionScore(title, selectionTitle(candidate.title)),
            )
        }

        // Every season question below is answered from the posts that could be this series at all.
        // Reading them off the raw results would let an unrelated "Season 22" that the score is
        // about to discard decide which season was asked for.
        val qualified = results.distinctBy { it.url }.filter { score(it) >= minScore }

        val explicitSeason = if (episodic) titles.firstNotNullOfOrNull(::seasonNumber) else null
        // AniList names Attack on Titan's fourth season "Final Season" and gives it no number,
        // while the site posts it as "Season 4". With nothing to match on, every season post tied
        // and the earliest won, so asking for the final season played the first one.
        val finalSeason = if (episodic && explicitSeason == null && titles.any(::asksForFinalSeason)) {
            qualified.mapNotNull { seasonNumber(it.title) }.maxOrNull()
        } else {
            null
        }
        val exactBaseMatch = episodic && titles.any { title ->
            val normalized = NativeProviderParsers.normalize(title)
            normalized.isNotBlank() && qualified.any { candidate ->
                normalized == NativeProviderParsers.normalize(seriesTitle(candidate.title))
            }
        }
        val requestedSeason = explicitSeason ?: finalSeason ?: 1.takeIf {
            exactBaseMatch && qualified.any { seasonNumber(it.title) == 1 }
        }
        fun seasonPriority(candidate: SearchResult): Int = when {
            requestedSeason == null -> 1
            seasonNumber(candidate.title) == requestedSeason -> 2
            seasonNumber(candidate.title) == null -> 1
            else -> 0
        }

        return qualified
            .sortedWith(
                compareByDescending<SearchResult>(::seasonPriority)
                    .thenByDescending(::score)
                    // Every season post of a series scores the same, so without a last key the
                    // winner was whichever WordPress had published most recently — which meant the
                    // answer changed each time the site posted a new season. Lowest season instead:
                    // stable, and right whenever the entry asking is the base series. An unnumbered
                    // post is treated as the first, which is what one usually is.
                    .thenBy { seasonNumber(it.title) ?: 1 },
            )
    }

    /** An AniList sequel name reduced to the series the site files it under. */
    fun baseSeriesTitle(title: String): String = NativeProviderParsers.baseSeriesTitle(title)

    /** Whether a title says "final season" instead of numbering it, as AniList does for sequels. */
    private fun asksForFinalSeason(title: String): Boolean = FINAL_SEASON.containsMatchIn(title)

    /** The meaningful post title, retaining season/arc/movie words but dropping site boilerplate. */
    private fun selectionTitle(postTitle: String): String {
        val cut = SELECTION_BOILERPLATE.find(postTitle)?.range?.first ?: postTitle.length
        return postTitle.substring(0, cut).trim().trim('-', '–', '—', ':', '|', '(', '[').trim()
    }

    /**
     * The series name with the site's stock suffix removed, e.g.
     * "Demon Slayer Season 1 – Episodes Hindi Dubbed Download HD Jio Cinema" → "Demon Slayer".
     * Used for both ranking and for grouping a series' season posts together.
     */
    fun seriesTitle(postTitle: String): String {
        val cut = TITLE_BOILERPLATE.find(postTitle)?.range?.first ?: postTitle.length
        return postTitle.substring(0, cut).trim().trim('-', '–', '—', ':', '|', '(', '[').trim()
    }

    /**
     * Episode blocks run from one `>Episode NN` heading to the next. Inside a block, a language
     * label ("Hindi Uncut", "Tamil", "Telugu") opens that language's section — but plenty of posts
     * are single-language and label their rows by *dub source* instead ("New Sony Dub",
     * "Crunchyroll"). Those blocks carry no language word at all, so the whole block is taken as
     * [defaultLanguage]; without that fallback a post like Jujutsu Kaisen Season 1 parses to one
     * episode out of twenty-four.
     */
    fun parseEpisodes(html: String, defaultLanguage: String = "hindi"): List<Episode> {
        val heads = EPISODE_HEAD.findAll(html).toList()
        return heads.mapIndexed { index, head ->
            val end = heads.getOrNull(index + 1)?.range?.first ?: html.length
            val block = html.substring(head.range.first, end)
            val labels = LANGUAGE.findAll(block).toList()
            val languages = if (labels.isEmpty()) {
                links(block).takeIf { it.isNotEmpty() }?.let { mapOf(defaultLanguage to it) }.orEmpty()
            } else {
                labels.mapIndexedNotNull { labelIndex, label ->
                    val sectionEnd = labels.getOrNull(labelIndex + 1)?.range?.first ?: block.length
                    val sectionLinks = links(block.substring(label.range.first, sectionEnd))
                    if (sectionLinks.isEmpty()) null else label.value.lowercase() to sectionLinks
                }.toMap()
            }
            Episode(head.groupValues[1].toInt(), languages)
        }.filter { it.languages.isNotEmpty() }.distinctBy { it.number }
    }

    /**
     * The single "episode" of a movie post, or null if the post is not one.
     *
     * Films are posted with the servers straight under a "Hindi – Download" heading and no
     * `Episode NN` anywhere, so [parseEpisodes] returns nothing for every one of them — which is
     * how a Grave of the Fireflies report came in reading "RareAnimes is unavailable for this
     * episode" against a post that resolves by hand. Language sections are read exactly as they
     * are inside an episode block, so a film posted in Hindi, Tamil and Telugu still offers three
     * tracks.
     *
     * Two narrowings keep this from finding "movies" everywhere:
     *
     * - Only the post body is searched. A sidebar or related-posts strip carries other titles'
     *   links, and there is no episode heading here to bound the search instead.
     * - Only the decodable server is collected. Without a heading proving a link is a server row,
     *   the anchor has to: the 2019-era Grave of the Fireflies post runs its prose through a link
     *   rewriter that turns the words "Japanese" and "Hepburn" into codedew URLs, and [preferred]'s
     *   last-ditch "first link" would happily hand one of those to the player.
     */
    fun parseMovie(html: String, defaultLanguage: String = "hindi"): Episode? {
        val body = entryContent(html)
        val labels = LANGUAGE.findAll(body).toList()
        val languages = if (labels.isEmpty()) {
            decodableLinks(body).takeIf { it.isNotEmpty() }?.let { mapOf(defaultLanguage to it) }.orEmpty()
        } else {
            // Grouped rather than mapped: a post names its language several times over (blurb,
            // info table, heading), and only some of those spans hold links.
            labels.mapIndexedNotNull { index, label ->
                val end = labels.getOrNull(index + 1)?.range?.first ?: body.length
                decodableLinks(body.substring(label.range.first, end))
                    .takeIf { it.isNotEmpty() }
                    ?.let { label.value.lowercase() to it }
            }.groupBy({ it.first }, { it.second }).mapValues { (_, links) -> links.flatten() }
        }
        return if (languages.isEmpty()) null else Episode(1, languages)
    }

    /** The post body alone, or the whole document when the theme's wrapper cannot be found. */
    private fun entryContent(html: String): String {
        val start = ENTRY_CONTENT.find(html)?.range?.last?.plus(1) ?: return html
        val end = ENTRY_CONTENT_END.find(html, start)?.range?.first ?: html.length
        return html.substring(start, end)
    }

    /** Server rows that can actually be followed to an argon embed — the ones [preferred] ranks first. */
    private fun decodableLinks(section: String): List<ServerLink> = links(section).filter {
        val anchor = letters(it.anchor)
        anchor.contains("multiquality") || anchor.contains("multquality")
    }

    private fun letters(value: String) = value.lowercase().filter(Char::isLetter)

    private fun links(section: String): List<ServerLink> = ZIPPER_LINK.findAll(section)
        .map { match ->
            ServerLink(
                url = NativeProviderParsers.decodeEntities(match.groupValues[1]),
                anchor = NativeProviderParsers.stripTags(match.groupValues[2]),
            )
        }
        .toList()

    /**
     * WatchMultiQuality is the only server we can decode natively. The site spells it both
     * "WatchMultiQuality" and "WatchMultQuality", so match on letters only rather than either
     * literal, and keep the other servers as a fallback order.
     */
    fun preferred(links: List<ServerLink>): ServerLink? {
        return links.firstOrNull { letters(it.anchor).contains("multiquality") }
            ?: links.firstOrNull { letters(it.anchor).contains("multquality") }
            ?: links.firstOrNull { it.anchor.contains("watch", ignoreCase = true) }
            ?: links.firstOrNull()
    }

    /** The argon (or successor-domain) player iframe on a codedew multiquality page. */
    fun embedUrl(multiqualityHtml: String): String? =
        IFRAME_EMBED.find(multiqualityHtml)?.groupValues?.get(1)
            ?: BARE_EMBED.find(multiqualityHtml)?.value
}
