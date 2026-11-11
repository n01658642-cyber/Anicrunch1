package com.anilili.data.backup

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * The passphrase lock on a backup's logins.
 *
 * A backup is a file people mail to themselves and leave in Downloads. A bearer token written into
 * one in the clear is the account itself, readable by every app with storage access and by whoever
 * ends up with the file — so the credential section is sealed under a passphrase the viewer holds
 * and the file never contains. Everything else in the backup stays readable: settings and a
 * watchlist are not worth a passphrase, and a file you cannot inspect is a file you cannot trust.
 *
 * No Android here — okio does the Base64 that `android.util.Base64` would, so the whole thing runs
 * in a plain JVM test, and the key schedule can be checked against another implementation.
 */
internal object BackupCrypto {
    const val KDF = "pbkdf2-hmac-sha256"

    /**
     * Deliberately below OWASP's desktop recommendation. This runs on television boxes whose CPUs
     * are a decade behind a phone's, and it runs while the viewer waits at a passphrase prompt; the
     * number is recorded in the file rather than assumed, so it can be raised for new backups
     * without stranding the ones already written.
     */
    const val ITERATIONS = 210_000

    const val SALT_BYTES = 16
    const val IV_BYTES = 12

    /**
     * Short enough to type on a remote, long enough that the file is not worth grinding at. Eight
     * characters from [PASSPHRASE_ALPHABET] is about 40 bits, and each guess costs [ITERATIONS]
     * HMACs. A viewer who types their own is held to the same length.
     */
    const val MIN_PASSPHRASE_LENGTH = 8

    /**
     * Crockford-style: no I, L, O, U, so nothing reads as a digit and nothing spells anything.
     * The generated passphrase is what most people will use, and most of them will type it into a
     * television with a D-pad.
     */
    private const val PASSPHRASE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val GENERATED_GROUPS = 2
    private const val GENERATED_GROUP_LENGTH = 4

    private const val GCM_TAG_BITS = 128
    private const val GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val KEY_BYTES = 32

    /** `K7M4-9QXR`: something the app can offer so nobody has to invent "password1". */
    fun generatePassphrase(random: SecureRandom = SecureRandom()): String =
        (1..GENERATED_GROUPS).joinToString("-") {
            (1..GENERATED_GROUP_LENGTH)
                .map { PASSPHRASE_ALPHABET[random.nextInt(PASSPHRASE_ALPHABET.length)] }
                .joinToString("")
        }

    /**
     * Seal [plaintext] under [passphrase].
     *
     * [associatedData] is the document's own format and version: a sealed blob lifted out of one
     * backup and pasted into another with a different header no longer opens, so a file cannot be
     * assembled out of parts of two.
     */
    fun seal(
        passphrase: String,
        plaintext: String,
        associatedData: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): SealedCredentials {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt, ITERATIONS)
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(associatedData)
        }
        return SealedCredentials(
            iterations = ITERATIONS,
            salt = salt.toByteString().base64(),
            iv = iv.toByteString().base64(),
            ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)).toByteString().base64(),
        )
    }

    /**
     * Open [sealed], or throw with something the viewer can act on.
     *
     * A wrong passphrase and a corrupted file are the same event to AES-GCM — the tag simply fails
     * — so the message names the likely cause rather than the cryptographic one.
     */
    fun open(passphrase: String, sealed: SealedCredentials, associatedData: ByteArray): String {
        if (sealed.kdf != KDF) error("These logins were sealed by a newer version of Anilili.")
        val salt = sealed.salt.decodeBase64()?.toByteArray()
            ?: error("The logins in that backup are damaged.")
        val iv = sealed.iv.decodeBase64()?.toByteArray()
            ?: error("The logins in that backup are damaged.")
        val ciphertext = sealed.ciphertext.decodeBase64()?.toByteArray()
            ?: error("The logins in that backup are damaged.")
        require(iv.size == IV_BYTES) { "The logins in that backup are damaged." }
        val key = deriveKey(passphrase, salt, sealed.iterations)
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(associatedData)
        }
        return try {
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (error: AEADBadTagException) {
            throw IllegalStateException("That passphrase doesn't match this backup.", error)
        }
    }

    /**
     * PBKDF2 (RFC 2898) over HMAC-SHA256, by hand.
     *
     * Android only offers `PBKDF2WithHmacSHA256` from API 26, and this app runs further back than
     * that; the SHA-1 variant that is available everywhere would have made the file's key schedule
     * depend on which device wrote it. Twenty lines of RFC beats that.
     */
    internal fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        require(passphrase.isNotEmpty()) { "A passphrase is needed to read these logins." }
        require(iterations > 0) { "The logins in that backup are damaged." }
        val mac = Mac.getInstance(HMAC_ALGORITHM).apply {
            init(SecretKeySpec(passphrase.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        }
        val blockLength = mac.macLength
        val blocks = (KEY_BYTES + blockLength - 1) / blockLength
        val output = ByteArray(blocks * blockLength)
        for (block in 1..blocks) {
            var u = mac.doFinal(salt + blockIndex(block))
            val accumulated = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (index in accumulated.indices) {
                    accumulated[index] = (accumulated[index].toInt() xor u[index].toInt()).toByte()
                }
            }
            accumulated.copyInto(output, destinationOffset = (block - 1) * blockLength)
        }
        return output.copyOf(KEY_BYTES)
    }

    /** The block counter, big-endian, as RFC 2898 appends it to the salt. */
    private fun blockIndex(block: Int): ByteArray = byteArrayOf(
        (block ushr 24).toByte(),
        (block ushr 16).toByte(),
        (block ushr 8).toByte(),
        block.toByte(),
    )
}
