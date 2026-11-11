package com.anilili.data.library

import com.anilili.data.model.EPISODE_WATCHABLE_AFTER_SEC

/**
 * AniList ids to their episodes airing in the window Home reads, with each episode's airing time.
 *
 * The window runs from yesterday to a fortnight out ([UPCOMING_AIRING_DAYS]): far enough for a
 * weekly show's next episode and the odd skipped week, and back far enough to see an episode that
 * aired late last night and is not watchable yet. One read covers every row on screen rather than a
 * request each. The times are kept rather than the day alone because the reader has to be able to
 * ask whether one has passed, and a row must not stay away after its episode is out.
 */
internal typealias UpcomingAiring = Map<Int, Map<Int, Long>>

/** How many days the [UpcomingAiring] window covers, starting yesterday. */
internal const val UPCOMING_AIRING_DAYS = 16

/**
 * Whether Continue Watching should step aside for [entry] because nothing on it is left to play.
 *
 * A card on the row promises that pressing it plays something. A title followed week by week, with
 * the latest episode watched, has nothing to play until the next one is out — so for the whole week
 * the row is either finished with the episode it names, or pointed at one that does not exist yet.
 * Either way it holds a slot that a row of playable things should not spend.
 *
 * Hidden, never removed: nothing is written, no dismissal is recorded, and the account never hears
 * about it. The row comes back on its own once the episode is out — at [returnsToContinueAt], on the
 * new episode, in its old place by `updatedAt`.
 */
internal fun hideFromContinueUntilAired(
    entry: HistoryEntry,
    upcoming: UpcomingAiring,
    nowSec: Long = System.currentTimeMillis() / 1000,
): Boolean = returnsToContinueAt(entry, upcoming, nowSec) != null

/**
 * When a row that is hidden now comes back to Continue Watching, in epoch seconds; null when it is
 * not hidden.
 *
 * That moment is the next episode's broadcast plus the two hours the servers need to carry it, the
 * same bar the detail page greys rows out by. Home re-runs the filter then, because nothing else
 * about the row changes at that instant.
 */
internal fun returnsToContinueAt(
    entry: HistoryEntry,
    upcoming: UpcomingAiring,
    nowSec: Long = System.currentTimeMillis() / 1000,
): Long? {
    val (episode, returnsAt) = nextUnreleasedEpisode(entry, upcoming, nowSec) ?: return null
    // What is playing, or was left part-way, is what Continue Watching is for, and what happens to be
    // airing next has nothing to do with the episode under the finger.
    if (entry.positionMs > 0) return null
    // Anything behind the last episode out is a rewatch, an earlier season or an episode not yet
    // watched: there is something to play, and the schedule has nothing to do with it.
    val lastAired = episode - 1
    if (entry.episodeNumber < lastAired) return null
    // On the last episode out but not through it, which is where finishing the one before leaves a
    // row, and where a seeded row rests when the account's count is one behind: that episode is the
    // thing to play. Only a set that records something can say so — an empty one is a row that
    // predates the set, and says nothing either way.
    if (entry.episodeNumber < episode &&
        entry.watchedEpisodes.isNotEmpty() &&
        entry.episodeNumber !in entry.watchedEpisodes
    ) {
        return null
    }
    // The row is caught up — on the last episode out, or already advanced onto the next one, which is
    // where finishing the latest one on this device leaves it, since `markEpisodeWatched` moves the
    // row on whether or not the next episode is out. Neither has anything left to play.
    //
    // Unless an episode was left part-way. That is a place to resume whatever the schedule says, and
    // a seeded row can carry the account's progress without this device ever having held the clock:
    // `HistoryEntry.resumeFor` is the one thing that knows the difference.
    if (entry.resumeFor(entry.episodeNumber) != null) return null
    return returnsAt
}

/**
 * The first episode of [entry]'s title in [upcoming], at or after the row's own, that is not
 * watchable yet — with the moment it will be — or null when the window holds none.
 *
 * At-or-after rather than after, because the row may already be on it: finishing the latest episode
 * advances the row onto the next one before it has aired. And the first *unreleased* one rather than
 * the first, because the window reaches back to yesterday: an episode in it that is already out is
 * something to play, and the question is what the row is still waiting for after it.
 */
private fun nextUnreleasedEpisode(
    entry: HistoryEntry,
    upcoming: UpcomingAiring,
    nowSec: Long,
): Pair<Int, Long>? =
    upcoming[entry.anilistId]
        ?.mapValues { (_, airingAt) -> airingAt + EPISODE_WATCHABLE_AFTER_SEC }
        ?.filter { (episode, watchableAt) -> episode >= entry.episodeNumber && nowSec < watchableAt }
        ?.minByOrNull { (episode, _) -> episode }
        ?.toPair()
