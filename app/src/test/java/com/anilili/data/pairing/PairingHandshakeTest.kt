package com.anilili.data.pairing

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The pairing handshake, both roles driven against each other in-process.
 *
 * The two tests that carry the security argument are the man-in-the-middle one — which is the
 * whole reason the viewers compare a code at all — and the commitment one, without which an
 * attacker can grind ephemeral keys until the two codes happen to match, at six digits a matter
 * of seconds.
 */
class PairingHandshakeTest {
    @Test
    fun `a clean pairing agrees on a code and delivers the token`() {
        val tv = responder()
        val phone = initiator(PAIRING_SERVICE_ANILIST)

        val hello = tv.onCommit(phone.commit())
        tv.onOpen(phone.onHello(hello))

        assertEquals(phone.sas, tv.sas)
        assertEquals(PairingCrypto.SAS_DIGITS, tv.sas!!.length)
        assertEquals(TV_LABEL, phone.peerLabel)
        assertEquals(ResponderState.AWAITING_CONFIRM, tv.state)
        assertEquals(InitiatorState.AWAITING_CONFIRM, phone.state)

        tv.onPeerConfirm(phone.confirmLocally())
        phone.onPeerConfirm(tv.confirmLocally())
        assertEquals(ResponderState.AWAITING_CREDENTIAL, tv.state)
        assertEquals(InitiatorState.AWAITING_CREDENTIAL_SEND, phone.state)

        val delivered = tv.openCredential(phone.sealCredential(AniListTokenCredential("jwt-token")))
        assertEquals(AniListTokenCredential("jwt-token"), delivered)
        assertEquals(ResponderState.COMPLETE, tv.state)

        phone.onResult(ResultFrame(ok = true))
        assertEquals(InitiatorState.COMPLETE, phone.state)
    }

    @Test
    fun `a man in the middle cannot make the two codes agree`() {
        // The attacker terminates both legs: it looks like a TV to the phone and like a phone to
        // the TV. Each leg is internally consistent — what it cannot do is reconcile them.
        val phone = initiator(PAIRING_SERVICE_ANILIST, keySeed = 11)
        val attackerFacingPhone = ResponderHandshake(
            deviceLabel = "Living Room TV",
            keyPair = keyPair(31),
            nonce = nonce(31),
        )
        val attackerFacingTv = InitiatorHandshake(
            service = PAIRING_SERVICE_ANILIST,
            keyPair = keyPair(41),
            nonce = nonce(41),
        )
        val tv = responder()

        attackerFacingPhone.onOpen(phone.onHello(attackerFacingPhone.onCommit(phone.commit())))
        attackerFacingTv.onHello(tv.onCommit(attackerFacingTv.commit()))
            .also { tv.onOpen(it) }

        assertEquals(phone.sas, attackerFacingPhone.sas)
        assertEquals(attackerFacingTv.sas, tv.sas)
        // What the two humans actually see, and the reason they are asked to look.
        assertNotEquals(phone.sas, tv.sas)
    }

    @Test
    fun `an opened key that does not match the commitment is refused`() {
        val tv = responder()
        val phone = initiator(PAIRING_SERVICE_ANILIST)
        phone.onHello(tv.onCommit(phone.commit()))

        val substituted = OpenFrame(key = keyPair(77).publicKey.toByteString(), nonce = nonce(3).toByteString())
        assertThrows(PairingProtocolException::class.java) { tv.onOpen(substituted) }
        assertEquals(ResponderState.FAILED, tv.state)
    }

    @Test
    fun `a swapped nonce is caught even when the key is genuine`() {
        val tv = responder()
        val phone = initiator(PAIRING_SERVICE_ANILIST, keySeed = 11, nonceSeed = 12)
        val open = phone.onHello(tv.onCommit(phone.commit()))

        val tampered = open.copy(nonce = nonce(99).toByteString())
        assertThrows(PairingProtocolException::class.java) { tv.onOpen(tampered) }
    }

    @Test
    fun `the TV refuses a service it was not opened for`() {
        val tv = ResponderHandshake(
            deviceLabel = TV_LABEL,
            keyPair = keyPair(1),
            nonce = nonce(1),
            supportedServices = setOf(PAIRING_SERVICE_ANILIST),
        )
        assertThrows(PairingProtocolException::class.java) {
            tv.onCommit(initiator(PAIRING_SERVICE_MAL).commit())
        }
        assertEquals(ResponderState.FAILED, tv.state)
    }

    @Test
    fun `a mismatched protocol version is refused by both roles`() {
        assertThrows(PairingProtocolException::class.java) {
            responder().onCommit(CommitFrame(v = 99, svc = PAIRING_SERVICE_ANILIST, commit = hex("00").toByteString()))
        }
        assertThrows(PairingProtocolException::class.java) {
            initiator(PAIRING_SERVICE_ANILIST).onHello(
                HelloFrame(
                    v = 99,
                    key = keyPair(1).publicKey.toByteString(),
                    nonce = nonce(1).toByteString(),
                    label = TV_LABEL,
                ),
            )
        }
    }

    @Test
    fun `the PKCE challenge is sent for MAL and withheld for AniList`() {
        val forMal = responder().onCommit(initiator(PAIRING_SERVICE_MAL).commit())
        assertEquals(MAL_CHALLENGE, forMal.challenge)

        val forAniList = responder().onCommit(initiator(PAIRING_SERVICE_ANILIST).commit())
        assertNull(forAniList.challenge)
    }

    @Test
    fun `a MAL pairing without a challenge is abandoned rather than started`() {
        // Without the TV's verifier the TV could never complete the exchange, so there is no
        // point running the OAuth — and a TV that omits it is not one this phone understands.
        val phone = initiator(PAIRING_SERVICE_MAL)
        val hello = HelloFrame(
            key = keyPair(1).publicKey.toByteString(),
            nonce = nonce(1).toByteString(),
            label = TV_LABEL,
            challenge = null,
        )
        assertThrows(PairingProtocolException::class.java) { phone.onHello(hello) }
        assertEquals(InitiatorState.FAILED, phone.state)
    }

    @Test
    fun `either side declining the code stops the pairing`() {
        val tv = responder()
        val phone = initiator(PAIRING_SERVICE_ANILIST)
        tv.onOpen(phone.onHello(tv.onCommit(phone.commit())))

        assertThrows(PairingProtocolException::class.java) { tv.onPeerConfirm(ConfirmFrame(ok = false)) }
        assertEquals(ResponderState.FAILED, tv.state)
        assertThrows(PairingProtocolException::class.java) { phone.onPeerConfirm(ConfirmFrame(ok = false)) }
        assertEquals(InitiatorState.FAILED, phone.state)
    }

    @Test
    fun `confirmation order does not matter`() {
        // The TV's viewer may press first, or the phone's; the socket delivers whichever arrives.
        val tv = responder()
        val phone = initiator(PAIRING_SERVICE_ANILIST)
        tv.onOpen(phone.onHello(tv.onCommit(phone.commit())))

        val fromTv = tv.confirmLocally()
        assertEquals(ResponderState.AWAITING_CONFIRM, tv.state)
        phone.onPeerConfirm(fromTv)
        tv.onPeerConfirm(phone.confirmLocally())

        assertEquals(ResponderState.AWAITING_CREDENTIAL, tv.state)
        assertEquals(InitiatorState.AWAITING_CREDENTIAL_SEND, phone.state)
    }

    @Test
    fun `a credential cannot be sent before both viewers have confirmed`() {
        val tv = responder()
        val phone = initiator(PAIRING_SERVICE_ANILIST)
        tv.onOpen(phone.onHello(tv.onCommit(phone.commit())))

        assertThrows(PairingProtocolException::class.java) {
            phone.sealCredential(AniListTokenCredential("too-early"))
        }
    }

    @Test
    fun `a credential for the wrong service is rejected after decryption`() {
        // The associated data already binds ciphertext to service; this catches a payload that
        // decrypts cleanly but claims to be something the session never negotiated.
        val tv = responder()
        val phone = initiator(PAIRING_SERVICE_MAL)
        tv.onOpen(phone.onHello(tv.onCommit(phone.commit())))
        tv.onPeerConfirm(phone.confirmLocally())
        phone.onPeerConfirm(tv.confirmLocally())

        val wrong = phone.sealCredential(AniListTokenCredential("not-a-mal-code"))
        assertThrows(PairingProtocolException::class.java) { tv.openCredential(wrong) }
        assertEquals(ResponderState.FAILED, tv.state)
    }

    @Test
    fun `a credential from a different session does not authenticate`() {
        val tv = responder()
        val phone = initiator(PAIRING_SERVICE_ANILIST)
        tv.onOpen(phone.onHello(tv.onCommit(phone.commit())))
        tv.onPeerConfirm(phone.confirmLocally())
        phone.onPeerConfirm(tv.confirmLocally())

        // A second, unrelated pairing between different ephemeral keys.
        val otherTv = ResponderHandshake(deviceLabel = TV_LABEL, keyPair = keyPair(51), nonce = nonce(51))
        val otherPhone = initiator(PAIRING_SERVICE_ANILIST, keySeed = 61, nonceSeed = 61)
        otherTv.onOpen(otherPhone.onHello(otherTv.onCommit(otherPhone.commit())))
        otherTv.onPeerConfirm(otherPhone.confirmLocally())
        otherPhone.onPeerConfirm(otherTv.confirmLocally())

        val replayed = otherPhone.sealCredential(AniListTokenCredential("someone-elses"))
        assertThrows(PairingProtocolException::class.java) { tv.openCredential(replayed) }
    }

    @Test
    fun `frames arriving out of order are refused`() {
        val tv = responder()
        assertThrows(PairingProtocolException::class.java) {
            tv.onOpen(OpenFrame(key = keyPair(1).publicKey.toByteString(), nonce = nonce(1).toByteString()))
        }
        assertEquals(ResponderState.FAILED, tv.state)
    }

    private fun responder() = ResponderHandshake(
        deviceLabel = TV_LABEL,
        keyPair = keyPair(1),
        nonce = nonce(1),
        malChallenge = MAL_CHALLENGE,
    )

    private fun initiator(service: String, keySeed: Int = 9, nonceSeed: Int = 3) = InitiatorHandshake(
        service = service,
        keyPair = keyPair(keySeed),
        nonce = nonce(nonceSeed),
    )

    private companion object {
        const val TV_LABEL = "Living Room TV"
        const val MAL_CHALLENGE = "a-43-char-plain-pkce-verifier-goes-right-here"
    }
}
