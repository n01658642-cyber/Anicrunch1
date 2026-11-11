package com.anilili.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * How quickly each server has actually handed this device a playable stream, learned from real
 * resolves and used to rank servers when the viewer has not pinned one.
 *
 * The static order in [ProviderCatalog] is an editorial guess made on one phone; which server is
 * quick for *this* viewer depends on their network, their region and which upstreams are having a
 * bad week. So every resolve outcome is folded into two running averages per server — how long a
 * success takes, and how often an attempt succeeds at all — and the ranking is the expected wait
 * those two imply: a server that answers in 400ms half the time is worse than one that answers in
 * 900ms every time.
 *
 * ### Per network
 *
 * Kept per [NetworkIdentity] as well as for the device as a whole, because a phone's home Wi-Fi and
 * its mobile data are different worlds: a server that is quick at home can be slow or blocked on a
 * carrier. A network seen for the first time borrows the device-wide picture entirely, shifts onto
 * its own numbers as they accumulate, and uses only its own once it has a few. Nothing is shared
 * between devices; each install learns alone.
 *
 * Only outcomes the resolver believed are recorded. An attempt cancelled because another server
 * won the race never reaches [record] (see `resolveProviderCandidatesHedged`), so losing a race is
 * not mistaken for being slow or broken.
 *
 * Persisted in its own preferences file rather than [com.anilili.data.settings.SettingsStore],
 * whose every write re-applies all preferences.
 */
object ProviderSpeed {

    @Serializable
    internal data class Stats(
        /** Running average of successful resolve time, in milliseconds. */
        val resolveMs: Double,
        /** Running average of 1 for a stream, 0 for anything else. */
        val success: Double,
        val samples: Int,
    )

    @Serializable
    internal data class NetworkRecord(
        val providers: Map<String, Stats> = emptyMap(),
        /** Wall-clock time of the last sample, for evicting networks not seen in a while. */
        val lastUsedMs: Long = 0L,
    )

    private const val PREFS = "provider_speed"
    private const val KEY_V1 = "stats_v1"
    private const val KEY = "stats_v2"

    /** The device-wide record, blended under every network's own. */
    internal const val DEVICE = "*"

    /** Enough for home, work, a couple of friends' Wi-Fi and a carrier or two. */
    private const val MAX_NETWORKS = 12

    /**
     * How many of its own samples a network needs before its numbers are used alone. Below this the
     * device-wide record fills in the rest; at it, the device's numbers stop counting, because they
     * mix every other network in — a carrier that refuses a server would otherwise drag that server
     * down at home too, which is the very thing keeping records per network is for.
     */
    private const val NETWORK_CONFIDENCE = 4.0

    /** Weight of the newest sample. High enough to follow a server that has just gone bad. */
    private const val ALPHA = 0.3

    /**
     * What a failed attempt costs on top of the average success time: roughly the time a viewer
     * spends waiting on a server that turns out not to answer before the next one gets its turn.
     */
    private const val FAILURE_COST_MS = 6_000.0

    /**
     * Anything faster than this was served from memory — a preheated resolve or a cached answer —
     * and says nothing about the server, so it is not allowed to flatter it.
     */
    private const val MIN_BELIEVABLE_MS = 25L

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), NetworkRecord.serializer())
    private val records = ConcurrentHashMap<String, NetworkRecord>()
    @Volatile private var prefs: SharedPreferences? = null

    /** Which network a sample belongs to. Swappable so tests need no Android framework. */
    @Volatile internal var currentNetwork: () -> String = NetworkIdentity::current

    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching {
            store.getString(KEY, null)?.let { json.decodeFromString(serializer, it) }
                // The first version kept one device-wide record; it becomes that record here.
                ?: store.getString(KEY_V1, null)?.let {
                    val v1 = json.decodeFromString(MapSerializer(String.serializer(), Stats.serializer()), it)
                    mapOf(DEVICE to NetworkRecord(v1, System.currentTimeMillis()))
                }
        }.getOrNull()?.let(records::putAll)
        prefs = store
    }

    /** Folds one believed resolve outcome into [provider]'s averages, here and device-wide. */
    fun record(provider: String, succeeded: Boolean, durationMs: Long) {
        val name = provider.lowercase()
        if (succeeded && durationMs < MIN_BELIEVABLE_MS) return
        val now = System.currentTimeMillis()
        for (network in setOf(currentNetwork(), DEVICE)) {
            records.compute(network) { _, record ->
                val providers = record?.providers.orEmpty()
                val old = providers[name]
                val prior = old ?: prior(name)
                val updated = Stats(
                    resolveMs = if (succeeded) prior.resolveMs + ALPHA * (durationMs - prior.resolveMs) else prior.resolveMs,
                    success = prior.success + ALPHA * ((if (succeeded) 1.0 else 0.0) - prior.success),
                    samples = (old?.samples ?: 0) + 1,
                )
                NetworkRecord(providers + (name to updated), now)
            }
        }
        evictStaleNetworks()
        persist()
    }

    /**
     * Expected wait for a stream from [provider] on the current network, lower is better. Servers
     * never measured anywhere get a prior from what [ProviderCatalog] already knows about their
     * shape, so a new install still starts from a sensible order and a new server is neither buried
     * nor promoted on no evidence.
     */
    fun score(provider: String): Double {
        val name = provider.lowercase()
        val device = expectedWait(records[DEVICE]?.providers?.get(name) ?: prior(name))
        val network = currentNetwork().takeIf { it != DEVICE }
            ?.let { records[it]?.providers?.get(name) }
            ?: return device
        val weight = (network.samples / NETWORK_CONFIDENCE).coerceAtMost(1.0)
        return weight * expectedWait(network) + (1 - weight) * device
    }

    /** [names] fastest first on this network; ties keep the catalogue's own order. */
    fun rank(names: List<String>): List<String> =
        names.sortedWith(compareBy<String> { score(it) }.thenBy { ProviderCatalog.sortKey(it) })

    private fun expectedWait(s: Stats): Double = s.resolveMs + (1.0 - s.success) * FAILURE_COST_MS

    internal fun prior(name: String): Stats = when {
        ProviderCatalog.webViewResolverProviders.contains(name) -> Stats(6_000.0, 0.7, 0)
        ProviderCatalog.isFast(name) -> Stats(1_200.0, 0.85, 0)
        else -> Stats(3_500.0, 0.75, 0)
    }

    internal fun resetForTest() {
        records.clear()
        prefs = null
        currentNetwork = { "test-network" }
    }

    private fun evictStaleNetworks() {
        val networks = records.keys.filter { it != DEVICE }
        if (networks.size <= MAX_NETWORKS) return
        networks.sortedBy { records[it]?.lastUsedMs ?: 0L }
            .take(networks.size - MAX_NETWORKS)
            .forEach(records::remove)
    }

    private fun persist() {
        val store = prefs ?: return
        store.edit()
            .putString(KEY, json.encodeToString(serializer, HashMap(records)))
            .remove(KEY_V1)
            .apply()
    }
}
