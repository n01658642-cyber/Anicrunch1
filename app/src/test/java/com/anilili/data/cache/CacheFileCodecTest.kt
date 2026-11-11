package com.anilili.data.cache

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CacheFileCodecTest {
    @Test
    fun `cache entry round trips and file timestamp advances access time`() {
        val entry = CacheEntry(
            key = "episodes:42",
            payload = "gzip:payload",
            createdAt = 10,
            expiresAt = 20,
            lastAccessedAt = 15,
        )
        val bytes = ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { CacheFileCodec.write(it, entry) }
        }.toByteArray()

        val decoded = DataInputStream(ByteArrayInputStream(bytes)).use {
            CacheFileCodec.read(it, entry.key, fileLastModified = 18)
        }

        assertEquals(entry.copy(lastAccessedAt = 18), decoded)
    }

    @Test
    fun `hashed file cannot be read as a different cache key`() {
        val entry = CacheEntry("key-a", "payload", 1, 2, 1)
        val bytes = ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { CacheFileCodec.write(it, entry) }
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            DataInputStream(ByteArrayInputStream(bytes)).use {
                CacheFileCodec.read(it, "key-b", fileLastModified = 1)
            }
        }
    }

    @Test
    fun `truncated cache file is rejected`() {
        val entry = CacheEntry("key", "payload", 1, 2, 1)
        val bytes = ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { CacheFileCodec.write(it, entry) }
        }.toByteArray().copyOf(12)

        assertThrows(Exception::class.java) {
            DataInputStream(ByteArrayInputStream(bytes)).use {
                CacheFileCodec.read(it, "key", fileLastModified = 1)
            }
        }
    }
}
