package com.anilili.playback

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class ResizableLruCacheEvictorTest {
    private val removed = mutableListOf<CacheSpan>()
    private val evictor = ResizableLruCacheEvictor(initialMaxBytes = 300)

    /** A cache that does only what the evictor asks of it: removing a span, and reporting it back. */
    private val cache: Cache = Proxy.newProxyInstance(
        Cache::class.java.classLoader,
        arrayOf(Cache::class.java),
    ) { proxy, method, args ->
        check(method.name == "removeSpan") { "The evictor should only remove spans, not ${method.name}" }
        val span = args[0] as CacheSpan
        removed += span
        evictor.onSpanRemoved(proxy as Cache, span)
        null
    } as Cache

    private fun span(key: String, touchedAt: Long) = CacheSpan(key, 0, 100, touchedAt, File(key))

    @Test
    fun `adding past the limit evicts the least recently used span`() {
        listOf(span("a", 1), span("b", 2), span("c", 3)).forEach { evictor.onSpanAdded(cache, it) }
        evictor.onSpanAdded(cache, span("d", 4))
        assertEquals(listOf("a"), removed.map { it.key })
    }

    @Test
    fun `shrinking trims at once, oldest first`() {
        listOf(span("a", 1), span("b", 2), span("c", 3)).forEach { evictor.onSpanAdded(cache, it) }
        evictor.resize(cache, 100)
        assertEquals(listOf("a", "b"), removed.map { it.key })
    }

    @Test
    fun `a touched span counts as recent`() {
        val a = span("a", 1)
        listOf(a, span("b", 2), span("c", 3)).forEach { evictor.onSpanAdded(cache, it) }
        evictor.onSpanTouched(cache, a, span("a", 9))
        evictor.resize(cache, 200)
        assertEquals(listOf("b"), removed.map { it.key })
    }

    @Test
    fun `growing removes nothing, and zero empties the cache`() {
        listOf(span("a", 1), span("b", 2)).forEach { evictor.onSpanAdded(cache, it) }
        evictor.resize(cache, 10_000)
        assertEquals(emptyList<String>(), removed.map { it.key })
        evictor.resize(cache, 0)
        assertEquals(listOf("a", "b"), removed.map { it.key })
    }
}
