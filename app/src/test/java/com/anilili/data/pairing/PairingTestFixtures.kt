package com.anilili.data.pairing

import fr.acinq.secp256k1.Secp256k1

/**
 * Deterministic material for the pairing tests.
 *
 * Keys are fixed rather than generated because the man-in-the-middle test asserts that two codes
 * differ, and with random keys that assertion would collide once in a million runs — a flake that
 * would look exactly like the security property failing.
 */

internal fun hex(value: String): ByteArray {
    require(value.length % 2 == 0) { "Hex string must have an even length" }
    return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun keyPair(seed: Int): PairingKeyPair {
    val privateKey = ByteArray(PairingCrypto.PRIVATE_KEY_BYTES) { (seed + it).toByte() }
    check(Secp256k1.secKeyVerify(privateKey)) { "Test seed $seed is not a valid scalar" }
    return PairingKeyPair(privateKey, Secp256k1.pubkeyCreate(privateKey))
}

internal fun nonce(seed: Int): ByteArray =
    ByteArray(PairingCrypto.NONCE_BYTES) { (seed * 7 + it).toByte() }
