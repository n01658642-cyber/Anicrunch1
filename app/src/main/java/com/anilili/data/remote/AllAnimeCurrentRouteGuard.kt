package com.anilili.data.remote

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Suppresses only AllAnime's versioned current route after a deterministic protocol mismatch.
 * Catalog search and the legacy source route remain available, so one broken route cannot disable
 * the whole provider.
 */
internal class AllAnimeCurrentRouteGuard(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var blockedUntilMs = 0L

    fun shouldAttempt(): Boolean = nowMs() >= blockedUntilMs

    @Synchronized
    fun recordSuccess() {
        blockedUntilMs = 0L
    }

    @Synchronized
    fun recordFailure(throwable: Throwable): Boolean {
        if (!isDeterministicProtocolFailure(throwable)) return false
        blockedUntilMs = nowMs() + cooldownMs
        return true
    }

    internal fun isDeterministicProtocolFailure(throwable: Throwable): Boolean {
        val failure = generateSequence(throwable) { it.cause }.toList()
        val http = failure.filterIsInstance<AllAnimeHttpException>().firstOrNull()
        val text = failure.joinToString(" ") { it.message.orEmpty() } + " " + http?.responseBody.orEmpty()
        val protocolMarker = PROTOCOL_MARKERS.any { text.contains(it, ignoreCase = true) }
        if (http != null) return http.code in PROTOCOL_HTTP_CODES && protocolMarker
        return protocolMarker && LOCAL_PROTOCOL_MARKERS.any { text.contains(it, ignoreCase = true) }
    }

    companion object {
        private const val DEFAULT_COOLDOWN_MS = 30L * 60L * 1_000L
        private val PROTOCOL_HTTP_CODES = setOf(400, 401, 403, 404, 422)
        private val PROTOCOL_MARKERS = listOf(
            "missing_or_invalid_lane",
            "invalid lane",
            "boot token",
            "build id",
            "build-id",
            "persistedquery",
            "persisted query",
            "crypto bootstrap",
            "aa_req",
            "aareq",
        )
        private val LOCAL_PROTOCOL_MARKERS = listOf(
            "current route",
            "bootstrap",
            "epoch",
            "build",
            "persisted",
            "aa_req",
            "aareq",
        )
    }
}

internal class AllAnimeHttpException(
    val code: Int,
    val responseBody: String,
) : IOException("AllAnime HTTP $code")

/**
 * Short per-endpoint cooldown after a 5xx.
 *
 * A title resolve searches every AniList alias, each search retries once, and each attempt tries
 * the current endpoint and then the legacy one — so a single upstream outage fans one dead server
 * out into a dozen identical requests in a few seconds, which is exactly what the 502 spells in
 * the diagnostics bundles look like. Cooling each endpoint separately (rather than the host) keeps
 * the legacy fallback reachable when both routes happen to share one origin.
 *
 * Deliberately separate from [AllAnimeCurrentRouteGuard]: that one suppresses a *protocol*
 * mismatch for half an hour, far too blunt for an outage that usually clears in under a minute.
 */
internal class AllAnimeEndpointOutageGuard(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val blockedUntilMs = ConcurrentHashMap<String, Long>()

    fun shouldAttempt(endpoint: String): Boolean = nowMs() >= (blockedUntilMs[endpoint] ?: 0L)

    fun recordSuccess(endpoint: String) {
        blockedUntilMs.remove(endpoint)
    }

    fun recordFailure(endpoint: String, throwable: Throwable): Boolean {
        if (!isUpstreamOutage(throwable)) return false
        blockedUntilMs[endpoint] = nowMs() + cooldownMs
        return true
    }

    companion object {
        private const val DEFAULT_COOLDOWN_MS = 60L * 1_000L
    }
}

/**
 * Whether the host answered with a 5xx — it has already told us it is broken, so an immediate
 * retry against the same host can only repeat the same answer.
 */
internal fun isUpstreamOutage(throwable: Throwable): Boolean =
    generateSequence(throwable) { it.cause }
        .filterIsInstance<AllAnimeHttpException>()
        .any { it.code in 500..599 }
