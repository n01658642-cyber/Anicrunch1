package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * Kartoons (kartoons.to), a Hindi-dubbed anime catalogue.
 *
 * The site exposes one headless path worth having: its own CloudStream surface answers
 * `/cloudstream/episode/{id}/links?token=` with plain, already-decrypted HLS playlist URLs that
 * play with no headers at all (proxy master → signed workers.dev playlists → MPEG-TS segments,
 * one Hindi AAC track). No PoW, no Turnstile, no AES layer. The credential is an account token from
 * [KartoonsTokenPool], which rotates a pool of them.
 *
 * Everything else is the open web API: `/shows?search=` for candidates, `/shows/{id}` for seasons,
 * `/shows/{showId}/season/{seasonId}/all-episodes` for episode ids. Which season is the AniList
 * entry, and how the site numbered its episodes, is [KartoonsParser]'s business; this class fetches
 * what it needs and keeps the answer.
 *
 * Every catalogue carries its episode ids in the app's numbering, fetched from the site's lists
 * when the catalogue is built — for a whole-series run too, because the claimed season counts
 * follow uploads and only the lists say where each season really ends. Availability is therefore
 * exactly the episodes the site lists, never a count it claims.
 *
 * The catalogue is declared Hindi in [com.anilili.data.ProviderCatalog], so it is never the automatic
 * choice for a viewer who did not ask for Hindi audio, and `audio = "Hindi"` on each stream says so
 * in the picker.
 */
internal class KartoonsProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val pool: KartoonsTokenPool,
    private val bases: List<String> = BASES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Catalog(
        /** App episode number → kartoons episode id. */
        val episodeIds: Map<Int, String>,
        val builtAt: Long,
    )

    private val catalogs = boundedMap<Int, Catalog>(64)
    private val misses = boundedMap<Int, Long>(128)
    /**
     * One build per title at a time. The fast catalogue, the full one and a resolve all ask for the
     * same title within a second of opening it, and each used to run its own search and probes.
     * Interruptible, so a cancelled caller waiting on another's build leaves at once.
     */
    private val buildLocks = ConcurrentHashMap<Int, ReentrantLock>()
    @Volatile private var preferredBase: String? = null

    fun episodeAvailability(media: Media): EpisodeAvailability =
        EpisodeAvailability(sub = emptySet(), dub = catalog(media).episodeIds.keys.toSet())

    /** The kartoons episode id behind app episode [episode], or throws when there is none. */
    internal fun episodeId(media: Media, episode: Int): String =
        catalog(media).episodeIds[episode] ?: error("Kartoons episode $episode is not in the catalog")

    /** @param audio unused: the catalogue is intentionally exposed through the dub category. */
    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val links = resolveLinks(episodeId(media, episode))
        return SourcesResult(
            streams = links.mapIndexed { index, link ->
                StreamItem(
                    url = link.url,
                    type = "hls",
                    quality = link.name.ifBlank { "Odelu ${index + 1}" },
                    audio = "Hindi",
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
        fresh(media.id)?.let { return it }
        val lock = buildLocks.computeIfAbsent(media.id) { ReentrantLock() }
        try {
            lock.lockInterruptibly()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Kartoons catalogue wait interrupted")
        }
        try {
            // Whoever held the lock may have just answered this.
            fresh(media.id)?.let { return it }
            val built = build(media)
            if (built == null) {
                misses[media.id] = now()
                error("Kartoons has no entry for this title")
            }
            catalogs[media.id] = built
            return built
        } finally {
            lock.unlock()
        }
    }

    /** The cached catalogue while young enough, or throws for a recent miss, or null. */
    private fun fresh(id: Int): Catalog? {
        catalogs[id]?.let { cached ->
            // Episodes are uploaded as the dub progresses, so a catalogue is rebuilt after a while
            // rather than kept for the life of the process.
            if (now() - cached.builtAt < CATALOG_TTL_MS) return cached
            catalogs.remove(id)
        }
        misses[id]?.let { seenAt ->
            if (now() - seenAt < MISS_TTL_MS) error("Kartoons has no entry for this title")
            misses.remove(id)
        }
        return null
    }

    /**
     * Returns null only for a title this site was actually asked about and did not have.
     *
     * Same contract as AnimeDekho's: a transport failure or an expired budget is rethrown at the
     * end so nothing is learned from a pass that never got an answer, while an answered pass with
     * no matching title is remembered as a miss.
     *
     * Candidates are probed best title first. A complete match on a show's plain copy ends the
     * search; a partial one, or one on a dub-channel copy ("(MUSE) Attack on Titan"), is kept while
     * the remaining candidates get their turn, because another copy may carry the whole entry.
     */
    private fun build(media: Media): Catalog? {
        // Kartoons' films live under a separate API this provider does not read, and an entry not
        // yet released has nothing uploaded; either would only ever match the wrong thing.
        if (media.format.equals("MOVIE", ignoreCase = true) || media.status == "NOT_YET_RELEASED") return null
        val wanted = KartoonsParser.Wanted(
            episodes = media.episodes?.takeIf { it > 0 }
                ?: media.nextAiringEpisode?.episode?.minus(1)?.takeIf { it > 0 },
            year = media.seasonYear ?: media.startDate?.year,
            titles = titles(media),
        )
        var unanswered: Throwable? = null
        // Remembers a failure for the end, except a cancellation, which ends the build now: every
        // request after it would be spent on a caller that has already left.
        fun noteUnanswered(failure: Throwable) {
            if (failure is InterruptedException || Thread.currentThread().isInterrupted) throw failure
            unanswered = failure
        }
        val probed = mutableSetOf<String>()
        var best: Pair<Catalog, Int>? = null
        var probeIndex = 0
        for (query in searchQueries(media)) {
            val rows = runCatching { KartoonsParser.parseSearch(json, get("/shows?search=${enc(query)}&limit=20")) }
                .onFailure(::noteUnanswered)
                .getOrDefault(emptyList())
            val ranked = rows
                .filter { KartoonsParser.isAnime(it) && it._id !in probed }
                .map { it to candidateScore(wanted.titles, it) }
                .filter { (_, score) -> score >= MIN_TITLE_SCORE }
                .sortedByDescending { (_, score) -> score }
                .take(MAX_PROBE_SHOWS)
            for ((candidate, _) in ranked) {
                probed += candidate._id
                probeIndex++
                val show = runCatching { KartoonsParser.parseShow(json, get("/shows/${candidate._id}")) }
                    .onFailure(::noteUnanswered)
                    .getOrNull() ?: continue
                val plan = KartoonsParser.planSeasons(show, wanted)
                val partial = when (plan) {
                    is KartoonsSeasonPlan.Single -> plan.partial
                    is KartoonsSeasonPlan.Run -> plan.partial
                    KartoonsSeasonPlan.None -> continue
                }
                val episodeIds = runCatching { episodeIdsFor(show, plan, wanted.episodes) }
                    .onFailure(::noteUnanswered)
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() } ?: continue
                val catalog = Catalog(episodeIds, now())
                // Higher is better: complete over partial, then the plain copy over a channel's, then
                // more episodes — "Pokemon (CN)"'s 7 and "Pokemon (HUNGAMA)"'s 209 are both partial
                // channel copies, and the first probed used to win.
                val rank = (if (partial) 0 else 2) + (if (KartoonsParser.isChannelVariant(show.title)) 0 else 1)
                DiagnosticsLog.event(
                    category = "provider",
                    name = "kartoons.catalog.planned",
                    attributes = mapOf(
                        "anilistId" to media.id,
                        "plan" to if (plan is KartoonsSeasonPlan.Run) "run" else "single",
                        "seasons" to seasonsOf(plan),
                        "partial" to partial,
                        "channel" to KartoonsParser.isChannelVariant(show.title),
                        "episodes" to episodeIds.size,
                        "probe" to probeIndex,
                    ),
                )
                if (rank == 3) return catalog
                val held = best
                if (held == null || rank > held.second ||
                    (rank == held.second && episodeIds.size > held.first.episodeIds.size)
                ) {
                    best = catalog to rank
                }
            }
        }
        best?.let { return it.first }
        unanswered?.let { throw it }
        return null
    }

    private fun seasonsOf(plan: KartoonsSeasonPlan): String = when (plan) {
        is KartoonsSeasonPlan.Single -> plan.season.seasonNumber.toString()
        is KartoonsSeasonPlan.Run -> plan.seasons.joinToString(",") { it.seasonNumber.toString() }
        KartoonsSeasonPlan.None -> ""
    }

    private fun episodeIdsFor(show: KartoonsParser.Show, plan: KartoonsSeasonPlan, cap: Int?): Map<Int, String> =
        when (plan) {
            is KartoonsSeasonPlan.Single -> KartoonsParser.layoutSingle(
                show,
                plan.season,
                KartoonsParser.parseEpisodes(json, get(seasonPath(show, plan.season))),
            )
            is KartoonsSeasonPlan.Run -> {
                val seasons = plan.seasons.take(MAX_RUN_SEASONS)
                val bodies = getAll(seasons.map { seasonPath(show, it) })
                KartoonsParser.layoutRun(
                    seasons.zip(bodies.map { KartoonsParser.parseEpisodes(json, it) }),
                    cap,
                )
            }
            KartoonsSeasonPlan.None -> emptyMap()
        }

    private fun seasonPath(show: KartoonsParser.Show, season: KartoonsParser.Season): String =
        "/shows/${season.showId ?: show._id}/season/${season._id}/all-episodes"

    /**
     * Resolves links through the token pool, rotating on the API's own refusals.
     *
     * Only a JSON 401/403 is a refused token ([KartoonsParser.isTokenRefusal]); the pool escalates
     * that token's cooldown and the next attempt takes another. A body naming a ban takes the token
     * out for the process. Anything else — a challenge page, a rate limit, a 404 — is not the
     * token's fault and is thrown as it is, without touching the pool.
     */
    private fun resolveLinks(episodeId: String): List<KartoonsParser.Link> {
        var refused = 0
        repeat(MAX_TOKEN_ATTEMPTS) {
            val token = pool.pick()
            val path = "/cloudstream/episode/$episodeId/links?token=${enc(token)}"
            val answer = fetch(path)
            if (KartoonsParser.isTokenRefusal(answer.code, answer.body)) {
                if (KartoonsParser.namesBan(answer.body)) pool.markBanned(token) else pool.markFailed(token)
                refused++
                return@repeat
            }
            if (answer.code !in 200..299) throw KartoonsHttpException(answer.code, path)
            val links = KartoonsParser.orderLinks(
                KartoonsParser.parseLinks(json, answer.body).filter { it.url.isNotBlank() },
            )
            if (links.isNotEmpty()) return links
            // An answered request with no links is the episode, not the credential; say so rather
            // than burning the pool on a retry that cannot change it.
            error("Kartoons episode $episodeId returned no links")
        }
        throw IOException("Kartoons refused $refused account tokens in a row for episode $episodeId")
    }

    private fun candidateScore(titles: List<String>, candidate: KartoonsParser.SearchRow): Double {
        val name = KartoonsParser.searchable(KartoonsParser.cleanTitle(candidate.title))
        // Against the full names and their series names both: "Attack on Titan: The Final Season
        // Part 2" is filed under "Attack on Titan", and only the reduced name says so plainly.
        val score = titles
            .map(KartoonsParser::searchable)
            .flatMap { listOf(it, NativeProviderParsers.baseSeriesTitle(it)) }
            .maxOfOrNull { NativeProviderParsers.titleSelectionScore(it, name) } ?: 0.0
        // Search rows carry no year (every row on 2026-10-03), so the year is checked against the
        // show record in the season plan instead.
        return if (KartoonsParser.isChannelVariant(candidate.title)) score * CHANNEL_VARIANT_WEIGHT else score
    }

    private fun searchQueries(media: Media): List<String> = titles(media)
        .flatMap {
            listOf(NativeProviderParsers.baseSeriesTitle(it), it.substringBefore(':').trim(), it)
        }
        .map(KartoonsParser::searchable)
        .filter { it.length >= 3 }
        .distinctBy { it.lowercase() }
        .take(MAX_SEARCH_QUERIES)

    private fun titles(media: Media): List<String> = listOfNotNull(
        media.title.english,
        media.title.romaji,
        media.title.native,
    ).filter { it.isNotBlank() }.distinct()

    private class Answer(val code: Int, val body: String)

    /**
     * GETs [path] from the working base, falling back to the other one on a connect failure, and
     * returns whatever status it answered with.
     *
     * The domain has moved once already (api.kartoons.me → api.kartoons.to by 2026-10-02, the old
     * name still serving), so a base that answers is remembered for the session. An answer of any
     * status ends the search — only a failure to connect says anything about the base.
     */
    private fun fetch(path: String): Answer {
        val ordered = listOfNotNull(preferredBase) + bases.filter { it != preferredBase }
        var lastConnect: IOException? = null
        for (base in ordered) {
            try {
                client.newCall(request(base + path)).execute().use { response ->
                    val answer = Answer(response.code, response.body?.string().orEmpty())
                    preferredBase = base
                    return answer
                }
            } catch (e: IOException) {
                // A cancelled caller, not a dead base: trying the next one would only delay it. A
                // timeout is an InterruptedIOException too, so the thread's flag is what tells them apart.
                if (Thread.currentThread().isInterrupted) throw e
                lastConnect = e
            }
        }
        throw lastConnect ?: IOException("Kartoons unreachable")
    }

    /** [fetch], requiring a 2xx. */
    private fun get(path: String): String {
        val answer = fetch(path)
        if (answer.code !in 200..299) throw KartoonsHttpException(answer.code, path)
        return answer.body
    }

    /**
     * [get] for several paths at once, in order.
     *
     * A whole-series run needs every season's list before the first episode can be numbered, and
     * one at a time that is a second or more for Naruto's four and several for a twenty-season
     * series. They go out together on the base the search just answered from. An interrupted
     * caller cancels every call still in flight.
     */
    private fun getAll(paths: List<String>): List<String> {
        if (paths.size <= 1) return paths.map(::get)
        val base = preferredBase ?: bases.first()
        val bodies = arrayOfNulls<String>(paths.size)
        val failure = AtomicReference<IOException?>()
        val done = CountDownLatch(paths.size)
        val calls = paths.mapIndexed { index, path ->
            client.newCall(request(base + path)).also { call ->
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        failure.compareAndSet(null, e)
                        done.countDown()
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                if (it.code !in 200..299) throw KartoonsHttpException(it.code, path)
                                bodies[index] = it.body?.string().orEmpty()
                            }
                        } catch (e: IOException) {
                            failure.compareAndSet(null, e)
                        } finally {
                            done.countDown()
                        }
                    }
                })
            }
        }
        try {
            done.await()
        } catch (e: InterruptedException) {
            calls.forEach(Call::cancel)
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Kartoons season lists interrupted")
        }
        failure.get()?.let { throw it }
        return bodies.map { it ?: throw IOException("Kartoons season list missing") }
    }

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")
        .get()
        .build()

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun <K, V> boundedMap(maxEntries: Int): MutableMap<K, V> = Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > maxEntries
        },
    )

    /** A non-2xx answer. The message names the path without its query: see [KartoonsParser.redact]. */
    internal class KartoonsHttpException(val code: Int, path: String) :
        IOException("Kartoons HTTP $code for ${KartoonsParser.redact(path)}")

    private companion object {
        val BASES = listOf("https://api.kartoons.to/api", "https://api.kartoons.me/api")
        /**
         * The floor a cleaned candidate title must reach against the entry's names. Matching is
         * verified by the season plan as well, but a plan only checks lengths and years, and a
         * show of the right length is not rare enough to rest on alone.
         */
        private const val MIN_TITLE_SCORE = 0.5
        /** A dub channel's copy of a show loses ties to the plain one; see [build]. */
        private const val CHANNEL_VARIANT_WEIGHT = 0.95
        private const val MAX_SEARCH_QUERIES = 3
        private const val MAX_PROBE_SHOWS = 3
        private const val MAX_TOKEN_ATTEMPTS = 3
        /** Shin Chan has eighteen; nothing that long is anime here, so this only bounds a bad record. */
        private const val MAX_RUN_SEASONS = 40
        private const val CATALOG_TTL_MS = 3L * 60L * 60L * 1000L
        private const val MISS_TTL_MS = 6L * 60L * 60L * 1000L

        /** The API answered this UA on every probe from a desktop and from the site's own plugin shape. */
        private const val USER_AGENT = "okhttp/4.12"
    }
}
