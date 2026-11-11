package com.anilili.data.pairing

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import okio.ByteString
import okio.ByteString.Companion.decodeBase64

internal const val PAIRING_PROTOCOL_VERSION = 1

internal const val PAIRING_SERVICE_ANILIST = "anilist"
internal const val PAIRING_SERVICE_MAL = "mal"

/**
 * The frames exchanged over the pairing socket, per `docs/TV_PAIRING_PROTOCOL.md`.
 *
 * Byte fields are [ByteString] rather than `ByteArray` for two reasons that both matter in
 * protocol code: a `data class` holding a `ByteArray` compares by *reference*, so frame equality
 * would quietly be identity, and okio's type is immutable, so key material cannot be altered
 * underneath a session that already used it.
 *
 * Services are plain strings rather than an enum so a newer phone asking an older TV for something
 * it has never heard of gets a clean refusal instead of a parse failure.
 */
@Serializable
internal sealed interface PairingFrame

/** 1. Initiator binds itself to a key it has not revealed yet. */
@Serializable
@SerialName("commit")
internal data class CommitFrame(
    val v: Int = PAIRING_PROTOCOL_VERSION,
    val svc: String,
    @Serializable(with = ByteStringSerializer::class) val commit: ByteString,
) : PairingFrame

/** 2. Responder reveals its key. [challenge] carries the TV's PKCE challenge for MAL. */
@Serializable
@SerialName("hello")
internal data class HelloFrame(
    val v: Int = PAIRING_PROTOCOL_VERSION,
    @Serializable(with = ByteStringSerializer::class) val key: ByteString,
    @Serializable(with = ByteStringSerializer::class) val nonce: ByteString,
    val label: String,
    val challenge: String? = null,
) : PairingFrame

/** 3. Initiator opens its commitment. */
@Serializable
@SerialName("open")
internal data class OpenFrame(
    @Serializable(with = ByteStringSerializer::class) val key: ByteString,
    @Serializable(with = ByteStringSerializer::class) val nonce: ByteString,
) : PairingFrame

/** 4 & 5. Each side reports that its viewer accepted the displayed code. */
@Serializable
@SerialName("confirm")
internal data class ConfirmFrame(val ok: Boolean) : PairingFrame

/** 6. The credential, sealed under the session key. */
@Serializable
@SerialName("credential")
internal data class CredentialFrame(
    @Serializable(with = ByteStringSerializer::class) val iv: ByteString,
    @Serializable(with = ByteStringSerializer::class) val ciphertext: ByteString,
) : PairingFrame

/** 7. Responder reports whether the credential was accepted and stored. */
@Serializable
@SerialName("result")
internal data class ResultFrame(val ok: Boolean, val error: String? = null) : PairingFrame

/**
 * The plaintext inside [CredentialFrame]. AniList has no PKCE, so its bearer token itself crosses;
 * MAL sends only the authorization code, which the TV exchanges with the verifier it never sent.
 */
@Serializable
internal sealed interface PairingCredential

@Serializable
@SerialName("anilist_token")
internal data class AniListTokenCredential(val token: String) : PairingCredential

@Serializable
@SerialName("mal_code")
internal data class MalCodeCredential(val code: String) : PairingCredential

/**
 * Byte fields ride as Base64 inside JSON, decoded through okio.
 *
 * Deliberately not `android.util.Base64`: it is a framework stub under unit tests and this module
 * sets `isReturnDefaultValues`, so it would return null instead of failing — and every pairing
 * test would then pass against empty keys.
 */
internal object ByteStringSerializer : KSerializer<ByteString> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Base64ByteString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteString) {
        encoder.encodeString(value.base64())
    }

    override fun deserialize(decoder: Decoder): ByteString =
        decoder.decodeString().decodeBase64() ?: throw PairingProtocolException("Field is not valid Base64")
}

/**
 * Length-prefixed JSON frames: `[4-byte big-endian length][UTF-8 JSON]`.
 *
 * Raw framed TCP rather than HTTP because there is no browser on either end of this link, and a
 * frame reader is less code than a server.
 */
internal object PairingCodec {
    /** Generous for a token, small enough that a hostile peer cannot make the TV allocate. */
    const val MAX_FRAME_BYTES = 64 * 1024

    private const val LENGTH_PREFIX_BYTES = 4

    val json: Json = Json {
        classDiscriminator = "kind"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(frame: PairingFrame): ByteArray {
        val body = json.encodeToString(PairingFrame.serializer(), frame).toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_FRAME_BYTES) { "Frame of ${body.size} bytes exceeds $MAX_FRAME_BYTES" }
        return lengthPrefix(body.size) + body
    }

    fun decode(body: ByteArray): PairingFrame =
        json.decodeFromString(PairingFrame.serializer(), body.toString(Charsets.UTF_8))

    fun encodeCredential(credential: PairingCredential): ByteArray =
        json.encodeToString(PairingCredential.serializer(), credential).toByteArray(Charsets.UTF_8)

    fun decodeCredential(plaintext: ByteArray): PairingCredential =
        json.decodeFromString(PairingCredential.serializer(), plaintext.toString(Charsets.UTF_8))

    fun writeFrame(output: OutputStream, frame: PairingFrame) {
        output.write(encode(frame))
        output.flush()
    }

    fun readFrame(input: InputStream): PairingFrame {
        val length = readInt(input)
        if (length !in 1..MAX_FRAME_BYTES) {
            throw PairingProtocolException("Frame length $length is outside 1..$MAX_FRAME_BYTES")
        }
        return decode(readExactly(input, length))
    }

    private fun lengthPrefix(length: Int): ByteArray = byteArrayOf(
        (length ushr 24).toByte(),
        (length ushr 16).toByte(),
        (length ushr 8).toByte(),
        length.toByte(),
    )

    private fun readInt(input: InputStream): Int {
        val header = readExactly(input, LENGTH_PREFIX_BYTES)
        var value = 0
        for (byte in header) value = (value shl 8) or (byte.toInt() and 0xFF)
        return value
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(buffer, read, length - read)
            if (count < 0) throw EOFException("Peer closed after $read of $length bytes")
            read += count
        }
        return buffer
    }
}

internal class PairingProtocolException(message: String) : Exception(message)
