package com.anilili.data.recs

import android.content.Context
import android.util.AtomicFile
import com.anilili.data.AppGraph
import com.anilili.data.library.LibraryStore
import com.anilili.data.model.Media
import com.anilili.data.remote.KonohaClient
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.GZIPInputStream

/** A row of recommended titles, and — for "Because you watched X" — the X. */
data class RecommendationRow(val items: List<Media>, val seed: Media? = null)

/**
 * The device half of recommendations: keeps the published [NeighbourGraph] on disk, refreshes it
 * daily, and turns [TasteEngine]'s ids into cards.
 *
 * The graph is kept as the downloaded file, not in [com.anilili.data.cache.AppCache]: it is 1.5 MB
 * of gzip that has to survive offline launches whole, and parsing it is the only cost worth
 * avoiding twice, so the parsed copy is held in memory for the life of the process.
 */
object Recommendations {
    private const val PATH = "recs/neighbours-v1.tsv.gz"

    /** The build writes it daily; a day-old graph recommends the same things. */
    private const val REFRESH_AFTER_MS = 24L * 60 * 60 * 1000

    /** A bound on a body, not a budget: the file is ~1.5 MB. Stops a mis-served page being parsed. */
    private const val MAX_BYTES = 24L * 1024 * 1024

    private lateinit var appContext: Context
    private lateinit var client: OkHttpClient
    private lateinit var konoha: KonohaClient
    private val lock = Mutex()

    @Volatile
    private var graph: NeighbourGraph? = null

    @Volatile
    private var checkedAt = 0L

    fun attach(context: Context, httpClient: OkHttpClient, konohaClient: KonohaClient) {
        appContext = context.applicationContext
        client = httpClient
        konoha = konohaClient
    }

    private fun file() = AtomicFile(File(appContext.filesDir, PATH))

    /**
     * The graph, from memory, then disk, then the CDN. Null only when none of the three has one —
     * a fresh install offline, or before the build has ever published.
     */
    suspend fun graph(): NeighbourGraph? = lock.withLock {
        if (!::appContext.isInitialized) return@withLock null
        withContext(Dispatchers.IO) {
            val stored = file()
            if (graph == null && stored.baseFile.exists()) {
                graph = runCatching { parse(stored.readFully()) }
                    .onFailure { DiagnosticsLog.throwable("Neighbour graph on disk unreadable", it) }
                    .getOrNull()
            }
            val now = System.currentTimeMillis()
            val age = now - stored.baseFile.lastModified()
            if ((graph == null || age > REFRESH_AFTER_MS) && now - checkedAt > CHECK_INTERVAL_MS) {
                checkedAt = now
                runCatching { download(stored) }
                    .onFailure { DiagnosticsLog.throwable("Neighbour graph download failed", it) }
            }
            graph
        }
    }

    private fun download(stored: AtomicFile) {
        val request = Request.Builder()
            .url(konoha.dataUrl(PATH))
            // jsDelivr sends max-age=604800 and the shared HTTP cache honours it, which would pin
            // a week-old graph. Revalidating is a 304 when nothing changed.
            .cacheControl(CacheControl.Builder().noCache().build())
            .build()
        val bytes = client.newCall(request).execute().use { response ->
            if (response.code == 404) return
            check(response.isSuccessful) { "Neighbour graph HTTP ${response.code}" }
            val source = checkNotNull(response.body).source()
            require(!source.request(MAX_BYTES + 1)) { "Neighbour graph too large" }
            source.readByteArray()
        }
        // Parsed before it replaces anything: a body that will not parse must not overwrite a
        // graph that does.
        val parsed = parse(bytes)
        val out = stored.startWrite()
        try {
            out.write(bytes)
            stored.finishWrite(out)
        } catch (error: Throwable) {
            stored.failWrite(out)
            throw error
        }
        graph = parsed
        DiagnosticsLog.event("Neighbour graph updated generated=${parsed.generated} titles=${parsed.size} edges=${parsed.edgeCount}")
    }

    private fun parse(gzipped: ByteArray): NeighbourGraph =
        GZIPInputStream(gzipped.inputStream()).use { NeighbourGraph.parse(it) }

    /** Everything the viewer has on a list, played here, or saved: never worth recommending. */
    private fun known(): Set<Int> = buildSet {
        addAll(LibraryStore.remoteStatuses.value.keys)
        LibraryStore.history.value.forEach { add(it.anilistId) }
        LibraryStore.watchlist.value.forEach { add(it.anilistId) }
        TasteStore.ratings.value.forEach { add(it.anilistId) }
    }

    /** Titles actually played on this device — account-seeded rows are already in the list. */
    private fun played(): List<PlayedTitle> = LibraryStore.history.value
        .filterNot { it.fromRemote }
        .map { PlayedTitle(it.anilistId, it.watchedEpisodes.size, it.updatedAt) }

    /** "For you": the whole list's taste, blended. Null when there is nothing to go on yet. */
    suspend fun forYou(limit: Int = 30): RecommendationRow? {
        val graph = graph() ?: return null
        val signals = TasteEngine.signals(TasteStore.ratings.value, played(), TasteStore.feedback.value)
        val picks = withContext(Dispatchers.Default) {
            TasteEngine.recommend(graph, signals, known(), hideAdult = SettingsStore.hideAdultContent.value, limit = limit)
        }
        if (picks.isEmpty()) return null
        return RecommendationRow(cards(picks.map { it.anilistId }))
            .takeIf { it.items.isNotEmpty() }
    }

    /** "Because you watched X", for the latest title the viewer clearly liked. */
    suspend fun becauseYouWatched(limit: Int = 24): RecommendationRow? {
        val graph = graph() ?: return null
        val seed = TasteEngine.featuredSeed(graph, TasteStore.ratings.value, played()) ?: return null
        val picks = withContext(Dispatchers.Default) {
            TasteEngine.because(graph, seed, known(), hideAdult = SettingsStore.hideAdultContent.value, limit = limit)
        }
        if (picks.isEmpty()) return null
        val cards = cards(listOf(seed) + picks.map { it.anilistId })
        val seedMedia = cards.firstOrNull { it.id == seed } ?: return null
        val items = cards.filter { it.id != seed }
        return RecommendationRow(items, seedMedia).takeIf { items.isNotEmpty() }
    }

    /**
     * A small random handful of the viewer's recommendations, for the dice to mix in. Taken from
     * well down the list rather than the top, so a roll still surprises.
     */
    suspend fun diceCandidates(count: Int = 12): List<Media> {
        val graph = graph() ?: return emptyList()
        val signals = TasteEngine.signals(TasteStore.ratings.value, played(), TasteStore.feedback.value)
        val picks = withContext(Dispatchers.Default) {
            TasteEngine.recommend(graph, signals, known(), hideAdult = SettingsStore.hideAdultContent.value, limit = DICE_POOL)
        }
        if (picks.isEmpty()) return emptyList()
        return cards(picks.shuffled().take(count).map { it.anilistId })
    }

    /** AniList cards for [ids], in [ids]' order; one request per 50, cached a day. */
    private suspend fun cards(ids: List<Int>): List<Media> {
        val byId = AppGraph.repository.mediaByIds(ids).associateBy { it.id }
        val hideAdult = SettingsStore.hideAdultContent.value
        return ids.mapNotNull { byId[it] }.filterNot { hideAdult && it.isAdult }
    }

    /** How often a failed or unchanged check may be retried within one process. */
    private const val CHECK_INTERVAL_MS = 15L * 60 * 1000
    private const val DICE_POOL = 60
}
