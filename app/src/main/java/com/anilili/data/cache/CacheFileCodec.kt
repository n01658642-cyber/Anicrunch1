package com.anilili.data.cache

import java.io.DataInput
import java.io.DataOutput
import java.nio.charset.StandardCharsets

internal data class CacheEntry(
    val key: String,
    val payload: String,
    val createdAt: Long,
    val expiresAt: Long,
    val lastAccessedAt: Long,
)

/** Versioned, bounded binary representation for one atomic metadata-cache file. */
internal object CacheFileCodec {
    private const val FILE_MAGIC = 0x414E4348 // ANCH
    private const val FILE_VERSION = 1
    private const val MAX_KEY_BYTES = 16 * 1024
    private const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024

    fun write(output: DataOutput, entry: CacheEntry) {
        val keyBytes = entry.key.toByteArray(StandardCharsets.UTF_8)
        val payloadBytes = entry.payload.toByteArray(StandardCharsets.UTF_8)
        require(keyBytes.size in 1..MAX_KEY_BYTES) { "Cache key is too large" }
        require(payloadBytes.size <= MAX_PAYLOAD_BYTES) { "Cache payload is too large" }
        output.writeInt(FILE_MAGIC)
        output.writeInt(FILE_VERSION)
        output.writeLong(entry.createdAt)
        output.writeLong(entry.expiresAt)
        output.writeLong(entry.lastAccessedAt)
        output.writeInt(keyBytes.size)
        output.writeInt(payloadBytes.size)
        output.write(keyBytes)
        output.write(payloadBytes)
    }

    fun read(input: DataInput, expectedKey: String, fileLastModified: Long): CacheEntry {
        require(input.readInt() == FILE_MAGIC) { "Unknown cache file" }
        require(input.readInt() == FILE_VERSION) { "Unsupported cache file version" }
        val createdAt = input.readLong()
        val expiresAt = input.readLong()
        val storedAccess = input.readLong()
        val keySize = input.readInt()
        val payloadSize = input.readInt()
        require(keySize in 1..MAX_KEY_BYTES) { "Invalid cache key size" }
        require(payloadSize in 0..MAX_PAYLOAD_BYTES) { "Invalid cache payload size" }
        val keyBytes = ByteArray(keySize).also(input::readFully)
        val storedKey = String(keyBytes, StandardCharsets.UTF_8)
        require(storedKey == expectedKey) { "Cache key hash collision" }
        val payloadBytes = ByteArray(payloadSize).also(input::readFully)
        return CacheEntry(
            key = expectedKey,
            payload = String(payloadBytes, StandardCharsets.UTF_8),
            createdAt = createdAt,
            expiresAt = expiresAt,
            lastAccessedAt = maxOf(storedAccess, fileLastModified),
        )
    }
}
