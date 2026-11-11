package com.anilili.ui.random

import com.anilili.data.library.CustomList
import com.anilili.data.library.CustomListStore
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.LocalWatchedStore
import com.anilili.data.library.WatchlistEntry
import com.anilili.data.library.listKey

/**
 * Where the dice deals from: the whole catalogue ([ANYWHERE]), or one of the viewer's own lists —
 * "surprise me, but from what I already meant to watch".
 *
 * [ids] is the list as it stood when the sources were read; a list source with none is never
 * offered, so a chip never opens on an empty stack.
 */
data class DiceSource(
    val key: String,
    val label: String,
    val ids: List<Int> = emptyList(),
) {
    val isAnywhere: Boolean get() = key == ANYWHERE_KEY

    companion object {
        const val ANYWHERE_KEY = "any"
        val ANYWHERE = DiceSource(ANYWHERE_KEY, "Anything")
    }
}

/** The sources on offer right now, read from what the device already holds — no request. */
fun currentDiceSources(): List<DiceSource> = diceSources(
    saved = LibraryStore.watchlist.value,
    statuses = LibraryStore.remoteStatuses.value,
    history = LibraryStore.history.value,
    marked = LocalWatchedStore.ids(),
    customLists = CustomListStore.lists.value,
)

/**
 * [DiceSource.ANYWHERE] first, then each status list and each custom list that has titles in it.
 *
 * Plan to watch is the Saved list — this device's saves, which already carry the account's
 * Planning — plus any Planning title the saves have not caught up with. Watching is the account's
 * Watching and Rewatching, plus what this device has in progress, so it works signed out too; a
 * title the account calls finished or dropped, or one marked watched here, is not "in progress"
 * however recently it was played.
 *
 * Ids of zero or below are left out: catalogue-native titles have no AniList card to deal.
 */
internal fun diceSources(
    saved: List<WatchlistEntry>,
    statuses: Map<Int, String>,
    history: List<HistoryEntry>,
    marked: Set<Int>,
    customLists: List<CustomList>,
): List<DiceSource> {
    fun withStatus(vararg wanted: String) = statuses.filterValues { it in wanted }.keys.toList()
    val finished = withStatus("COMPLETED", "DROPPED").toSet() + marked
    val inProgress = history.map(HistoryEntry::anilistId).filterNot { it in finished }
    val lists = listOf(
        DiceSource("status:PLANNING", "Plan to watch", saved.map(WatchlistEntry::anilistId) + withStatus("PLANNING")),
        DiceSource("status:CURRENT", "Watching", withStatus("CURRENT", "REPEATING") + inProgress),
        DiceSource("status:PAUSED", "Paused", withStatus("PAUSED")),
        DiceSource("status:DROPPED", "Dropped", withStatus("DROPPED")),
        DiceSource("status:COMPLETED", "Completed", withStatus("COMPLETED")),
    ) + customLists.map { list ->
        DiceSource("list:${listKey(list.name)}", list.name, list.entries.map(WatchlistEntry::anilistId))
    }
    return listOf(DiceSource.ANYWHERE) + lists
        .map { source -> source.copy(ids = source.ids.filter { it > 0 }.distinct()) }
        .filter { it.ids.isNotEmpty() }
}
