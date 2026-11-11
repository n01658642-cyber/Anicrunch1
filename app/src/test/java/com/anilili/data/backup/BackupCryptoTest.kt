package com.anilili.data.backup

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    private val aad = "anilili-backup/1".toByteArray(Charsets.UTF_8)

    @Test
    fun `the key schedule matches RFC 7914's PBKDF2-HMAC-SHA256 vector`() {
        // P="passwd", S="salt", c=1 — the first 32 bytes of the RFC's 64-byte answer, since the
        // backup key is 32 bytes.
        val derived = BackupCrypto.deriveKey("passwd", "salt".toByteArray(Charsets.UTF_8), 1)
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", derived.hex())
    }

    /**
     * The hand-rolled PBKDF2 exists because Android has no `PBKDF2WithHmacSHA256` before API 26.
     * The JVM this test runs on does have it, so the two can be compared directly — which is the
     * only way to know the RFC was followed rather than merely implemented consistently.
     */
    @Test
    fun `the key schedule agrees with the platform implementation`() {
        val salt = ByteArray(16) { it.toByte() }
        listOf(1, 2, 1000).forEach { iterations ->
            val ours = BackupCrypto.deriveKey("K7M4-9QXR", salt, iterations)
            val platform = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec("K7M4-9QXR".toCharArray(), salt, iterations, 256))
                .encoded
            assertEquals("at $iterations iterations", platform.toByteString().hex(), ours.hex())
        }
    }

    @Test
    fun `a sealed payload comes back with the right passphrase`() {
        val sealed = BackupCrypto.seal("K7M4-9QXR", SECRET, aad)
        assertEquals(SECRET, BackupCrypto.open("K7M4-9QXR", sealed, aad))
    }

    @Test
    fun `the wrong passphrase is refused, and says so in words`() {
        val sealed = BackupCrypto.seal("K7M4-9QXR", SECRET, aad)
        val message = runCatching { BackupCrypto.open("K7M4-9QXS", sealed, aad) }
            .exceptionOrNull()?.message
        assertEquals("That passphrase doesn't match this backup.", message)
    }

    @Test
    fun `a seal cannot be lifted into a document with a different header`() {
        val sealed = BackupCrypto.seal("K7M4-9QXR", SECRET, aad)
        val other = "anilili-backup/2".toByteArray(Charsets.UTF_8)
        assertTrue(runCatching { BackupCrypto.open("K7M4-9QXR", sealed, other) }.isFailure)
    }

    @Test
    fun `an altered ciphertext is refused rather than half-decrypted`() {
        val sealed = BackupCrypto.seal("K7M4-9QXR", SECRET, aad)
        val bytes = sealed.ciphertext.decodeBase64()!!.toByteArray()
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val tampered = sealed.copy(ciphertext = bytes.toByteString().base64())
        assertTrue(runCatching { BackupCrypto.open("K7M4-9QXR", tampered, aad) }.isFailure)
    }

    @Test
    fun `the plaintext never appears in the sealed form`() {
        val sealed = BackupCrypto.seal("K7M4-9QXR", SECRET, aad)
        assertTrue(SECRET !in sealed.ciphertext)
        assertTrue("token" !in sealed.ciphertext)
    }

    @Test
    fun `sealing the same thing twice produces different bytes`() {
        val first = BackupCrypto.seal("K7M4-9QXR", SECRET, aad)
        val second = BackupCrypto.seal("K7M4-9QXR", SECRET, aad)
        assertNotEquals(first.ciphertext, second.ciphertext)
        assertNotEquals(first.salt, second.salt)
        assertNotEquals(first.iv, second.iv)
    }

    @Test
    fun `a generated passphrase is long enough and free of look-alike characters`() {
        val random = SecureRandom()
        repeat(50) {
            val generated = BackupCrypto.generatePassphrase(random)
            assertTrue(generated, generated.length >= BackupCrypto.MIN_PASSPHRASE_LENGTH)
            assertTrue(generated, generated.none { it in "ILOU01" })
        }
    }

    @Test
    fun `an empty passphrase is refused before it reaches the cipher`() {
        val sealed = BackupCrypto.seal("K7M4-9QXR", SECRET, aad)
        assertTrue(runCatching { BackupCrypto.open("", sealed, aad) }.isFailure)
    }

    private fun ByteArray.hex(): String = toByteString().hex()

    private companion object {
        const val SECRET = """{"aniListToken":"a-very-real-token"}"""
    }
}
