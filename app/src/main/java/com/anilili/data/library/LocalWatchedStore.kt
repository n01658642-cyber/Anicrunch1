package com.anilili.data.library

import android.content.SharedPreferences
import com.anilili.data.AppGraph
import com.anilili.data.auth.AccountService
import com.anilili.data.model.MediaListEntry
import com.anilili.data.remote.isCatalogueNativeMediaId
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One title the viewer marked watched on this device, with no account to hold the mark. */
@Serializable
data class WatchedEntry(
    val anilistId: Int,
    val title: String,
    val cover: String?,
    val format: String? = null,
    val averageScore: Int? = null,
    /** The title's length, so the account's episode count can be filled in when it is delivered. */
    val episodes: Int? = null,
    val watchedAt: Long = 0,
)

/**
 * What a title page calls a title marked watched here: the label an account's Completed would have
 * had, or null for one that is not marked. Only ever reached when the account has no status for it.
 */
fun localWatchedLabel(entries: List<WatchedEntry>, anilistId: Int): String? =
    "Watched".takeIf { entries.any { it.anilistId == anilistId } }

/**
 * Whether finishing [episode] finishes the series: the rule an account applies when progress
 * reaches the episode count (see `planMediaListProgressUpdate`). A title whose length is not known
 * is never finished by an episode, the way the account never completes one either.
 */
internal fun finishesSeries(episode: Int, totalEpisodes: Int?): Boolean =
    totalEpisodes?.takeIf { it > 0 }?.let { episode >= it } == true

/** What one account refresh does with the device's marks: send these, forget those. */
internal data class WatchedDeliveryPlan(val push: List<WatchedEntry>, val settled: Set<Int>)

/**
 * Decide which marks the account still needs. [remote] is the account's status and last-change
 * time (epoch millis) per title, for entries actually on a status list.
 *
 * - The account already calls it Completed: nothing to send, and the device copy can go.
 * - The account changed the title *after* it was marked here: the newer word wins, so the mark is
 *   dropped rather than overwriting, say, a rewatch begun on another device.
 * - Otherwise it is sent as Completed.
 *
 * A catalogue-only title has no id either service knows, so it is neither sent nor dropped: it
 * stays a device mark for good.
 */
internal fun plannedWatchedDelivery(
    local: List<WatchedEntry>,
    remote: Map<Int, Pair<String, Long>>,
): WatchedDeliveryPlan {
    val push = mutableListOf<WatchedEntry>()
    val settled = mutableSetOf<Int>()
    local.forEach { entry ->
        if (isCatalogueNativeMediaId(entry.anilistId)) return@forEach
        val (status, updatedAt) = remote[entry.anilistId] ?: run {
            push += entry
            return@forEach
        }
        if (status == "COMPLETED" || updatedAt >= entry.watchedAt) settled += entry.anilistId else push += entry
    }
    return WatchedDeliveryPlan(push, settled)
}

/**
 * "I have seen this" for a viewer with no AniList or MyAnimeList account — the one list status
 * that matters most to someone who only wants the catalogue to stop offering what they finished.
 *
 * The marks are a device copy of Completed, and nothing more: Browse's watched filter reads them,
 * My List shows them as Completed, and the list menu ticks them. Signing in hands them to the
 * account as Completed (see [plannedWatchedDelivery]), and each one leaves the device as the
 * account confirms it — from then on the account's own status is the one every screen reads, and a
 * device copy beside it could only disagree with it.
 *
 * Shares `miruro_library` with [LibraryStore] so a TV profile switch swaps these with the rest of
 * the library.
 */
object LocalWatchedStore {
    private const val KEY_WATCHED = "local_watched"

    /**
     * Marks sent per account refresh. Each push ends in a refresh, so a long list is worked off a
     * batch at a time rather than as one burst against AniList's per-minute quota.
     */
    private const val MAX_PUSHES_PER_RUN = 20

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pushMutex = Mutex()

    /**
     * Marks the account refused this session — a title MyAnimeList cannot match, say. Skipped until
     * the next launch, so one that can never land does not hold its batch slot on every refresh and
     * starve the marks behind it.
     */
    private val failedThisSession = mutableSetOf<Int>()

    private val _entries = MutableStateFlow<List<WatchedEntry>>(emptyList())
    val entries = _entries.asStateFlow()

    internal fun init(prefs: SharedPreferences) {
        this.prefs = prefs
        _entries.value = runCatching {
            prefs.getString(KEY_WATCHED, null)?.let {
                json.decodeFromString(ListSerializer(WatchedEntry.serializer()), it)
            }
        }.getOrNull().orEmpty()
    }

    fun isWatched(anilistId: Int): Boolean = _entries.value.any { it.anilistId == anilistId }

    fun ids(): Set<Int> = _entries.value.mapTo(mutableSetOf(), WatchedEntry::anilistId)

    /**
     * Mark [entry] watched, or take the mark off. Marking it does what Completed does on an
     * account: the title stops being a save and leaves Continue Watching.
     */
    @Synchronized
    fun setWatched(entry: WatchedEntry, watched: Boolean) {
        if (isWatched(entry.anilistId) == watched) return
        val updated = if (watched) {
            listOf(entry.copy(watchedAt = System.currentTimeMillis())) +
                _entries.value.filterNot { it.anilistId == entry.anilistId }
        } else {
            _entries.value.filterNot { it.anilistId == entry.anilistId }
        }
        write(updated)
        DiagnosticsLog.event("Local watched ${if (watched) "marked" else "cleared"} id=${entry.anilistId}")
        if (!watched) return
        LibraryStore.watchlist.value.firstOrNull { it.anilistId == entry.anilistId }
            ?.let(LibraryStore::toggleWatchlist)
        LibraryStore.removeHistory(entry.anilistId, dismissRemoteSeed = false)
    }

    /**
     * The signed-out half of finishing a series. Signed in, the last episode's progress sync moves
     * the title to Completed on the account; with no account nothing did, so a show played to the
     * end here stayed out of My List's Completed and in the watchlist, and a viewer had to mark it
     * by hand. Signed in, this does nothing: the account's own Completed is already on its way.
     *
     * Unlike [setWatched] it leaves Continue Watching to the caller. Playback already drops a
     * finished series' row, and a mark made from the episode list must keep the row whose watched
     * set is every tick that list draws.
     */
    @Synchronized
    fun markFinished(anilistId: Int, title: String, cover: String?, totalEpisodes: Int?) {
        if (AccountService.active != null || isWatched(anilistId)) return
        val saved = LibraryStore.watchlist.value.firstOrNull { it.anilistId == anilistId }
        val entry = WatchedEntry(
            anilistId = anilistId,
            title = title,
            cover = cover,
            format = saved?.format,
            averageScore = saved?.averageScore,
            episodes = totalEpisodes,
            watchedAt = System.currentTimeMillis(),
        )
        write(listOf(entry) + _entries.value)
        DiagnosticsLog.event("Local watched marked id=$anilistId reason=finished episodes=${totalEpisodes ?: "unknown"}")
        // A finished title is not a save any more, as it stops being one on the account.
        saved?.let(LibraryStore::toggleWatchlist)
    }

    /** Merge marks from a backup; a title marked on both devices keeps this one's. */
    @Synchronized
    fun importEntries(imported: List<WatchedEntry>): Int {
        val known = ids()
        val fresh = imported.distinctBy { it.anilistId }.filter { it.anilistId !in known }
        if (fresh.isEmpty()) return 0
        write(_entries.value + fresh)
        return fresh.size
    }

    /**
     * Hand the marks to the account that just refreshed. [entries] is its whole collection, minus
     * the custom-list-only placeholders, whose status is not a statement.
     */
    internal fun deliverToAccount(service: AccountService, entries: List<MediaListEntry>) {
        if (_entries.value.isEmpty()) return
        val remote = entries.mapNotNull { entry ->
            val media = entry.media ?: return@mapNotNull null
            val status = entry.status?.uppercase() ?: return@mapNotNull null
            media.id to (status to entry.updatedAt * 1_000L)
        }.toMap()
        val plan = plannedWatchedDelivery(_entries.value, remote)
        if (plan.settled.isNotEmpty()) {
            forget(plan.settled)
            DiagnosticsLog.event("Local watched settled by ${service.label} titles=${plan.settled.size}")
        }
        if (plan.push.isEmpty()) return
        scope.launch {
            // A refresh that lands while the previous batch is still being sent would only send the
            // same titles twice; the batch in flight ends in a refresh of its own.
            if (!pushMutex.tryLock()) return@launch
            try {
                val delivered = mutableSetOf<Int>()
                plan.push.filterNot { it.anilistId in failedThisSession }.take(MAX_PUSHES_PER_RUN).forEach { entry ->
                    if (!isWatched(entry.anilistId)) return@forEach
                    runCatching { AppGraph.repository.setAnimeWatched(entry.anilistId, true, entry.episodes) }
                        .onSuccess {
                            delivered += entry.anilistId
                            LibraryStore.updateRemoteStatus(entry.anilistId, "COMPLETED")
                        }
                        .onFailure {
                            failedThisSession += entry.anilistId
                            DiagnosticsLog.throwable(
                                "${service.label} local watched delivery failed id=${entry.anilistId}",
                                it,
                            )
                        }
                }
                forget(delivered)
                DiagnosticsLog.event(
                    "${service.label} local watched delivered titles=${delivered.size} " +
                        "outstanding=${_entries.value.count { !isCatalogueNativeMediaId(it.anilistId) }}",
                )
                if (delivered.isNotEmpty()) LibraryStore.refreshRemoteLibrary(force = true)
            } finally {
                pushMutex.unlock()
            }
        }
    }

    @Synchronized
    private fun forget(anilistIds: Set<Int>) {
        if (anilistIds.isEmpty()) return
        write(_entries.value.filterNot { it.anilistId in anilistIds })
    }

    private fun write(entries: List<WatchedEntry>) {
        _entries.value = entries
        prefs.edit()
            .putString(KEY_WATCHED, json.encodeToString(ListSerializer(WatchedEntry.serializer()), entries))
            .apply()
    }
}
