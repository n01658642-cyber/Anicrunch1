package com.anilili.playback

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import com.anilili.data.AppGraph
import com.anilili.data.settings.SettingsStore
import com.anilili.data.settings.VideoCacheSize
import com.anilili.diagnostics.DiagnosticsLog
import java.io.File
import java.util.TreeSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Singleton, system-evictable disk cache for manifests and already-streamed video segments.
 *
 * Its size used to be a flat 512 MB everywhere, which had it backwards: a TV box with 8 GB of
 * storage and a gigabyte free gave half of that gigabyte to segments, while a phone with a hundred
 * free could have kept far more. It is now [videoCacheBudget] of whatever [VideoCacheSize] the
 * viewer picked, recomputed whenever that setting changes and applied to the running cache on the
 * spot — the evictor below trims down to a smaller size immediately rather than at the next launch.
 */
@OptIn(UnstableApi::class)
object MediaCache {
    @Volatile private var instance: SimpleCache? = null
    private val evictor = ResizableLruCacheEvictor(DEFAULT_BYTES)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _appliedBytes = MutableStateFlow<Long?>(null)

    /**
     * The limit the open cache was last trimmed to, or null before it has been opened. Settings
     * re-measures on a change, because a smaller size has just deleted files.
     */
    val appliedBytes: StateFlow<Long?> = _appliedBytes.asStateFlow()

    /**
     * False when the viewer turned the cache off. Playback and the index warm-up both read this per
     * data source, so the switch reaches an episode already playing from its next request.
     */
    val enabled: Boolean get() = SettingsStore.videoCacheSize.value != VideoCacheSize.OFF

    fun get(context: Context): SimpleCache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(context.applicationContext.cacheDir, DIRECTORY),
            evictor,
            // Shared with the download index. Both caches live in the same exoplayer_internal.db,
            // so a second provider means a second SQLiteOpenHelper on one file — duplicated setup
            // and needless lock contention between playback and downloads.
            ExoDatabase.provider(context),
        ).also { cache ->
            instance = cache
            val app = context.applicationContext
            // StateFlow collection replays the current value, so this also applies the stored
            // choice the first time — the constructor's default only lasts until then.
            scope.launch {
                SettingsStore.videoCacheSize.collect { size -> resize(app, cache, size) }
            }
        }
    }

    /** The size the cache is held to right now, in bytes. Zero when it is off. */
    fun budgetBytes(context: Context): Long =
        videoCacheBudget(SettingsStore.videoCacheSize.value, readStorage(context))

    private fun resize(context: Context, cache: SimpleCache, size: VideoCacheSize) {
        val storage = readStorage(context)
        val budget = videoCacheBudget(size, storage)
        // The evictor is only ever called under the cache's own monitor, so the trim takes it too.
        synchronized(cache) { evictor.resize(cache, budget) }
        _appliedBytes.value = budget
        DiagnosticsLog.event(
            "MediaCache budget size=${size.storedValue} bytes=$budget tv=${storage.isTv} " +
                "lowRam=${storage.isLowRamDevice} freeMb=${storage.freeBytes / MB} " +
                "cachedMb=${storage.cachedBytes / MB} quotaMb=${storage.systemQuotaBytes?.div(MB) ?: -1}",
        )
    }

    private fun readStorage(context: Context): CacheStorage {
        val app = context.applicationContext
        val root = app.cacheDir
        val free = runCatching { StatFs(root.absolutePath).availableBytes }.getOrDefault(0L)
        val quota = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val manager = app.getSystemService(StorageManager::class.java)
                manager.getCacheQuotaBytes(manager.getUuidForPath(root))
            }.getOrNull()?.takeIf { it > 0 }
        } else {
            null
        }
        return CacheStorage(
            isTv = AppGraph.isTv,
            isLowRamDevice = AppGraph.isLowRamDevice,
            freeBytes = free,
            cachedBytes = instance?.cacheSpace ?: 0L,
            systemQuotaBytes = quota,
        )
    }

    /**
     * Removes every cached segment, freeing the disk space under [cacheDir]/media. Keeps the
     * singleton valid so an active player keeps working against the now-empty cache. A segment
     * currently locked by an in-flight read is left in place. Does disk IO; call off the main thread.
     */
    fun clear(context: Context) {
        val cache = get(context)
        for (key in cache.keys.toList()) {
            try {
                cache.removeResource(key)
            } catch (_: Exception) {
                // Locked or already-gone resource; skip it and keep clearing the rest.
            }
        }
    }

    private const val DIRECTORY = "media"
    private const val MB = 1024L * 1024

    /** Only until the stored setting is applied, moments after the cache is first opened. */
    private const val DEFAULT_BYTES = 256L * MB
}

/** What [videoCacheBudget] reads off the device. */
internal data class CacheStorage(
    val isTv: Boolean,
    val isLowRamDevice: Boolean,
    /** Free bytes on the volume holding the cache directory. */
    val freeBytes: Long,
    /** Bytes the video cache holds already; they are space it may keep, not space it would take. */
    val cachedBytes: Long,
    /**
     * `StorageManager.getCacheQuotaBytes`, or null below Android 8 or when it will not say. Logged,
     * not obeyed — see [videoCacheBudget].
     */
    val systemQuotaBytes: Long?,
)

/**
 * How many bytes of streamed video to keep, from the viewer's choice and what the device can spare.
 *
 * [VideoCacheSize.AUTOMATIC] starts from a ceiling per kind of device — 256 MB on a television or a
 * low-RAM device, which are the ones that ship with 8 GB of storage, and 1 GB on anything else —
 * then comes down to a tenth of the space the cache could use at all (what is free plus what it
 * holds already).
 *
 * The system's cache quota is deliberately not a cap. It is derived from how much the app has been
 * used, so a fresh install gets very little — 172 MB on the phone emulator with 4.5 GB free, below
 * the flat 512 MB this replaced — and all it decides is whose cache Android clears first once space
 * is already short, which the tenth-of-free rule has handled before that point.
 *
 * It never goes under 64 MB, which is still enough for seeking back through the last few minutes.
 *
 * An explicit size is honoured as asked, except that it may never take more than half of that same
 * usable space: a 2 GB choice restored onto a nearly full box should shrink, not fill the disk.
 */
internal fun videoCacheBudget(size: VideoCacheSize, storage: CacheStorage): Long {
    val usable = (storage.freeBytes + storage.cachedBytes).coerceAtLeast(0L)
    return when (size) {
        VideoCacheSize.OFF -> 0L
        VideoCacheSize.AUTOMATIC -> {
            val ceiling = if (storage.isTv || storage.isLowRamDevice) AUTOMATIC_CONSTRAINED_BYTES else AUTOMATIC_BYTES
            (usable / 10).coerceIn(MINIMUM_BYTES, ceiling)
        }
        else -> (size.bytes ?: MINIMUM_BYTES).coerceAtMost(usable / 2).coerceAtLeast(MINIMUM_BYTES)
    }
}

private const val AUTOMATIC_CONSTRAINED_BYTES = 256L * 1024 * 1024
private const val AUTOMATIC_BYTES = 1024L * 1024 * 1024
private const val MINIMUM_BYTES = 64L * 1024 * 1024

/**
 * Media3's `LeastRecentlyUsedCacheEvictor`, line for line, with a size that can change.
 *
 * The stock evictor takes its limit in the constructor and `SimpleCache` takes the evictor in its
 * own, and a directory can only have one `SimpleCache` open at a time — so a new size would
 * otherwise mean closing the cache under a playing episode. [resize] trims to the new limit at once.
 *
 * Every method runs under the owning cache's monitor: `SimpleCache` calls the listener methods from
 * inside its synchronized methods, and [MediaCache] takes the same lock around [resize].
 */
@OptIn(UnstableApi::class)
internal class ResizableLruCacheEvictor(initialMaxBytes: Long) : CacheEvictor {
    private var maxBytes = initialMaxBytes
    private val leastRecentlyUsed = TreeSet<CacheSpan> { a, b ->
        when {
            a.lastTouchTimestamp == b.lastTouchTimestamp -> a.compareTo(b)
            a.lastTouchTimestamp < b.lastTouchTimestamp -> -1
            else -> 1
        }
    }
    private var currentSize = 0L

    fun resize(cache: Cache, bytes: Long) {
        maxBytes = bytes
        evictCache(cache, 0)
    }

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evictCache(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.add(span)
        currentSize += span.length
        evictCache(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.remove(span)
        currentSize -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        while (currentSize + requiredSpace > maxBytes && leastRecentlyUsed.isNotEmpty()) {
            cache.removeSpan(leastRecentlyUsed.first())
        }
    }
}
