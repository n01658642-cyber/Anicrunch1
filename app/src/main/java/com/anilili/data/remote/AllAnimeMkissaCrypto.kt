package com.anilili.data.remote

import com.anilili.util.Base64Compat
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable

/**
 * Stateless primitives for MKissa's build- and epoch-scoped episode-source envelope, adapted
 * under Apache-2.0 from yuzono/anime-extensions revision
 * d4d64d315f127b9cf2ae60e2f2d53754ce722c02.
 */
internal object AllAnimeMkissaCrypto {
    const val SEED_COUNT = 4
    private const val KEY_SIZE = 32
    private const val SEED_SIZE = KEY_SIZE / SEED_COUNT
    private const val IV_SIZE = 12
    private const val REQUEST_WINDOW_MS = 5L * 60L * 1_000L
    private const val EPOCH_WINDOW_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val EPOCH_GRACE_MS = 24L * 60L * 60L * 1_000L

    /**
     * The per-build constants of the mask and the boot token.
     *
     * These were fixed until MKissa build 175 (live 2026-09-22), which moved every one of them into
     * a config object in the bundle — `{v:1,saltMul:34,saltAdd:47,fragMul:93,fragAdd:29,
     * bootPrefix:…,join:"|",parts:[…],omitEmptyLane:!1,envXor:9}` — and changed them all. A token
     * built from the old constants is well-formed and simply wrong, which the server reports as
     * `{"error":"invalid_boot_token"}`, the failure this provider sat in since 2026-09-13.
     *
     * [LEGACY] is the old constants written as a scheme, so a bundle without the config still
     * derives exactly what it always did. `envXor` is not read: Anivexa ignores it too, and its
     * bootstrap and signed episode request both succeed without it (measured off-device against
     * build 175).
     */
    @Serializable
    data class Scheme(
        val saltMul: Int,
        val saltAdd: Int,
        val fragMul: Int,
        val fragAdd: Int,
        val bootPrefix: String,
        val join: String,
        val parts: List<String>,
        val omitEmptyLane: Boolean,
    ) {
        companion object {
            val FIELDS = setOf("buildId", "group", "host", "epoch", "lane")
            val LEGACY = Scheme(
                saltMul = 17,
                saltAdd = 31,
                fragMul = 41,
                fragAdd = 7,
                bootPrefix = "aa-boot:",
                join = ":",
                parts = listOf("buildId", "group", "host", "epoch", "lane"),
                omitEmptyLane = false,
            )
        }
    }

    /** The four public bundle seeds XOR the current build and their positions into the client mask. */
    fun deriveMask(buildId: String, seeds: List<String>, scheme: Scheme = Scheme.LEGACY): ByteArray? {
        if (buildId.isEmpty() || seeds.size != SEED_COUNT) return null
        val stream = ByteArray(KEY_SIZE) { index ->
            (buildId[index % buildId.length].code xor ((index * scheme.saltMul + scheme.saltAdd) and 0xff)).toByte()
        }
        val mask = ByteArray(KEY_SIZE)
        seeds.forEachIndexed { seedIndex, seed ->
            val bytes = runCatching { Base64Compat.decode(seed) }.getOrNull() ?: return null
            if (bytes.size < SEED_SIZE) return null
            val base = seedIndex * SEED_SIZE
            repeat(SEED_SIZE) { offset ->
                mask[base + offset] = (
                    (bytes[offset].toInt() and 0xff) xor
                        (stream[base + offset].toInt() and 0xff) xor
                        ((seedIndex * scheme.fragMul + offset * scheme.fragAdd) and 0xff)
                    ).toByte()
            }
        }
        return mask
    }

    fun deriveKey(mask: ByteArray, partB: ByteArray): ByteArray? {
        if (mask.isEmpty() || partB.size < KEY_SIZE) return null
        return ByteArray(KEY_SIZE) { index ->
            ((partB[index].toInt() and 0xff) xor (mask[index % mask.size].toInt() and 0xff)).toByte()
        }
    }

    fun bootToken(
        mask: ByteArray,
        buildId: String,
        epoch: Long,
        keyGroup: String,
        refererHost: String,
        lane: String,
        scheme: Scheme = Scheme.LEGACY,
    ): String {
        val inner = hmac(mask, "${scheme.bootPrefix}$buildId")
        val fields = mapOf(
            "buildId" to buildId,
            "group" to keyGroup,
            "host" to refererHost,
            "epoch" to epoch.toString(),
            "lane" to lane,
        )
        val parts = if (scheme.omitEmptyLane && lane.isEmpty()) scheme.parts - "lane" else scheme.parts
        val message = parts.joinToString(scheme.join) { fields[it].orEmpty() }
        return hmac(inner, message).joinToString("") { "%02x".format(it) }
    }

    /** Oldest first while the previous seven-day epoch remains in its one-day grace period. */
    fun epochCandidates(nowMs: Long = System.currentTimeMillis()): List<Long> {
        val current = nowMs / EPOCH_WINDOW_MS
        val inGrace = nowMs - current * EPOCH_WINDOW_MS < EPOCH_GRACE_MS && current > 0L
        return if (inGrace) listOf(current - 1L, current) else listOf(current)
    }

    fun skewedEpochCandidates(nowMs: Long = System.currentTimeMillis()): List<Long> {
        val current = nowMs / EPOCH_WINDOW_MS
        return listOf(current + 1L, current - 1L)
            .filter { it > 0L }
            .filterNot { it in epochCandidates(nowMs) }
    }

    fun signRequest(
        key: ByteArray,
        epoch: Long,
        buildId: String,
        queryHash: String,
        lane: String,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val timestamp = nowMs / REQUEST_WINDOW_MS * REQUEST_WINDOW_MS
        val iv = MessageDigest.getInstance("SHA-256")
            .digest("$epoch:$buildId:$queryHash:$timestamp:$lane".toByteArray(StandardCharsets.UTF_8))
            .copyOfRange(0, IV_SIZE)
        val plaintext =
            """{"v":1,"ts":$timestamp,"epoch":$epoch,"buildId":"$buildId","qh":"$queryHash","k":"$lane"}"""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Base64Compat.encode(byteArrayOf(1) + iv + cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8)))
    }

    fun decrypt(payload: String, key: ByteArray): String? {
        val envelope = runCatching { Base64Compat.decode(payload) }.getOrNull() ?: return null
        if (envelope.size <= 29 || envelope[0].toInt() != 1) return null
        return runCatching {
            val iv = envelope.copyOfRange(1, 13)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.doFinal(envelope.copyOfRange(13, envelope.size)).toString(StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun hmac(key: ByteArray, message: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(message.toByteArray(StandardCharsets.UTF_8))
    }
}
