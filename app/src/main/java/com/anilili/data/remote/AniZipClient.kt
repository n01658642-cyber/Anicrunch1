package com.anilili.data.remote

import com.anilili.data.cache.AppCache
import com.anilili.data.model.EpisodeMetadata
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
internal data class AniZipImage(val coverType: String? = null, val url: String? = null)

@Serializable
internal data class AniZipMappings(val images: List<AniZipImage> = emptyList())

/**
 * AniZip (api.ani.zip): keyless TVDB/TMDB-backed mappings for one AniList id.
 *
 * Used for fanart (background picker) and per-episode stills + titles.
 */
class AniZipClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val cache: AppCache,
) {
    /** TheTVDB wide backdrop(s) for [anilistId], or empty. */
    suspend fun fanart(anilistId: Int): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            cache.getOrFetch(
                key = "anizipfanart:v1:$anilistId",
                serializer = ListSerializer(String.serializer()),
                ttlMs = FANART_TTL_MS,
            ) { fetchFanart(anilistId) }
        }
            .onFailure { DiagnosticsLog.throwable("AniZip fanart failed id=$anilistId", it) }
            .getOrDefault(emptyList())
    }

    /**
     * Per-episode stills and titles from AniZip (TVDB art). Empty when the mapping has no episodes.
     */
    suspend fun episodeMetadata(anilistId: Int): List<EpisodeMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            cache.getOrFetch(
                key = "anizipeps:v1:$anilistId",
                serializer = ListSerializer(EpisodeMetadata.serializer()),
                ttlMs = EPISODES_TTL_MS,
                cacheIf = { it.isNotEmpty() },
            ) { fetchEpisodes(anilistId) }
        }
            .onFailure { DiagnosticsLog.throwable("AniZip episodes failed id=$anilistId", it) }
            .getOrDefault(emptyList())
    }

    private fun fetchFanart(anilistId: Int): List<String> {
        val body = getMappingsBody(anilistId) ?: return emptyList()
        return json.decodeFromString(AniZipMappings.serializer(), body)
            .images
            .filter { it.coverType.equals("Fanart", ignoreCase = true) }
            .mapNotNull { it.url?.takeIf(String::isNotBlank) }
            .distinct()
    }

    private fun fetchEpisodes(anilistId: Int): List<EpisodeMetadata> {
        val body = getMappingsBody(anilistId) ?: return emptyList()
        val root = json.parseToJsonElement(body).jsonObject
        val episodes = root["episodes"]?.jsonObject ?: return emptyList()
        val rows = episodes.mapNotNull { (key, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val number = obj["episodeNumber"]?.jsonPrimitive?.intOrNull
                ?: obj["absoluteEpisodeNumber"]?.jsonPrimitive?.intOrNull
                ?: key.toIntOrNull()
                ?: return@mapNotNull null
            val image = obj["image"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val titleMap = obj["title"]?.jsonObject
            val title = titleMap?.get("en")?.jsonPrimitive?.contentOrNull
                ?: titleMap?.get("x-jat")?.jsonPrimitive?.contentOrNull
                ?: titleMap?.get("ja")?.jsonPrimitive?.contentOrNull
            val overview = obj["overview"]?.jsonPrimitive?.contentOrNull
            if (image == null && title == null) return@mapNotNull null
            EpisodeMetadata(
                number = number.toDouble(),
                title = title?.takeIf { it.isNotBlank() },
                still = image,
                overview = overview?.takeIf { it.isNotBlank() },
            )
        }
            .distinctBy { it.number }
            .sortedBy { it.number }
        DiagnosticsLog.event(
            "AniZip episodes id=$anilistId rows=${rows.size} stills=${rows.count { !it.still.isNullOrBlank() }}",
        )
        return rows
    }

    private fun getMappingsBody(anilistId: Int): String? {
        val request = Request.Builder()
            .url("$API_BASE/mappings?anilist_id=$anilistId")
            .header("Accept", "application/json")
            .header("User-Agent", "Anime/1.0")
            .build()
        return client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> error("AniZip HTTP ${response.code}")
                else -> response.body?.string()
            }
        }
    }

    private companion object {
        const val API_BASE = "https://api.ani.zip"
        const val FANART_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val EPISODES_TTL_MS = 6L * 60 * 60 * 1000
    }
}
