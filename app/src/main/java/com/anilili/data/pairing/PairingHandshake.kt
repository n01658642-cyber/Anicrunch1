package com.anilili.data.pairing

import java.security.SecureRandom
import okio.ByteString.Companion.toByteString

/**
 * The pairing handshake as two pure state machines, one per role, driven entirely by frames.
 *
 * No sockets, no discovery, no Android: the transport feeds frames in and writes frames out, so
 * every security-relevant decision in `docs/TV_PAIRING_PROTOCOL.md` is reachable from a plain JVM
 * test — including running both roles against each other in-process, with and without an attacker
 * in between.
 */

internal enum class ResponderState {
    AWAITING_COMMIT,
    AWAITING_OPEN,
    AWAITING_CONFIRM,
    AWAITING_CREDENTIAL,
    COMPLETE,
    FAILED,
}

internal enum class InitiatorState {
    AWAITING_HELLO,
    AWAITING_CONFIRM,
    AWAITING_CREDENTIAL_SEND,
    AWAITING_RESULT,
    COMPLETE,
    FAILED,
}

/**
 * The TV. Listens, learns which service the phone is offering, and never reveals its own key until
 * the phone has committed to one.
 *
 * [malChallenge] is the PKCE challenge for a MAL pairing. Under `plain` — the only method MAL
 * supports — the challenge is the verifier, so this is not secret from the phone. It is sent
 * anyway so the *TV* performs the token exchange and ends up with its own token pair: MAL rotates
 * refresh tokens, and two devices sharing one pair would log each other out.
 */
internal class ResponderHandshake(
    private val deviceLabel: String,
    private val keyPair: PairingKeyPair,
    private val nonce: ByteArray,
    private val malChallenge: String? = null,
    private val supportedServices: Set<String> = setOf(PAIRING_SERVICE_ANILIST, PAIRING_SERVICE_MAL),
) {
    var state: ResponderState = ResponderState.AWAITING_COMMIT
        private set

    /** The six digits to display. Available once [onOpen] has run. */
    var sas: String? = null
        private set

    var service: String? = null
        private set

    private var commitment: ByteArray? = null
    private var sessionKey: ByteArray? = null
    private var peerConfirmed = false
    private var localConfirmed = false

    fun onCommit(frame: CommitFrame): HelloFrame {
        expect(ResponderState.AWAITING_COMMIT)
        if (frame.v != PAIRING_PROTOCOL_VERSION) fail("Unsupported protocol version ${frame.v}")
        if (frame.svc !in supportedServices) fail("This build cannot pair ${frame.svc}")
        commitment = frame.commit.toByteArray()
        service = frame.svc
        state = ResponderState.AWAITING_OPEN
        return HelloFrame(
            key = keyPair.publicKey.toByteString(),
            nonce = nonce.toByteString(),
            label = deviceLabel,
            challenge = if (frame.svc == PAIRING_SERVICE_MAL) malChallenge else null,
        )
    }

    fun onOpen(frame: OpenFrame) {
        expect(ResponderState.AWAITING_OPEN)
        val expected = commitment ?: fail("No commitment recorded")
        val initiatorKey = runCatching { PairingCrypto.requireValidPublicKey(frame.key.toByteArray()) }
            .getOrElse { fail("Initiator sent an invalid public key") }
        val initiatorNonce = frame.nonce.toByteArray()
        if (initiatorNonce.size != PairingCrypto.NONCE_BYTES) fail("Initiator nonce is the wrong length")
        // The whole anti-grinding argument rests on this check: the initiator had to name its key
        // before it could see ours, so it never had the chance to search for one whose code
        // happens to match a second session's.
        if (!PairingCrypto.constantTimeEquals(PairingCrypto.commitment(initiatorKey, initiatorNonce), expected)) {
            fail("Opened key does not match the commitment")
        }
        val secrets = PairingCrypto.deriveSecrets(
            privateKey = keyPair.privateKey,
            peerPublicKey = initiatorKey,
            transcript = PairingCrypto.transcript(
                initiatorPublicKey = initiatorKey,
                responderPublicKey = keyPair.publicKey,
                initiatorNonce = initiatorNonce,
                responderNonce = nonce,
                service = requireService(),
            ),
        )
        sessionKey = secrets.sessionKey
        sas = secrets.sas
        state = ResponderState.AWAITING_CONFIRM
    }

    fun onPeerConfirm(frame: ConfirmFrame) {
        expect(ResponderState.AWAITING_CONFIRM)
        if (!frame.ok) fail("The phone declined the code")
        peerConfirmed = true
        advanceIfConfirmed()
    }

    /** The viewer pressed the confirm button on the television. */
    fun confirmLocally(): ConfirmFrame {
        expect(ResponderState.AWAITING_CONFIRM)
        localConfirmed = true
        advanceIfConfirmed()
        return ConfirmFrame(ok = true)
    }

    fun openCredential(frame: CredentialFrame): PairingCredential {
        expect(ResponderState.AWAITING_CREDENTIAL)
        val key = sessionKey ?: fail("No session key")
        val plaintext = runCatching {
            PairingCrypto.open(
                sessionKey = key,
                associatedData = requireService().toByteArray(Charsets.UTF_8),
                payload = SealedPayload(frame.iv.toByteArray(), frame.ciphertext.toByteArray()),
            )
        }.getOrElse { fail("Credential failed authentication") }
        val credential = runCatching { PairingCodec.decodeCredential(plaintext) }
            .getOrElse { fail("Credential payload was unreadable") }
        val expectedService = when (credential) {
            is AniListTokenCredential -> PAIRING_SERVICE_ANILIST
            is MalCodeCredential -> PAIRING_SERVICE_MAL
        }
        // A phone that negotiated one service must not deliver another; the AAD already binds the
        // ciphertext to `service`, and this rejects a payload that disagrees with it in the clear.
        if (expectedService != requireService()) fail("Credential is for $expectedService, not ${requireService()}")
        state = ResponderState.COMPLETE
        return credential
    }

    fun abort() {
        state = ResponderState.FAILED
    }

    private fun advanceIfConfirmed() {
        if (peerConfirmed && localConfirmed) state = ResponderState.AWAITING_CREDENTIAL
    }

    private fun requireService(): String = service ?: fail("No service negotiated")

    private fun expect(required: ResponderState) {
        if (state != required) fail("Expected $required but the session is $state")
    }

    private fun fail(message: String): Nothing {
        state = ResponderState.FAILED
        throw PairingProtocolException(message)
    }
}

/**
 * The phone. Commits first, runs the OAuth once both viewers have accepted the code, and sends
 * nothing that could be replayed against a different session.
 */
internal class InitiatorHandshake(
    private val service: String,
    private val keyPair: PairingKeyPair,
    private val nonce: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    var state: InitiatorState = InitiatorState.AWAITING_HELLO
        private set

    var sas: String? = null
        private set

    /** The TV's own name, for the confirmation prompt. Available once [onHello] has run. */
    var peerLabel: String? = null
        private set

    /** The TV's PKCE challenge for a MAL pairing; the phone authorizes with it. */
    var malChallenge: String? = null
        private set

    private var sessionKey: ByteArray? = null
    private var peerConfirmed = false
    private var localConfirmed = false

    fun commit(): CommitFrame = CommitFrame(
        svc = service,
        commit = PairingCrypto.commitment(keyPair.publicKey, nonce).toByteString(),
    )

    fun onHello(frame: HelloFrame): OpenFrame {
        expect(InitiatorState.AWAITING_HELLO)
        if (frame.v != PAIRING_PROTOCOL_VERSION) fail("Unsupported protocol version ${frame.v}")
        val responderKey = runCatching { PairingCrypto.requireValidPublicKey(frame.key.toByteArray()) }
            .getOrElse { fail("The TV sent an invalid public key") }
        val responderNonce = frame.nonce.toByteArray()
        if (responderNonce.size != PairingCrypto.NONCE_BYTES) fail("The TV's nonce is the wrong length")
        if (service == PAIRING_SERVICE_MAL && frame.challenge.isNullOrBlank()) {
            fail("The TV did not supply a PKCE challenge")
        }
        peerLabel = frame.label
        malChallenge = frame.challenge
        val secrets = PairingCrypto.deriveSecrets(
            privateKey = keyPair.privateKey,
            peerPublicKey = responderKey,
            transcript = PairingCrypto.transcript(
                initiatorPublicKey = keyPair.publicKey,
                responderPublicKey = responderKey,
                initiatorNonce = nonce,
                responderNonce = responderNonce,
                service = service,
            ),
        )
        sessionKey = secrets.sessionKey
        sas = secrets.sas
        state = InitiatorState.AWAITING_CONFIRM
        return OpenFrame(key = keyPair.publicKey.toByteString(), nonce = nonce.toByteString())
    }

    /** The viewer accepted the code on the phone. */
    fun confirmLocally(): ConfirmFrame {
        expect(InitiatorState.AWAITING_CONFIRM)
        localConfirmed = true
        advanceIfConfirmed()
        return ConfirmFrame(ok = true)
    }

    fun onPeerConfirm(frame: ConfirmFrame) {
        expect(InitiatorState.AWAITING_CONFIRM)
        if (!frame.ok) fail("The TV declined the code")
        peerConfirmed = true
        advanceIfConfirmed()
    }

    fun sealCredential(credential: PairingCredential): CredentialFrame {
        expect(InitiatorState.AWAITING_CREDENTIAL_SEND)
        val key = sessionKey ?: fail("No session key")
        val sealed = PairingCrypto.seal(
            sessionKey = key,
            associatedData = service.toByteArray(Charsets.UTF_8),
            plaintext = PairingCodec.encodeCredential(credential),
            random = random,
        )
        state = InitiatorState.AWAITING_RESULT
        return CredentialFrame(iv = sealed.iv.toByteString(), ciphertext = sealed.ciphertext.toByteString())
    }

    fun onResult(frame: ResultFrame) {
        expect(InitiatorState.AWAITING_RESULT)
        if (!frame.ok) fail(frame.error ?: "The TV rejected the credential")
        state = InitiatorState.COMPLETE
    }

    fun abort() {
        state = InitiatorState.FAILED
    }

    private fun advanceIfConfirmed() {
        if (peerConfirmed && localConfirmed) state = InitiatorState.AWAITING_CREDENTIAL_SEND
    }

    private fun expect(required: InitiatorState) {
        if (state != required) fail("Expected $required but the session is $state")
    }

    private fun fail(message: String): Nothing {
        state = InitiatorState.FAILED
        throw PairingProtocolException(message)
    }
}
