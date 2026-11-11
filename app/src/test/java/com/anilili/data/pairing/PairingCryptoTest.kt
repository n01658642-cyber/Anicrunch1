package com.anilili.data.pairing

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key schedule behind TV pairing. HKDF is hand-rolled here — Android has none at minSdk 22 —
 * and a hand-rolled HKDF is exactly the place a counter bug hides without changing anything
 * observable, so it is pinned against RFC 5869's own vector.
 */
class PairingCryptoTest {
    @Test
    fun `HKDF matches RFC 5869 test case 1`() {
        val prk = PairingCrypto.hkdfExtract(
            salt = hex("000102030405060708090a0b0c"),
            keyMaterial = hex("0b".repeat(22)),
        )
        assertArrayEquals(hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"), prk)
        assertArrayEquals(
            hex(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                    "34007208d5b887185865",
            ),
            PairingCrypto.hkdfExpand(prk, hex("f0f1f2f3f4f5f6f7f8f9"), 42),
        )
    }

    @Test
    fun `a longer expansion extends a shorter one rather than replacing it`() {
        // The property that breaks first if the block counter is mishandled.
        val prk = PairingCrypto.hkdfExtract(salt = hex("0a0b0c"), keyMaterial = hex("ff00ff00"))
        val short = PairingCrypto.hkdfExpand(prk, "label", 32)
        val long = PairingCrypto.hkdfExpand(prk, "label", 96)
        assertArrayEquals(short, long.copyOfRange(0, 32))
    }

    @Test
    fun `codes are always six digits, zero padding included`() {
        assertEquals("000000", PairingCrypto.sasDigits(hex("00000000")))
        assertEquals("000001", PairingCrypto.sasDigits(hex("00000001")))
        // 0xFFFFFFFF unsigned is 4294967295; mod 10^6 is 967295. A signed read would give a
        // negative number here, which is the bug this pins.
        assertEquals("967295", PairingCrypto.sasDigits(hex("ffffffff")))
        assertEquals(PairingCrypto.SAS_DIGITS, PairingCrypto.sasDigits(hex("0007a120")).length)
    }

    @Test
    fun `both sides of an exchange derive the same key and the same code`() {
        val tv = keyPair(1)
        val phone = keyPair(9)
        val transcript = PairingCrypto.transcript(
            initiatorPublicKey = phone.publicKey,
            responderPublicKey = tv.publicKey,
            initiatorNonce = nonce(3),
            responderNonce = nonce(4),
            service = PAIRING_SERVICE_ANILIST,
        )
        val fromPhone = PairingCrypto.deriveSecrets(phone.privateKey, tv.publicKey, transcript)
        val fromTv = PairingCrypto.deriveSecrets(tv.privateKey, phone.publicKey, transcript)
        assertArrayEquals(fromPhone.sessionKey, fromTv.sessionKey)
        assertEquals(fromPhone.sas, fromTv.sas)
    }

    @Test
    fun `changing any transcript field changes the code`() {
        val tv = keyPair(1)
        val phone = keyPair(9)
        fun sasFor(service: String, responderNonce: ByteArray): String =
            PairingCrypto.deriveSecrets(
                phone.privateKey,
                tv.publicKey,
                PairingCrypto.transcript(phone.publicKey, tv.publicKey, nonce(3), responderNonce, service),
            ).sas

        val baseline = sasFor(PAIRING_SERVICE_ANILIST, nonce(4))
        assertNotEquals(baseline, sasFor(PAIRING_SERVICE_MAL, nonce(4)))
        assertNotEquals(baseline, sasFor(PAIRING_SERVICE_ANILIST, nonce(5)))
    }

    @Test
    fun `a sealed credential survives the trip and refuses a changed service`() {
        val key = ByteArray(32) { it.toByte() }
        val plaintext = "the-token".toByteArray()
        val sealed = PairingCrypto.seal(key, PAIRING_SERVICE_ANILIST.toByteArray(), plaintext)

        assertArrayEquals(plaintext, PairingCrypto.open(key, PAIRING_SERVICE_ANILIST.toByteArray(), sealed))
        // The associated data binds the ciphertext to the negotiated service, so replaying an
        // AniList payload into a MAL session cannot authenticate.
        assertThrows(AEADBadTagException::class.java) {
            PairingCrypto.open(key, PAIRING_SERVICE_MAL.toByteArray(), sealed)
        }
    }

    @Test
    fun `a flipped ciphertext bit is rejected rather than decrypted`() {
        val key = ByteArray(32) { it.toByte() }
        val sealed = PairingCrypto.seal(key, "svc".toByteArray(), "payload".toByteArray())
        val tampered = SealedPayload(
            iv = sealed.iv,
            ciphertext = sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
        )
        assertThrows(AEADBadTagException::class.java) {
            PairingCrypto.open(key, "svc".toByteArray(), tampered)
        }
    }

    @Test
    fun `every generated pair is fresh and usable`() {
        val first = PairingCrypto.generateKeyPair()
        val second = PairingCrypto.generateKeyPair()
        assertEquals(PairingCrypto.PUBLIC_KEY_BYTES, first.publicKey.size)
        assertFalse(first.privateKey.contentEquals(second.privateKey))
        assertFalse(first.publicKey.contentEquals(second.publicKey))
        PairingCrypto.requireValidPublicKey(first.publicKey)
    }

    @Test
    fun `a public key that is not on the curve is refused`() {
        val garbage = ByteArray(PairingCrypto.PUBLIC_KEY_BYTES) { 0x04 }
        assertThrows(IllegalArgumentException::class.java) {
            PairingCrypto.requireValidPublicKey(garbage)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingCrypto.requireValidPublicKey(ByteArray(33) { 0x02 })
        }
    }

    @Test
    fun `constant time comparison still compares`() {
        assertTrue(PairingCrypto.constantTimeEquals(hex("00ff10"), hex("00ff10")))
        assertFalse(PairingCrypto.constantTimeEquals(hex("00ff10"), hex("00ff11")))
        assertFalse(PairingCrypto.constantTimeEquals(hex("00ff10"), hex("00ff")))
    }
}
