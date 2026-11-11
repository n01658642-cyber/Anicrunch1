package com.anilili.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The request bodies the Simkl tracker sends, and the decisions behind them, kept free of Android
 * and the network so they can be tested on the JVM.
 *
 * Every write names the title by its AniList id, plus its MyAnimeList id when one is known. Simkl
 * maps anime-native ids 1:1 onto its own records and walks them in order until one resolves, so no
 * search is needed first. Episodes are flat and absolute, as AniList numbers them — Simkl's "Path
 * B" for anime-native apps — which is why no season is ever sent. No title is sent either: when no
 * id resolves Simkl falls back to a fuzzy title match, and sequels sharing a name would land on the
 * wrong show. An unmatched title is reported in `not_found` instead.
 */
internal object SimklPayloads {
    /** One series' entry as Simkl's `anime` envelope carries it. */
    private const val ENVELOPE = "anime"

    /**
     * A ceiling on one history write. The longest series here is about 1,150 episodes; this only
     * stops a corrupt progress value from becoming a megabyte request.
     */
    const val MAX_HISTORY_EPISODES = 3_000

    fun ids(anilistId: Int, malId: Int?): JsonObject = buildJsonObject {
        put("anilist", anilistId)
        if (malId != null && malId > 0) put("mal", malId)
    }

    /**
     * Episodes 1 through [progress] watched. AniList's progress means exactly that, and Simkl skips
     * an episode it already has (a re-post is a no-op without `allow_rewatch`), so sending the whole
     * run is idempotent — and it is what fills in the earlier episodes for someone who connects
     * Simkl halfway through a series. Sending only the latest would leave Simkl at "1 of 12".
     */
    fun history(ids: JsonObject, progress: Int): JsonObject = envelope(
        buildJsonObject {
            put("ids", ids)
            putJsonArray("episodes") {
                for (number in 1..progress.coerceIn(0, MAX_HISTORY_EPISODES)) {
                    add(buildJsonObject { put("number", number) })
                }
            }
        },
    )

    /** The whole series watched: `status: completed` with no episodes marks every one. */
    fun completed(ids: JsonObject): JsonObject = envelope(
        buildJsonObject {
            put("ids", ids)
            put("status", "completed")
        },
    )

    /** A status move with no watch event. `to` is per item; the top-level form is refused. */
    fun addToList(ids: JsonObject, to: String): JsonObject = envelope(
        buildJsonObject {
            put("ids", ids)
            put("to", to)
        },
    )

    /** Takes the title off the account, watch history and rating included. */
    fun remove(ids: JsonObject): JsonObject = envelope(buildJsonObject { put("ids", ids) })

    /** The `/sync/watched` lookup for one title. */
    fun watchedQuery(ids: JsonObject): JsonArray = buildJsonArray { add(buildJsonObject { put("ids", ids) }) }

    private fun envelope(item: JsonObject): JsonObject = buildJsonObject {
        put(ENVELOPE, JsonArray(listOf(item)))
    }

    /** Whether a write response says the title matched nothing on Simkl. */
    fun notFound(response: JsonObject): Boolean {
        val missing = response["not_found"] as? JsonObject ?: return false
        return missing.values.any { (it as? JsonArray)?.isNotEmpty() == true }
    }
}

/**
 * AniList's list vocabulary in, Simkl's watchlist status out. Simkl has no rewatching status, so
 * REPEATING is Watching there — the same thing a viewer sees on simkl.com mid-rewatch. COMPLETED
 * is null because it is not a list move: it goes through [SimklPayloads.completed], which also
 * marks the episodes.
 */
internal fun simklListStatus(aniListStatus: String): String? = when (aniListStatus) {
    "CURRENT", "REPEATING" -> "watching"
    "PLANNING" -> "plantowatch"
    "PAUSED" -> "hold"
    "DROPPED" -> "dropped"
    else -> null
}

/** What a save or unsave in the app does to Simkl. */
internal enum class SimklSavedAction { ADD_PLANNED, REMOVE, NONE }

/**
 * The same rule the AniList and MyAnimeList saved sync follow: a save only adds a title the account
 * does not track yet, so it can never demote something already being watched to Plan to Watch; an
 * unsave only removes a title that is still nothing more than a plan, so it can never delete watch
 * history.
 *
 * @param list the title's current Simkl watchlist status, null when it is on none.
 * @param watchedBefore whether Simkl has any watch event for it.
 */
internal fun simklSavedAction(saved: Boolean, list: String?, watchedBefore: Boolean): SimklSavedAction = when {
    saved && list == null && !watchedBefore -> SimklSavedAction.ADD_PLANNED
    !saved && list == "plantowatch" && !watchedBefore -> SimklSavedAction.REMOVE
    else -> SimklSavedAction.NONE
}
