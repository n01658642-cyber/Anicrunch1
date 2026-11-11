package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Builds the dub index the cards read, by asking the providers the app already talks to.
 *
 * Inert unless ANILILI_CRAWL is set:
 *
 *     ANILILI_CRAWL=1 ANILILI_CRAWL_PER_SECTION=500 gradle :app:testMobileReleaseUnitTest \
 *       --tests '*DubIndexCrawler*'
 *
 * Three things keep this from looking like a crawl, in order of how much they matter:
 *
 *  - **It stops as soon as it knows.** `hasDub` is an OR across providers, so the walk ends on the
 *    first yes. Only titles that genuinely have no dub cost the full round, and they are the
 *    minority.
 *  - **It pauses between titles**, by a randomised amount rather than a fixed tick, so the request
 *    pattern has no machine rhythm to match on.
 *  - **It gives up early.** A run of consecutive total failures means something is rejecting us,
 *    and pressing on from there is how an address gets remembered. It stops and says so.
 *
 * It is also resumable: results already on disk are skipped, so an aborted run costs nothing and
 * the index can be filled in over several sittings rather than one long burst.
 *
 * ## "Nobody answered" is not "no dub"
 *
 * Every provider here *throws* when its search finds nothing good enough, so a title none of them
 * can name produces no answer at all rather than a negative one. Those titles stay out of the
 * results file on purpose — a `false` written there would be a fact nobody observed, and the next
 * run would skip it forever. They are instead recorded in `dub-index-outcomes.json` as
 * `all_probes_failed`, with each provider's message, so the backlog is visible and retried.
 *
 * This mattered more than it sounds: run against the catalogue's single English title, the failure
 * fell almost entirely on **sequels** — the middle seasons of the biggest franchises on the service
 * had no badge for months because nothing had ever successfully asked about them. Hence the AniList
 * enrichment below, which gives each probe the romaji and native names too.
 *
 * `scripts/dub_index_backfill.py` answers the same question a different way, by AniList id against
 * Miruro's own providers, and reads this file's results. The two are complementary: this one covers
 * seven providers that endpoint knows nothing about, and roughly one dub in seven is only found
 * here.
 */
class DubIndexCrawler {

    private val requests = AtomicInteger()
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .eventListener(
            object : EventListener() {
                override fun callStart(call: Call) {
                    requests.incrementAndGet()
                }
            },
        )
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private data class Probe(val name: String, val availability: (Media) -> EpisodeAvailability)

    /** Fastest and most complete first, so the common "yes it has a dub" ends the walk soonest. */
    private val probes: List<Probe> by lazy {
        val allanime = AllAnimeProvider(client, json)
        val kaa = KickAssAnimeProvider(client, json)
        val animekai = AnimeKaiProvider(client)
        val anidbapp = AniDbAppProvider(client, json)
        val rareanimes = RareAnimesProvider(client)
        val animeDekho = AnimeDekhoProvider(client)
        val toonHub = ToonHubProvider(client)
        listOf(
            Probe("allanime") { allanime.episodeAvailability(it) },
            Probe("kaa") { kaa.episodeAvailability(it) },
            Probe("animekai") { animekai.episodeAvailability(it) },
            Probe("anidbapp") { anidbapp.episodeAvailability(it, it.episodes ?: 0) },
            Probe("rareanimes") { rareanimes.episodeAvailability(it) },
            Probe("animedekho") { animeDekho.episodeAvailability(it) },
            Probe("toonhub") { toonHub.episodeAvailability(it) },
        )
    }

    @Test
    fun buildDubIndex() {
        assumeTrue("set ANILILI_CRAWL=1 to run the crawler", System.getenv("ANILILI_CRAWL") != null)
        val perSection = System.getenv("ANILILI_CRAWL_PER_SECTION")?.toIntOrNull() ?: 500
        val minPauseMs = System.getenv("ANILILI_CRAWL_MIN_PAUSE_MS")?.toLongOrNull() ?: 1_200L
        val maxPauseMs = System.getenv("ANILILI_CRAWL_MAX_PAUSE_MS")?.toLongOrNull() ?: 3_500L
        val timeoutMs = System.getenv("ANILILI_CRAWL_TIMEOUT_MS")?.toLongOrNull() ?: 15_000L
        val abortAfter = System.getenv("ANILILI_CRAWL_ABORT_AFTER")?.toIntOrNull() ?: 12

        val out = File("build/dub-index").apply { mkdirs() }
        val resultsFile = File(out, "dub-index-raw.json")
        val known = loadExisting(resultsFile)
        val targets = homeSectionTitles(perSection).filterNot { it.id in known }

        println(
            "dub crawler: ${targets.size} to do, ${known.size} already known, " +
                "pause ${minPauseMs}-${maxPauseMs}ms, abort after $abortAfter consecutive blanks",
        )

        // Why the outcomes live beside the results rather than in them: the results file is a flat
        // id -> Boolean that the Python backfill also reads, and widening it would break that. This
        // is the diagnostic half — what each provider actually said — and nothing consumes it
        // automatically, which is the point. It is where you look when a title has no badge and you
        // want to know whether anyone was ever asked.
        val outcomesFile = File(out, "dub-index-outcomes.json")
        val outcomes = if (outcomesFile.isFile) {
            json.parseToJsonElement(outcomesFile.readText()).jsonObject.toMutableMap()
        } else {
            mutableMapOf<String, JsonElement>()
        }
        fun writeOutcomes() = outcomesFile.writeText(JsonObject(outcomes).toString())

        // The catalogue snapshot carries one English title and nothing else, so every provider got
        // a single query string where the app gives it three or four. That is not a detail: every
        // provider *throws* when nothing scores well enough, so a title whose English name is not
        // what the provider filed it under was recorded as nothing at all — which is how the middle
        // seasons of the biggest franchises on the service ended up unbadged. Fifty ids per request
        // against a 30-a-minute budget, spaced by the crawl's own pacing.
        val aniList = AniListClient(client, json)
        val enriched = mutableMapOf<Int, Media>()
        val results = LinkedHashMap(known)
        var consecutiveBlanks = 0
        var aborted = false
        var dubYes = 0
        var dubNo = 0
        val startedAt = System.currentTimeMillis()

        runBlocking {
            for ((index, seed) in targets.withIndex()) {
                if (index % 50 == 0) {
                    if (index > 0) delay(2_100)
                    runCatching { aniList.mediaByIds(targets.drop(index).take(50).map { it.id }) }
                        .onSuccess { rows -> rows.forEach { enriched[it.id] = it } }
                        .onFailure { println("AniList enrichment failed: ${it.message}") }
                }
                val media = enriched[seed.id] ?: seed
                // Plain map: the probes run one at a time and each one's write happens inside a
                // `withContext` this loop suspends on, so there is nothing concurrent to guard.
                val errors = linkedMapOf<String, String>()
                var hasDub = false
                var hasSub = false
                val answered = mutableListOf<String>()

                for (probe in probes) {
                    val result = withTimeoutOrNull(timeoutMs) {
                        withContext(Dispatchers.IO) {
                            runCatching { probe.availability(media) }
                                .onFailure { errors[probe.name] = it.message.orEmpty().take(300) }
                                .getOrNull()
                        }
                    }
                    if (result == null && !errors.containsKey(probe.name)) errors[probe.name] = "Timed out"
                    if (result != null) {
                        answered += probe.name
                        if (result.sub.isNotEmpty()) hasSub = true
                        if (result.dub.isNotEmpty()) hasDub = true
                    }
                    // Knowing a dub exists is the whole answer; nothing after this can change it.
                    if (hasDub) break
                }

                outcomes[media.id.toString()] = buildJsonObject {
                    put("outcome", dubCrawlOutcome(hasDub, answered.size))
                    put("titleEnriched", seed.id in enriched)
                    put("checkedAt", System.currentTimeMillis())
                    put("errors", JsonObject(errors.mapValues { JsonPrimitive(it.value) }))
                }
                if (answered.isEmpty()) {
                    consecutiveBlanks++
                    if (consecutiveBlanks >= abortAfter) {
                        println(
                            "ABORT: $consecutiveBlanks titles in a row answered by nobody. " +
                                "Something is rejecting us; stopping rather than pushing through.",
                        )
                        // Flushed here as well as at the checkpoint below, because the title that
                        // tripped the abort is the one you came to this file to read.
                        writeOutcomes()
                        aborted = true
                        break
                    }
                } else {
                    consecutiveBlanks = 0
                    results[media.id] = hasDub
                    if (hasDub) dubYes++ else dubNo++
                }

                if (index % 25 == 0 || index == targets.lastIndex) {
                    writeIndex(out, results)
                    writeOutcomes()
                    val elapsed = (System.currentTimeMillis() - startedAt) / 1000
                    println(
                        "[%4d/%4d] dub=%-5s src=%-10s  yes=%d no=%d  req=%d  %ds elapsed".format(
                            index + 1, targets.size, hasDub, answered.firstOrNull() ?: "none",
                            dubYes, dubNo, requests.get(), elapsed,
                        ),
                    )
                }

                // Randomised so the gaps carry no machine rhythm. Skipped after the final title.
                if (index != targets.lastIndex) delay(Random.nextLong(minPauseMs, maxPauseMs))
            }
        }

        writeIndex(out, results)
        writeOutcomes()
        val elapsed = (System.currentTimeMillis() - startedAt) / 1000
        val thisRun = outcomes.values.count {
            (it.jsonObject["checkedAt"]?.jsonPrimitive?.long ?: 0L) >= startedAt
        }
        val allFailed = outcomes.values.count {
            it.jsonObject["outcome"]?.jsonPrimitive?.content == "all_probes_failed"
        }
        val summary = buildString {
            appendLine("=== dub index ===")
            appendLine("aborted early   : $aborted")
            appendLine("titles resolved : ${results.size}")
            appendLine("  with dub      : ${results.values.count { it }}")
            appendLine("  without dub   : ${results.values.count { !it }}")
            // Not a failure of the crawl so much as its backlog: these are the titles no provider
            // would name, and they are deliberately left out of the results file so the next run
            // asks again rather than inheriting a negative nobody ever observed.
            appendLine("still unasked   : $allFailed (every probe failed; retried next run)")
            appendLine("outcomes on file: ${outcomes.size}, $thisRun touched this run")
            appendLine("this run        : $dubYes yes, $dubNo no, ${requests.get()} requests, ${elapsed}s")
            if (dubYes + dubNo > 0) {
                appendLine("requests/title  : ${requests.get() / (dubYes + dubNo)}")
            }
        }
        println(summary)
        File(out, "summary.txt").writeText(summary)
    }

    /** Absent means unknown, so only the ids that positively have a dub are written. */
    private fun writeIndex(out: File, results: Map<Int, Boolean>) {
        val dubbed = results.filterValues { it }.keys.sorted()
        File(out, "dub-index.json").writeText(dubbed.joinToString(",", "[", "]"))
        File(out, "dub-index-raw.json").writeText(
            results.entries.sortedBy { it.key }.joinToString(",\n", "{\n", "\n}\n") {
                """  "${it.key}": ${it.value}"""
            },
        )
    }

    private fun loadExisting(file: File): Map<Int, Boolean> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.parseToJsonElement(file.readText()).jsonObject
                .mapKeys { it.key.toInt() }
                .mapValues { it.value.jsonPrimitive.content.toBoolean() }
        }.getOrDefault(emptyMap())
    }

    /**
     * The four rails the home screen actually shows, taken from the catalogue snapshot the app
     * ships. Deduped across sections, because popular and top-rated overlap heavily and there is
     * no reason to ask about the same title twice.
     */
    private fun homeSectionTitles(perSection: Int): List<Media> {
        val assets = File("src/main/assets").takeIf { it.isDirectory } ?: File("app/src/main/assets")
        val index = json.parseToJsonElement(File(assets, "index.json").readText()) as JsonArray
        val idMap = json.parseToJsonElement(File(assets, "id-map.json").readText()).jsonObject
        val entries = index.map { it.jsonObject }

        fun intOf(o: JsonObject, key: String) = o[key]?.jsonPrimitive?.intOrNull ?: 0
        fun strOf(o: JsonObject, key: String) = o[key]?.jsonPrimitive?.content

        val tv = entries.filter { strOf(it, "format") != "MOVIE" }
        val popular = tv.sortedByDescending { intOf(it, "popularity") }.take(perSection)
        val topRated = tv.sortedByDescending { intOf(it, "score") }.take(perSection)
        val newest = tv
            .sortedWith(compareByDescending<JsonObject> { intOf(it, "year") }.thenByDescending { intOf(it, "popularity") })
            .take(perSection)
        val movies = entries.filter { strOf(it, "format") == "MOVIE" }
            .sortedByDescending { intOf(it, "popularity") }
            .take(perSection)

        return (popular + topRated + newest + movies)
            .distinctBy { intOf(it, "id") }
            .map { entry ->
                val id = entry["id"]!!.jsonPrimitive.int
                val title = strOf(entry, "title").orEmpty()
                Media(
                    id = id,
                    idMal = (idMap[id.toString()] as? JsonObject)?.get("mal")?.jsonPrimitive?.intOrNull,
                    title = MediaTitle(romaji = title, english = title, userPreferred = title),
                    episodes = entry["episodes"]?.jsonPrimitive?.intOrNull,
                    format = strOf(entry, "format"),
                    seasonYear = entry["year"]?.jsonPrimitive?.intOrNull,
                )
            }
    }
}


/** Failure remains retryable: it must never become a negative availability record. */
internal fun dubCrawlOutcome(hasDub: Boolean, answers: Int): String = when {
    hasDub -> "dub"
    answers == 0 -> "all_probes_failed"
    else -> "no_dub_observed"
}
