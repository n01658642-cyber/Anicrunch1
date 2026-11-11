package com.anilili.data.library

import com.anilili.data.model.Media
import com.anilili.data.model.WatchedFilter

/**
 * What "already watched" means for Browse's [WatchedFilter], from the three things the device
 * knows: the signed-in account's list statuses, this device's own watch history, and the titles
 * marked watched here without an account ([LocalWatchedStore]), which count as Completed.
 *
 * Read at request time, not held, so a title finished a minute ago is gone from the next search.
 */
object WatchedTitles {
    /** Account statuses that mean the viewer has started a title. PLANNING means they have not. */
    private val STARTED_STATUSES = setOf("CURRENT", "REPEATING", "COMPLETED", "PAUSED", "DROPPED")

    /**
     * The ids to send AniList as `id_not_in`, so the catalogue itself leaves them out and pages
     * stay full. Local "every episode played" completions are not in here — deciding those needs
     * the title's episode count, which only arrives with the result — so [hides] catches them.
     */
    fun excludedIds(
        filter: WatchedFilter,
        statuses: Map<Int, String> = LibraryStore.remoteStatuses.value,
        history: List<HistoryEntry> = LibraryStore.history.value,
        marked: Set<Int> = LocalWatchedStore.ids(),
    ): Set<Int> = when (filter) {
        WatchedFilter.OFF -> emptySet()
        WatchedFilter.COMPLETED -> statuses.filterValues { it == "COMPLETED" }.keys + marked
        WatchedFilter.STARTED ->
            statuses.filterValues { it in STARTED_STATUSES }.keys + history.map(HistoryEntry::anilistId) + marked
    }

    /** Whether [media] is one [filter] leaves out, for results that did not come from AniList. */
    fun hides(
        media: Media,
        filter: WatchedFilter,
        statuses: Map<Int, String> = LibraryStore.remoteStatuses.value,
        history: List<HistoryEntry> = LibraryStore.history.value,
        marked: Set<Int> = LocalWatchedStore.ids(),
    ): Boolean {
        if (filter == WatchedFilter.OFF) return false
        if (media.id in excludedIds(filter, statuses, history, marked)) return true
        if (filter != WatchedFilter.COMPLETED) return false
        val row = history.firstOrNull { it.anilistId == media.id } ?: return false
        return playedThrough(media, row)
    }

    /**
     * Every episode of a finished title played on this device. A title still airing is never
     * "completed" here: its next episode is something the viewer has not seen yet.
     */
    internal fun playedThrough(media: Media, row: HistoryEntry): Boolean {
        if (media.status != "FINISHED") return false
        val total = media.episodes?.takeIf { it > 0 } ?: return false
        return (1..total).all { it.toDouble() in row.watchedEpisodes }
    }
}
