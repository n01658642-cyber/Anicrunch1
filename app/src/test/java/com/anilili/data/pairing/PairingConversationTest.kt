package com.anilili.data.pairing

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both halves of the conversation run against each other over a real loopback socket, which is
 * what proves the frame ordering actually works: each role writes its own confirmation before
 * reading the peer's, so neither ordering of the two viewers can deadlock the pair. A test that
 * only drove the state machines would never have exercised that.
 */
class PairingConversationTest {
    @Test
    fun `an AniList pairing carries the token to the television`() = runBlocking {
        val delivered = exchange(
            service = PAIRING_SERVICE_ANILIST,
            credential = AniListTokenCredential("a-real-looking-jwt"),
        )
        assertEquals(AniListTokenCredential("a-real-looking-jwt"), delivered.result?.credential)
        assertEquals(PAIRING_SERVICE_ANILIST, delivered.result?.service)
        assertEquals(delivered.responderCode, delivered.initiatorCode)
        assertEquals(PairingCrypto.SAS_DIGITS, delivered.responderCode?.length)
        // AniList has no PKCE; the phone must not be handed a challenge it would only misuse.
        assertNull(delivered.challengeSeenByPhone)
    }

    @Test
    fun `a MAL pairing carries the code and the television keeps its own verifier`() = runBlocking {
        val delivered = exchange(
            service = PAIRING_SERVICE_MAL,
            credential = MalCodeCredential("an-authorization-code"),
        )
        assertEquals(MalCodeCredential("an-authorization-code"), delivered.result?.credential)
        // The verifier handed back for the exchange is the one this attempt generated, and it is
        // what the phone authorized against.
        assertEquals(VERIFIER, delivered.result?.malVerifier)
        assertEquals(VERIFIER, delivered.challengeSeenByPhone)
    }

    @Test
    fun `the television refusing the code stops the pairing before any sign-in`() = runBlocking {
        var authorizeRan = false
        val outcome = runCatching {
            exchange(
                service = PAIRING_SERVICE_ANILIST,
                credential = AniListTokenCredential("never-requested"),
                responderConfirms = false,
                onAuthorize = { authorizeRan = true },
            )
        }
        assertTrue("the pairing should not have completed", outcome.isFailure)
        // The whole point of confirming first: nothing is asked of the account until both viewers
        // have agreed they are looking at the same digits.
        assertTrue("the OAuth must not have run", !authorizeRan)
    }

    @Test
    fun `the phone refusing the code stops the pairing`() = runBlocking {
        val outcome = runCatching {
            exchange(
                service = PAIRING_SERVICE_ANILIST,
                credential = AniListTokenCredential("never-sent"),
                initiatorConfirms = false,
            )
        }
        assertTrue(outcome.isFailure)
    }

    private class Exchange {
        var result: PairingResult? = null
        var responderCode: String? = null
        var initiatorCode: String? = null
        var peerLabel: String? = null
        var challengeSeenByPhone: String? = null
    }

    private suspend fun exchange(
        service: String,
        credential: PairingCredential,
        responderConfirms: Boolean = true,
        initiatorConfirms: Boolean = true,
        onAuthorize: () -> Unit = {},
    ): Exchange = coroutineScope {
        val outcome = Exchange()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val responder = async(Dispatchers.IO) {
                listener.accept().use { socket ->
                    PairingConversation.responder(
                        input = socket.getInputStream(),
                        output = socket.getOutputStream(),
                        deviceLabel = TV_LABEL,
                        keyPair = keyPair(1),
                        nonce = nonce(1),
                        malVerifier = VERIFIER,
                        onCodeShown = { code, _ -> outcome.responderCode = code },
                        awaitLocalConfirm = { responderConfirms },
                        onCredential = { outcome.result = it },
                    )
                }
            }
            val initiator = async(Dispatchers.IO) {
                Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
                    PairingConversation.initiator(
                        input = socket.getInputStream(),
                        output = socket.getOutputStream(),
                        service = service,
                        keyPair = keyPair(9),
                        nonce = nonce(3),
                        onCodeShown = { code, label ->
                            outcome.initiatorCode = code
                            outcome.peerLabel = label
                        },
                        awaitLocalConfirm = { initiatorConfirms },
                        authorize = { challenge ->
                            outcome.challengeSeenByPhone = challenge
                            onAuthorize()
                            credential
                        },
                    )
                }
            }
            // Both must finish; awaiting only one would let a failure on the other side pass.
            initiator.await()
            responder.await()
        }
        assertNotNull(outcome.responderCode)
        assertEquals(TV_LABEL, outcome.peerLabel)
        outcome
    }

    private companion object {
        const val TV_LABEL = "Living Room TV"
        const val VERIFIER = "a-43-char-plain-pkce-verifier-goes-right-here"
    }
}
