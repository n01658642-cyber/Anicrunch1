package com.anilili.ui.detail

import com.anilili.data.SERIES_AIRING_ORDER
import com.anilili.data.model.Media

/**
 * Which shelf of the watch guide an entry sits on: what the Related chips narrow the guide to.
 *
 * The guide itself is not grouped. [buildWatchOrder] returns one running order with the films and
 * the extras between the seasons they aired between, because a side story that came out after
 * Season 3 belongs after Season 3, not at the foot of a "Movies" heading below the last season.
 */
enum class WatchOrderGroup(val label: String) {
    SEASONS("Seasons"),
    MOVIES("Movies"),
    OVAS("OVAs"),
    SPECIALS("Specials"),
    OTHER("Other"),
}

/**
 * One entry of the franchise, placed on a shelf and given its place in the running order.
 *
 * [position] is franchise-wide and counted in airing order, so it stays the same number when the
 * guide is filtered to one shelf — filtering to Movies must not renumber the films 1, 2, 3 as if
 * they were the whole story.
 */
data class WatchOrderEntry(
    val media: Media,
    val group: WatchOrderGroup,
    val position: Int,
    /** 1-based place in the season chain, for [WatchOrderGroup.SEASONS] entries only. */
    val seasonNumber: Int?,
    /** How AniList joins this entry to the series ("Side story", "Summary"), for the extras. */
    val relationLabel: String?,
    /** The entry this detail page is describing; it is listed, but it does not open a second page. */
    val isCurrent: Boolean,
)

/**
 * The whole franchise as a watch guide: seasons, films, OVAs and specials in one running order.
 *
 * [series] is the PREQUEL/SEQUEL walk, which is deliberately narrow — it exists to find the seasons
 * and it follows nothing else, so a film joined to the series as a SIDE_STORY is not in it at all.
 * That is the gap this fills. Every member of the chain already carries its own relation edges (the
 * walk fetches each one in full), so the rest of the franchise is read straight off those edges and
 * costs no request: the guide is complete as soon as the series walk lands, and until then it holds
 * whatever the page's own relations say.
 *
 * Only relations that are the same story are followed. ADAPTATION and SOURCE point at the manga,
 * CHARACTER at a title that merely shares a face, and OTHER at anything at all — those belong under
 * "More like this" if anywhere, not in a running order. A node reached through a followed relation
 * still has to be something watchable: the connection carries manga volumes as readily as anime.
 */
internal fun buildWatchOrder(
    info: Media,
    series: List<Media>,
    seasons: List<Media>,
    hideAdult: Boolean = true,
): List<WatchOrderEntry> {
    val members = LinkedHashMap<Int, Media>()
    // The chain's records win over a relation node describing the same title: the walk fetches
    // full media, while an edge node carries the compact field set.
    members[info.id] = info
    series.forEach { members[it.id] = it }

    val relationLabels = HashMap<Int, String>()
    (sequenceOf(info) + series.asSequence()).forEach { member ->
        member.relations.edges.forEach edge@{ edge ->
            val type = edge.relationType ?: return@edge
            if (type !in FRANCHISE_RELATIONS) return@edge
            val node = edge.node ?: return@edge
            if (hideAdult && node.isAdult) return@edge
            val known = node.id in members
            if (!known && node.format !in FRANCHISE_FORMATS) return@edge
            if (!known) members[node.id] = node
            // PREQUEL/SEQUEL is what the season chain already says; anything else is worth naming.
            if (type !in SEASON_RELATIONS && node.id !in relationLabels) {
                relationLabels[node.id] = type.pretty()
            }
        }
    }

    // Airing order, except that the seasons keep their chain order among themselves: a season whose
    // AniList start date disagrees with its numbering would otherwise read 1, 3, 2. Each slot a
    // season holds in airing order is handed to the next season of the chain instead, so the films
    // and extras stay where they aired and only the seasons are re-dealt.
    val seasonIds = seasons.map(Media::id)
    val listedSeasons = seasonIds.distinct().filter { it in members }
    val listedSeasonIds = listedSeasons.toHashSet()
    val chain = ArrayDeque(listedSeasons.map(members::getValue))
    val ordered = members.values.sortedWith(SERIES_AIRING_ORDER).map { media ->
        if (media.id in listedSeasonIds) chain.removeFirst() else media
    }
    return ordered.mapIndexed { index, media ->
        val seasonIndex = seasonIds.indexOf(media.id)
        val group = when {
            seasonIndex >= 0 -> WatchOrderGroup.SEASONS
            media.format == "MOVIE" -> WatchOrderGroup.MOVIES
            media.format == "OVA" -> WatchOrderGroup.OVAS
            media.format == "SPECIAL" -> WatchOrderGroup.SPECIALS
            else -> WatchOrderGroup.OTHER
        }
        WatchOrderEntry(
            media = media,
            group = group,
            position = index + 1,
            // A one-entry chain is the anime itself, and calling that "Season 1" invents a series.
            seasonNumber = (seasonIndex + 1).takeIf { seasonIndex >= 0 && seasonIds.size > 1 },
            relationLabel = relationLabels[media.id]?.takeIf { group != WatchOrderGroup.SEASONS },
            isCurrent = media.id == info.id,
        )
    }
}

/** Relations that are the same story told on, told beside, or told again. */
private val FRANCHISE_RELATIONS = setOf(
    "PREQUEL",
    "SEQUEL",
    "PARENT",
    "SIDE_STORY",
    "SUMMARY",
    "ALTERNATIVE",
    "SPIN_OFF",
    "COMPILATION",
    "CONTAINS",
)

private val SEASON_RELATIONS = setOf("PREQUEL", "SEQUEL")

/**
 * Formats a watch guide can list. Reading formats are the obvious exclusion, but MUSIC is the one
 * worth stating: openings and ending videos are related media AniList files like any other, and a
 * running order buried under a dozen of them is not a running order.
 */
private val FRANCHISE_FORMATS = setOf("TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA")

/**
 * What kind of entry this is, in one line: "Season 2 · 2023 · 12 eps", "Movie · Side story · 2024".
 * The phone's rows print it whole; the TV cards put [kindLabel] in a pill and the rest beside it.
 */
internal fun WatchOrderEntry.metaLine(): String =
    listOfNotNull(kindLabel(), detailLine().takeIf { it.isNotEmpty() }).joinToString(" · ")

/** "Season 2", or the format for anything off the numbered run: "Movie", "Special". */
internal fun WatchOrderEntry.kindLabel(): String? = seasonNumber?.let { "Season $it" } ?: media.format?.pretty()

/** Everything but the kind: "Side story · 2024", "2023 · 12 eps". */
internal fun WatchOrderEntry.detailLine(): String =
    (listOfNotNull(relationLabel) + media.yearAndEpisodes()).joinToString(" · ")

/** The year and, for anything longer than one sitting, the episode count. */
internal fun Media.yearAndEpisodes(): List<String> = listOfNotNull(
    (seasonYear ?: startDate?.year)?.toString(),
    episodes?.takeIf { it > 1 && !isMovieFormat(format) }?.let { "$it eps" },
)
