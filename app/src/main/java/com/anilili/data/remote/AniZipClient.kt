package com.anilili.data.remote

import com.anilili.data.cache.AppCache
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
internal data class AniZipImage(val coverType: String? = null, val url: String? = null)

@Serializable
internal data class AniZipMappings(val images: List<AniZipImage> = emptyList())

/**
 * AniZip (api.ani.zip): a keyless service that answers for one AniList id with TheTVDB's artwork.
 *
 * The metadata tree already reads its clearlogos off the device (see `scripts/konoha_build.py`).
 * The app asks it live for one thing only — the "Fanart" backdrop, for the background picker — which
 * is a picker's worth of calls a week, far inside what a keyless service tolerates. It rate-limits
 * bursts with a 429, which the picker survives by showing TMDB's pictures alone.
 */
class AniZipClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val cache: AppCache,
) {
    /** TheTVDB's wide backdrop(s) for [anilistId], or empty. */
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

    private fun fetchFanart(anilistId: Int): List<String> {
        val request = Request.Builder()
            .url("$API_BASE/mappings?anilist_id=$anilistId")
            .header("Accept", "application/json")
            .build()
        return client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> emptyList()
                !response.isSuccessful -> error("AniZip HTTP ${response.code}")
                else -> json.decodeFromString(AniZipMappings.serializer(), response.body?.string().orEmpty())
                    .images
                    .filter { it.coverType.equals("Fanart", ignoreCase = true) }
                    .mapNotNull { it.url?.takeIf(String::isNotBlank) }
                    .distinct()
            }
        }
    }

    private companion object {
        const val API_BASE = "https://api.ani.zip"
        const val FANART_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
