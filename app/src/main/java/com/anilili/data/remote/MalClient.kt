package com.anilili.data.remote

import com.anilili.data.auth.MalAuthManager
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

// ---- DTOs (MAL API v2 shapes) ----

@Serializable
data class MalUser(
    val id: Int,
    val name: String,
    val picture: String? = null,
    @SerialName("joined_at") val joinedAt: String? = null,
    @SerialName("anime_statistics") val animeStatistics: MalAnimeStatistics? = null,
)

@Serializable
data class MalAnimeStatistics(
    @SerialName("num_items") val numItems: Int = 0,
    @SerialName("num_days_watched") val numDaysWatched: Double = 0.0,
    @SerialName("mean_score") val meanScore: Double = 0.0,
)

@Serializable
data class MalListStatus(
    val status: String? = null,
    val score: Int = 0,
    @SerialName("num_episodes_watched") val numEpisodesWatched: Int = 0,
    @SerialName("is_rewatching") val isRewatching: Boolean = false,
    /** Finished rewatches. MAL counts them here; the original watch is not one of them. */
    @SerialName("num_times_rewatched") val numTimesRewatched: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
    /** The entry's tags — what this app shows as custom lists on a MyAnimeList account. */
    val tags: List<String> = emptyList(),
)

@Serializable
data class MalAnimeNode(
    val id: Int,
    val title: String = "",
    @SerialName("num_episodes") val numEpisodes: Int? = null,
)

@Serializable
private data class MalListItem(val node: MalAnimeNode, @SerialName("list_status") val listStatus: MalListStatus? = null)

@Serializable
private data class MalPaging(val next: String? = null)

@Serializable
private data class MalListResponse(val data: List<MalListItem> = emptyList(), val paging: MalPaging? = null)

@Serializable
private data class MalAnimeWithStatus(
    @SerialName("num_episodes") val numEpisodes: Int? = null,
    @SerialName("my_list_status") val myListStatus: MalListStatus? = null,
)

/** What MAL says about one anime: the viewer's entry, and MAL's own episode count. */
data class MalAnimeStatus(
    val listStatus: MalListStatus?,
    /** MAL's episode count, null when it doesn't know one (an airing series often has 0). */
    val totalEpisodes: Int?,
)

/** One entry of the viewer's MAL anime list, statuses translated to AniList vocabulary. */
data class MalListEntry(
    val malId: Int,
    val title: String,
    val totalEpisodes: Int?,
    /** AniList-style status: CURRENT/REPEATING/PLANNING/PAUSED/COMPLETED/DROPPED. */
    val status: String,
    val progress: Int,
    val score: Double,
    val updatedAt: Long = 0L,
    val tags: List<String> = emptyList(),
)

/**
 * Minimal MyAnimeList v2 client for the logged-in account: profile, anime list, and the two
 * non-destructive list mutations the app performs. Every call refreshes the OAuth token as
 * needed via [MalAuthManager.freshAccessToken]. All ids here are MAL ids.
 */
class MalClient(
    private val client: OkHttpClient,
    private val json: Json,
    apiBaseUrl: String = API,
    private val accessTokenProvider: suspend () -> String? = MalAuthManager::freshAccessToken,
) {
    private val apiBaseUrl = apiBaseUrl.trimEnd('/')

    suspend fun viewer(): MalUser = withContext(Dispatchers.IO) {
        json.decodeFromString(
            MalUser.serializer(),
            get("$apiBaseUrl/users/@me?fields=anime_statistics"),
        )
    }

    /** The whole list in one call when possible (MAL allows limit up to 1000), else paged. */
    suspend fun animeList(): List<MalListEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<MalListEntry>()
        var url: String? = "$apiBaseUrl/users/@me/animelist" +
            "?fields=list_status{$LIST_STATUS_FIELDS},num_episodes&limit=1000&nsfw=true"
        var pages = 0
        while (url != null && pages < MAX_LIST_PAGES) {
            val parsed = json.decodeFromString(MalListResponse.serializer(), get(url))
            parsed.data.forEach { item ->
                val list = item.listStatus ?: return@forEach
                val epochSec = malEpochSeconds(list.updatedAt)
                entries += MalListEntry(
                    malId = item.node.id,
                    title = item.node.title,
                    totalEpisodes = item.node.numEpisodes?.takeIf { it > 0 },
                    status = anilistStatus(list),
                    progress = list.numEpisodesWatched,
                    score = list.score.toDouble(),
                    updatedAt = epochSec,
                    tags = list.tags,
                )
            }
            url = parsed.paging?.next
            pages++
        }
        entries
    }

    /** The viewer's list entry for one anime, or null when it isn't on their list. */
    suspend fun listStatus(malId: Int): MalListStatus? = animeStatus(malId).listStatus

    /**
     * The viewer's entry plus MAL's episode count, in the one request that already carries both.
     *
     * The count is what a progress push has to be measured against: MAL refuses a
     * `num_watched_episodes` above it, and [updateListStatus] verifies what it confirmed.
     */
    suspend fun animeStatus(malId: Int): MalAnimeStatus = withContext(Dispatchers.IO) {
        val parsed = json.decodeFromString(
            MalAnimeWithStatus.serializer(),
            get("$apiBaseUrl/anime/$malId?fields=my_list_status{$LIST_STATUS_FIELDS},num_episodes"),
        )
        MalAnimeStatus(
            listStatus = parsed.myListStatus?.takeIf { it.status != null },
            totalEpisodes = parsed.numEpisodes?.takeIf { it > 0 },
        )
    }

    /**
     * Upsert progress/status. [status] uses MAL vocabulary (watching/completed/…); null keeps it.
     *
     * [score] is the viewer's own 1–10, or 0 to clear it. MAL holds whole numbers only.
     *
     * [tags] replaces the entry's whole tag set when given — MAL takes them as one comma-separated
     * field — so a caller changing one tag passes every other tag the entry already has.
     *
     * [numTimesRewatched] sets the count of finished rewatches. A rewatch's own episode count goes
     * out with [strictProgress] off: a rewatch sits on `completed`, and should MAL hold that at the
     * full count, failing would retry the write forever over a number playback cannot change. The
     * difference is logged instead, and the rewatch still finishes on its last episode.
     */
    suspend fun updateListStatus(
        malId: Int,
        progress: Int? = null,
        status: String? = null,
        isRewatching: Boolean? = null,
        tags: List<String>? = null,
        score: Int? = null,
        numTimesRewatched: Int? = null,
        strictProgress: Boolean = true,
    ): MalListStatus = withContext(Dispatchers.IO) {
            score?.let { require(it in 0..10) { "A MyAnimeList score is 0 to 10, not $it" } }
            val body = FormBody.Builder().apply {
                progress?.let { add("num_watched_episodes", it.toString()) }
                status?.let { add("status", it) }
                isRewatching?.let { add("is_rewatching", it.toString()) }
                tags?.let { add("tags", it.joinToString(",")) }
                score?.let { add("score", it.toString()) }
                numTimesRewatched?.let { add("num_times_rewatched", it.toString()) }
            }.build()
            val request = authed("$apiBaseUrl/anime/$malId/my_list_status").patch(body).build()
            val confirmed = json.decodeFromString(MalListStatus.serializer(), execute(request))
            progress?.takeIf { strictProgress || confirmed.numEpisodesWatched == it }?.let { requested ->
                check(confirmed.numEpisodesWatched == requested) {
                    "MyAnimeList confirmed ${confirmed.numEpisodesWatched} watched episodes after requesting $requested"
                }
            }
            status?.let { requested ->
                check(confirmed.status == requested) {
                    "MyAnimeList confirmed status ${confirmed.status ?: "missing"} after requesting $requested"
                }
            }
            score?.let { requested ->
                check(confirmed.score == requested) {
                    "MyAnimeList confirmed score ${confirmed.score} after requesting $requested"
                }
            }
            isRewatching?.let { requested ->
                check(confirmed.isRewatching == requested) {
                    "MyAnimeList confirmed is_rewatching=${confirmed.isRewatching} after requesting $requested"
                }
            }
            numTimesRewatched?.let { requested ->
                check(confirmed.numTimesRewatched == requested) {
                    "MyAnimeList confirmed num_times_rewatched=${confirmed.numTimesRewatched} after requesting $requested"
                }
            }
            if (!strictProgress && progress != null && confirmed.numEpisodesWatched != progress) {
                DiagnosticsLog.event(
                    "MAL kept ${confirmed.numEpisodesWatched} watched episodes on a rewatch after requesting " +
                        "$progress malId=$malId",
                )
            }
            // Logged rather than thrown: MAL may tidy a tag's spacing, and failing the write over
            // that would retry it forever. A real refusal still shows here and on the next refresh.
            tags?.let { requested ->
                if (confirmed.tags.map(String::trim).toSet() != requested.map(String::trim).toSet()) {
                    DiagnosticsLog.event(
                        "MAL tags differ after write malId=$malId requested=${requested.size} confirmed=${confirmed.tags.size}",
                    )
                }
            }
            confirmed
        }

    suspend fun deleteListEntry(malId: Int) = withContext(Dispatchers.IO) {
        execute(authed("$apiBaseUrl/anime/$malId/my_list_status").delete().build())
    }

    // ---- helpers ----

    private suspend fun get(url: String): String = execute(authed(url).get().build())

    private suspend fun authed(url: String): Request.Builder {
        val token = accessTokenProvider() ?: error("Not logged in to MyAnimeList")
        return Request.Builder().url(url.toHttpUrl()).header("Authorization", "Bearer $token")
    }

    private fun execute(request: Request): String =
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                DiagnosticsLog.event("MAL HTTP ${response.code} ${request.method} ${request.url.encodedPath}")
                error("MyAnimeList HTTP ${response.code}")
            }
            text
        }

    companion object {
        private const val API = "https://api.myanimelist.net/v2"
        private const val MAX_LIST_PAGES = 5

        /**
         * Named in full rather than left to MAL's defaults: `tags` is not a default field, and
         * asking for it by name is only certain to keep the rest when the rest is named as well.
         */
        private const val LIST_STATUS_FIELDS =
            "status,score,num_episodes_watched,is_rewatching,num_times_rewatched,updated_at,tags"

        internal fun anilistStatus(list: MalListStatus): String = when {
            list.isRewatching -> "REPEATING"
            list.status == "watching" -> "CURRENT"
            list.status == "completed" -> "COMPLETED"
            list.status == "on_hold" -> "PAUSED"
            list.status == "dropped" -> "DROPPED"
            else -> "PLANNING"
        }

        /**
         * A rewatch is `completed` on MAL, flagged `is_rewatching`: that is how MAL's own editor
         * stores one, and it keeps the title among the finished ones while it is rewatched. Sending
         * `watching` instead took it off Completed for as long as the rewatch lasted.
         */
        internal fun malStatus(anilistStatus: String): String = when (anilistStatus) {
            "CURRENT" -> "watching"
            "COMPLETED", "REPEATING" -> "completed"
            "PAUSED" -> "on_hold"
            "DROPPED" -> "dropped"
            else -> "plan_to_watch"
        }
    }
}

/**
 * Seconds since the epoch for a MAL timestamp, or 0 when it can't be read.
 *
 * MAL stamps these with a numeric offset — `2018-04-25T22:23:11+00:00`, not `…Z`. `Instant.parse`
 * only accepts that on runtimes carrying the JDK 12 relaxation of `ISO_INSTANT`; the desugared
 * library this app ships below API 26 predates it, as does the platform `java.time` on older
 * Android. Every row then fell back to 0, and Continue Watching is ordered by exactly this value:
 * a MAL account's seeded rows sank below every locally played title and, past the history cap,
 * fell off the list entirely. `OffsetDateTime` reads both spellings on every runtime.
 */
internal fun malEpochSeconds(raw: String?): Long {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return 0L
    return runCatching { java.time.OffsetDateTime.parse(value).toEpochSecond() }
        .recoverCatching { java.time.Instant.parse(value).epochSecond }
        .getOrDefault(0L)
}
