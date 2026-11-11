package com.anilili.data.pairing

import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Key agreement and authentication for TV pairing, as specified in
 * `docs/TV_PAIRING_PROTOCOL.md`. Nothing here touches a socket or the Android framework, so the
 * whole security argument is exercised by plain JVM unit tests.
 *
 * secp256k1 rather than a JCA curve because the library already ships for the Nostr update
 * verifier, its R8 keep rule is already in place, and `secp256k1-kmp-jni-jvm` is already a test
 * dependency — so the same code runs identically off-device.
 */
internal object PairingCrypto {
    const val PROTOCOL_LABEL = "anilili-pair/1"

    const val PRIVATE_KEY_BYTES = 32
    const val PUBLIC_KEY_BYTES = 65
    const val NONCE_BYTES = 16
    const val SESSION_KEY_BYTES = 32
    const val GCM_IV_BYTES = 12
    const val SAS_DIGITS = 6

    private const val GCM_TAG_BITS = 128
    private const val SAS_MODULUS = 1_000_000L
    private const val UNCOMPRESSED_POINT_PREFIX = 0x04.toByte()

    /**
     * A 32-byte scalar is only a valid secp256k1 key if it is below the curve order. Random draws
     * fail that with probability around 2^-128, so the retry exists for completeness — but it is
     * bounded, because an unbounded loop against a broken native backend would hang the pairing
     * screen rather than fail it.
     */
    private const val KEY_GENERATION_ATTEMPTS = 8

    fun generateKeyPair(random: SecureRandom = SecureRandom()): PairingKeyPair {
        repeat(KEY_GENERATION_ATTEMPTS) {
            val privateKey = ByteArray(PRIVATE_KEY_BYTES).also(random::nextBytes)
            if (Secp256k1.secKeyVerify(privateKey)) {
                return PairingKeyPair(privateKey, Secp256k1.pubkeyCreate(privateKey))
            }
        }
        error("Could not generate a valid secp256k1 key in $KEY_GENERATION_ATTEMPTS attempts")
    }

    fun randomNonce(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(NONCE_BYTES).also(random::nextBytes)

    /**
     * Rejects anything that is not a well-formed uncompressed point before it reaches [ecdh].
     * A peer is untrusted until the codes have been compared, so its key is parsed defensively:
     * `pubkeyParse` throws on a point that is not on the curve.
     */
    fun requireValidPublicKey(publicKey: ByteArray): ByteArray {
        require(publicKey.size == PUBLIC_KEY_BYTES) {
            "Public key must be $PUBLIC_KEY_BYTES bytes, was ${publicKey.size}"
        }
        require(publicKey[0] == UNCOMPRESSED_POINT_PREFIX) { "Public key is not an uncompressed point" }
        runCatching { Secp256k1.pubkeyParse(publicKey) }
            .onFailure { throw IllegalArgumentException("Public key is not on the curve", it) }
        return publicKey
    }

    /** The initiator's binding commitment, sent before the responder reveals its own key. */
    fun commitment(publicKey: ByteArray, nonce: ByteArray): ByteArray =
        sha256(publicKey + nonce)

    /**
     * The bytes both sides agree the handshake consisted of.
     *
     * Ordered by role rather than by who spoke first, so the two devices build an identical buffer
     * without tracking direction. Every field ahead of [service] is fixed-length — two 65-byte
     * points and two 16-byte nonces — so plain concatenation is unambiguous and no separators or
     * length prefixes are needed.
     */
    fun transcript(
        initiatorPublicKey: ByteArray,
        responderPublicKey: ByteArray,
        initiatorNonce: ByteArray,
        responderNonce: ByteArray,
        service: String,
    ): ByteArray = PROTOCOL_LABEL.toByteArray(Charsets.UTF_8) +
        initiatorPublicKey +
        responderPublicKey +
        initiatorNonce +
        responderNonce +
        service.toByteArray(Charsets.UTF_8)

    /**
     * The session key and the six digits the two viewers compare.
     *
     * Both public keys and both nonces are inside [transcript], so a man in the middle — who by
     * definition runs two separate agreements — derives two different codes and cannot make them
     * agree. Nothing low-entropy enters the schedule, which is what keeps the recorded handshake
     * immune to an offline guessing attack.
     */
    fun deriveSecrets(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
        transcript: ByteArray,
    ): PairingSecrets {
        val sharedSecret = Secp256k1.ecdh(privateKey, requireValidPublicKey(peerPublicKey))
        val prk = hkdfExtract(salt = sha256(transcript), keyMaterial = sharedSecret)
        return PairingSecrets(
            sessionKey = hkdfExpand(prk, "$PROTOCOL_LABEL session", SESSION_KEY_BYTES),
            sas = sasDigits(hkdfExpand(prk, "$PROTOCOL_LABEL sas", 4)),
        )
    }

    fun seal(
        sessionKey: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): SealedPayload {
        val iv = ByteArray(GCM_IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(associatedData)
        }
        return SealedPayload(iv = iv, ciphertext = cipher.doFinal(plaintext))
    }

    /** Throws [javax.crypto.AEADBadTagException] if the payload or its associated data was altered. */
    fun open(sessionKey: ByteArray, associatedData: ByteArray, payload: SealedPayload): ByteArray {
        require(payload.iv.size == GCM_IV_BYTES) { "IV must be $GCM_IV_BYTES bytes, was ${payload.iv.size}" }
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, payload.iv))
            updateAAD(associatedData)
        }
        return cipher.doFinal(payload.ciphertext)
    }

    /** Length-independent comparison, for commitments and codes that an attacker supplies. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var difference = 0
        for (index in a.indices) difference = difference or (a[index].toInt() xor b[index].toInt())
        return difference == 0
    }

    internal fun sasDigits(bytes: ByteArray): String {
        require(bytes.size >= 4) { "Need at least 4 bytes to derive a code" }
        var value = 0L
        for (index in 0 until 4) value = (value shl 8) or (bytes[index].toLong() and 0xFF)
        return (value % SAS_MODULUS).toString().padStart(SAS_DIGITS, '0')
    }

    internal fun hkdfExtract(salt: ByteArray, keyMaterial: ByteArray): ByteArray =
        hmac(key = salt, message = keyMaterial)

    internal fun hkdfExpand(pseudoRandomKey: ByteArray, info: String, length: Int): ByteArray =
        hkdfExpand(pseudoRandomKey, info.toByteArray(Charsets.UTF_8), length)

    /**
     * RFC 5869 HKDF-Expand over HMAC-SHA256. Android has no HKDF of its own at minSdk 22.
     *
     * The `info` overload takes raw bytes so the RFC's own test vectors — whose `info` is not
     * valid UTF-8 — can be run against this implementation verbatim.
     */
    internal fun hkdfExpand(pseudoRandomKey: ByteArray, info: ByteArray, length: Int): ByteArray {
        val infoBytes = info
        val output = ByteArray(length)
        var block = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            block = hmac(pseudoRandomKey, block + infoBytes + byteArrayOf(counter.toByte()))
            val take = minOf(block.size, length - written)
            block.copyInto(output, destinationOffset = written, endIndex = take)
            written += take
            counter++
        }
        return output
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance(HMAC_ALGORITHM).apply { init(SecretKeySpec(key, HMAC_ALGORITHM)) }.doFinal(message)

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private const val GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HMAC_ALGORITHM = "HmacSHA256"
}

/** An ephemeral pairing identity. Never reused: a new one is generated for every attempt. */
internal class PairingKeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

/** The agreed session key, and the code the two viewers compare before it is used. */
internal class PairingSecrets(val sessionKey: ByteArray, val sas: String)

internal class SealedPayload(val iv: ByteArray, val ciphertext: ByteArray)
