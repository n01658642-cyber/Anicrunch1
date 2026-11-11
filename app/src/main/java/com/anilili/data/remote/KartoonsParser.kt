package com.anilili.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer
import kotlin.math.abs

/**
 * Pure payload handling for [KartoonsProvider], kept network-free for unit tests.
 *
 * Kartoons records carry no AniList/MAL/TMDB id — only TMDB image URLs — and its CloudStream
 * surface (the one headless path; the site's own plugin uses nothing else) is keyed on kartoons'
 * own Mongo ids throughout. There is no external-id endpoint to ask: `show/tmdb:{id}` 404s and a
 * `tmdb` parameter on search and catalog is ignored (probed 2026-10-03). Matching therefore happens
 * here, by title, and is verified by the season structure rather than trusted.
 *
 * Three things about the data decide the shape of everything below, each measured on the live API
 * on 2026-10-03 and each the cause of a wrong episode in the first version of this file:
 *
 * - **Episode numbering is per show.** Naruto, Naruto Shippūden and Jujutsu Kaisen number straight
 *   through (Naruto's season 3 is 105–158, JJK's season 2 is 25–47); Demon Slayer, Attack on Titan,
 *   My Hero Academia and One Punch Man restart at 1 every season. [localNumbers] works out which.
 * - **Claimed counts follow uploads, not TMDB.** Pokémon (HUNGAMA)'s season 1 claims 80 episodes and
 *   numbers to 82; Frieren's season 2 claims the 6 dubbed so far. A whole-series run is therefore
 *   laid out from the real episode lists ([layoutRun]), never from the counts alone.
 * - **Season years are mostly right, not always.** JJK's season 2 says 2020 (it aired 2023) and
 *   Demon Slayer's season 3 says 2021. A year can confirm a season; it cannot be the only thing that
 *   picks one when a wrong pick plays another season's episodes.
 */
internal object KartoonsParser {
    @Serializable
    internal data class SearchRow(
        val _id: String = "",
        val title: String = "",
        val releaseYear: Int? = null,
        val category: String? = null,
    )

    @Serializable
    internal data class SearchResponse(val data: List<SearchRow> = emptyList())

    @Serializable
    internal data class Season(
        val _id: String = "",
        val seasonNumber: Int = 0,
        val title: String? = null,
        val releaseYear: Int? = null,
        val slug: String? = null,
        val episodeCount: Int = 0,
        val showId: String? = null,
    )

    @Serializable
    internal data class Show(
        val _id: String = "",
        val title: String = "",
        val startYear: Int? = null,
        val seasons: List<Season> = emptyList(),
        val category: String? = null,
        val type: String? = null,
    )

    @Serializable
    internal data class ShowResponse(val data: Show? = null)

    @Serializable
    internal data class Episode(
        val _id: String = "",
        val seasonId: String? = null,
        val episodeNumber: Int = 0,
        val title: String? = null,
    )

    @Serializable
    internal data class EpisodesResponse(val data: List<Episode> = emptyList())

    @Serializable
    internal data class Link(val name: String = "", val url: String = "")

    @Serializable
    internal data class Links(val links: List<Link> = emptyList())

    @Serializable
    internal data class LinksResponse(val data: Links? = null)

    @Serializable
    internal data class PoolToken(val t: String = "")

    @Serializable
    internal data class TokenPool(val tokens: List<PoolToken> = emptyList())

    /** What the AniList entry being shown asks of a kartoons show. */
    internal data class Wanted(
        /** The entry's episode count, or what has aired of it; null when neither is known. */
        val episodes: Int?,
        /** The year the entry began. */
        val year: Int?,
        /** Every AniList name for the entry, as written. */
        val titles: List<String>,
    ) {
        /** The season number the entry's names claim, with "Part N" not read as one; see [seasonHint]. */
        val seasonHint: Int? = seasonHint(titles)
    }

    fun parseSearch(json: Json, body: String): List<SearchRow> =
        json.decodeFromString(SearchResponse.serializer(), body).data.filter { it._id.isNotBlank() }

    fun parseShow(json: Json, body: String): Show? =
        json.decodeFromString(ShowResponse.serializer(), body).data?.takeIf { it._id.isNotBlank() }

    /** Episode number → kartoons episode id, as the site numbers them. */
    fun parseEpisodes(json: Json, body: String): Map<Int, String> =
        json.decodeFromString(EpisodesResponse.serializer(), body).data
            .filter { it._id.isNotBlank() && it.episodeNumber > 0 }
            .associate { it.episodeNumber to it._id }

    fun parseLinks(json: Json, body: String): List<Link> =
        json.decodeFromString(LinksResponse.serializer(), body).data?.links.orEmpty()

    fun parseTokenPool(json: Json, body: String): List<String> =
        json.decodeFromString(TokenPool.serializer(), body).tokens.map { it.t }.filter { it.isNotBlank() }

    /**
     * Whether a search row can be an anime at all.
     *
     * The catalogue is half western cartoons ("Secret Level", "Iron Man", "Shin Chan"), and AniList
     * only ever asks about anime. A row with no category says nothing either way and is let through.
     */
    fun isAnime(row: SearchRow): Boolean = row.category == null || row.category.equals("Anime", ignoreCase = true)

    /**
     * A title with the site's dub-channel decoration taken off.
     *
     * Kartoons files one show once per Indian dub channel — "(MUSE) Attack on Titan", "Pokemon
     * (HUNGAMA)", "Haikyu!! (Hotstar)", "One Piece (SONY)" — and fan edits as "One Punch Man -
     * Definitive Hindi Dub (Fan Edit)". The tag says which Hindi dub, not which show, so it is no
     * evidence for or against a match and must not cost a candidate its title score.
     */
    fun cleanTitle(title: String): String = title
        .replace(DUB_SUFFIX, "")
        .replace(CHANNEL_TAG, " ")
        .replace(WHITESPACE, " ")
        .trim()

    /**
     * A search query with its accents taken off. The site's search is accent-sensitive one way:
     * "Pokémon" finds only "Pokemon (CN)" and never "Pokemon (HUNGAMA)", while "shippuden" does
     * find "Naruto Shippūden" — so the plain-letter query is never worse.
     */
    fun searchable(query: String): String =
        Normalizer.normalize(query, Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

    /** Whether [title] carried a channel tag or fan-edit suffix; such a copy loses ties to the plain one. */
    fun isChannelVariant(title: String): Boolean = cleanTitle(title) != title.replace(WHITESPACE, " ").trim()

    /**
     * The season number an AniList entry's names claim, or null.
     *
     * [NativeProviderParsers.seasonHint] reads a bare trailing number as a season ("Gintama 2"),
     * which is right for those sites and wrong for "Attack on Titan: The Final Season Part 2": that
     * is AoT's fourth season, and reading it as the second played Season 2's episodes under it. A
     * part or cour number is a split of a season, never a season, so it is dropped first.
     */
    fun seasonHint(titles: List<String>): Int? =
        NativeProviderParsers.seasonHint(titles.map { it.replace(PART_SUFFIX, "").trim() })

    /**
     * Which kartoons seasons hold the AniList entry being shown.
     *
     * The rules run strongest first, and every one of them can refuse — a refusal costs one server
     * in the picker, a wrong pick plays another season's episodes under this one's numbers:
     *
     * 1. **Arc title.** Kartoons names seasons after arcs ("Entertainment District", "Swordsmith
     *    Village"), which AniList puts in the entry's own name. Demon Slayer has three seasons of 11
     *    and years that disagree with the broadcast, so this is the only rule that can tell them
     *    apart. A named season whose length cannot be this entry refuses outright rather than
     *    falling through to the count rules: it has said which season it is.
     * 2. **Whole series.** Every season, numbered 1..N without gaps, adding up to the entry: Naruto's
     *    220 is four seasons of 52/52/54/62. Or adding up to less, opening in the entry's own year:
     *    the whole series still being uploaded (Shippūden's 377 of 500).
     * 3. **Season hint.** "Season 2" is season 2 or nothing. When the entry's base name is the show's
     *    name the hint is trusted over the year (JJK season 2 says 2020); when it is not — "Tokyo
     *    Ghoul:re 2nd Season" counts within ":re" — the year must agree as well. An entry named
     *    exactly as the show is its season 1.
     * 4. **Count and year**, through [pickSeason], the rule [TmdbClient] uses. Its [farYearSeason]
     *    fallback is deliberately not used: it is what took AoT's 2017 season 2 for 2022's twelve
     *    episodes, and a fallback that costs TmdbClient a wrong still costs this a wrong episode.
     * 5. **A one-season show still being uploaded**, opening in the entry's own year.
     */
    fun planSeasons(show: Show, wanted: Wanted): KartoonsSeasonPlan {
        val numbered = show.seasons
            .filter { it.seasonNumber > 0 && it.episodeCount > 0 }
            .sortedBy { it.seasonNumber }
        if (numbered.isEmpty()) return KartoonsSeasonPlan.None
        // A series that began after this entry aired cannot hold it: "My Hero Academia: Vigilantes"
        // (2025) is not My Hero Academia (2016), whatever its first season's length.
        if (show.startYear != null && wanted.year != null && show.startYear > wanted.year + YEAR_SLACK) {
            return KartoonsSeasonPlan.None
        }

        arcSeason(show, numbered, wanted)?.let { return it }

        val explicitHint = wanted.seasonHint
        val first = numbered.first()
        val openingYear = first.releaseYear ?: show.startYear
        runFrom(contiguousFromOne(numbered), explicitHint, wanted, openingYear)?.let { return it }

        // An entry named exactly as the show, with no season in its name, is the show's first
        // season. Left to the count rules, AniList's "SPY x FAMILY" (12 episodes, 2022) took
        // kartoons' season 2 (12, 2023) through the year's one-year slack, because kartoons' season
        // 1 is both 2022 cours merged into 25.
        val hint = explicitHint
            ?: 1.takeIf { wanted.titles.any { nameKey(it) == showNameOf(show) } }
        if (hint != null) {
            val season = numbered.firstOrNull { it.seasonNumber == hint } ?: return KartoonsSeasonPlan.None
            val yearAgrees = when {
                // Named by its own number within this show: the number outranks a wrong year.
                explicitHint != null && hintNamesThisShow(show, wanted) -> true
                // A number counted within a sub-series: only the year can confirm it.
                explicitHint != null -> yearIsKnownAndNear(season.releaseYear, wanted.year)
                // The show's own name: a remake shares it ("Hunter x Hunter" 1999 and 2011), so a
                // year that is known has to agree.
                else -> yearIsNear(season.releaseYear ?: show.startYear, wanted.year)
            }
            if (!yearAgrees) return KartoonsSeasonPlan.None
            return lengthPlan(season, wanted.episodes)
        }

        val asTmdb = numbered.map { season ->
            TmdbSeasonSummary(
                season_number = season.seasonNumber,
                episode_count = season.episodeCount,
                // Year precision is all kartoons has, and all pickSeason's year rules read.
                air_date = season.releaseYear?.let { year -> "%04d-01-01".format(year) },
            )
        }
        pickSeason(asTmdb, wanted.episodes, wanted.year)?.let { picked ->
            numbered.firstOrNull { it.seasonNumber == picked.season_number }
                ?.let { return KartoonsSeasonPlan.Single(it, partial = false) }
        }

        // A one-season show still being uploaded, under a name of its own ("Black Clover" is caught
        // by the first-season rule above; a renamed one is not). Same year rule as the partial run.
        if (wanted.episodes != null && numbered.size == 1 && first.seasonNumber == 1 &&
            first.episodeCount < wanted.episodes && yearIsKnownAndNear(openingYear, wanted.year)
        ) {
            return KartoonsSeasonPlan.Single(first, partial = true)
        }
        return KartoonsSeasonPlan.None
    }

    /**
     * Rule 2 of [planSeasons]: the series from its first season on, as far as it is unbroken.
     *
     * Only the gap-free start counts. Dragon Ball Z lists seasons 1, 4, 5 and 6, and joining them
     * would number season 4's first episode 28; Pokémon (HUNGAMA) runs 1–20 and then 23, which
     * leaves its first twenty usable. Of that start, the seasons that add up to exactly the entry
     * are the whole series (Naruto's 52+52+54+62 = 220). Failing that, the longest stretch that
     * still fits inside the entry is its beginning, still being uploaded or split differently —
     * which needs a year that is actually known to agree, because "fewer episodes than the entry"
     * is otherwise true of almost any show.
     */
    private fun runFrom(start: List<Season>, explicitHint: Int?, wanted: Wanted, openingYear: Int?): KartoonsSeasonPlan? {
        val episodes = wanted.episodes ?: return null
        if (explicitHint != null && explicitHint != 1) return null
        var sum = 0
        val fitting = start.takeWhile { season -> (sum + season.episodeCount <= episodes).also { if (it) sum += season.episodeCount } }
        if (fitting.size < 2) return null
        if (sum == episodes && yearIsNear(openingYear, wanted.year)) return KartoonsSeasonPlan.Run(fitting, partial = false)
        if (sum < episodes && yearIsKnownAndNear(openingYear, wanted.year)) return KartoonsSeasonPlan.Run(fitting, partial = true)
        return null
    }

    private fun contiguousFromOne(numbered: List<Season>): List<Season> {
        if (numbered.firstOrNull()?.seasonNumber != 1) return emptyList()
        var expected = 1
        return numbered.takeWhile { (it.seasonNumber == expected).also { expected++ } }
    }

    private fun showNameOf(show: Show): String = nameKey(cleanTitle(show.title))

    /** A name reduced for comparison: accents folded first, so "Pokémon" is "pokemon" and not "pokmon". */
    private fun nameKey(name: String): String = NativeProviderParsers.normalize(searchable(name))

    /** Rule 1 of [planSeasons]: null when no season's name is in the entry's, else the answer. */
    private fun arcSeason(show: Show, numbered: List<Season>, wanted: Wanted): KartoonsSeasonPlan? {
        val entryNames = wanted.titles.map(::nameKey).filter { it.isNotBlank() }
        if (entryNames.isEmpty()) return null
        val showName = showNameOf(show)
        val named = numbered.mapNotNull { season ->
            val name = nameKey(season.title.orEmpty())
            val generic = name.length < MIN_ARC_NAME || GENERIC_SEASON_NAME.matches(name) || name == showName
            if (generic || entryNames.none { it.contains(name) }) null else season to name.length
        }
        if (named.isEmpty()) return null
        val longest = named.maxOf { it.second }
        val best = named.filter { it.second == longest }
        // Two seasons claiming the same name is the data contradicting itself; nothing to go on.
        val season = best.singleOrNull()?.first ?: return KartoonsSeasonPlan.None
        return lengthPlan(season, wanted.episodes)
    }

    private fun lengthPlan(season: Season, wantedEpisodes: Int?): KartoonsSeasonPlan = when {
        wantedEpisodes == null || season.episodeCount == wantedEpisodes -> KartoonsSeasonPlan.Single(season, partial = false)
        // Still being uploaded: everything it has is this entry's, numbered from the start.
        season.episodeCount < wantedEpisodes -> KartoonsSeasonPlan.Single(season, partial = true)
        // Longer than the entry: a season TMDB merged from several AniList entries (AoT's final
        // season of 30 holds three of them). Which part is this one is not knowable from here.
        else -> KartoonsSeasonPlan.None
    }

    /**
     * Whether the entry's season number counts within this show, rather than within a sub-series
     * whose name merely starts the same way: "JUJUTSU KAISEN Season 2" reduced to its series is
     * "JUJUTSU KAISEN", which is the show; "Tokyo Ghoul:re 2nd Season" reduces to "Tokyo Ghoul:re",
     * which is not "Tokyo Ghoul".
     */
    private fun hintNamesThisShow(show: Show, wanted: Wanted): Boolean {
        val showName = showNameOf(show)
        return wanted.titles.any { title ->
            val base = NativeProviderParsers.baseSeriesTitle(title.replace(PART_SUFFIX, "").trim())
            nameKey(base) == showName
        }
    }

    /**
     * How a season numbers its episodes: [offset] is what the site's numbers are ahead of the
     * season's own 1-based ones (0 for a season that restarts), and [local] the episodes renumbered
     * from 1, with anything that does not belong to the season dropped.
     */
    internal data class Numbering(val offset: Int, val local: Map<Int, String>)

    /** [numbering]'s episodes alone. */
    fun localNumbers(episodes: Map<Int, String>, claimedCount: Int, offsets: Collection<Int>): Map<Int, String> =
        numbering(episodes, claimedCount, offsets).local

    /**
     * Works out a season's numbering, whichever way the site numbered it.
     *
     * Each candidate window is a place the season could start: 0 for a season that restarts, and
     * each of [offsets] — where it would start if the show numbers straight through (the earlier
     * seasons' claimed counts, and in a run also how far the run has actually reached). The window
     * holding most of the season's episodes wins and the rest are dropped as strays: Naruto
     * Shippūden's season 8 lists 152–175 *and* seventeen of season 4's episodes again as 72–88, and
     * reading 72 as its start numbered the whole season, and everything after it, wrongly.
     *
     * When no window holds half the season but most of it numbers past the season's own length,
     * it is a straight-through season with nothing before it to measure from — Black Butler's
     * catalogue begins at its season 4 (47–57) — and it is shifted to start at its first such
     * episode. Otherwise it is a restart-numbered season with gaps, and keeps its numbers.
     */
    fun numbering(episodes: Map<Int, String>, claimedCount: Int, offsets: Collection<Int>): Numbering {
        if (episodes.isEmpty()) return Numbering(0, episodes)
        val slack = maxOf(MIN_NUMBERING_SLACK, claimedCount / 10)
        val span = claimedCount + slack
        fun inWindow(offset: Int) = episodes.keys.count { it in (offset + 1)..(offset + span) }
        val windows = (listOf(0) + offsets.filter { it > 0 }).distinct()
        // Ties go to the earlier window, so a restart-numbered season is never read as shifted.
        val best = windows.maxWith(compareBy<Int> { inWindow(it) }.thenByDescending { windows.indexOf(it) })
        if (inWindow(best) * 2 >= episodes.size) return Numbering(best, window(episodes, best, span))
        val beyond = episodes.keys.filter { it > span }
        if (beyond.size * 2 > episodes.size) {
            val offset = beyond.min() - 1
            return Numbering(offset, window(episodes, offset, span))
        }
        return Numbering(0, episodes)
    }

    private fun window(episodes: Map<Int, String>, offset: Int, span: Int): Map<Int, String> =
        episodes.entries
            .filter { it.key in (offset + 1)..(offset + span) }
            .associate { (it.key - offset) to it.value }

    /**
     * A single season's episodes in the app's numbering.
     *
     * The season's place in the show is measured from the seasons kartoons lists before it, which
     * is what an absolute numbering continues from (JJK season 2 starts at 25 after a season of 24).
     */
    fun layoutSingle(show: Show, season: Season, episodes: Map<Int, String>): Map<Int, String> {
        val before = show.seasons
            .filter { it.seasonNumber in 1 until season.seasonNumber }
            .sumOf { it.episodeCount }
        return localNumbers(episodes, season.episodeCount, listOf(before))
    }

    /**
     * A whole-series run in the app's numbering.
     *
     * A season numbered straight through is placed by the site's own numbers, which already count
     * across the run: they cannot drift, where a sum of claimed counts can — Shippūden's season 8
     * claims 41 for its 24 episodes because the claim counts seventeen strays.
     *
     * A restart-numbered season is placed after everything before it, occupying the larger of its
     * claimed count and its highest episode: the claim covers episodes not uploaded yet, and the
     * highest episode covers a claim that undercounts — Pokémon's first season claims 80 and runs
     * to 82, and laying the second after 80 would put every later episode two places early.
     *
     * Numbers past [cap] (the AniList entry's length) are dropped — AniList has no episode there to
     * put them under.
     */
    fun layoutRun(seasons: List<Pair<Season, Map<Int, String>>>, cap: Int?): Map<Int, String> {
        val laidOut = LinkedHashMap<Int, String>()
        var position = 0
        var claimed = 0
        for ((season, episodes) in seasons) {
            val numbering = numbering(episodes, season.episodeCount, listOf(position, claimed))
            val highest = numbering.local.keys.maxOrNull() ?: 0
            if (numbering.offset > 0) {
                for ((number, id) in numbering.local) laidOut.putIfAbsent(numbering.offset + number, id)
                position = maxOf(position, numbering.offset + highest)
            } else {
                for ((number, id) in numbering.local) laidOut.putIfAbsent(position + number, id)
                position += maxOf(season.episodeCount, highest)
            }
            claimed += season.episodeCount
        }
        return if (cap == null) laidOut else laidOut.filterKeys { it <= cap }
    }

    /**
     * Odelu 1 ahead of Odelu 2. Odelu 1 is the direct HLS re-host whose chain was measured playing
     * header-free; Odelu 2's origin is an embed player page, so it stays in the picker as a manual
     * fallback. Any other server name follows, by the number in its name if it has one. The payload
     * order is not stable — it listed Odelu 2 first on 2026-10-03.
     */
    fun orderLinks(links: List<Link>): List<Link> = links.sortedWith(
        compareBy({ trailingNumber(it.name) ?: Integer.MAX_VALUE }, { it.name }, { it.url }),
    )

    /**
     * Whether a 401/403 is the API refusing the account token, rather than something in front of
     * the API refusing the request.
     *
     * The API answers a bad token in JSON — `{"success":false,"message":"Authentication
     * required","auth_required":true}`. A Cloudflare challenge or an ISP block page is HTML, and
     * says nothing about the token: cooling the token for it would let one viewer's VPN burn the
     * whole pool on their device for the next two days.
     */
    fun isTokenRefusal(status: Int, body: String): Boolean =
        (status == 401 || status == 403) && body.trimStart().startsWith("{")

    fun namesBan(body: String): Boolean = body.contains("banned", ignoreCase = true)

    /**
     * A request path safe to put in an exception message. The links path carries the account token
     * as its query, and exception messages land in diagnostics bundles that leave the device.
     */
    fun redact(path: String): String = path.substringBefore('?')

    private fun trailingNumber(name: String): Int? =
        TRAILING_NUMBER.find(name.trim())?.groupValues?.get(1)?.toIntOrNull()

    private fun yearIsNear(year: Int?, wanted: Int?): Boolean =
        year == null || wanted == null || abs(year - wanted) <= YEAR_SLACK

    private fun yearIsKnownAndNear(year: Int?, wanted: Int?): Boolean =
        year != null && wanted != null && abs(year - wanted) <= YEAR_SLACK

    /** One year either way: AniList files by announced season, kartoons by first episode. */
    private const val YEAR_SLACK = 1
    /** Below this a season name is too short to mean anything inside another title. */
    private const val MIN_ARC_NAME = 6
    private const val MIN_NUMBERING_SLACK = 2

    private val CHANNEL_TAG = Regex("""\s*[(\[][^()\[\]]{1,24}[)\]]\s*""")
    private val DUB_SUFFIX = Regex("""\s+[-–—|]\s+.*\b(?:hindi|dub|fan\s*edit)\b.*$""", RegexOption.IGNORE_CASE)
    private val PART_SUFFIX = Regex("""[\s:-]*\b(?:part|cour)\s*\d+\s*$""", RegexOption.IGNORE_CASE)
    private val GENERIC_SEASON_NAME = Regex("""season\d*|specials?|\d+""")
    private val WHITESPACE = Regex("""\s+""")
    private val TRAILING_NUMBER = Regex("""(\d+)\s*$""")
}

/** The season plan [KartoonsParser.planSeasons] worked out for one AniList entry. */
internal sealed interface KartoonsSeasonPlan {
    /** One kartoons season holds the entry. [partial]: it is still being uploaded. */
    data class Single(val season: KartoonsParser.Season, val partial: Boolean) : KartoonsSeasonPlan

    /**
     * The entry is the show's seasons concatenated. [partial]: they hold the start of the entry
     * but not all of it yet.
     */
    data class Run(val seasons: List<KartoonsParser.Season>, val partial: Boolean) : KartoonsSeasonPlan

    /** No season can be shown to be this entry; the provider declines the title. */
    data object None : KartoonsSeasonPlan
}
