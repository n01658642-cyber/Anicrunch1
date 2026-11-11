package com.anilili.data.remote

import com.anilili.BuildConfig
import com.anilili.data.AppGraph
import com.anilili.data.auth.SimklAuthManager
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Simkl is not signed in (any more): there is nobody to send this to. */
class SimklSignedOutException : IllegalStateException("Simkl is not signed in")

/**
 * Simkl answered with a status worth acting on. [retryable] covers 429 and 5xx — Simkl's own advice
 * is to back off and retry those — and nothing else: a 4xx will fail the same way next time.
 */
class SimklHttpException(val status: Int, body: String) :
    IOException("Simkl HTTP $status: ${body.take(200)}") {
    val retryable: Boolean get() = status == 429 || status >= 500
}

/** One title's state on the account, from `/sync/watched`. */
internal data class SimklWatchState(
    /** False when Simkl matched none of the ids. */
    val found: Boolean,
    /** `watching`, `plantowatch`, `hold`, `dropped`, `completed`, or null when on no list. */
    val list: String?,
    val watchedBefore: Boolean,
)

/**
 * The few Simkl calls the tracker makes, all as the signed-in viewer.
 *
 * Simkl caps every app at one POST a second per user token, and suspends a `client_id` that keeps
 * going over — with no warning and no appeal. Writes here are therefore serialised and spaced
 * process-wide, whatever WorkManager decides to run in parallel — the `/sync/watched` lookup
 * included, since it is a POST too. GETs are not spaced: their budget is ten a second, and the
 * tracker makes one per sign-in.
 */
object SimklClient {
    private const val BASE = "https://api.simkl.com"
    private const val MIN_POST_GAP_MS = 1_100L

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()
    private val postGate = Mutex()
    private var lastPostAtMs = 0L

    /** The account's display name, from `/users/settings`. */
    suspend fun accountName(): String? {
        val body = request("GET", "/users/settings", null) as? JsonObject ?: return null
        return (body["user"] as? JsonObject)?.string("name")?.takeIf { it.isNotBlank() }
    }

    /** The account's numeric id, from `/users/settings`: the usage ping's `people` hashes it. */
    suspend fun accountId(): Long? {
        val body = request("GET", "/users/settings", null) as? JsonObject ?: return null
        return ((body["account"] as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
    }

    /** POST `/sync/history`. Returns false when Simkl matched none of the title's ids. */
    internal suspend fun addToHistory(body: JsonObject): Boolean = write("/sync/history", body)

    /** POST `/sync/add-to-list`. Returns false when Simkl matched none of the title's ids. */
    internal suspend fun addToList(body: JsonObject): Boolean = write("/sync/add-to-list", body)

    /** POST `/sync/history/remove`. Returns false when Simkl matched none of the title's ids. */
    internal suspend fun removeFromHistory(body: JsonObject): Boolean = write("/sync/history/remove", body)

    /**
     * One title's watchlist status. This is the lookup Simkl offers for exactly the tracker's case —
     * a handful of known titles, without pulling the whole library first.
     */
    internal suspend fun watchState(query: JsonArray): SimklWatchState {
        val first = (request("POST", "/sync/watched", query) as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return SimklWatchState(found = false, list = null, watchedBefore = false)
        val result = first["result"]
        if ((result as? JsonPrimitive)?.contentOrNull == "not_found") {
            return SimklWatchState(found = false, list = null, watchedBefore = false)
        }
        return SimklWatchState(
            found = true,
            list = first.string("list")?.takeIf { it.isNotBlank() },
            watchedBefore = first.string("last_watched_at") != null,
        )
    }

    private suspend fun write(path: String, body: JsonObject): Boolean {
        val response = request("POST", path, body) as? JsonObject ?: return true
        return !SimklPayloads.notFound(response)
    }

    private suspend fun request(method: String, path: String, body: JsonElement?): JsonElement? {
        val token = SimklAuthManager.freshAccessToken() ?: throw SimklSignedOutException()
        val first = send(method, path, body, token)
        if (first.first != 401) return parsed(first)
        // A 401 is either an expired token or another refresh having replaced it. One refresh and
        // one retry; a second 401 means the grant itself is gone, and the refresh has signed out.
        val renewed = SimklAuthManager.refreshAfterRejection(token) ?: throw SimklSignedOutException()
        return parsed(send(method, path, body, renewed))
    }

    private fun parsed(response: Pair<Int, String>): JsonElement? {
        val (status, text) = response
        if (status !in 200..299) throw SimklHttpException(status, text)
        if (text.isBlank() || text == "null") return null
        return json.parseToJsonElement(text)
    }

    private suspend fun send(method: String, path: String, body: JsonElement?, token: String): Pair<Int, String> {
        val url = "$BASE$path".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", SimklAuthManager.CLIENT_ID)
            .addQueryParameter("app-name", SimklAuthManager.APP_NAME)
            .addQueryParameter("app-version", BuildConfig.VERSION_NAME)
            .build()
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", SimklAuthManager.userAgent)
            .header("Authorization", "Bearer $token")
        if (method == "POST") {
            builder.post(json.encodeToString(JsonElement.serializer(), body ?: JsonNull).toRequestBody(jsonMedia))
        }
        val call = { AppGraph.httpClient.newCall(builder.build()).execute().use { it.code to it.body?.string().orEmpty() } }
        if (method != "POST") return withContext(Dispatchers.IO) { call() }
        return postGate.withLock {
            val wait = lastPostAtMs + MIN_POST_GAP_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try {
                withContext(Dispatchers.IO) { call() }
            } finally {
                lastPostAtMs = System.currentTimeMillis()
            }
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
}
