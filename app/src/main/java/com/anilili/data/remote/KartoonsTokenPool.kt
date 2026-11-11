package com.anilili.data.remote

import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Which account tokens a [KartoonsTokenPool] hands out, and when.
 *
 * Deliberately network-free so the rotation policy is unit-testable: the pool wraps this with the
 * mirror fetch, the rotator only answers for what it was given.
 *
 * The policy is stickiness plus escalation. Each caller keeps its last healthy token ([pick]
 * returns it again), which is what spreads load without any coordination: every process shuffles
 * the list for itself, so N devices spread across M tokens roughly N/M each, and no token sees
 * every playback of every device. The pick is per process rather than per install — a restart may
 * land on a different token, which spreads load the same way and needs nothing stored. An auth refusal puts its token on a cooldown that doubles each time
 * (10 minutes to two days) and hands the caller the next healthy one immediately — a viewer
 * waiting for a stream never waits for the pool. A banned account never heals, so it is dropped
 * outright until the process restarts or [addAll] brings a refreshed list.
 */
internal class KartoonsTokenRotator(
    tokens: List<String>,
    private val now: () -> Long = System::currentTimeMillis,
    random: Random = Random.Default,
) {
    private data class State(var failures: Int = 0, var cooldownUntil: Long = 0, var banned: Boolean = false)

    // Insertion order is the fallback order, so the initial shuffle is the whole load-spreading
    // story: two installs that lose their sticky token at the same moment do not both land on the
    // same replacement.
    private val states = LinkedHashMap<String, State>()
    @Volatile private var sticky: String? = null

    init {
        tokens.distinct().shuffled(random).forEach { states[it] = State() }
    }

    /** The sticky token while healthy, else the first healthy token in fallback order. */
    @Synchronized
    fun pick(): String? {
        sticky?.let { current ->
            val state = states[current]
            if (state != null && !state.banned && now() >= state.cooldownUntil) return current
        }
        val candidate = states.entries
            .firstOrNull { (_, state) -> !state.banned && now() >= state.cooldownUntil }
            ?.key ?: return null
        sticky = candidate
        return candidate
    }

    @Synchronized
    fun markFailed(token: String) {
        val state = states[token] ?: return
        state.failures++
        state.cooldownUntil = now() + COOLDOWNS[(state.failures - 1).coerceIn(0, COOLDOWNS.size - 1)]
        if (sticky == token) sticky = null
    }

    @Synchronized
    fun markBanned(token: String) {
        val state = states[token] ?: return
        state.banned = true
        if (sticky == token) sticky = null
    }

    /** Merges a refreshed pool from the mirror; known tokens keep their health state. */
    @Synchronized
    fun addAll(tokens: Collection<String>) {
        tokens.filter { it.isNotBlank() }.forEach { states.putIfAbsent(it, State()) }
    }

    @Synchronized
    fun hasHealthy(): Boolean = states.values.any { !it.banned && now() >= it.cooldownUntil }

    @Synchronized
    fun size(): Int = states.size

    private companion object {
        /** Doubled per failure; the top step means a token failing four times is out for two days. */
        val COOLDOWNS = listOf(
            TimeUnit.MINUTES.toMillis(10),
            TimeUnit.HOURS.toMillis(1),
            TimeUnit.HOURS.toMillis(6),
            TimeUnit.HOURS.toMillis(48),
        )
    }
}

/**
 * The account tokens that unlock Kartoons' headless CloudStream API, with rotation and failover.
 *
 * Kartoons' `/cloudstream/episode/{id}/links` accepts one credential and nothing else — a `?token=`
 * query parameter (verified 2026-10-03: the surface rejects PoW headers, Turnstile tokens, the web
 * JWT and Bearer forms alike). A token is account-scoped: minting one from a second device returns
 * the same value, so the pool below is a list of accounts, not of devices. The tokens ship baked in
 * so the provider works offline and on first run, and a refresh file on the update mirror can add
 * or replace them without an app release — which is the recovery path when an account dies.
 *
 * The mirror file is fetched once per process, in the background, the first time a token is
 * wanted: on 2026-10-03 the fetch sat in front of every cold first play and cost 310–340 ms each
 * time — for a file that, while the baked-in list is healthy, changes nothing about that play. It is
 * fetched in line only when every token is unhealthy, once per process, because then it is the one
 * thing that can produce an answer. Its format is `{"tokens": [{"t": "..."}]}`
 * ([KartoonsParser.parseTokenPool]); a failed fetch is silent because the baked-in list answers
 * either way.
 */
internal class KartoonsTokenPool(
    private val client: OkHttpClient,
    private val json: Json,
    private val scope: CoroutineScope,
    tokens: List<String> = BAKED_IN,
    private val mirrorUrl: String = TOKENS_MIRROR_URL,
) {
    // Each process its own fallback order, without coordinating with anything.
    private val rotator = KartoonsTokenRotator(tokens = tokens)
    private val mirrorStarted = AtomicBoolean(false)
    private val mirrorRetriedOnExhaustion = AtomicBoolean(false)

    /**
     * A token to put in the `?token=` query, or throws when none is usable.
     *
     * Throwing rather than returning null keeps the provider's resolve path a plain expression —
     * there is no useful way to answer a links request without a credential.
     */
    fun pick(): String {
        if (mirrorStarted.compareAndSet(false, true)) {
            scope.launch { refreshFromMirror(forced = false) }
        }
        rotator.pick()?.let { return it }
        // Every token is cooling down or banned. One forced mirror refresh per process covers the
        // case the baked-in list was built for: a dead account replaced by a live one without an
        // app update. Retrying on every pick would hammer the mirror while a provider retries.
        if (mirrorRetriedOnExhaustion.compareAndSet(false, true)) refreshFromMirror(forced = true)
        return rotator.pick() ?: error("Kartoons has no usable account token")
    }

    fun markFailed(token: String) {
        DiagnosticsLog.event(
            category = "provider",
            name = "kartoons.token.failed",
            attributes = mapOf("token" to describe(token)),
        )
        rotator.markFailed(token)
    }

    fun markBanned(token: String) {
        DiagnosticsLog.event(
            category = "provider",
            name = "kartoons.token.banned",
            attributes = mapOf("token" to describe(token)),
        )
        rotator.markBanned(token)
    }

    private fun refreshFromMirror(forced: Boolean) {
        val fetched = runCatching {
            val request = Request.Builder()
                .url(mirrorUrl)
                .header("Accept", "application/json")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                KartoonsParser.parseTokenPool(json, response.body?.string().orEmpty())
            }
        }
            .onFailure { DiagnosticsLog.throwable("Kartoons token mirror fetch failed", it) }
            .getOrNull()
            .orEmpty()
        if (fetched.isNotEmpty()) rotator.addAll(fetched)
        DiagnosticsLog.event(
            category = "provider",
            name = "kartoons.tokens.refreshed",
            attributes = mapOf("fetched" to fetched.size, "forced" to forced),
        )
    }

    /**
     * A token shortened to what diagnostics can correlate on. The full value is the credential
     * itself; diagnostic bundles leave this device, so only a prefix may travel.
     */
    private fun describe(token: String) = token.take(4) + "…"

    private companion object {
        /**
         * Kartoons account tokens minted via the site's own Stremio flow (sign in, enable the
         * addon, the token is in the generated manifest URL). Each is one whole account: a token
         * that dies takes its account with it, which is why the pool exists and why the mirror
         * refresh path must keep working.
         *
         * These are credentials by any reading — they ship in every APK, which is the point, but
         * they are not placeholders and new accounts should extend this list through the mirror
         * file first.
         */
        val BAKED_IN = listOf(
            // account 1 (minted 2026-10-03)
            "iBusCdh5-L1Q-2-AYohA6BM-PxazVhRtbb1azsHV524",
            // account 2 (2026-10-03)
            "bbytX_j6mSa4UXcPXhkghKSpm3fonM2n9hQI9nNKdoQ",
            // account 3 (2026-10-03)
            "yQarz-Kjvnw1idAFFvuT-XOtNrfGcJx-fqU0k3jEifI",
        )

        /**
         * The same mirror that serves the update manifest and announcements, so a token refresh
         * reaches installs without an app release and without jsDelivr's twelve-hour cache.
         */
        const val TOKENS_MIRROR_URL =
            "https://huggingface.co/HF_USER/HF_REPO/resolve/main/latest/kartoons-tokens.json"
    }
}
