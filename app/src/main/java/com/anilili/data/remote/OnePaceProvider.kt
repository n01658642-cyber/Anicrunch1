package com.anilili.data.remote

import com.anilili.data.cache.AppCache
import com.anilili.data.model.CoverImage
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodeMetadata
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import com.anilili.data.model.ProviderData
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One Pace (onepace.net) — the fan recut of One Piece, offered as a title of its own.
 *
 * It is not on AniList, so it rides the [Media] rails under one reserved negative id, the way
 * hanime's catalogue-native entries do, and like them it never syncs to an account.
 *
 * Its episodes are numbered as one flat list in the site's own order (Romance Dawn 01 is 1), with
 * the arc named in each episode title. The consequence worth knowing: One Pace sometimes inserts an
 * arc or an episode mid-run, and everything after the insertion then moves up a number, so saved
 * progress past that point points one episode early.
 *
 * ```
 * GET https://onepace.net/en/watch   (RSC: 1)  -> React flight payload, ~480KB     (~0.5s)
 * GET https://pixeldrain.net/api/file/<id>     -> the episode itself, video/mp4
 * ```
 *
 * The flight payload is the same data the page renders, as JSON rather than 3.4MB of HTML, which
 * matters on a TV (see [[tv-catalogue-parse-cost]]). Every episode is an H.264/AAC MP4 on
 * pixeldrain with English subtitles burned in, at 480/720/1080p. Measured 2026-09-24: 206 on a
 * Range request, `Access-Control-Allow-Origin: *`, no Referer or cookie needed, and served from
 * One Pace's paid account (`show_ads:false`, no speed limit) — so [StreamItem.referer] is null for
 * the reason [AnimeHeavenProvider] gives, and the files cast and download without the proxy.
 *
 * The site's release index (`/en/releases`, `rss.xml`) carries different files: HEVC MKV masters
 * with both audio tracks and soft ASS subtitles in up to nine languages. They are deliberately not
 * used — HEVC is what the older TV boxes cannot decode, Media3 drops the ASS styling One Pace's
 * typesetting depends on, and only about a third of them are on pixeldrain at all.
 */
internal class OnePaceProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val cache: AppCache,
) {
    /**
     * The whole library, flat and in watch order. A day-old copy is served while a fresh one is
     * fetched behind it, and a month-old one still answers when the site cannot — the list only
     * ever grows, so a stale copy is short, not wrong.
     */
    suspend fun catalogue(force: Boolean = false): List<OnePaceEpisode> = cache.getOrFetch(
        key = CACHE_KEY,
        serializer = ListSerializer(OnePaceEpisode.serializer()),
        ttlMs = CATALOGUE_TTL_MS,
        staleForMs = CATALOGUE_STALE_MS,
        forceRefresh = force,
        cacheIf = { it.isNotEmpty() },
    ) { runInterruptible(Dispatchers.IO) { download() } }

    suspend fun media(): Media = onePaceMedia(catalogue().size)

    suspend fun episodes(): EpisodesResult {
        val episodes = catalogue()
        fun rows(category: OnePaceAudio): List<EpisodeItem> = episodes.mapIndexedNotNull { index, episode ->
            if (OnePaceParser.groupFor(episode, category) == null) return@mapIndexedNotNull null
            val number = index + 1
            EpisodeItem(
                pipeId = "watch/$PROVIDER/$ONE_PACE_MEDIA_ID/${category.audio}/$PROVIDER-$number",
                number = number.toDouble(),
                title = episode.displayTitle,
                image = episode.still,
                filler = false,
                overview = episode.description,
                runtime = episode.runtimeMinutes,
            )
        }
        return EpisodesResult(
            listOf(ProviderData(PROVIDER, sub = rows(OnePaceAudio.SUB), dub = rows(OnePaceAudio.DUB))),
        )
    }

    suspend fun metadata(): List<EpisodeMetadata> = catalogue().mapIndexed { index, episode ->
        EpisodeMetadata(
            number = (index + 1).toDouble(),
            title = episode.displayTitle,
            still = episode.still,
            overview = episode.description,
            runtime = episode.runtimeMinutes,
        )
    }

    suspend fun sources(audio: String, episode: Int): SourcesResult {
        val entry = catalogue().getOrNull(episode - 1) ?: error("One Pace has no episode $episode")
        val category = if (audio.equals("dub", ignoreCase = true)) OnePaceAudio.DUB else OnePaceAudio.SUB
        val group = OnePaceParser.groupFor(entry, category)
            ?: error("One Pace has no ${category.audio} release of ${entry.displayTitle}")
        return SourcesResult(OnePaceParser.streams(group), emptyList(), null, null)
    }

    private fun download(): List<OnePaceEpisode> {
        val request = Request.Builder()
            .url(WATCH_URL)
            // Asks Next.js for the flight payload instead of the rendered page.
            .header("RSC", "1")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("One Pace watch page HTTP ${response.code}")
            val parsed = OnePaceParser.parseTimeline(json, response.body?.string().orEmpty())
            if (parsed.isEmpty()) error("One Pace watch page carried no episodes")
            DiagnosticsLog.event("One Pace catalogue downloaded episodes=${parsed.size}")
            return parsed
        }
    }

    companion object {
        const val PROVIDER = "onepace"
        private const val WATCH_URL = "https://onepace.net/en/watch"
        private const val CACHE_KEY = "onepace-catalogue:v1"
        private const val CATALOGUE_TTL_MS = 24L * 60 * 60 * 1000
        private const val CATALOGUE_STALE_MS = 30L * 24 * 60 * 60 * 1000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/** One release of one episode: an audio/subtitle combination, in every resolution it comes in. */
@Serializable
data class OnePaceFileGroup(
    val sub: String? = null,
    val dub: String? = null,
    /** English dub with English closed captions burned in; offered only when no plain dub exists. */
    val closedCaptions: Boolean = false,
    /** Null for the regular cut; `extended` and `alternate_*` are alternative edits. */
    val variant: String? = null,
    val files: List<OnePaceFile> = emptyList(),
)

@Serializable
data class OnePaceFile(val id: String, val resolution: Int? = null)

@Serializable
data class OnePaceEpisode(
    val slug: String,
    val arc: String,
    val part: Int,
    val title: String? = null,
    val description: String? = null,
    val duration: String? = null,
    val still: String? = null,
    val special: Boolean = false,
    /** Empty for a slot the site lists but has no individual release of (Wano 63-70 today). */
    val groups: List<OnePaceFileGroup> = emptyList(),
) {
    /**
     * "Alabasta 05: Title", since a flat list has nowhere else to say which arc a row is in.
     *
     * Unreleased slots are titled "Wano 63" by the site itself, which would print the arc twice.
     */
    val displayTitle: String get() {
        val label = if (special && part <= 1) arc else "$arc ${part.toString().padStart(2, '0')}"
        val own = title?.trim()?.takeIf(String::isNotEmpty) ?: return label
        return when {
            own.equals(label, ignoreCase = true) || own.equals("$arc $part", ignoreCase = true) -> label
            own.equals(arc, ignoreCase = true) -> label
            else -> "$label: $own"
        }
    }

    /** "17:57" or "1:02:03", rounded to whole minutes. */
    val runtimeMinutes: Int? get() {
        val parts = duration?.split(':')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
        val seconds = parts.fold(0) { total, part -> total * 60 + part }
        return ((seconds + 30) / 60).takeIf { it > 0 }
    }
}

internal enum class OnePaceAudio(val audio: String) { SUB("sub"), DUB("dub") }

/** Pure payload handling for [OnePaceProvider], kept network-free for unit tests. */
internal object OnePaceParser {
    private const val ORIGIN = "https://onepace.net"

    /**
     * Every episode on the watch page, in its order, out of the React flight payload.
     *
     * The payload is one row per line, `<id>:<value>`, and the timeline is a JSON row holding
     * `{"data":{"timeline":{"segments":[arc…]}}}`. Rather than depend on that path, which is only
     * the current page's component tree, this finds every object shaped like an arc — a `slug` and
     * a `parts` array — wherever it sits. Scanned with `indexOf` rather than a regex, since a
     * `Regex` over half a megabyte is what a TV cannot afford ([[android-regex-is-icu]]).
     */
    fun parseTimeline(json: Json, payload: String): List<OnePaceEpisode> {
        val episodes = mutableListOf<OnePaceEpisode>()
        var start = 0
        while (start < payload.length) {
            val end = payload.indexOf('\n', start).let { if (it < 0) payload.length else it }
            val colon = payload.indexOf(':', start)
            // Only JSON rows can hold the timeline; `T` rows are raw text and `I` rows are imports.
            val isJsonRow = colon in start until end - 1 && payload[colon + 1].let { it == '[' || it == '{' }
            if (isJsonRow && payload.indexOf("\"fileGroups\"", colon).let { it in colon until end }) {
                runCatching { json.parseToJsonElement(payload.substring(colon + 1, end)) }
                    .onFailure { DiagnosticsLog.throwable("One Pace flight row unparseable", it) }
                    .getOrNull()
                    ?.let { collectArcs(it, episodes) }
            }
            start = end + 1
        }
        return episodes
    }

    private fun collectArcs(element: JsonElement, into: MutableList<OnePaceEpisode>) {
        when (element) {
            is JsonObject -> {
                val parts = element["parts"] as? JsonArray
                val slug = element.text("slug")
                if (parts != null && slug != null) {
                    val arc = element.text("title") ?: slug
                    val special = (element["special"] as? JsonPrimitive)?.booleanOrNull == true
                    parts.forEach { part -> (part as? JsonObject)?.let { episode(it, arc, special) }?.let(into::add) }
                } else {
                    element.values.forEach { collectArcs(it, into) }
                }
            }
            is JsonArray -> element.forEach { collectArcs(it, into) }
            else -> Unit
        }
    }

    private fun episode(part: JsonObject, arc: String, special: Boolean): OnePaceEpisode? {
        val slug = part.text("slug") ?: return null
        val still = (part["stills"] as? JsonArray)
            ?.firstNotNullOfOrNull { (it as? JsonObject)?.text("src") }
            ?.let { if (it.startsWith("/")) ORIGIN + it else it }
        val groups = (part["fileGroups"] as? JsonArray).orEmpty().mapNotNull { group ->
            group as? JsonObject ?: return@mapNotNull null
            val files = (group["items"] as? JsonArray).orEmpty().mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val id = obj.text("id") ?: return@mapNotNull null
                OnePaceFile(id, (obj["resolution"] as? JsonPrimitive)?.intOrNull)
            }
            if (files.isEmpty()) return@mapNotNull null
            OnePaceFileGroup(
                sub = group.text("sub"),
                dub = group.text("dub"),
                closedCaptions = group.text("sub_accessibility") != null,
                variant = group.text("variant"),
                files = files,
            )
        }
        return OnePaceEpisode(
            slug = slug,
            arc = arc,
            part = (part["part"] as? JsonPrimitive)?.intOrNull ?: 1,
            title = part.text("title"),
            description = part.text("description"),
            duration = part.text("duration"),
            still = still,
            special = special,
            groups = groups,
        )
    }

    /**
     * The release to play for [category]: Japanese audio for sub, English audio for dub.
     *
     * The regular cut is preferred over an `extended` or `alternate` one, which are extras the site
     * lists beside it rather than replacements. A dub falls back to the closed-captioned dub only
     * when there is no plain one, because its captions are burned in and cannot be turned off.
     */
    fun groupFor(episode: OnePaceEpisode, category: OnePaceAudio): OnePaceFileGroup? {
        val matching = episode.groups.filter { group ->
            when (category) {
                OnePaceAudio.SUB -> group.dub == null || group.dub == "ja"
                OnePaceAudio.DUB -> group.dub == "en"
            }
        }
        return matching.sortedWith(compareBy({ it.closedCaptions }, { it.variant != null })).firstOrNull()
    }

    /** Sharpest first — the player takes the head of the list as its default. */
    fun streams(group: OnePaceFileGroup): List<StreamItem> = group.files
        .sortedByDescending { it.resolution ?: 0 }
        .mapIndexed { index, file ->
            StreamItem(
                url = "https://pixeldrain.net/api/file/${file.id}",
                type = "mp4",
                quality = file.resolution?.let { "${it}p" } ?: "One Pace",
                audio = null,
                referer = null,
                isActive = index == 0,
                width = null,
                height = file.resolution,
            )
        }

    /**
     * A string field, with the flight format's markers undone: `$undefined` is an absent value and
     * a literal leading `$` is escaped as `$$`. Any other `$…` is a reference to another row, which
     * no field read here should be, so it counts as absent rather than being shown.
     */
    private fun JsonObject.text(name: String): String? {
        val raw = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        return when {
            raw.startsWith("$$") -> raw.substring(1)
            raw.startsWith("$") -> null
            else -> raw.takeIf(String::isNotBlank)
        }
    }
}

/**
 * The id One Pace lives under. Negative, so no AniList id can collide with it, and far outside
 * hanime's namespace (the negated hanime video id, a few thousand at most).
 */
const val ONE_PACE_MEDIA_ID = -1_000_000_000

fun isOnePaceMediaId(id: Int): Boolean = id == ONE_PACE_MEDIA_ID

/**
 * Whether search [query] is asking for One Pace: its own name, or One Piece's, which is how most
 * people who want it will look. A short prefix of either is enough once it is unambiguous.
 */
fun matchesOnePaceQuery(query: String): Boolean {
    val wanted = normalizeHanimeTitle(query).replace(" ", "")
    if (wanted.length < 4) return false
    return listOf("onepace", "onepiece").any { name -> name.startsWith(wanted) || wanted.contains(name) }
}

/**
 * One Pace as a [Media]. The artwork is One Piece's from AniList: onepace.net's own images live at
 * hashed Next.js paths that change with every deploy, and a library row keeps its cover URL.
 */
fun onePaceMedia(episodeCount: Int?): Media = Media(
    id = ONE_PACE_MEDIA_ID,
    title = MediaTitle(romaji = "One Pace", english = "One Pace", userPreferred = "One Pace"),
    description = "One Pace is a fan project that recuts the One Piece anime to bring it more in " +
        "line with the pacing of Eiichiro Oda's manga: filler scenes not in the source material " +
        "are removed, animation errors fixed and subtitles corrected.<br><br>" +
        "Episodes are listed in watch order, arc by arc. From onepace.net.",
    coverImage = CoverImage(
        large = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21-ELSYx3yMPcKM.jpg",
        extraLarge = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21-ELSYx3yMPcKM.jpg",
        color = "#e49335",
    ),
    bannerImage = "https://s4.anilist.co/file/anilistcdn/media/anime/banner/21-wf37VakJmZqs.jpg",
    format = "TV",
    status = "RELEASING",
    episodes = episodeCount?.takeIf { it > 0 },
    seasonYear = 2013,
    countryOfOrigin = "JP",
    genres = listOf("Action", "Adventure", "Comedy", "Drama", "Fantasy"),
)
