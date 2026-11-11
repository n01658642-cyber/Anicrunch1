package com.anilili.data

import com.anilili.BuildConfig

/**
 * Debug-only switch that makes per-provider measurement possible.
 *
 * Two things defeat a benchmark on the normal resolve path, and both are behaviour we want to keep
 * for real viewers:
 *
 * - the provider in a watch route is a *preference*, not a pin — stored `serverPriority` can still
 *   win, so a run labelled "kiwi" may actually time `anibd`;
 * - [SourceFailureCooldowns] blacklists a provider for 1-10 minutes after any failure, so rapid
 *   repeated probing ends up measuring whichever server was not already cooling.
 *
 * When [pinnedProvider] is set, [MiruroRepository.resolveSources] narrows the attempt list to that
 * one provider and clears every exclusion — cooldowns *and* the set the watch screen has already
 * given up on for this episode. Clearing only the cooldowns was not enough: a pinned provider in
 * the caller's exclusion set produced a resolve with one candidate and zero attempts, which reads
 * in the telemetry exactly like a server the catalogue does not carry, so most of a sweep silently
 * measured nothing. [pinnedProviderOrNull] is hard-gated on [BuildConfig.DEBUG]: in a release build it always
 * returns null, so the shipped resolve path is byte-for-byte the ordinary one.
 */
object ProviderBenchmark {
    @Volatile
    var pinnedProvider: String? = null

    /**
     * Catalogue cache lifetime, in milliseconds, for measurement runs only.
     *
     * A catalogue is cached for two hours, which makes the thing most worth measuring impossible
     * to measure: opening the same title twice fetches nothing the second time, so a change that
     * should make the second visit faster produces no second visit at all. Four separate attempts
     * to time the identity store died on exactly this. Shortening the lifetime for a run makes the
     * cold and warm paths observable back to back.
     */
    @Volatile
    var catalogTtlMs: Long? = null

    /** The measurement lifetime, or null in any release build regardless of what was set. */
    fun catalogTtlOrNull(): Long? =
        if (BuildConfig.DEBUG) catalogTtlMs?.takeIf { it > 0L } else null

    /**
     * Forces the next catalogue build to ask every provider, as a viewer's refresh does.
     *
     * The refresh path is the only one that can catch a remembered answer that has gone wrong, and
     * it is reached from a gesture on the watch screen — which a scripted run cannot make. Without
     * this, the drift check could only be exercised by hand, so in practice it would ship untested.
     */
    @Volatile
    var forceCatalogRefresh: Boolean = false

    /** Whether to bypass remembered availability, always false in a release build. */
    fun forceCatalogRefreshEnabled(): Boolean = BuildConfig.DEBUG && forceCatalogRefresh

    /**
     * Host suffixes the network's own resolver is made to answer with 0.0.0.0, the way a blocking
     * ISP does, so [com.anilili.data.remote.AutoDns] can be exercised end to end on a real device.
     * There is no other way to reproduce a DNS block on demand: the emulators cannot be rooted to
     * redirect their DNS, and the host's port 53 belongs to a Windows service.
     */
    @Volatile
    var dnsSinkhole: Set<String> = emptySet()

    /** Whether [host] is being sinkholed; always false in a release build. */
    fun sinkholes(host: String): Boolean {
        if (!BuildConfig.DEBUG) return false
        val name = host.lowercase().trimEnd('.')
        return dnsSinkhole.any { name == it || name.endsWith(".$it") }
    }

    /** The pinned provider, or null in any release build regardless of what was set. */
    fun pinnedProviderOrNull(): String? =
        if (BuildConfig.DEBUG) pinnedProvider?.takeIf { it.isNotBlank() } else null

    /** Intent extra carrying the provider to pin, honoured only by debug builds. */
    const val EXTRA_PIN = "com.miruronative.extra.BENCH_PROVIDER"

    /** Intent extra carrying [catalogTtlMs], honoured only by debug builds. */
    const val EXTRA_CATALOG_TTL = "com.miruronative.extra.BENCH_CATALOG_TTL_MS"

    /** Intent extra setting [dnsSinkhole] as comma-separated suffixes; empty clears it. */
    const val EXTRA_DNS_SINKHOLE = "com.miruronative.extra.BENCH_DNS_SINKHOLE"

    /** Intent extra setting [forceCatalogRefresh], honoured only by debug builds. */
    const val EXTRA_CATALOG_REFRESH = "com.miruronative.extra.BENCH_CATALOG_REFRESH"
}
