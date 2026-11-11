package com.anilili.data

import android.content.Context
import com.anilili.data.model.CoverImage
import com.anilili.data.model.DiscoverFilters
import com.anilili.data.model.HomeCollections
import com.anilili.data.model.Media
import com.anilili.data.model.MediaPage
import com.anilili.data.model.MediaTitle
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence

/**
 * One title in the catalogue snapshot that ships in the APK.
 *
 * Deliberately a separate type from [Media]: the snapshot is a flat row, and mapping it explicitly
 * is what keeps the difference between "what AniList knows" and "what we shipped" visible at the
 * boundary rather than buried in optional fields.
 */
@Serializable
data class LocalCatalogEntry(
    val id: Int,
    val title: String,
    val poster: String? = null,
    val poster_color: String? = null,
    val year: Int? = null,
    val status: String? = null,
    val format: String? = null,
    val episodes: Int? = null,
    val score: Int? = null,
    val popularity: Int? = null,
    val genres: List<String> = emptyList(),
) {
    /**
     * The snapshot carries no `isAdult` field, but AniList's adult flag and its Hentai genre mark
     * the same titles, so the genre stands in for it. Without this every snapshot row read as
     * safe, and the 1,600-odd hentai titles in it reached search and its suggestions with adult
     * content turned off.
     */
    val isAdult: Boolean get() = ADULT_GENRE in genres

    fun toMedia(): Media = Media(
        id = id,
        title = MediaTitle(romaji = title, english = title, userPreferred = title),
        coverImage = CoverImage(large = poster, color = poster_color),
        format = format,
        seasonYear = year,
        episodes = episodes,
        status = status,
        averageScore = score,
        popularity = popularity,
        genres = genres,
        isAdult = isAdult,
    )
}

private const val ADULT_GENRE = "Hentai"

/**
 * The catalogue snapshot bundled with the app, used when AniList cannot be reached.
 *
 * AniList is the metadata backbone for every browse and search screen, so the day it returned 403
 * to everyone ("temporarily disabled due to severe stability issues", 2 Aug 2026) the app had
 * nothing to show a viewer whose cache was cold — a blank home and a dead search, on a device with
 * a 4 MB catalogue of ten thousand titles sitting unread in its own APK.
 *
 * This is a floor, not a replacement. The snapshot is fixed at release time, so it cannot know what
 * aired this week and its scores drift; anything live still has to come from AniList. What it can
 * always do is let someone browse, search, and reach a title they already watch.
 */
@OptIn(ExperimentalSerializationApi::class)
class LocalCatalog(private val context: Context, private val json: Json) {
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Parsed once, on first use — 4 MB of JSON is not worth touching during startup.
     *
     * The parse belongs to a scope of its own rather than to whoever asked first, because the
     * search box now asks for it on the way to [titleSuggestions]: every keystroke cancels the
     * previous lookup, and a caller cancelled mid-parse would otherwise discard a finished parse
     * on its way out — leaving the next keystroke to start the whole thing again. On a TV box,
     * where this costs seconds, typing could restart it indefinitely and never once get an answer.
     * Owned here, a keystroke only abandons the *wait*; the parse runs to completion and is kept.
     */
    private val parsed: Deferred<List<LocalCatalogEntry>> by lazy {
        loadScope.async {
            val startedMs = System.currentTimeMillis()
            val loaded = runCatching {
                // Streamed rather than read into a String first: the asset is over 4 MB, and
                // materialising it whole costs a second allocation of that size on devices
                // already tight enough to have sent us heap crashes.
                // A copy fetched by Settings → Re-download metadata when there is one. That copy
                // is the tree's full index, so it gets the trim the bundled one had at build
                // time (`konoha_build.py assets`): music videos are not something to stream.
                MetadataOverrides.read(context, CATALOG_ASSET) { stream ->
                    json.decodeToSequence(
                        stream,
                        LocalCatalogEntry.serializer(),
                        DecodeSequenceMode.ARRAY_WRAPPED,
                    ).filter { it.format !in BUNDLE_DROPPED_FORMATS }.toList()
                }
            }.getOrElse { error ->
                DiagnosticsLog.throwable("LocalCatalog failed to read $CATALOG_ASSET", error)
                emptyList()
            }
            DiagnosticsLog.event(
                "LocalCatalog loaded entries=${loaded.size} in ${System.currentTimeMillis() - startedMs}ms",
            )
            loaded
        }
    }

    private suspend fun entries(): List<LocalCatalogEntry> = parsed.await()

    /**
     * Built on the first search that comes back empty, not with the snapshot: most sessions never
     * misspell anything, and a viewer who does can wait the once for it. Owned by [loadScope] for
     * the reason [parsed] is — a keystroke that cancels its lookup must not discard the build.
     */
    private val speller: Deferred<TitleSpeller> by lazy {
        loadScope.async {
            val startedMs = System.currentTimeMillis()
            TitleSpeller(parsed.await()).also {
                DiagnosticsLog.event("TitleSpeller built in ${System.currentTimeMillis() - startedMs}ms")
            }
        }
    }

    private suspend fun entries(hideAdult: Boolean): List<LocalCatalogEntry> =
        entries().let { all -> if (hideAdult) all.filterNot { it.isAdult } else all }

    suspend fun isEmpty(): Boolean = entries().isEmpty()

    /** Home rows built entirely from the snapshot. Empty when the snapshot failed to load. */
    suspend fun homeCollections(): HomeCollections = homeCollectionsFrom(entries())

    /** Title search over the snapshot, paged like the remote one so callers cannot tell them apart. */
    suspend fun search(query: String, page: Int, hideAdult: Boolean, perPage: Int = SEARCH_PAGE_SIZE): MediaPage =
        searchIn(entries(hideAdult), query, page, perPage)

    /** Browse results from the snapshot, honouring the filters it has the fields to answer. */
    suspend fun discover(
        filters: DiscoverFilters,
        page: Int,
        hideAdult: Boolean,
        perPage: Int = SEARCH_PAGE_SIZE,
    ): MediaPage = discoverIn(entries(hideAdult), filters, page, perPage)

    /** Titles to offer while the viewer is still typing; see [suggestTitlesIn]. */
    suspend fun titleSuggestions(
        query: String,
        hideAdult: Boolean,
        limit: Int = SUGGESTION_LIMIT,
    ): List<TitleSuggestion> = suggestTitlesIn(entries(hideAdult), query, limit)

    /** What a misspelled [query] most likely meant; see [TitleSpeller]. */
    suspend fun spelling(query: String, hideAdult: Boolean, limit: Int = SUGGESTION_LIMIT): TitleSpelling? {
        val speller = speller.await()
        return withContext(Dispatchers.Default) { speller.suggest(query, hideAdult, limit) }
    }

    suspend fun media(id: Int): Media? = entries().firstOrNull { it.id == id }?.toMedia()

    private companion object {
        const val CATALOG_ASSET = "index.json"
        /** Mirrors `--drop-formats` in `konoha_build.py assets`. */
        val BUNDLE_DROPPED_FORMATS = setOf("MUSIC")
        const val SEARCH_PAGE_SIZE = 30
        const val SUGGESTION_LIMIT = 8
    }
}

/**
 * One title offered under the search box as the viewer types.
 *
 * Carries an id and nothing else worth drawing: picking a suggestion opens that title outright, so
 * the list never needs a poster, and keeping it to text is what lets it answer on a TV box without
 * a single image load.
 */
data class TitleSuggestion(val id: Int, val title: String)

/**
 * Titles from the snapshot that could still become what the viewer is typing.
 *
 * This exists because AniList cannot answer a half-typed word at all. `search: "narut"` returns an
 * empty page, and `"nar"` returns *Omoide no Marnie* — it matches whole words, so a viewer entering
 * a title one letter at a time, which on a remote is the only way anyone enters one, sees nothing
 * until the word is finished. The snapshot is a plain list this app owns, so it can be asked the
 * question AniList has no answer for.
 *
 * Ranked so that what the viewer is most likely to have meant comes first: the whole title, then
 * any title with a *word* starting with what was typed, then a match buried inside a word — which
 * is where "Narutimate" sits relative to "naruto". Popularity orders each tier, so a franchise's
 * main entry leads its own spin-offs.
 *
 * Which word it is does not matter, and that is deliberate. Ranking a title that *begins* with the
 * term above one that merely contains the word reads well until it meets the catalogue: "shippu"
 * put "Shippuu! Iron Leaguer" (1993, barely watched) above Naruto: Shippuden, "slayer" led with
 * Slayers rather than Demon Slayer, and "note" opened with "Notes on Monstropedia" instead of Death
 * Note. Where the word sits in the title says far less about what someone meant than how many
 * people watch the thing does.
 *
 * Matching is case-insensitive in place rather than over lowercased copies: this runs against every
 * row in an 18,000-title snapshot on each keystroke, and `lowercase()` per row would allocate a
 * fresh string 18,000 times for every letter typed.
 */
internal fun suggestTitlesIn(
    entries: List<LocalCatalogEntry>,
    query: String,
    limit: Int = 8,
): List<TitleSuggestion> {
    val needle = query.trim()
    if (needle.length < MIN_SUGGESTION_CHARS || entries.isEmpty()) return emptyList()
    return entries.mapNotNull { entry ->
        val rank = titleMatchRank(entry.title, needle) ?: return@mapNotNull null
        rank to entry
    }.sortedWith(
        compareBy<Pair<Int, LocalCatalogEntry>> { it.first }
            .thenByDescending { it.second.popularity ?: 0 },
    ).take(limit).map { TitleSuggestion(id = it.second.id, title = it.second.title) }
}

/** Lower is a better match; null when [needle] does not appear in [title] at all. */
private fun titleMatchRank(title: String, needle: String): Int? {
    val at = title.indexOf(needle, ignoreCase = true)
    if (at < 0) return null
    if (at == 0) return if (title.length == needle.length) TITLE_IS_THE_TERM else WORD_STARTS_WITH_TERM
    var from = at
    while (from > 0) {
        if (!title[from - 1].isLetterOrDigit()) return WORD_STARTS_WITH_TERM
        from = title.indexOf(needle, from + 1, ignoreCase = true)
    }
    return TERM_IS_INSIDE_A_WORD
}

private const val TITLE_IS_THE_TERM = 0
private const val WORD_STARTS_WITH_TERM = 1
private const val TERM_IS_INSIDE_A_WORD = 2

/**
 * Below this, a suggestion list is noise rather than help: two letters already reach past nine
 * thousand of the snapshot's titles, and one reaches most of it.
 */
internal const val MIN_SUGGESTION_CHARS = 2

/** Rows for the offline home screen, ordered by the snapshot's own fields. */
internal fun homeCollectionsFrom(entries: List<LocalCatalogEntry>, rowSize: Int = 30): HomeCollections {
    if (entries.isEmpty()) return HomeCollections()
    val byPopularity = entries.sortedByDescending { it.popularity ?: 0 }
    return HomeCollections(
        // The hero row wants a handful of titles anyone would recognise.
        spotlight = byPopularity.take(rowSize / 3).map { it.toMedia() },
        // "Newest" cannot mean "airing now" from a fixed snapshot; the honest reading is the most
        // recent years it knows about, most popular first within them.
        newest = entries.sortedWith(
            compareByDescending<LocalCatalogEntry> { it.year ?: 0 }.thenByDescending { it.popularity ?: 0 },
        ).take(rowSize).map { it.toMedia() },
        popular = byPopularity.take(rowSize).map { it.toMedia() },
        movies = byPopularity.filter { it.format.equals("MOVIE", ignoreCase = true) }
            .take(rowSize).map { it.toMedia() },
        // A handful of ratings come from very few votes, so popularity breaks ties to keep obscure
        // perfect scores from filling the row.
        topRated = entries.sortedWith(
            compareByDescending<LocalCatalogEntry> { it.score ?: 0 }.thenByDescending { it.popularity ?: 0 },
        ).take(rowSize).map { it.toMedia() },
        // Only as fresh as the snapshot: some of these will have aired since it was taken.
        upcoming = byPopularity.filter { it.status.equals("NOT_YET_RELEASED", ignoreCase = true) }
            .take(rowSize).map { it.toMedia() },
    )
}

/**
 * Offline Browse.
 *
 * The snapshot carries title, genres, year, status, format, score and popularity, so those filters
 * are answered honestly. Tags, studios and the airing season it simply does not have, and silently
 * ignoring them would hand back results that contradict the filter chips on screen — so a filter the
 * snapshot cannot honour returns nothing rather than something wrong.
 *
 * Dubbed-only is the one filter the offline path answers *better* than the online one: the index
 * lives on the device, so no part of it depends on reaching AniList. [dubbed] is a parameter only so
 * tests can state their own index rather than loading the shipped asset.
 */
internal fun discoverIn(
    entries: List<LocalCatalogEntry>,
    filters: DiscoverFilters,
    page: Int,
    perPage: Int = 30,
    dubbed: (Int) -> Boolean = { DubIndex.hasDub(it) },
): MediaPage {
    val safePage = page.coerceAtLeast(1)
    val empty = MediaPage(items = emptyList(), hasNextPage = false, page = safePage)
    if (entries.isEmpty()) return empty
    if (filters.tags.isNotEmpty() || filters.studioId != null || filters.studioName != null) return empty
    if (filters.season != null) return empty

    val needle = filters.query.trim().lowercase()
    val matched = entries.filter { entry ->
        (needle.isEmpty() || entry.title.lowercase().contains(needle)) &&
            (filters.genres.isEmpty() || entry.genres.any { it in filters.genres }) &&
            (filters.year == null || entry.year == filters.year) &&
            (filters.status == null || entry.status.equals(filters.status, ignoreCase = true)) &&
            (filters.format == null || entry.format.equals(filters.format, ignoreCase = true)) &&
            (filters.minimumScore == null || (entry.score ?: 0) >= filters.minimumScore!!) &&
            (!filters.dubbedOnly || dubbed(entry.id))
    }
    val sorted = when {
        filters.sort.startsWith("SCORE") ->
            matched.sortedWith(
                compareByDescending<LocalCatalogEntry> { it.score ?: 0 }
                    .thenByDescending { it.popularity ?: 0 },
            )
        filters.sort.startsWith("START_DATE") ->
            matched.sortedWith(
                compareByDescending<LocalCatalogEntry> { it.year ?: 0 }
                    .thenByDescending { it.popularity ?: 0 },
            )
        // TRENDING has no meaning in a snapshot taken months ago; popularity is the closest
        // honest stand-in, and it is also the right answer for POPULARITY_DESC.
        else -> matched.sortedByDescending { it.popularity ?: 0 }
    }
    val from = (safePage - 1) * perPage
    if (from >= sorted.size) return empty
    val window = sorted.subList(from, minOf(from + perPage, sorted.size))
    return MediaPage(
        items = window.map { it.toMedia() },
        hasNextPage = from + perPage < sorted.size,
        page = safePage,
    )
}

/**
 * Offline title search.
 *
 * Ranked so an exact title beats a prefix and a prefix beats a word appearing anywhere, because
 * substring matching alone puts "Fullmetal Alchemist: Brotherhood" above "Naruto" for the query
 * "naruto" whenever the former is more popular. Ties break on popularity.
 */
internal fun searchIn(
    entries: List<LocalCatalogEntry>,
    query: String,
    page: Int,
    perPage: Int = 30,
): MediaPage {
    val needle = query.trim().lowercase()
    val safePage = page.coerceAtLeast(1)
    if (needle.isEmpty() || entries.isEmpty()) {
        return MediaPage(items = emptyList(), hasNextPage = false, page = safePage)
    }
    val ranked = entries.mapNotNull { entry ->
        val title = entry.title.lowercase()
        val rank = when {
            title == needle -> 0
            title.startsWith(needle) -> 1
            title.contains(needle) -> 2
            else -> return@mapNotNull null
        }
        rank to entry
    }.sortedWith(
        compareBy<Pair<Int, LocalCatalogEntry>> { it.first }
            .thenByDescending { it.second.popularity ?: 0 },
    )
    val from = (safePage - 1) * perPage
    if (from >= ranked.size) {
        return MediaPage(items = emptyList(), hasNextPage = false, page = safePage)
    }
    val window = ranked.subList(from, minOf(from + perPage, ranked.size))
    return MediaPage(
        items = window.map { it.second.toMedia() },
        hasNextPage = from + perPage < ranked.size,
        page = safePage,
    )
}
