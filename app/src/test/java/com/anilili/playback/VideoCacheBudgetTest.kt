package com.anilili.playback

import com.anilili.data.settings.VideoCacheSize
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoCacheBudgetTest {
    private val mb = 1024L * 1024
    private val gb = 1024 * mb

    private fun storage(
        isTv: Boolean = false,
        isLowRamDevice: Boolean = false,
        freeBytes: Long = 100 * gb,
        cachedBytes: Long = 0,
        systemQuotaBytes: Long? = null,
    ) = CacheStorage(isTv, isLowRamDevice, freeBytes, cachedBytes, systemQuotaBytes)

    private fun automatic(storage: CacheStorage) = videoCacheBudget(VideoCacheSize.AUTOMATIC, storage)

    @Test
    fun `a roomy phone gets a gigabyte and a television a quarter of one`() {
        assertEquals(gb, automatic(storage()))
        assertEquals(256 * mb, automatic(storage(isTv = true)))
        assertEquals(256 * mb, automatic(storage(isLowRamDevice = true)))
    }

    @Test
    fun `automatic shrinks to a tenth of the usable space`() {
        // A TV box with 1.5 GB free: 150 MB, not the 512 MB it used to take.
        assertEquals(150 * mb, automatic(storage(isTv = true, freeBytes = 1500 * mb)))
    }

    @Test
    fun `what the cache already holds counts as space it may keep`() {
        // 1 GB free beside a full 1 GB cache: a tenth of the two, not of the free gigabyte alone.
        assertEquals(2 * gb / 10, automatic(storage(freeBytes = gb, cachedBytes = gb)))
    }

    @Test
    fun `a small system cache quota does not shrink a roomy phone`() {
        // A fresh install's quota is usage-derived and tiny; it only matters once space is short.
        assertEquals(gb, automatic(storage(systemQuotaBytes = 172 * mb)))
    }

    @Test
    fun `automatic never drops below the floor`() {
        assertEquals(64 * mb, automatic(storage(freeBytes = 100 * mb)))
    }

    @Test
    fun `a fixed size is honoured when it fits and halved space caps it when it does not`() {
        assertEquals(2 * gb, videoCacheBudget(VideoCacheSize.GB_2, storage()))
        assertEquals(500 * mb, videoCacheBudget(VideoCacheSize.GB_2, storage(freeBytes = gb - 24 * mb)))
    }

    @Test
    fun `off keeps nothing`() {
        assertEquals(0L, videoCacheBudget(VideoCacheSize.OFF, storage()))
    }
}
