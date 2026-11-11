package com.anilili.data.cache

import android.app.ActivityManager
import android.content.Context
import android.system.Os
import com.anilili.data.AppGraph
import com.anilili.diagnostics.DiagnosticsLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Bounded two-level cache used for public metadata and episode catalogs.
 *
 * Disk entries are independent atomic files. The previous Room implementation invalidated a query
 * on every read-time access update; Room's tracker then tried to allocate a 4 MB CursorWindow and
 * could terminate low-memory TV devices even though the query itself contained almost no data.
 * Cache data is re-derivable from the network, so a small file store is both safer and simpler.
 *
 * Fresh entries return immediately. Expired-but-usable entries implement stale-while-revalidate:
 * the caller gets the last successful value while one coalesced refresh happens in the background.
 * Stream URLs are deliberately not stored here because provider signatures often expire quickly.
 */
class AppCache(
    context: Context,
    private val json: Json,
) {
    private val appContext = context.applicationContext
    private val diskDirectory = File(appContext.filesDir, CACHE_DIRECTORY).apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyLocks = Array(64) { Mutex() }
    private val isLowRamDevice =
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    private val memoryEntryLimit = if (AppGraph.isTv || isLowRamDevice) 40 else MEMORY_ENTRIES
    private val memoryByteLimit =
        if (AppGraph.isTv || isLowRamDevice) LOW_MEMORY_BYTES else MEMORY_BYTES
    private val diskEntryLimit = if (isLowRamDevice) LOW_RAM_DISK_ENTRIES else DISK_ENTRIES
    private val memory = LinkedHashMap<String, CacheEntry>(memoryEntryLimit, 0.75f, true)
    private var memoryBytes = 0L
    private val pendingTouches = LinkedHashMap<String, Long>()
    private var touchFlushJob: Job? = null

    init {
        DiagnosticsLog.event(
            "AppCache file store memoryEntries=$memoryEntryLimit memoryBytes=$memoryByteLimit " +
                "diskEntries=$diskEntryLimit lowRam=$isLowRamDevice tv=${AppGraph.isTv}",
        )
        // The old database contains only re-fetchable network data. Delete it off the main thread
        // after switching backends so Room and CursorWindow can no longer enter this cache path.
        scope.launch {
            runCatching { appContext.deleteDatabase(LEGACY_DATABASE_NAME) }
                .onFailure { DiagnosticsLog.throwable("Legacy cache database cleanup failed", it) }
        }
    }

    suspend fun <T> getOrFetch(
        key: String,
        serializer: KSerializer<T>,
        ttlMs: Long,
        staleForMs: Long = DEFAULT_STALE_MS,
        forceRefresh: Boolean = false,
        /** Do not persist a freshly fetched value when it is known to be incomplete. */
        cacheIf: (T) -> Boolean = { true },
        /**
         * How long to believe *this* answer, when that cannot be decided before fetching it.
         *
         * [ttlMs] is the lifetime of a complete answer. A partially complete one is still worth
         * storing — refusing it altogether refetches on every single read — but must not be
         * believed for as long, or one bad pass is pinned for the full lifetime. Returning a
         * shorter value here is what turns that into a background refresh a few minutes later
         * instead. Defaults to [ttlMs], so every existing caller is unchanged.
         */
        ttlMsFor: ((T) -> Long)? = null,
        /**
         * True when a stored value is known to be wrong now, whatever its lifetime says — given the
         * value and when it was fetched.
         *
         * A lifetime is a guess made at write time; some answers carry the moment they stop being
         * true. Such a value is fetched again rather than served, fresh or stale, and is still
         * what comes back if that fetch fails.
         */
        outdated: ((T, fetchedAtMs: Long) -> Boolean)? = null,
        /**
         * The oldest stored answer worth returning, whatever its lifetime says — anything older is
         * fetched again and waited for rather than served.
         *
         * For a screen that has already drawn the cached value and wants the live one in its
         * place. Unlike [forceRefresh] it reuses an answer a refresh already in flight is about to
         * store — the stale branch below launches exactly such a refresh on the read that drew the
         * screen — so asking straight afterwards costs one request, not two.
         */
        maxAgeMs: Long? = null,
        fetch: suspend () -> T,
    ): T {
        val now = System.currentTimeMillis()
        val cached = read(key)
        val decoded = cached?.let { decode(it, serializer) }
        val superseded = cached != null && decoded != null &&
            (outdated?.invoke(decoded, cached.createdAt) == true ||
                (maxAgeMs != null && now - cached.createdAt >= maxAgeMs))
        if (superseded && !forceRefresh) DiagnosticsLog.event("AppCache refetching outdated value key=$key")
        if (!forceRefresh && !superseded && cached != null && decoded != null && now <= cached.expiresAt) {
            touch(cached, now)
            return decoded
        }
        if (!forceRefresh && !superseded && cached != null && decoded != null &&
            now <= cached.expiresAt + staleForMs
        ) {
            touch(cached, now)
            scope.launch {
                try {
                    refresh(key, serializer, ttlMs, false, cacheIf, ttlMsFor, outdated, null, fetch)
                } catch (_: Exception) {
                    // The stale value remains available until its safety window closes.
                }
            }
            return decoded
        }
        return try {
            refresh(key, serializer, ttlMs, forceRefresh, cacheIf, ttlMsFor, outdated, maxAgeMs, fetch)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            decoded ?: throw e
        }
    }

    suspend fun hasKey(key: String): Boolean = read(key) != null

    /**
     * Drops every entry, in memory and on disk. For Settings → Re-download metadata, which restarts
     * the app straight afterwards — an entry a request still in flight writes back in the meantime
     * is a fresh answer, so it is harmless.
     */
    suspend fun clearAll() {
        synchronized(memory) {
            memory.clear()
            memoryBytes = 0L
        }
        synchronized(pendingTouches) { pendingTouches.clear() }
        withContext(Dispatchers.IO) {
            diskDirectory.listFiles().orEmpty().forEach(File::delete)
        }
        DiagnosticsLog.event("AppCache cleared")
    }

    /** Fresh-only read: the decoded value when [key] exists and has not expired, else null. */
    suspend fun <T> getIfFresh(key: String, serializer: KSerializer<T>): T? {
        val now = System.currentTimeMillis()
        val entry = read(key) ?: return null
        if (now > entry.expiresAt) return null
        val value = decode(entry, serializer) ?: return null
        touch(entry, now)
        return value
    }

    suspend fun putBatch(entries: Map<String, String>, ttlMs: Long) {
        val now = System.currentTimeMillis()
        entries.forEach { (key, jsonString) ->
            val entry = CacheEntry(
                key = key,
                payload = CachePayloadCodec.encode(jsonString),
                createdAt = now,
                expiresAt = now + ttlMs,
                lastAccessedAt = now,
            )
            write(entry)
            putMemory(entry)
        }
        prune(now)
    }

    private suspend fun <T> refresh(
        key: String,
        serializer: KSerializer<T>,
        ttlMs: Long,
        forceRefresh: Boolean,
        cacheIf: (T) -> Boolean,
        ttlMsFor: ((T) -> Long)?,
        outdated: ((T, Long) -> Boolean)?,
        maxAgeMs: Long?,
        fetch: suspend () -> T,
    ): T {
        val lock = keyLocks[(key.hashCode() and Int.MAX_VALUE) % keyLocks.size]
        return lock.withLock {
            val now = System.currentTimeMillis()
            val newer = read(key)
            if (!forceRefresh && newer != null && now <= newer.expiresAt &&
                (maxAgeMs == null || now - newer.createdAt < maxAgeMs)
            ) {
                decode(newer, serializer)
                    ?.takeUnless { outdated?.invoke(it, newer.createdAt) == true }
                    ?.let { return@withLock it }
            }

            val value = fetch()
            if (!cacheIf(value)) {
                DiagnosticsLog.event("AppCache skipped storing degraded value key=$key")
                return@withLock value
            }
            val lifetimeMs = ttlMsFor?.invoke(value)?.coerceAtLeast(0L) ?: ttlMs
            if (lifetimeMs != ttlMs) {
                DiagnosticsLog.event(
                    "AppCache shortened lifetime key=$key ttlMs=$lifetimeMs fullTtlMs=$ttlMs",
                )
            }
            val entry = CacheEntry(
                key = key,
                payload = CachePayloadCodec.encode(json.encodeToString(serializer, value)),
                createdAt = now,
                expiresAt = now + lifetimeMs,
                lastAccessedAt = now,
            )
            write(entry)
            putMemory(entry)
            prune(now)
            value
        }
    }

    private suspend fun read(key: String): CacheEntry? {
        synchronized(memory) { memory[key] }?.let { return it }
        val entry = withContext(Dispatchers.IO) { readDisk(key) } ?: return null
        putMemory(entry)
        return entry
    }

    private fun readDisk(key: String): CacheEntry? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                CacheFileCodec.read(input, key, file.lastModified())
            }
        }.onFailure {
            file.delete()
            DiagnosticsLog.throwable("AppCache discarded corrupt entry key=$key", it)
        }.getOrNull()
    }

    private suspend fun write(entry: CacheEntry) = withContext(Dispatchers.IO) {
        diskDirectory.mkdirs()
        val target = fileFor(entry.key)
        val temporary = File(diskDirectory, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            val fileOutput = FileOutputStream(temporary)
            DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                CacheFileCodec.write(output, entry)
                output.flush()
                fileOutput.fd.sync()
            }
            atomicReplace(temporary, target)
            target.setLastModified(entry.lastAccessedAt)
        } finally {
            temporary.delete()
        }
    }

    private suspend fun <T> decode(entry: CacheEntry, serializer: KSerializer<T>): T? =
        try {
            json.decodeFromString(serializer, CachePayloadCodec.decode(entry.payload))
        } catch (_: Exception) {
            withContext(Dispatchers.IO) { fileFor(entry.key).delete() }
            removeMemory(entry.key)
            null
        }

    private fun touch(entry: CacheEntry, now: Long) {
        putMemory(entry.copy(lastAccessedAt = now))
        synchronized(pendingTouches) {
            pendingTouches[entry.key] = now
            if (touchFlushJob?.isActive != true) scheduleTouchFlushLocked()
        }
    }

    /** Must be called while holding [pendingTouches]' monitor. */
    private fun scheduleTouchFlushLocked() {
        touchFlushJob = scope.launch {
            delay(TOUCH_FLUSH_INTERVAL_MS)
            flushTouches()
            synchronized(pendingTouches) {
                touchFlushJob = null
                if (pendingTouches.isNotEmpty()) scheduleTouchFlushLocked()
            }
        }
    }

    private suspend fun flushTouches() = withContext(Dispatchers.IO) {
        val touches = synchronized(pendingTouches) {
            pendingTouches.toMap().also { pendingTouches.clear() }
        }
        touches.forEach { (key, timestamp) ->
            fileFor(key).takeIf(File::isFile)?.setLastModified(timestamp)
        }
        if (touches.isNotEmpty()) DiagnosticsLog.event("AppCache flushed touches=${touches.size}")
    }

    private suspend fun prune(now: Long) = withContext(Dispatchers.IO) {
        flushTouches()
        val entries = diskDirectory.listFiles { file -> file.extension == CACHE_FILE_EXTENSION }
            .orEmpty()
        val staleCutoff = now - DISK_STALE_RETENTION_MS
        entries.filter { it.lastModified() < staleCutoff }.forEach(File::delete)
        val retained = entries.filter(File::exists).sortedBy(File::lastModified)
        val overflow = retained.size - diskEntryLimit
        if (overflow > 0) retained.take(overflow).forEach(File::delete)
        DiagnosticsLog.event(
            "AppCache prune files=${retained.size} bytes=${retained.sumOf(File::length)} " +
                "overflow=${overflow.coerceAtLeast(0)}",
        )
    }

    private fun putMemory(entry: CacheEntry) {
        synchronized(memory) {
            memory.remove(entry.key)?.let { memoryBytes -= it.memorySizeBytes() }
            memory[entry.key] = entry
            memoryBytes += entry.memorySizeBytes()
            val iterator = memory.entries.iterator()
            while ((memory.size > memoryEntryLimit || memoryBytes > memoryByteLimit) && iterator.hasNext()) {
                val eldest = iterator.next()
                memoryBytes -= eldest.value.memorySizeBytes()
                iterator.remove()
            }
        }
    }

    private fun removeMemory(key: String) {
        synchronized(memory) {
            memory.remove(key)?.let { memoryBytes -= it.memorySizeBytes() }
        }
    }

    private fun fileFor(key: String): File = File(diskDirectory, "${key.sha256()}.$CACHE_FILE_EXTENSION")

    private companion object {
        const val CACHE_DIRECTORY = "metadata-cache-v2"
        const val CACHE_FILE_EXTENSION = "cache"
        const val LEGACY_DATABASE_NAME = "anilili-cache.db"
        const val MEMORY_ENTRIES = 150
        const val MEMORY_BYTES = 8L * 1024 * 1024
        const val LOW_MEMORY_BYTES = 2L * 1024 * 1024
        const val DISK_ENTRIES = 800
        const val LOW_RAM_DISK_ENTRIES = 300
        const val TOUCH_FLUSH_INTERVAL_MS = 5L * 60 * 1000
        const val DEFAULT_STALE_MS = 7L * 24 * 60 * 60 * 1000
        const val DISK_STALE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}

private fun CacheEntry.memorySizeBytes(): Long =
    (key.length.toLong() + payload.length.toLong()) * 2L + 32L

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun atomicReplace(source: File, target: File) {
    runCatching { Os.rename(source.absolutePath, target.absolutePath) }
        .getOrElse {
            if (target.exists() && !target.delete()) error("Could not replace cache entry")
            if (!source.renameTo(target)) error("Could not publish cache entry")
        }
}
