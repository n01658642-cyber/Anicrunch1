package com.anilili.data.remote

import com.anilili.data.model.SkipTimes
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.abs

/**
 * One entry of AniSkip's episode-number redirection table.
 *
 * MAL files a long-running show as one entry per cour while streaming providers usually number
 * episodes straight through, so the episode a viewer is watching is regularly not the episode number
 * the skip database is keyed by. A rule says "episodes [fromStart, fromEnd] of the anime you asked
 * about are episodes starting at [toStart] of [toMalId]".
 */
@Serializable
internal data class AniSkipRelationRule(
    val fromStart: Int,
    val fromEnd: Int?,
    val toMalId: Int,
    val toStart: Int,
    val toEnd: Int?,
)

/** Which MAL entry and episode number actually hold [episode]'s timings, or null if no rule does. */
internal fun List<AniSkipRelationRule>.redirect(malId: Int, episode: Int): Pair<Int, Int>? {
    for (rule in this) {
        if (episode < rule.fromStart) continue
        if (rule.fromEnd != null && episode > rule.fromEnd) continue
        val mapped = rule.toStart + (episode - rule.fromStart)
        if (mapped < 1) continue
        if (rule.toEnd != null && mapped > rule.toEnd) continue
        // Rule sets overlap — MAL 40028 carries both `from 60-75` and `from 17-75` — so the first
        // match wins, as AniSkip's own extension does it. A rule that points back at the lookup we
        // already made is not a redirect, and re-requesting it would only spend the rate limit.
        if (rule.toMalId == malId && mapped == episode) continue
        return rule.toMalId to mapped
    }
    return null
}

/**
 * AniSkip community skip-time database (aniskip.com), keyed by MAL id. Fallback for streaming
 * providers that don't carry intro/outro markers of their own (most Anivexa providers).
 */
class AniSkipClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    /**
     * Intro/outro/recap windows for one episode; null when AniSkip has none (unknown episodes 404).
     * [episodeLengthSeconds] lets AniSkip select timestamps for the actual encode being watched.
     */
    fun skipTimes(malId: Int, episode: Int, episodeLengthSeconds: Double = 0.0): SkipTimes? {
        require(episodeLengthSeconds.isFinite() && episodeLengthSeconds >= 0.0) {
            "episodeLengthSeconds must be finite and non-negative"
        }
        // AniSkip 500s intermittently on the full type list while answering the same episode
        // cleanly for openings and endings alone, so a narrower retry stands between a flaky
        // upstream and an episode with no timings at all. The dropped types are the rarer ones:
        // losing a recap marker is a much smaller loss than losing the opening. A second failure
        // still throws, because the caller caches an answer and must not cache an outage.
        val root = try {
            fetchSkipTimes(malId, episode, episodeLengthSeconds, ALL_TYPES)
        } catch (error: IOException) {
            fetchSkipTimes(malId, episode, episodeLengthSeconds, CORE_TYPES)
        } ?: return null
        if (root["found"]?.jsonPrimitive?.booleanOrNull != true) return null
        val results = root["results"]?.jsonArray?.mapNotNull { element ->
            val result = element.jsonObject
            val interval = result["interval"]?.jsonObject ?: return@mapNotNull null
            val start = interval["startTime"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val end = interval["endTime"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val type = result["skipType"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!start.isFinite() || !end.isFinite() || end <= start) return@mapNotNull null
            AniSkipSegment(
                type = type,
                start = start,
                end = end,
                episodeLength = result["episodeLength"]?.jsonPrimitive?.doubleOrNull,
                skipId = result["skipId"]?.jsonPrimitive?.contentOrNull,
            )
        }.orEmpty()
        return skipTimesFrom(results, episodeLengthSeconds)
    }

    /**
     * The intro, outro and recap to offer, chosen from every candidate AniSkip holds for an episode.
     *
     * Shared by the live lookup and the metadata tree's mirror of it (`data/skip/`), which keeps
     * every candidate precisely so this choice — by the length of the stream actually playing —
     * can still be made on the device.
     */
    internal fun skipTimesFrom(results: List<AniSkipSegment>, episodeLengthSeconds: Double): SkipTimes? {
        val intro = pickSegment(results, "op", "mixed-op", episodeLengthSeconds)
        val outro = pickSegment(results, "ed", "mixed-ed", episodeLengthSeconds)
        val recap = pickSegment(results, "recap", "recap", episodeLengthSeconds)
        if (intro == null && outro == null && recap == null) return null
        return SkipTimes(
            introStart = intro?.start,
            introEnd = intro?.end,
            outroStart = outro?.start,
            outroEnd = outro?.end,
            recapStart = recap?.start,
            recapEnd = recap?.end,
            introSkipId = intro?.skipId,
            outroSkipId = outro?.skipId,
            recapSkipId = recap?.skipId,
        )
    }

    /** One lookup: the parsed body, null for the documented 404, an [IOException] for anything else. */
    private fun fetchSkipTimes(
        malId: Int,
        episode: Int,
        episodeLengthSeconds: Double,
        types: List<String>,
    ): JsonObject? {
        val url = "https://api.aniskip.com/v2/skip-times/$malId/$episode".toHttpUrl().newBuilder()
            .apply { types.forEach { addQueryParameter("types[]", it) } }
            .addQueryParameter("episodeLength", episodeLengthSeconds.toString())
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("AniSkip HTTP ${response.code}")
            json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
        }
    }

    /**
     * The redirection table for one MAL entry, empty when AniSkip publishes none for it.
     *
     * Read only after a lookup has already come back empty, so an anime whose numbering needs no
     * translation — the common case — never pays for this request.
     */
    internal fun relationRules(malId: Int): List<AniSkipRelationRule> {
        val request = Request.Builder()
            .url("https://api.aniskip.com/v2/relation-rules/$malId")
            .header("Accept", "application/json")
            .build()
        val root = client.newCall(request).execute().use { response ->
            if (response.code == 404) return emptyList()
            if (!response.isSuccessful) throw IOException("AniSkip HTTP ${response.code}")
            json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
        }
        if (root["found"]?.jsonPrimitive?.booleanOrNull != true) return emptyList()
        return root["rules"]?.jsonArray?.mapNotNull { element ->
            val rule = element.jsonObject
            val from = rule["from"]?.jsonObject ?: return@mapNotNull null
            val to = rule["to"]?.jsonObject ?: return@mapNotNull null
            AniSkipRelationRule(
                fromStart = from["start"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                fromEnd = from["end"]?.jsonPrimitive?.intOrNull,
                toMalId = to["malId"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                toStart = to["start"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                toEnd = to["end"]?.jsonPrimitive?.intOrNull,
            )
        }.orEmpty()
    }

    /**
     * Votes on one segment, which is the only way a wrong timing gets corrected for anyone else:
     * the database is community-submitted and a downvote is what demotes a bad submission.
     */
    fun vote(skipId: String, upvote: Boolean) {
        val body = """{"voteType":"${if (upvote) "upvote" else "downvote"}"}"""
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.aniskip.com/v2/skip-times/vote/$skipId")
            .header("Accept", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("AniSkip HTTP ${response.code}")
        }
    }

    private fun pickSegment(
        results: List<AniSkipSegment>,
        primaryType: String,
        fallbackType: String,
        requestedLength: Double,
    ): AniSkipSegment? {
        val candidates = results.filter { it.type == primaryType }.ifEmpty {
            results.filter { it.type == fallbackType }
        }
        return candidates.minByOrNull { segment ->
            if (requestedLength <= 0.0) 0.0 else {
                segment.episodeLength?.takeIf(Double::isFinite)?.let { abs(it - requestedLength) }
                    ?: Double.MAX_VALUE
            }
        }
    }

    internal data class AniSkipSegment(
        val type: String,
        val start: Double,
        val end: Double,
        val episodeLength: Double?,
        val skipId: String?,
    )

    private companion object {
        /**
         * Everything worth skipping. "mixed" is AniSkip's name for an opening or ending that runs
         * over story, and "recap" is the "previously on" block — skippable for exactly the same
         * reason an opening is.
         */
        val ALL_TYPES = listOf("op", "ed", "mixed-op", "mixed-ed", "recap")

        /** The two every episode with any submission at all has; the fallback list. */
        val CORE_TYPES = listOf("op", "ed")
    }
}
