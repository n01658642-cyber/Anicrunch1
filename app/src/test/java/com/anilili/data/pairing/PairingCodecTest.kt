package com.anilili.data.pairing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format. The frame reader is the only part of pairing that a hostile peer reaches before
 * any authentication has happened, so its refusals matter as much as its successes.
 */
class PairingCodecTest {
    @Test
    fun `every frame survives a round trip`() {
        val frames = listOf(
            CommitFrame(svc = PAIRING_SERVICE_ANILIST, commit = hex("00112233").toByteString()),
            HelloFrame(
                key = keyPair(1).publicKey.toByteString(),
                nonce = nonce(2).toByteString(),
                label = "Living Room",
                challenge = "abc",
            ),
            OpenFrame(key = keyPair(3).publicKey.toByteString(), nonce = nonce(4).toByteString()),
            ConfirmFrame(ok = true),
            CredentialFrame(
                iv = hex("0102030405060708090a0b0c").toByteString(),
                ciphertext = hex("deadbeef").toByteString(),
            ),
            ResultFrame(ok = false, error = "nope"),
        )
        frames.forEach { frame ->
            assertEquals(frame, PairingCodec.decode(PairingCodec.encode(frame).drop(4).toByteArray()))
        }
    }

    @Test
    fun `frames read back in order from one stream`() {
        val buffer = ByteArrayOutputStream()
        PairingCodec.writeFrame(
            buffer,
            CommitFrame(svc = PAIRING_SERVICE_MAL, commit = hex("aabb").toByteString()),
        )
        PairingCodec.writeFrame(buffer, ConfirmFrame(ok = true))

        val input = ByteArrayInputStream(buffer.toByteArray())
        val first = PairingCodec.readFrame(input) as CommitFrame
        assertEquals(PAIRING_SERVICE_MAL, first.svc)
        assertEquals(hex("aabb").toByteString(), first.commit)
        assertEquals(ConfirmFrame(ok = true), PairingCodec.readFrame(input))
    }

    @Test
    fun `the length prefix is four big-endian bytes`() {
        val encoded = PairingCodec.encode(ConfirmFrame(ok = true))
        val declared = encoded.take(4).fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
        assertEquals(encoded.size - 4, declared)
    }

    @Test
    fun `an oversized length prefix is refused before anything is allocated`() {
        val hostile = byteArrayOf(0x7F, -1, -1, -1) + ByteArray(8)
        assertThrows(PairingProtocolException::class.java) {
            PairingCodec.readFrame(ByteArrayInputStream(hostile))
        }
    }

    @Test
    fun `a zero length frame is refused`() {
        assertThrows(PairingProtocolException::class.java) {
            PairingCodec.readFrame(ByteArrayInputStream(ByteArray(4)))
        }
    }

    @Test
    fun `a truncated body reports the short read rather than decoding junk`() {
        val encoded = PairingCodec.encode(ConfirmFrame(ok = true))
        assertThrows(EOFException::class.java) {
            PairingCodec.readFrame(ByteArrayInputStream(encoded.copyOfRange(0, encoded.size - 3)))
        }
    }

    @Test
    fun `a truncated length prefix reports the short read`() {
        assertThrows(EOFException::class.java) {
            PairingCodec.readFrame(ByteArrayInputStream(byteArrayOf(0, 0)))
        }
    }

    @Test
    fun `credential payloads round trip and stay distinguishable`() {
        val token = AniListTokenCredential("jwt-value")
        val code = MalCodeCredential("auth-code")
        assertEquals(token, PairingCodec.decodeCredential(PairingCodec.encodeCredential(token)))
        assertEquals(code, PairingCodec.decodeCredential(PairingCodec.encodeCredential(code)))
        assertTrue(PairingCodec.encodeCredential(token).toString(Charsets.UTF_8).contains("anilist_token"))
    }
}
