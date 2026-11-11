package com.anilili.data.discord

import com.anilili.diagnostics.DiagnosticsLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a cover image URL into something a presence card can display.
 *
 * Discord will not load an arbitrary URL into an activity's assets — the only images it renders
 * are art uploaded to the application, or paths it has proxied itself. Anime covers come from
 * AniList's CDN and are per-title, so uploading them is not an option; the application's
 * `external-assets` endpoint is. It takes a URL, fetches it through Discord's media proxy, and
 * returns a path that is then referenced as `mp:<path>`.
 *
 * Registrations are cached for the process. The same cover is re-sent on every seek, pause and
 * episode change, and each of those would otherwise be a round trip before the card could update.
 */
internal object DiscordAssets {
    private const val API_BASE = "https://discord.com/api/v10"

    private val registered = ConcurrentHashMap<String, String>()

    private val assetIds = ConcurrentHashMap<String, String>()

    @Volatile private var assetIdsLoaded = false

    /**
     * The presence-ready form of [imageUrl], or null when it could not be registered.
     *
     * A value that is not an http(s) URL is passed straight back. That is only correct for a
     * reference an activity already accepts — an asset id or an `mp:` path — *not* for the friendly
     * key an asset was uploaded under; see [applicationAssetId].
     */
    suspend fun resolve(accessToken: String, imageUrl: String?, client: OkHttpClient): String? {
        val image = imageUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!image.startsWith("http://") && !image.startsWith("https://")) return image

        // Discord's own CDN is already proxied; naming it is enough, and registering it would be
        // a pointless round trip.
        DISCORD_CDN_PREFIXES.firstOrNull { image.startsWith(it) }?.let { prefix ->
            return "mp:" + image.removePrefix(prefix)
        }

        registered[image]?.let { return "mp:$it" }
        val path = register(accessToken, image, client) ?: return null
        registered[image] = path
        return "mp:$path"
    }

    /**
     * The id of art uploaded to the application, looked up by the key it was uploaded under, or
     * null when that cannot be resolved.
     *
     * A gateway presence is not an RPC call. The SDK and RPC paths accept a friendly asset name and
     * resolve it for you; the activity object on the wire does not — it takes an asset id or an
     * `mp:` media-proxy path and nothing else. Sending the key renders Discord's "?" placeholder,
     * and because `small_text` still works the card looks like a broken upload rather than a bad
     * reference, which is what made this take three wrong guesses to find.
     *
     * The listing endpoint needs no authorization. Ids only change on a delete and re-upload, so
     * one fetch serves the process — and looking the id up rather than hardcoding it is what keeps
     * re-uploading the art from silently breaking the badge again.
     */
    suspend fun applicationAssetId(name: String, client: OkHttpClient): String? {
        assetIds[name]?.let { return it }
        if (!loadApplicationAssets(client)) return null
        return assetIds[name]
    }

    /** Covers are per-viewer; the application's own art is not, so its ids outlive a sign-out. */
    fun forget() = registered.clear()

    private suspend fun loadApplicationAssets(client: OkHttpClient): Boolean =
        withContext(Dispatchers.IO) {
            if (assetIdsLoaded) return@withContext true
            val request = Request.Builder()
                .url("$API_BASE/oauth2/applications/${DiscordAuthManager.APPLICATION_ID}/assets")
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error("Discord application assets HTTP ${response.code}: ${text.take(200)}")
                    }
                    val listed = JSONArray(text)
                    for (index in 0 until listed.length()) {
                        val asset = listed.optJSONObject(index) ?: continue
                        val key = asset.optString("name").takeIf { it.isNotEmpty() } ?: continue
                        val id = asset.optString("id").takeIf { it.isNotEmpty() } ?: continue
                        assetIds[key] = id
                    }
                }
                // Only on success, so a network blip is retried on the next publish rather than
                // pinning an empty map for the life of the process.
                assetIdsLoaded = true
                true
            }.getOrElse { error ->
                DiagnosticsLog.throwable("Discord application assets lookup failed", error)
                false
            }
        }

    private suspend fun register(
        accessToken: String,
        imageUrl: String,
        client: OkHttpClient,
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("urls", JSONArray().put(imageUrl))
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$API_BASE/applications/${DiscordAuthManager.APPLICATION_ID}/external-assets")
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("Discord external-assets HTTP ${response.code}: ${text.take(200)}")
                }
                JSONArray(text)
                    .takeIf { it.length() > 0 }
                    ?.optJSONObject(0)
                    ?.optString("external_asset_path")
                    ?.takeIf { it.isNotBlank() }
            }
        }.onFailure {
            // A card without its cover is still a useful card, so this never fails an update.
            DiagnosticsLog.throwable("Discord cover registration failed", it)
        }.getOrNull()
    }

    private val DISCORD_CDN_PREFIXES = listOf(
        "https://cdn.discordapp.com/",
        "https://media.discordapp.net/",
    )
}
