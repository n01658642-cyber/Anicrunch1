package com.anilili.data

import android.content.Context
import android.os.StrictMode
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anilili.data.remote.KonohaClient
import com.anilili.diagnostics.DiagnosticsLog
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Which titles are known to have a dub, so a card can say so without resolving anything.
 *
 * The answer cannot be derived on the device. AniList carries a single `episodes` count and no
 * notion of language, so dub availability only exists in the provider catalogues — and asking them
 * costs a request per provider per title, which is unthinkable for a grid that paints 168 cards.
 * This index is therefore built ahead of time, by `scripts/dub_index_backfill.py` and the
 * `DubIndexCrawler` test.
 *
 * **Absent means unknown, not "no dub".** A title nobody has asked about, and a title genuinely
 * without a dub, both simply have no badge. That asymmetry is deliberate: a missing badge costs a
 * viewer nothing, while a wrong one sends them to a server that cannot serve what it promised.
 *
 * ## Two sources, and why the bundled one is only a floor
 *
 * The asset is what shipped in this APK. The overlay is the same list published to the metadata
 * CDN, which is refreshed without waiting for a release — the crawl is slow and finds titles in
 * batches, and holding those back until the next build meant a season could air, gain a dub and
 * still be unbadged months later.
 *
 * The two are **unioned, never replaced**. A download can only add, so a CDN that is empty, stale,
 * truncated or 404 leaves the viewer exactly the badges the APK shipped with. That is the whole
 * safety argument for reading an id list off the network at all.
 *
 * ## Why it is read eagerly, and why the network is not
 *
 * [load] is a few kilobytes of integers off disk and runs on the startup path, because being ready
 * before first paint is what keeps badges from popping in after the grid has already drawn. The CDN
 * copy cannot make that promise, so [refresh] writes what it fetches to a file that the *next*
 * launch reads synchronously: the network is paid for once a day, and the badges it earns are
 * present from the first frame ever after.
 *
 * The in-memory set is Compose state so the one launch that does fetch still updates the cards it
 * has already drawn, rather than showing a stale grid until the viewer navigates away.
 */
object DubIndex {

    /**
     * Compose state rather than a `@Volatile` field: see the class note — [refresh] lands on a
     * background thread mid-session and every visible card has to notice. Writes are whole-set
     * replacements, so a reader never observes a half-built index.
     */
    private var dubbed by mutableStateOf<Set<Int>>(emptySet())

    /** Latest dubbed episode per title, from the CDN; see [dubbedThrough]. */
    private var through by mutableStateOf<Map<Int, Int>>(emptyMap())
    private var loaded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val size: Int get() = dubbed.size

    /**
     * Every id a crawl has found a dub for, for the catalog filter that asks AniList for exactly
     * these titles. Reading the whole set is only worth it there — a card asking about one title
     * uses [hasDub].
     */
    val ids: Set<Int> get() = dubbed + through.keys

    /** True only where a crawl positively found a dub. Unknown titles answer false. */
    fun hasDub(anilistId: Int): Boolean = anilistId in dubbed || anilistId in through

    /**
     * The highest episode a dub is known to exist for, or null when unknown. For an airing title
     * this is the answer to "can I watch the next one dubbed yet"; for the rest it is the whole run.
     */
    fun dubbedThrough(anilistId: Int): Int? = through[anilistId]

    /** Bundled positives plus whatever the last [refresh] persisted. Cheap enough for startup. */
    fun load(context: Context) {
        if (loaded) return
        val startedMs = System.currentTimeMillis()
        val asset = runCatching {
            context.assets.open(ASSET).bufferedReader().use { parseDubIndex(it.readText()) }
        }.getOrElse { error ->
            DiagnosticsLog.throwable("DubIndex failed to read $ASSET", error)
            emptySet()
        }
        // Read on the main thread on purpose — see the class note: badges that arrive after the
        // grid has drawn are worse than twelve kilobytes of blocking I/O. StrictMode is right that
        // this is a disk read and wrong that it is a mistake, so it is waived explicitly here
        // rather than across `load`, which would also hide the next one somebody adds by accident.
        //
        // No logging on the failure path: an install that has never completed a refresh has no
        // overlay file, which is the normal first-launch state and not worth a line in every bundle.
        val strictMode = StrictMode.allowThreadDiskReads()
        val overlay = try {
            runCatching {
                parseDubIndex(String(overlayFile(context).readFully(), Charsets.UTF_8))
            }.getOrDefault(emptySet())
        } finally {
            StrictMode.setThreadPolicy(strictMode)
        }
        dubbed = asset + overlay
        val strictModeEpisodes = StrictMode.allowThreadDiskReads()
        through = try {
            runCatching {
                parseDubEpisodes(String(episodesFile(context).readFully(), Charsets.UTF_8))
            }.getOrDefault(emptyMap())
        } finally {
            StrictMode.setThreadPolicy(strictModeEpisodes)
        }
        loaded = true
        DiagnosticsLog.event(
            "DubIndex loaded titles=${dubbed.size} asset=${asset.size} overlay=${overlay.size} " +
                "in ${System.currentTimeMillis() - startedMs}ms",
        )
    }

    /**
     * Tops the index up from the metadata CDN, at most once a day.
     *
     * The age is taken from the overlay file's own timestamp rather than a stored preference, so
     * there is one piece of state to keep honest instead of two that can disagree. A file that
     * exists but no longer parses counts as expired: it is the same repair either way, and leaving
     * a corrupt one in place would silently pin the install to its bundled badges forever.
     *
     * Failure is deliberately quiet. There is nothing for a viewer to do about it and nothing
     * visibly wrong — the badges the APK shipped with are all still there — so it goes to the
     * diagnostics log and is retried on the next launch.
     */
    fun refresh(context: Context, konoha: KonohaClient) {
        val appContext = context.applicationContext
        scope.launch {
            val file = overlayFile(appContext)
            val usable = runCatching {
                parseDubIndex(String(file.readFully(), Charsets.UTF_8))
            }.isSuccess
            refreshEpisodes(appContext, konoha)
            val age = System.currentTimeMillis() - file.baseFile.lastModified()
            if (usable && age in 0 until TTL_MS) return@launch
            try {
                val additions = konoha.dubIndex()
                file.write { stream ->
                    stream.write(additions.sorted().joinToString(",", "[", "]").toByteArray(Charsets.UTF_8))
                }
                dubbed = dubbed + additions
                DiagnosticsLog.event(
                    "DubIndex CDN loaded published=${additions.size} titles=${dubbed.size}",
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                DiagnosticsLog.throwable("DubIndex CDN refresh failed; keeping local index", error)
            }
        }
    }

    /**
     * Both files fetched now, whatever their age — Settings → Re-download metadata. Unlike the
     * daily top-up this throws, because the viewer pressed a button and has to hear that it failed.
     */
    suspend fun refreshNow(context: Context, konoha: KonohaClient) {
        val appContext = context.applicationContext
        val additions = konoha.dubIndex()
        val published = konoha.dubEpisodes()
        overlayFile(appContext).write { stream ->
            stream.write(additions.sorted().joinToString(",", "[", "]").toByteArray(Charsets.UTF_8))
        }
        episodesFile(appContext).write { stream ->
            stream.write(dubEpisodesJson(published).toByteArray(Charsets.UTF_8))
        }
        dubbed = dubbed + additions
        through = published
        DiagnosticsLog.event("DubIndex re-downloaded published=${additions.size} episodes=${published.size}")
    }

    /** The same daily top-up for [dubbedThrough], kept in its own file. */
    private suspend fun refreshEpisodes(context: Context, konoha: KonohaClient) {
        val file = episodesFile(context)
        val usable = runCatching { parseDubEpisodes(String(file.readFully(), Charsets.UTF_8)) }.isSuccess
        val age = System.currentTimeMillis() - file.baseFile.lastModified()
        if (usable && age in 0 until TTL_MS) return
        try {
            val published = konoha.dubEpisodes()
            file.write { stream ->
                stream.write(dubEpisodesJson(published).toByteArray(Charsets.UTF_8))
            }
            through = published
            DiagnosticsLog.event("DubIndex episodes CDN loaded titles=${published.size}")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            DiagnosticsLog.throwable("DubIndex episodes refresh failed; keeping local copy", error)
        }
    }

    private fun episodesFile(context: Context) = AtomicFile(File(context.filesDir, EPISODES_OVERLAY))

    private fun overlayFile(context: Context) = AtomicFile(File(context.filesDir, OVERLAY))

    /** [AtomicFile]'s own contract: a write that throws has to be abandoned, or the backup leaks. */
    private inline fun AtomicFile.write(body: (OutputStream) -> Unit) {
        val stream = startWrite()
        try {
            body(stream)
            finishWrite(stream)
        } catch (error: Throwable) {
            failWrite(stream)
            throw error
        }
    }

    private const val ASSET = "dub-index.json"
    private const val OVERLAY = "dub-index-overlay.json"
    private const val EPISODES_OVERLAY = "dub-episodes-overlay.json"
    /** The published file changes when someone re-runs the crawl, which is monthly at best. */
    private const val TTL_MS = 24L * 60 * 60 * 1000
}

/**
 * A flat array of AniList ids, checked hard enough to be worth writing to disk.
 *
 * The bundled asset and the CDN download go through the same function on purpose. One of them is
 * untrusted — jsDelivr answers a missing path with an HTML error page, and a truncated response is
 * indistinguishable from a short list — so it needs the bounds and the type check. Putting the
 * asset through them too means the format can never drift between the file we ship and the file we
 * publish without a test noticing.
 *
 * This replaced a hand-rolled digit scanner that was written to keep the startup path cheap. The
 * scanner could not reject anything: `<html>404</html>` parses as the empty set, and an empty set
 * is exactly what "this install has no overlay yet" looks like, so a broken download would have
 * been cached for a day as a legitimate answer.
 */
internal fun parseDubIndex(text: String): Set<Int> {
    val ids = Json.decodeFromString<List<Int>>(text)
    require(ids.isNotEmpty() && ids.size <= MAX_DUB_IDS && ids.all { it > 0 }) {
        "Invalid dub index: ${ids.size} ids"
    }
    return ids.toSet()
}

/** Five times the catalogue, so a real index can grow for years before this needs a thought. */
private const val MAX_DUB_IDS = 100_000

/**
 * `{"titles": {"<anilist id>": <latest dubbed episode>}}`, the shape `dub-episodes.json` is published
 * in. Every entry must be a positive id with a positive episode, or the whole file is refused.
 */
internal fun parseDubEpisodes(raw: String): Map<Int, Int> {
    val root = Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
    val titles = root["titles"] as kotlinx.serialization.json.JsonObject
    return titles.entries.associate { (key, value) ->
        val id = requireNotNull(key.toIntOrNull()) { "Bad id $key" }
        val episode = (value as kotlinx.serialization.json.JsonPrimitive).content.toInt()
        require(id > 0 && episode > 0) { "Bad entry $key" }
        id to episode
    }
}

internal fun dubEpisodesJson(map: Map<Int, Int>): String =
    map.entries.sortedBy { it.key }.joinToString(",", "{\"titles\":{", "}}") { "\"${it.key}\":${it.value}" }
