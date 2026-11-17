package com.anilili.data.remote

import com.anilili.data.cache.AppCache
import com.anilili.data.model.EpisodeMetadata
import com.anilili.data.model.Media
import com.anilili.diagnostics.DiagnosticsLog
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Episode titles + stills via Cinemeta (IMDb-backed episode art on episodes.metahub.space).
 * No API key. Replaces TMDB / Jikan / AniList streaming for per-episode pictures.
 */
class ImdbEpisodeClient(
    private val cache: AppCache,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun episodeMetadata(media: Media): List<EpisodeMetadata> {
        return runCatching {
            cache.getOrFetch(
                key = "imdbeps:v1:${media.id}",
                serializer = ListSerializer(EpisodeMetadata.serializer()),
                ttlMs = TTL_MS,
                cacheIf = { it.isNotEmpty() },
            ) { fetch(media) }
        }.onFailure {
            DiagnosticsLog.throwable("IMDb/Cinemeta episodes failed id=${media.id}", it)
        }.getOrDefault(emptyList())
    }

    private fun fetch(media: Media): List<EpisodeMetadata> {
        val imdbId = resolveImdbId(media) ?: return emptyList()
        val body = getString("$META_BASE/meta/series/$imdbId.json") ?: return emptyList()
        val root = json.parseToJsonElement(body).jsonObject
        val meta = root["meta"]?.jsonObject ?: return emptyList()
        val videos = meta["videos"]?.jsonArray.orEmpty()
        if (videos.isEmpty()) return emptyList()

        // Prefer season 1 (and season 0 only if nothing else). Match AniList season length when known.
        val wanted = media.episodes
        val bySeason = videos.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val season = o["season"]?.jsonPrimitive?.intOrNull ?: 1
            val episode = o["episode"]?.jsonPrimitive?.intOrNull
                ?: o["number"]?.jsonPrimitive?.intOrNull
                ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull
            val thumb = o["thumbnail"]?.jsonPrimitive?.contentOrNull
                ?: o["thumbnailUrl"]?.jsonPrimitive?.contentOrNull
                // Metahub convention when JSON omits thumbnail
                ?: "https://episodes.metahub.space/$imdbId/$season/$episode/w780.jpg"
            Triple(season, episode, EpisodeMetadata(
                number = episode.toDouble(),
                title = name?.takeIf { it.isNotBlank() && !it.startsWith("Episode ", ignoreCase = true) },
                still = thumb,
            ))
        }

        val seasons = bySeason.groupBy { it.first }
        val seasonNum = when {
            wanted != null -> seasons.entries
                .filter { it.key > 0 }
                .minByOrNull { (_, eps) -> kotlin.math.abs(eps.size - wanted) }
                ?.key
            else -> seasons.keys.filter { it > 0 }.minOrNull()
        } ?: seasons.keys.minOrNull() ?: return emptyList()

        val rows = seasons[seasonNum].orEmpty()
            .sortedBy { it.second }
            .map { it.third }
            .distinctBy { it.number }
        DiagnosticsLog.event(
            "IMDb episodes anilist=${media.id} imdb=$imdbId season=$seasonNum rows=${rows.size} " +
                "stills=${rows.count { !it.still.isNullOrBlank() }}",
        )
        return rows
    }

    private fun resolveImdbId(media: Media): String? {
        // Cinemeta search by English then romaji (IMDb ids under the hood)
        val year = media.seasonYear ?: media.startDate?.year
        for (query in listOf(media.title.english, media.title.romaji).mapNotNull { it?.trim()?.takeIf { s -> s.length >= 2 } }) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val body = getString("$META_BASE/catalog/series/top/search=$encoded.json") ?: continue
            val metas = runCatching {
                json.parseToJsonElement(body).jsonObject["metas"]?.jsonArray
            }.getOrNull().orEmpty()
            val hit = metas.mapNotNull { it as? JsonObject }.firstOrNull { candidate ->
                val name = candidate["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val release = candidate["releaseInfo"]?.jsonPrimitive?.contentOrNull
                val y = release?.take(4)?.toIntOrNull()
                val yearOk = year == null || y == null || kotlin.math.abs(y - year) <= 1
                yearOk && namesLooselyMatch(query, name)
            } ?: metas.mapNotNull { it as? JsonObject }.firstOrNull { candidate ->
                val release = candidate["releaseInfo"]?.jsonPrimitive?.contentOrNull
                val y = release?.take(4)?.toIntOrNull()
                year == null || y == null || kotlin.math.abs(y - year) <= 1
            }
            val id = hit?.get("imdb_id")?.jsonPrimitive?.contentOrNull
                ?: hit?.get("id")?.jsonPrimitive?.contentOrNull
            if (id != null && id.startsWith("tt")) return id
        }
        return null
    }

    private fun namesLooselyMatch(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val x = norm(a)
        val y = norm(b)
        if (x.length < 4 || y.length < 4) return x == y
        return x == y || x.contains(y) || y.contains(x)
    }

    private fun getString(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Anime/1.0")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> {
                    DiagnosticsLog.event("IMDb HTTP ${response.code} for $url")
                    null
                }
                else -> response.body?.string()
            }
        }
    }

    companion object {
        private const val META_BASE = "https://v3-cinemeta.strem.io"
        private const val TTL_MS = 12L * 60 * 60 * 1000
        private val IMDB_ID_IN_URL = Regex("""(tt\d{5,})""")
    }
}
