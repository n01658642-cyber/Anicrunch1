package com.anilili.ui.detail

import com.anilili.data.model.Media
import com.anilili.data.remote.TvdbRef

/**
 * What to call each entry of a season picker, by AniList id: "Season 2", "Season 2 Part 2", or —
 * when every entry is one season split into parts — just "Part 3".
 *
 * AniList files every cour as its own entry, so counting entries named Mushoku Tensei's second
 * half of its second season "Season 4" and the real third season "Season 5", and did the same to
 * Slime, Attack on Titan, Re:Zero, Dr. Stone and Spy x Family among the most-watched titles. The
 * entries themselves say otherwise, in two ways worth trusting in this order:
 *
 *  - TheTVDB's season number, from the bundled cross-reference, when it covers every entry of the
 *    list, places them all in the same series, and agrees with everything else the entries say.
 *    It groups cours the way viewers count seasons, and it is the only source that knows
 *    "Dr. STONE: STONE WARS" is season two. But the cross-reference also files whole franchises
 *    under season 1 when it knows no better — all four Ascendance of a Bookworm seasons, and
 *    "Fate/Zero Season 2" — so a title that names its own season outranks it, and so does the
 *    airing order.
 *  - Otherwise the titles: "Season 3", "3rd Season", and "Part 2" / "Cour 2" — an unnumbered part
 *    after the first belongs to the season before it, unless that leaves a numbered season with a
 *    gap before it, in which case the "part" was a season in all but name.
 *
 * Entries sharing a season are told apart as Part 1, Part 2, … in list order, so the first half of
 * a split season is named as such rather than left as a bare "Season 2" beside its "Part 2".
 */
internal fun seasonLabels(seasons: List<Media>, ref: (Int) -> TvdbRef?): Map<Int, String> {
    if (seasons.isEmpty()) return emptyMap()
    val numbers = tvdbSeasonNumbers(seasons, ref)
        ?.takeIf { agreesWithTitlesAndAiring(seasons, it) }
        ?: titleSeasonNumbers(seasons)
    val groupSizes = numbers.groupingBy { it }.eachCount()
    val singleSeason = groupSizes.size == 1 && seasons.size > 1
    val partSeen = mutableMapOf<Int, Int>()
    return seasons.indices.associate { index ->
        val season = numbers[index]
        val part = partSeen.merge(season, 1, Int::plus)!!
        val split = (groupSizes[season] ?: 1) > 1
        seasons[index].id to when {
            singleSeason -> "Part $part"
            split -> "Season $season Part $part"
            else -> "Season $season"
        }
    }
}

/** TheTVDB's numbering, or null unless it places every entry, all in one series. */
private fun tvdbSeasonNumbers(seasons: List<Media>, ref: (Int) -> TvdbRef?): List<Int>? {
    val refs = seasons.map { ref(it.id) ?: return null }
    if (refs.map(TvdbRef::id).distinct().size != 1) return null
    return refs.map { it.season?.takeIf { season -> season >= 1 } ?: return null }
}

/**
 * Whether TheTVDB's numbers can stand: every season a title names for itself matches, and the
 * numbers never go back down as the entries go on airing.
 */
private fun agreesWithTitlesAndAiring(seasons: List<Media>, numbers: List<Int>): Boolean {
    val titled = seasons.indices.all { index ->
        val named = explicitTitleSeason(seasons[index])
        named == null || named == numbers[index]
    }
    if (!titled) return false
    val byAiring = seasons.indices
        .filter { seasons[it].seasonYear != null }
        .sortedWith(compareBy({ seasons[it].seasonYear }, { it }))
        .map { numbers[it] }
    return byAiring.zipWithNext().all { (earlier, later) -> earlier <= later }
}

private fun titleSeasonNumbers(seasons: List<Media>): List<Int> {
    val joined = titleSeasonNumbers(seasons, partsJoinPreviousSeason = true)
    val distinct = joined.distinct().sorted()
    val contiguous = distinct.zipWithNext().all { (a, b) -> b == a + 1 }
    return if (contiguous) joined else titleSeasonNumbers(seasons, partsJoinPreviousSeason = false)
}

private fun titleSeasonNumbers(seasons: List<Media>, partsJoinPreviousSeason: Boolean): List<Int> {
    var previous = 0
    return seasons.map { media ->
        val part = titles(media).firstNotNullOfOrNull(::titlePart)
        val joinsPrevious = partsJoinPreviousSeason && part != null && part >= 2 && previous > 0
        val season = explicitTitleSeason(media) ?: if (joinsPrevious) previous else previous + 1
        previous = season
        season
    }
}

private fun titles(media: Media): List<String> = listOfNotNull(media.title.english, media.title.romaji)

private fun explicitTitleSeason(media: Media): Int? = titles(media).firstNotNullOfOrNull(::titleSeason)

private fun titleSeason(title: String): Int? =
    SEASON_WORD_FIRST.find(title)?.groupValues?.get(1)?.toIntOrNull()
        ?: SEASON_ORDINAL.find(title)?.groupValues?.get(1)?.toIntOrNull()

private fun titlePart(title: String): Int? {
    PART_WORD.find(title)?.let { match -> return partNumber(match.groupValues[1]) }
    return COUR_WORD_FIRST.find(title)?.groupValues?.get(1)?.toIntOrNull()
        ?: COUR_ORDINAL.find(title)?.groupValues?.get(1)?.toIntOrNull()
}

private fun partNumber(token: String): Int? =
    token.toIntOrNull() ?: ROMAN_PARTS[token.lowercase()]

private val SEASON_WORD_FIRST = Regex("""\bseason\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)
private val SEASON_ORDINAL = Regex("""\b(\d{1,2})(?:st|nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE)
private val PART_WORD = Regex("""\bpart\s*(\d{1,2}|iv|i{1,3})\b""", RegexOption.IGNORE_CASE)
private val COUR_WORD_FIRST = Regex("""\bcour\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)
private val COUR_ORDINAL = Regex("""\b(\d{1,2})(?:st|nd|rd|th)\s+cour\b""", RegexOption.IGNORE_CASE)
private val ROMAN_PARTS = mapOf("i" to 1, "ii" to 2, "iii" to 3, "iv" to 4)
