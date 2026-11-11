package com.anilili.data.remote

import android.content.Context
import com.anilili.data.cache.AppCache
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections

/**
 * Where each provider keeps a given title: `anilistId` → provider → the provider's own slug.
 *
 * This is the expensive half of a catalogue lookup and the stable half. Providers split in two:
 * the id-based ones (anibd 139ms, anikoto 886ms) are handed an AniList or MAL id and answer; the
 * search-based ones have to search their site by title, score the results and pick a match, which
 * is why animeshqip, rareanimes and uniquestream measured 12-14s against anibd's 139ms on the same
 * queue. What that work produces is one short string, and a site's URL for a title effectively
 * never changes — unlike its episode count, which moves every week for anything still airing.
 *
 * So slugs are worth keeping and episode counts are not, and they are deliberately separated here.
 * Two tiers, cheapest first:
 *
 *  1. memory, for the current session;
 *  2. [AppCache] on disk, so what one lookup learned survives the process — the previous store was
 *     an in-memory map bounded to 250 entries and thrown away on every process death, so a viewer
 *     paid the same 12s search again the next morning.
 *
 * There was a third: a slug snapshot shipped in the APK, to answer the *first* lookup on a device
 * that has never seen the title. It shipped empty for its whole life and always would have.
 * `scripts/catalog_seed.py` builds the snapshot from a device's `events-main.jsonl`, and
 * [com.anilili.diagnostics.DiagnosticRedactor] replaces any attribute named `slug` — at write time,
 * so the on-device file never held one either. A viewer's slugs staying on their device is the
 * point of that boundary, so the tier was removed rather than the redaction weakened. Availability
 * is a different matter and still ships: it is a pair of episode counts, which names nothing.
 *
 * A miss at both tiers falls through to the live search exactly as before, and anything learned
 * that way is written back to (1) and (2). Nothing here can make a lookup fail that would
 * otherwise have worked: a stale slug is tried, rejected by the provider, and the caller searches.
 */
internal class CatalogIdentityStore(
    private val context: Context,
    private val cache: AppCache,
    private val json: Json,
) {
    /**
     * Bounded, because what it replaced was. An unbounded map of every slug a long session touched
     * is a slow leak on a TV box, which runs for days on a 96-192 MB heap; the entries are small
     * but nothing ever removed them. Eviction costs at most one repeated lookup, and the disk tier
     * behind it usually answers anyway.
     */
    private val memory: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(MEMORY_SLUGS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) =
                size > MEMORY_SLUGS
        },
    )

    @Volatile private var seedAvailability: Map<String, Pair<Int, Int>>? = null

    private fun key(provider: String, anilistId: Int) = "$provider:$anilistId"

    /** The known slug, or null when nothing has one and the caller must search. */
    suspend fun slug(provider: String, anilistId: Int): String? {
        val key = key(provider, anilistId)
        memory[key]?.let { return it }
        return cache.getIfFresh(cacheKey(key), String.serializer())?.also { stored ->
            memory[key] = stored
            hit(provider, "disk")
        }
    }

    /** Records a slug the live search worked out, so neither this session nor the next repeats it. */
    suspend fun remember(provider: String, anilistId: Int, slug: String) {
        if (slug.isBlank()) return
        val key = key(provider, anilistId)
        if (memory.put(key, slug) == slug) return
        runCatching {
            cache.putBatch(
                mapOf(cacheKey(key) to json.encodeToString(String.serializer(), slug)),
                SLUG_TTL_MS,
            )
            // Without this the store is unfalsifiable from a bundle: a slug served from disk and a
            // slug searched for again look identical once the catalogue timing settles.
            DiagnosticsLog.event(
                category = "catalog",
                name = "catalog.identity.stored",
                attributes = mapOf("provider" to provider, "anilistId" to anilistId),
            )
        }.onFailure { DiagnosticsLog.throwable("Catalogue identity not stored provider=$provider", it) }
    }

    private fun hit(provider: String, tier: String) = DiagnosticsLog.event(
        category = "catalog",
        name = "catalog.identity.hit",
        attributes = mapOf("provider" to provider, "tier" to tier),
    )

    /**
     * Which episodes a provider carries for a title, as shipped in the APK, or null when the
     * snapshot does not cover it.
     *
     * Only settled titles belong in the snapshot. A finished series has the episode count it will
     * always have, so shipping it means a device that has never opened the title still answers
     * without touching the network — which is the whole cold-start cost, since a first visit is
     * the one case the on-disk cache cannot help with. Anything still airing is deliberately
     * absent: it would be wrong within a week and stay wrong until the app updated.
     */
    fun seededAvailability(provider: String, anilistId: Int): Pair<Set<Int>, Set<Int>>? =
        bundledAvailability()["$provider:$anilistId"]
            ?.let { (sub, dub) -> (1..sub).toSet() to (1..dub).toSet() }

    /**
     * Highest sub and dub episode per provider — counts, not the episode numbers themselves.
     *
     * Expanding them here instead would allocate one boxed Integer per episode per provider per
     * title, for the whole snapshot, held for the life of the process. Three titles hides that; a
     * real snapshot of a few thousand would be millions of them, and a TV box has 96-192 MB to
     * work with. The range is built at the one lookup that needs it.
     */
    private fun bundledAvailability(): Map<String, Pair<Int, Int>> {
        seedAvailability?.let { return it }
        val parsed = runCatching {
            context.assets.open(SEED_ASSET).use { stream ->
                val root = json.parseToJsonElement(stream.readBytes().decodeToString()).jsonObject
                buildMap {
                    root["availability"]?.jsonObject?.forEach { (anilistId, providers) ->
                        (providers as? JsonObject)?.forEach { (provider, entry) ->
                            val obj = entry as? JsonObject ?: return@forEach
                            val sub = obj["s"]?.jsonPrimitive?.intOrNull ?: 0
                            val dub = obj["d"]?.jsonPrimitive?.intOrNull ?: 0
                            if (sub > 0 || dub > 0) put("$provider:$anilistId", sub to dub)
                        }
                    }
                }
            }
        }.onFailure { DiagnosticsLog.throwable("Catalogue availability seed unreadable", it) }
            .getOrDefault(emptyMap())
        DiagnosticsLog.event("Catalogue availability seed loaded entries=${parsed.size}")
        seedAvailability = parsed
        return parsed
    }

    private fun cacheKey(key: String) = "slug:v1:$key"

    private companion object {
        const val SEED_ASSET = "catalog-seed.json"

        /** Enough for a long browsing session; the disk tier answers anything evicted. */
        const val MEMORY_SLUGS = 512

        /**
         * Long, because a provider's URL for a title does not drift and a wrong one costs a single
         * failed attempt before the caller searches again. Not forever, so a site that reorganises
         * its slugs is not remembered wrongly for the life of the install.
         */
        const val SLUG_TTL_MS = 60L * 24 * 60 * 60 * 1000
    }
}
