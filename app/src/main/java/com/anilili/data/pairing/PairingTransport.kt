package com.anilili.data.pairing

import com.anilili.diagnostics.DiagnosticsLog
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The sockets under [PairingConversation]. Blocking I/O on [Dispatchers.IO] with a read timeout,
 * because a pairing is one short conversation between two devices in the same room — there is
 * nothing here that benefits from being non-blocking.
 */

/** Bounds every blocked read. The overall pairing window is enforced by the caller. */
private const val READ_TIMEOUT_MS = 120_000

/**
 * How many conversations may fail before the television stops listening. A pairing that keeps
 * going wrong is either a bug or someone poking at the port; neither deserves an open socket for
 * the rest of the window.
 */
private const val MAX_ATTEMPTS = 3

/**
 * The television's side: binds an ephemeral port on every interface, then serves one conversation
 * at a time.
 *
 * Every accepted connection gets a **fresh** key pair, nonce and PKCE verifier. Nothing is carried
 * across attempts, so a failed or hostile connection cannot influence the next one.
 */
internal class PairingServer(
    private val deviceLabel: String,
    private val newVerifier: () -> String,
) : Closeable {
    private val serverSocket = ServerSocket()

    val port: Int get() = serverSocket.localPort

    fun bind(): Int {
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(0))
        DiagnosticsLog.event(
            category = "pairing",
            name = "pairing.listening",
            attributes = mapOf("port" to serverSocket.localPort),
        )
        PairingDiagnostics.log("Listening on port ${serverSocket.localPort}")
        localPairingAddresses().forEach { (iface, address) ->
            PairingDiagnostics.log("Reachable at $address:${serverSocket.localPort} ($iface)")
        }
        if (localPairingAddresses().isEmpty()) {
            PairingDiagnostics.log("WARNING no site-local IPv4 address on any interface")
        }
        return serverSocket.localPort
    }

    suspend fun serve(
        onCodeShown: (code: String, service: String) -> Unit,
        awaitLocalConfirm: suspend () -> Boolean,
        onCredential: suspend (PairingResult) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        var attempts = 0
        while (attempts < MAX_ATTEMPTS) {
            coroutineContext.ensureActive()
            val socket = runCatching { serverSocket.accept() }.getOrElse { failure ->
                // A closed socket is how cancellation reaches a blocked accept().
                coroutineContext.ensureActive()
                throw failure
            }
            attempts++
            try {
                socket.soTimeout = READ_TIMEOUT_MS
                socket.tcpNoDelay = true
                DiagnosticsLog.event(
                    category = "pairing",
                    name = "pairing.accepted",
                    attributes = mapOf("attempt" to attempts),
                )
                PairingDiagnostics.log(
                    "Connection $attempts/$MAX_ATTEMPTS from ${socket.inetAddress?.hostAddress ?: "?"}",
                )
                PairingConversation.responder(
                    input = socket.getInputStream(),
                    output = socket.getOutputStream(),
                    deviceLabel = deviceLabel,
                    keyPair = PairingCrypto.generateKeyPair(),
                    nonce = PairingCrypto.randomNonce(),
                    malVerifier = newVerifier(),
                    onCodeShown = onCodeShown,
                    awaitLocalConfirm = awaitLocalConfirm,
                    onCredential = onCredential,
                )
                return@withContext
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DiagnosticsLog.event(
                    category = "pairing",
                    name = "pairing.attempt_failed",
                    attributes = mapOf(
                        "attempt" to attempts,
                        "errorType" to failure.javaClass.simpleName,
                        "message" to (failure.message ?: "none"),
                    ),
                )
                PairingDiagnostics.log(
                    "Attempt $attempts failed: ${failure.javaClass.simpleName}: ${failure.message ?: "no detail"}",
                )
                if (attempts >= MAX_ATTEMPTS) throw failure
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
    }
}

/** The phone's side: one connection, one conversation. */
internal object PairingClient {
    private const val CONNECT_TIMEOUT_MS = 8_000

    suspend fun pair(
        host: String,
        port: Int,
        service: String,
        onCodeShown: (code: String, peerLabel: String) -> Unit,
        awaitLocalConfirm: suspend () -> Boolean,
        authorize: suspend (malChallenge: String?) -> PairingCredential,
    ): Unit = withContext(Dispatchers.IO) {
        PairingDiagnostics.log("Connecting to $host:$port")
        Socket().use { socket ->
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            } catch (failure: Exception) {
                // Classify here, while the address is still in hand: "connection refused" and
                // "timed out" have completely different fixes and a viewer cannot tell them apart
                // from the exception text.
                val advice = describePairingFailure(
                    error = failure,
                    host = host,
                    port = port,
                    phoneAddresses = localPairingAddresses().map { it.second },
                )
                PairingDiagnostics.log("COULD NOT CONNECT: ${advice.withCode()}")
                PairingDiagnostics.log(advice.hint)
                throw failure
            }
            PairingDiagnostics.log("Connected, local port ${socket.localPort}")
            socket.soTimeout = READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            PairingConversation.initiator(
                input = socket.getInputStream(),
                output = socket.getOutputStream(),
                service = service,
                keyPair = PairingCrypto.generateKeyPair(),
                nonce = PairingCrypto.randomNonce(),
                onCodeShown = onCodeShown,
                awaitLocalConfirm = awaitLocalConfirm,
                authorize = authorize,
            )
        }
    }
}

// localPairingAddress()/localPairingAddresses() live in PairingDiagnostics.kt, next to the rest of
// the network state a viewer may have to read off the screen.
