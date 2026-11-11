package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Measures what a self-hosted sub/dub crawl would actually cost, so the estimates behind that
 * decision can be replaced with numbers from this machine and this connection.
 *
 * Deliberately inert: it does nothing unless ANILILI_CRAWL is set, because it is the one test in
 * the suite that reaches the open internet and it must never join a normal `gradle test` run.
 *
 *     ANILILI_CRAWL=1 ANILILI_CRAWL_TITLES=50 gradle :app:testMobileReleaseUnitTest \
 *       --tests '*SubDubCrawlProbe*'
 *
 * Titles are taken from the catalogue the app already ships, most popular first, which is the same
 * population a real crawl would prioritise. Providers run in parallel per title while titles run
 * one at a time, so no single host sees more than roughly one request per title — polite by
 * construction rather than by a sleep.
 *
 * Covers the providers that expose [EpisodeAvailability] and need nothing from Android. The
 * six that live inside AnivexaClient need a Context, so the per-title cost measured here is a
 * floor, not the whole bill.
 */
class SubDubCrawlProbe {

    private val requests = AtomicInteger()
    private val responseBytes = AtomicLong()

    // Counted through the event listener rather than an interceptor: peeking a body to measure it
    // would buffer every page in memory just to learn its size.
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .eventListener(
            object : EventListener() {
                override fun callStart(call: Call) {
                    requests.incrementAndGet()
                }

                override fun responseBodyEnd(call: Call, byteCount: Long) {
                    responseBytes.addAndGet(byteCount)
                }
            },
        )
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private data class Probe(val name: String, val availability: (Media) -> EpisodeAvailability)

    private val probes: List<Probe> by lazy {
        val anidbapp = AniDbAppProvider(client, json)
        val kaa = KickAssAnimeProvider(client, json)
        val allanime = AllAnimeProvider(client, json)
        val animekai = AnimeKaiProvider(client)
        val rareanimes = RareAnimesProvider(client)
        val animeDekho = AnimeDekhoProvider(client)
        val toonHub = ToonHubProvider(client)
        // Ordered by measured latency, fastest first, because the crawl stops as soon as both bits
        // are known: the cheapest provider that answers both is the one worth asking first.
        // allanime reports sub and dub together and returned in ~255ms, so it usually ends the
        // walk on its own.
        listOf(
            Probe("allanime") { allanime.episodeAvailability(it) },
            Probe("rareanimes") { rareanimes.episodeAvailability(it) },
            Probe("animedekho") { animeDekho.episodeAvailability(it) },
            Probe("toonhub") { toonHub.episodeAvailability(it) },
            Probe("kaa") { kaa.episodeAvailability(it) },
            Probe("animekai") { animekai.episodeAvailability(it) },
            Probe("anidbapp") { anidbapp.episodeAvailability(it, it.episodes ?: 0) },
        )
    }

    @Test
    fun measureCrawlCostOverPopularTitles() {
        assumeTrue("set ANILILI_CRAWL=1 to run the live crawl probe", System.getenv("ANILILI_CRAWL") != null)
        val wanted = System.getenv("ANILILI_CRAWL_TITLES")?.toIntOrNull() ?: 50
        val perProviderTimeoutMs = System.getenv("ANILILI_CRAWL_TIMEOUT_MS")?.toLongOrNull() ?: 20_000L

        val skip = System.getenv("ANILILI_CRAWL_SKIP")?.toIntOrNull() ?: 0
        val titles = popularTitles(wanted, skip)
        println(
            "crawl probe: ${titles.size} titles from rank ${skip + 1}, " +
                "${probes.size} providers, timeout ${perProviderTimeoutMs}ms",
        )

        val providerOk = probes.associate { it.name to AtomicInteger() }
        val providerFail = probes.associate { it.name to AtomicInteger() }
        val providerMs = probes.associate { it.name to AtomicLong() }
        val providerConsulted = probes.associate { it.name to AtomicInteger() }
        val rows = mutableListOf<String>()
        var withSub = 0
        var withDub = 0
        var withNothing = 0

        var carriedByCount = 0
        val totalMs = measureTimeMillis {
            titles.forEachIndexed { index, media ->
                val found = LinkedHashMap<String, EpisodeAvailability>()
                val perTitleMs = measureTimeMillis {
                    runBlocking {
                        // Sequential with early exit rather than a parallel fan-out. Both bits are
                        // an OR, so once each is true nothing another provider says can change the
                        // answer — and every provider skipped here is a request never made, which
                        // is what keeps a full crawl from looking like one.
                        for (probe in probes) {
                            val knowSub = found.values.any { it.sub.isNotEmpty() }
                            val knowDub = found.values.any { it.dub.isNotEmpty() }
                            if (knowSub && knowDub) break
                            providerConsulted.getValue(probe.name).incrementAndGet()
                            val started = System.nanoTime()
                            val result = withTimeoutOrNull(perProviderTimeoutMs) {
                                withContext(Dispatchers.IO) {
                                    runCatching { probe.availability(media) }.getOrNull()
                                }
                            }
                            val elapsed = (System.nanoTime() - started) / 1_000_000
                            providerMs.getValue(probe.name).addAndGet(elapsed)
                            if (result == null) {
                                providerFail.getValue(probe.name).incrementAndGet()
                            } else {
                                providerOk.getValue(probe.name).incrementAndGet()
                                if (result.sub.isNotEmpty() || result.dub.isNotEmpty()) {
                                    found[probe.name] = result
                                }
                            }
                        }
                    }
                }

                // Everything below is derived from the sets the same call already returned, so it
                // costs nothing beyond the request that was made anyway. The union across providers
                // is the honest answer to "can this be watched": one provider missing episode 7
                // does not mean episode 7 is unavailable.
                val subEpisodes = found.values.flatMapTo(sortedSetOf()) { it.sub }
                val dubEpisodes = found.values.flatMapTo(sortedSetOf()) { it.dub }
                val hasSub = subEpisodes.isNotEmpty()
                val hasDub = dubEpisodes.isNotEmpty()
                val latestSub = subEpisodes.maxOrNull() ?: 0
                val latestDub = dubEpisodes.maxOrNull() ?: 0
                val dubLag = if (hasSub && hasDub) latestSub - latestDub else null
                val gaps = if (hasSub) (1..latestSub).filterNot { it in subEpisodes } else emptyList()

                if (hasSub) withSub++
                if (hasDub) withDub++
                if (!hasSub && !hasDub) withNothing++
                carriedByCount += found.size

                val perProvider = found.entries.joinToString(",") { (name, a) ->
                    """"$name":{"sub":${a.sub.size},"dub":${a.dub.size}}"""
                }
                rows += """  {"id":${media.id},"sub":$hasSub,"dub":$hasDub,""" +
                    """"subCount":${subEpisodes.size},"dubCount":${dubEpisodes.size},""" +
                    """"latestSub":$latestSub,"latestDub":$latestDub,"dubLag":${dubLag ?: "null"},""" +
                    """"gaps":${gaps.take(20)},"providers":{$perProvider},"ms":$perTitleMs}"""

                println(
                    "[%3d/%3d] %-40s sub=%-5s(%4d) dub=%-5s(%4d) lag=%-5s src=%d/%d %5dms".format(
                        index + 1, titles.size, media.title.romaji?.take(38),
                        hasSub, subEpisodes.size, hasDub, dubEpisodes.size,
                        dubLag?.toString() ?: "-", found.size, probes.size, perTitleMs,
                    ),
                )
            }
        }

        val out = File("build/crawl-probe").apply { mkdirs() }
        File(out, "sub-dub-index.json").writeText(rows.joinToString(",\n", "[\n", "\n]\n"))

        val summary = buildString {
            appendLine("=== crawl probe summary ===")
            appendLine("titles              : ${titles.size} from popularity rank ${skip + 1}")
            appendLine("wall clock          : ${totalMs / 1000}s  (${totalMs / titles.size}ms per title)")
            appendLine("http requests       : ${requests.get()}  (${requests.get() / titles.size} per title)")
            appendLine("response bytes      : ${responseBytes.get() / 1_000_000}MB  (${responseBytes.get() / titles.size / 1024}KB per title)")
            appendLine("titles with sub     : $withSub / ${titles.size}")
            appendLine("titles with dub     : $withDub / ${titles.size}")
            appendLine("titles with neither : $withNothing / ${titles.size}")
            // The payoff of shipping a per-title provider list: this is how many of the providers
            // the app currently fans out to on every title actually had anything.
            appendLine(
                "providers per title : %.1f of %d carried the title".format(
                    carriedByCount.toDouble() / titles.size, probes.size,
                ),
            )
            appendLine()
            appendLine("%-12s %10s %6s %6s %9s".format("provider", "consulted", "ok", "fail", "avgMs"))
            probes.forEach {
                val consulted = providerConsulted.getValue(it.name).get()
                val ok = providerOk.getValue(it.name).get()
                val fail = providerFail.getValue(it.name).get()
                val avg = if (consulted > 0) providerMs.getValue(it.name).get() / consulted else 0
                appendLine("%-12s %10d %6d %6d %9d".format(it.name, consulted, ok, fail, avg))
            }
            val consultedTotal = providerConsulted.values.sumOf { it.get() }
            appendLine()
            appendLine(
                "provider calls      : $consultedTotal of ${titles.size * probes.size} possible " +
                    "(%.1f per title, early exit saved %d)".format(
                        consultedTotal.toDouble() / titles.size,
                        titles.size * probes.size - consultedTotal,
                    ),
            )
            appendLine("NOTE: counts are lower bounds under early exit — the walk stops before")
            appendLine("      asking every provider, so subCount/latestSub see only what it asked.")
            appendLine()
            appendLine("extrapolated to 2000 titles:")
            appendLine("  wall clock   ~${totalMs.toDouble() / titles.size * 2000 / 60_000} min")
            appendLine("  requests     ~${requests.get().toDouble() / titles.size * 2000}")
            appendLine("  transfer     ~${responseBytes.get().toDouble() / titles.size * 2000 / 1_000_000_000} GB")
        }
        println(summary)
        File(out, "summary.txt").writeText(summary)
    }

    /**
     * A slice of the catalogue snapshot the app already ships, ordered by popularity.
     *
     * [skip] exists because measuring only the top of the list flatters the crawl: every provider
     * carries the twenty biggest shows, so the walk stops at the first one and says nothing about
     * how deep it goes where the catalogue thins out.
     */
    private fun popularTitles(count: Int, skip: Int = 0): List<Media> {
        val assets = File("src/main/assets").takeIf { it.isDirectory }
            ?: File("app/src/main/assets")
        val index = json.parseToJsonElement(File(assets, "index.json").readText()) as JsonArray
        val idMap = json.parseToJsonElement(File(assets, "id-map.json").readText()).jsonObject
        return index.map { it.jsonObject }
            .filter { it["format"]?.jsonPrimitive?.content == "TV" }
            .sortedByDescending { it["popularity"]?.jsonPrimitive?.intOrNull ?: 0 }
            .drop(skip)
            .take(count)
            .map { entry ->
                val id = entry["id"]!!.jsonPrimitive.int
                val title = entry["title"]?.jsonPrimitive?.content.orEmpty()
                Media(
                    id = id,
                    idMal = (idMap[id.toString()] as? JsonObject)?.get("mal")?.jsonPrimitive?.intOrNull,
                    title = MediaTitle(romaji = title, english = title, userPreferred = title),
                    episodes = entry["episodes"]?.jsonPrimitive?.intOrNull,
                    format = entry["format"]?.jsonPrimitive?.content,
                    seasonYear = entry["year"]?.jsonPrimitive?.intOrNull,
                )
            }
    }
}
