package com.anilili.data.model

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlinx.serialization.Serializable

/**
 * One episode's title and still, from whichever catalogue happened to know them.
 *
 * The app has three: the Konoha CDN (a static TMDB-derived mirror, free and instant, but a backfill
 * that had only reached 2010 and stopped syncing), TMDB itself (current, but a request away and
 * needing the AniList title mapped onto a TMDB season first), and AniList's own streaming rows
 * (free, already in the detail response, but numbered only by what someone typed in a title). None
 * is authoritative and all answer the same question, so the merge that overlays them onto an
 * episode list should not have to care which one spoke — hence one shape rather than each
 * source's own.
 *
 * What they have in common is that none of them is the *stream provider*. A thumbnail that comes
 * from the server currently selected changes when the viewer switches servers and is missing
 * entirely on the servers that ship no art, which is the whole reason these sources are preferred
 * to it.
 *
 * [number] is the AniList episode number, which is what the rest of the app keys episodes by.
 */
@Serializable
data class EpisodeMetadata(
    val number: Double,
    val title: String? = null,
    val still: String? = null,
    val airDate: String? = null,
    /**
     * The episode's own synopsis, its length in minutes, and what viewers scored it.
     *
     * All three are what a ten-foot episode browser has room to say about the row under the cursor,
     * and all three are absent far more often than the title is: the catalogues carry a synopsis
     * and a runtime for most of what they cover, but a score only where a rating source reached.
     * Defaulted like the rest, so a row that has none of them is still a valid row.
     */
    val overview: String? = null,
    val runtime: Int? = null,
    val rating: Double? = null,
)

/**
 * One catalogue air date as a day, or null when there is nothing usable to read.
 *
 * Konoha writes two shapes for the same field — `2016-04-04` for the titles its first backfill
 * reached and `2024-10-02T00:00:00+00:00` for the ones added since — so a parser that only knows
 * the plain date quietly treats half the catalogue as undated.
 */
internal fun episodeAirDate(raw: String?): LocalDate? {
    val day = raw?.trim()?.take(10)?.takeIf { it.length == 10 } ?: return null
    return runCatching { LocalDate.parse(day) }.getOrNull()
}

/**
 * Whether [episode] is one nobody can watch yet, and so must not be offered as though they could.
 *
 * Two sources, because neither covers the season on its own:
 *
 * - AniList's `nextAiringEpisode`, which names the next episode due. Everything below that number
 *   has aired and everything from it up has not, and it covers the rows no catalogue has dated —
 *   which is most of the current season, since a still is added after an episode airs, not before.
 * - The catalogue air date, when there is one. This is the only source that works for a title
 *   AniList has no schedule for.
 *
 * The synthetic catalogue is already bounded by `nextAiringEpisode` where AniList states one (see
 * `anilistEpisodeCatalog`), so the case this exists for is the one that slips past it: a RELEASING
 * title with a known episode total and no schedule attached. AniList then states twelve episodes,
 * five have aired, and the page lists all twelve as though they were there.
 *
 * AniList is asked first, and only when it positively says the episode is out. It is a live
 * schedule; the catalogue is a static TMDB mirror that can be a whole week adrift, and reading the
 * date first is what greyed out episode 11 of The Frontier Lord Begins with Zero Subjects on the day
 * after it aired — TMDB has that season's every date a week late (episode 1 on 10 Jul against a 3
 * Jul broadcast), so the row said "airs next Friday" while AniList had already moved on to 12.
 *
 * Deliberately conservative. A row wrongly greyed out is a viewer told they cannot watch something
 * they can, which is worse than a row that fails once they open it — so an episode is unaired only
 * when a source positively says so, never merely because nothing placed it, and never while the
 * other source says it is out. A season AniList calls FINISHED is such a statement about every
 * episode in it.
 *
 * The catalogue date is a calendar day in Japan, where these broadcasts are dated, so it is compared
 * with the day in Japan rather than on the device. Mushoku Tensei III's finale aired at 15:00 UTC on
 * 27 September — midnight in Tokyo — and TMDB dates it the 28th; compared with the device's date, a
 * viewer in the Americas saw the finished season's last episode greyed out for most of a day after
 * it aired, while pressing Next on episode 13 played it.
 *
 * An episode opens [EPISODE_WATCHABLE_AFTER_SEC] after it airs, not on the minute: the servers need
 * that long to carry it, and a row that opens onto "no servers" reads as the app being broken. The
 * exact time comes from AniList's schedule ([EpisodeItem.airingAt]) and is checked before anything
 * else, because AniList moves `nextAiringEpisode` on — and can mark a season FINISHED — the moment
 * the episode airs. Without it, a catalogue day counts from two hours into that day in Japan, which
 * is the late-night slot most of these air in.
 */
fun episodeIsUnaired(
    episode: EpisodeItem,
    season: Media,
    nowSec: Long = System.currentTimeMillis() / 1000,
    today: LocalDate = broadcastDay(nowSec),
): Boolean {
    episode.airingAt?.let { airedAt -> if (nowSec < airedAt + EPISODE_WATCHABLE_AFTER_SEC) return true }
    if (season.status == "FINISHED") return false
    val next = season.nextAiringEpisode?.episode
    if (next != null && episode.number < next) return false
    episodeAirDate(episode.airDate)?.let { return it.isAfter(today) }
    return next != null
}

/** The zone the catalogues' air dates are days in: Japanese broadcast dates. */
private val BROADCAST_ZONE: ZoneId = ZoneId.of("Asia/Tokyo")

/** How long after it airs an episode is treated as out: time for the servers to carry it. */
const val EPISODE_WATCHABLE_AFTER_SEC = 2L * 60 * 60

/** The broadcast day an episode dated only by day must have reached: Japan's, two hours in. */
private fun broadcastDay(nowSec: Long): LocalDate =
    java.time.Instant.ofEpochSecond(nowSec - EPISODE_WATCHABLE_AFTER_SEC).atZone(BROADCAST_ZONE).toLocalDate()

/**
 * A catalogue's run of episodes cut down to the one AniList season being shown, numbered from 1.
 *
 * Both catalogues are TMDB-shaped, and TMDB keeps a series the way it was broadcast rather than the
 * way AniList splits it: Re:Zero's four AniList seasons are a single run of 85 episodes there, and
 * Konoha therefore serves that same run of 85 under *every one* of the four AniList ids. Overlaid by
 * number, the second season's thirteen rows took the run's episodes 1..13 — the first season's
 * titles and stills, shown again under seasons two and three, on a page whose episode count was
 * right and whose every episode was wrong.
 *
 * Air dates are what tell the seasons apart. They are the one thing both catalogues state and
 * AniList states too, and a season sits months or years from its neighbours, so the run's episode
 * whose date lands nearest the season's own start is that season's first with room to spare.
 * Nearest rather than first-on-or-after, because the catalogues disagree by a day whenever one
 * recorded a broadcast date and the other a streaming one, and first-on-or-after turns that day
 * into an off-by-one across the whole season.
 *
 * A run no longer than the season is already an answer about this season and is returned untouched,
 * as is one no date in which comes near the season at all — the point is to stop the wrong episodes
 * being shown, and inventing a cut from dates that do not support one would only pick differently
 * wrong ones.
 */
internal fun alignMetadataToSeason(meta: List<EpisodeMetadata>, season: Media): List<EpisodeMetadata> {
    val start = season.startDate?.localDate() ?: return meta
    val count = season.episodes
    if (count != null && meta.size <= count) return meta
    val ordered = meta.sortedBy { it.number }
    val offset = ordered.indexOfSeasonOpener(start).takeIf { it >= 0 } ?: return meta
    return ordered
        .drop(offset)
        .let { rows -> if (count != null) rows.take(count) else rows }
        .mapIndexed { index, row -> row.copy(number = (index + 1).toDouble()) }
}

/** The row of a run that opens the season starting on [start], or -1 when no date is near enough. */
private fun List<EpisodeMetadata>.indexOfSeasonOpener(start: LocalDate): Int {
    var opener = -1
    var nearest = Long.MAX_VALUE
    forEachIndexed { index, row ->
        val aired = episodeAirDate(row.airDate) ?: return@forEachIndexed
        val distance = abs(ChronoUnit.DAYS.between(aired, start))
        if (distance < nearest) {
            opener = index
            nearest = distance
        }
    }
    return if (nearest <= SEASON_OPENER_TOLERANCE_DAYS) opener else -1
}

/**
 * How far a run's opening episode may sit from AniList's start date and still be it. Wide enough
 * for a premiere screened early or held back a fortnight, and nowhere near the months that separate
 * one season from the next.
 */
private const val SEASON_OPENER_TOLERANCE_DAYS = 21L

/** An AniList fuzzy date as a day, or null when AniList only knows part of it. */
private fun FuzzyDate.localDate(): LocalDate? {
    val year = year ?: return null
    val month = month ?: return null
    val day = day ?: return null
    return runCatching { LocalDate.of(year, month, day) }.getOrNull()
}

/**
 * The episode number AniList buried in a streaming-episode title, or null when there is none.
 *
 * The rows are written by whoever supplied the link, so the shapes vary: "Episode 12", "Episode 12
 * - Title", "EP12", and — for a handful of titles — a bare episode name with no number at all.
 * Anchored to the start so a number inside the episode's *own* name ("Kaiju No. 8") cannot be read
 * as its position, which is the failure that makes an unanchored match worse than no match.
 *
 * Specials and recaps are left alone deliberately: AniList numbers them in the same run as the
 * main episodes, so a decimal recap would be claimed by the integer above it.
 */
private val STREAMING_EPISODE_NUMBER = Regex("""^\s*(?:episode|ep)\.?\s*0*(\d{1,4})\b""", RegexOption.IGNORE_CASE)

internal fun streamingEpisodeNumber(title: String?): Int? =
    STREAMING_EPISODE_NUMBER.find(title.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

/**
 * AniList's streaming rows as episode metadata, keyed by the number in each row's title.
 *
 * Only the thumbnail is taken. The titles are the licensor's listing text rather than the
 * episode's name — they repeat the number the row already states and sometimes carry the service's
 * own branding — so letting them fill [EpisodeMetadata.title] would replace a clean TMDB title
 * with "Episode 12 - Episode 12".
 *
 * Several services can list the same episode; the first row wins, which is the order AniList
 * returns and therefore stable between calls.
 */
internal fun streamingEpisodeMetadata(episodes: List<StreamingEpisode>): List<EpisodeMetadata> =
    episodes
        .mapNotNull { episode ->
            val number = streamingEpisodeNumber(episode.title) ?: return@mapNotNull null
            val thumbnail = episode.thumbnail?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            EpisodeMetadata(number = number.toDouble(), still = thumbnail)
        }
        .distinctBy { it.number }
        .sortedBy { it.number }

/**
 * Whether AniList's streaming rows are numbered for [season] rather than for a longer run.
 *
 * The rows are the licensor's listing rather than anything AniList curates, and a licensor numbers
 * a series the way it streams it. AniList hands back the same sixteen Crunchyroll rows for the
 * first three seasons of Re:Zero, numbered 51 to 66 — the fourth season's episodes, filed under
 * every id in the series, exactly as the episode catalogues file the whole broadcast run under each
 * of them (see [alignMetadataToSeason]). Unlike the catalogues these rows carry no air date, so
 * there is nothing to cut them apart by; all that can be done is to notice that they do not belong.
 *
 * A number reaching past the end of the season is the tell, and it condemns the whole set rather
 * than the rows that stick out. A listing numbered for a longer run is numbered for a longer run
 * throughout, so the rows that happen to land inside the season are wrong too — and those are the
 * dangerous ones. Re:Zero's 51 to 66 miss a thirteen-episode season entirely and change nothing,
 * while a second season listed as 13 to 36 would quietly redecorate half of it.
 *
 * [established] is what the other catalogues already placed, standing in as the season's length
 * when AniList states no count and nothing is airing. With none of the three there is nothing to
 * check the numbering against, and the rows are refused: this source exists to put a picture on a
 * row that already exists, and it is the one source that cannot be trusted to invent the rows.
 */
internal fun streamingRowsFitSeason(
    season: Media,
    rows: List<EpisodeMetadata>,
    established: List<EpisodeMetadata>,
): Boolean {
    if (rows.isEmpty()) return false
    val ceiling = season.episodes?.toDouble()
        ?: season.nextAiringEpisode?.episode?.toDouble()
        ?: established.maxOfOrNull { it.number }
        ?: return false
    return rows.none { it.number > ceiling }
}

/**
 * [fallback]'s answers for the episodes [primary] could not answer for.
 *
 * Per field rather than per episode, because the gap is rarely a whole row: Konoha routinely
 * carries every episode title of a season and a still for only some of them, and a row that keeps
 * its title while gaining a picture is strictly better than either source alone. Rows [primary]
 * does not have at all are appended, which is the case where Konoha has never heard of the title
 * and the fallback is carrying the whole list.
 */
fun mergeEpisodeMetadataSources(
    primary: List<EpisodeMetadata>,
    fallback: List<EpisodeMetadata>,
): List<EpisodeMetadata> {
    if (fallback.isEmpty()) return primary
    if (primary.isEmpty()) return fallback
    val extra = fallback.associateBy { it.number }
    val filled = primary.map { episode ->
        val other = extra[episode.number] ?: return@map episode
        episode.copy(
            title = episode.title?.takeIf { it.isNotBlank() } ?: other.title,
            still = episode.still?.takeIf { it.isNotBlank() } ?: other.still,
            airDate = episode.airDate ?: other.airDate,
            // Filled field by field rather than row by row, because the sources are strong in
            // different places: the catalogue that knows an episode's name often has no score for
            // it, and the one that scores it may have written nothing else about it at all.
            overview = episode.overview?.takeIf { it.isNotBlank() } ?: other.overview,
            runtime = episode.runtime ?: other.runtime,
            rating = episode.rating ?: other.rating,
        )
    }
    val known = primary.map { it.number }.toSet()
    return filled + fallback.filterNot { it.number in known }
}
