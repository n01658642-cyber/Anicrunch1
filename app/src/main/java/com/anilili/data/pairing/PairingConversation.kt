package com.anilili.data.pairing

import java.io.InputStream
import java.io.OutputStream

/**
 * The handshake driven over a connected pair of streams, one function per role.
 *
 * Split from the sockets so both halves can be run against each other in a test — over loopback,
 * or over anything else that carries bytes.
 *
 * Both roles follow the same order around the code: show it, wait for this device's viewer, send
 * that decision, then read the peer's. Each side therefore writes before it reads, so neither
 * ordering of the two humans can deadlock the pair — whoever presses first simply leaves a frame
 * waiting in the other's socket buffer.
 */
internal object PairingConversation {

    /** The television. Receives a credential and hands it to [onCredential] to store. */
    suspend fun responder(
        input: InputStream,
        output: OutputStream,
        deviceLabel: String,
        keyPair: PairingKeyPair,
        nonce: ByteArray,
        malVerifier: String,
        onCodeShown: (code: String, service: String) -> Unit,
        awaitLocalConfirm: suspend () -> Boolean,
        onCredential: suspend (PairingResult) -> Unit,
    ) {
        val handshake = ResponderHandshake(
            deviceLabel = deviceLabel,
            keyPair = keyPair,
            nonce = nonce,
            malChallenge = malVerifier,
        )
        PairingCodec.writeFrame(output, handshake.onCommit(read(input)))
        handshake.onOpen(read<OpenFrame>(input))

        val code = handshake.sas ?: throw PairingProtocolException("No code was derived")
        val service = handshake.service ?: throw PairingProtocolException("No service was negotiated")
        onCodeShown(code, service)

        if (!awaitLocalConfirm()) {
            runCatching { PairingCodec.writeFrame(output, ConfirmFrame(ok = false)) }
            handshake.abort()
            throw PairingProtocolException("The code was declined on this television")
        }
        PairingCodec.writeFrame(output, handshake.confirmLocally())
        handshake.onPeerConfirm(read(input))

        val credential = handshake.openCredential(read(input))
        try {
            onCredential(PairingResult(credential = credential, service = service, malVerifier = malVerifier))
        } catch (failure: Exception) {
            runCatching {
                PairingCodec.writeFrame(
                    output,
                    ResultFrame(ok = false, error = failure.message ?: "The television could not store the account"),
                )
            }
            throw failure
        }
        PairingCodec.writeFrame(output, ResultFrame(ok = true))
    }

    /**
     * The phone. [authorize] runs the OAuth once both viewers have accepted the code, and receives
     * the TV's PKCE challenge for a MAL pairing.
     */
    suspend fun initiator(
        input: InputStream,
        output: OutputStream,
        service: String,
        keyPair: PairingKeyPair,
        nonce: ByteArray,
        onCodeShown: (code: String, peerLabel: String) -> Unit,
        awaitLocalConfirm: suspend () -> Boolean,
        authorize: suspend (malChallenge: String?) -> PairingCredential,
    ) {
        val handshake = InitiatorHandshake(service = service, keyPair = keyPair, nonce = nonce)
        PairingCodec.writeFrame(output, handshake.commit())
        PairingCodec.writeFrame(output, handshake.onHello(read(input)))

        val code = handshake.sas ?: throw PairingProtocolException("No code was derived")
        onCodeShown(code, handshake.peerLabel.orEmpty())

        if (!awaitLocalConfirm()) {
            runCatching { PairingCodec.writeFrame(output, ConfirmFrame(ok = false)) }
            handshake.abort()
            throw PairingProtocolException("The code was declined on this phone")
        }
        PairingCodec.writeFrame(output, handshake.confirmLocally())
        handshake.onPeerConfirm(read(input))

        // Nothing has been asked of the account until here: the OAuth only runs once both people
        // have agreed they are looking at the same six digits.
        PairingCodec.writeFrame(output, handshake.sealCredential(authorize(handshake.malChallenge)))
        handshake.onResult(read(input))
    }

    private inline fun <reified T : PairingFrame> read(input: InputStream): T {
        val frame = PairingCodec.readFrame(input)
        return frame as? T
            ?: throw PairingProtocolException("Expected ${T::class.simpleName} but received ${frame::class.simpleName}")
    }
}

/**
 * What a completed pairing hands the television.
 *
 * [malVerifier] is the PKCE verifier this attempt generated. It never left the TV as a secret —
 * MAL only supports `plain`, so the phone saw it as the challenge — but the exchange has to be
 * performed here, with this value, so the television ends up holding its own token pair.
 */
internal data class PairingResult(
    val credential: PairingCredential,
    val service: String,
    val malVerifier: String,
)
