package com.anilili.data.discord

import com.anilili.diagnostics.DiagnosticsLog
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** Gateway opcodes, of the handful a presence-only client ever sees. */
internal object GatewayOp {
    const val DISPATCH = 0
    const val HEARTBEAT = 1
    const val IDENTIFY = 2
    const val PRESENCE_UPDATE = 3
    const val RECONNECT = 7
    const val INVALID_SESSION = 9
    const val HELLO = 10
    const val HEARTBEAT_ACK = 11
}

/**
 * Close codes Discord will never let a session resume through. 4004 is the one that matters here:
 * it means the token was refused, which is a signed-out session rather than a network blip, so
 * retrying with the same token would only loop.
 */
internal val FATAL_GATEWAY_CLOSE_CODES = setOf(4004, 4010, 4011, 4012, 4013, 4014)

internal data class GatewayClosed(val code: Int, val reason: String) {
    val fatal: Boolean get() = code in FATAL_GATEWAY_CLOSE_CODES
}

/**
 * One connection to Discord's gateway, opened with an OAuth bearer token.
 *
 * This is how a presence is set without the Discord app installed and without an account token:
 * the `sdk.social_layer_presence` scope expands to include `gateway.connect`, so `IDENTIFY` is
 * accepted with `Bearer <access token>` where a real client would send an account token. The
 * session is worth exactly one capability — publishing an activity — and nothing else.
 *
 * Deliberately not resumable. A presence carries no history worth restoring: if the socket drops,
 * the next update opens a fresh connection and republishes, which is both simpler and correct.
 * Note that a *closed* connection is also how a presence is cleared — Discord drops the activity
 * when the session that published it goes away — so [close] is the intended way to stop showing.
 */
internal class DiscordGateway(private val httpClient: OkHttpClient) {
    private companion object {
        const val GATEWAY_URL = "wss://gateway.discord.gg/?v=9&encoding=json"
        const val HELLO_TIMEOUT_MS = 20_000L

        /**
         * The bitfields a client identifies with. These are not documented for third parties, and
         * they are carried verbatim from a known-working client rather than derived: an IDENTIFY
         * missing them is closed rather than answered. Nothing here subscribes to message content;
         * the connection is used only to publish op 3.
         */
        const val CAPABILITIES = (1 shl 4) or (1 shl 5) or (1 shl 12) or (1 shl 16)
        const val INTENTS = (1 shl 12) or (1 shl 18) or (1 shl 19) or
            (1 shl 22) or (1 shl 23) or (1 shl 27) or (1 shl 28) or (1 shl 29)
    }

    private sealed interface Frame {
        data class Text(val text: String) : Frame
        data class Closed(val code: Int, val reason: String) : Frame
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val incoming = Channel<Frame>(Channel.UNLIMITED)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var shutDown = false
    private var pumpJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastSequence: Int? = null
    private var awaitingAck = false

    /** Invoked once, off the caller's thread, when the connection ends for any reason. */
    var onClosed: ((GatewayClosed) -> Unit)? = null

    val isReady: Boolean get() = ready && !shutDown && socket != null

    /** Opens the socket and suspends until Discord answers READY, or throws. */
    suspend fun connect(accessToken: String) {
        check(socket == null) { "Gateway already connected" }
        val readySignal = CompletableDeferred<Unit>()
        val request = Request.Builder().url(GATEWAY_URL).build()

        socket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    incoming.trySend(Frame.Text(text))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    incoming.trySend(Frame.Closed(code, reason))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    incoming.trySend(Frame.Closed(code, reason))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    response?.close()
                    incoming.trySend(Frame.Closed(response?.code ?: 4000, t.message ?: "failure"))
                }
            },
        )

        pumpJob = scope.launch {
            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> handleMessage(frame.text, accessToken, readySignal)
                        is Frame.Closed -> {
                            val closed = GatewayClosed(frame.code, frame.reason)
                            DiagnosticsLog.event(
                                category = "discord",
                                name = "gateway.closed",
                                // The reason tells a real 4000 from Discord apart from the one
                                // synthesised below for RECONNECT and INVALID_SESSION; 0.1.80's
                                // refusals were logged as a bare 4000 and could have been either.
                                attributes = mapOf(
                                    "code" to closed.code,
                                    "fatal" to closed.fatal,
                                    "reason" to closed.reason.take(120),
                                ),
                            )
                            // Not cancelling the pump here: this *is* the pump, and killing it
                            // before the listener below has run would drop the one notification
                            // the presence controller needs to redial on.
                            teardown(cancelPump = false)
                            if (!readySignal.isCompleted) {
                                readySignal.completeExceptionally(
                                    IllegalStateException("Gateway closed ${frame.code} before ready"),
                                )
                            }
                            onClosed?.invoke(closed)
                            return@launch
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!readySignal.isCompleted) readySignal.completeExceptionally(e)
            }
        }

        try {
            withTimeout(HELLO_TIMEOUT_MS) { readySignal.await() }
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    /** Publishes an activity. False when the socket is gone, which is the caller's cue to redial. */
    fun sendPresence(presence: JSONObject): Boolean {
        if (!isReady) return false
        return send(GatewayOp.PRESENCE_UPDATE, presence)
    }

    fun close() {
        val active = socket
        teardown(cancelPump = true)
        runCatching { active?.close(1000, "client closed") }
    }

    private fun handleMessage(
        raw: String,
        accessToken: String,
        readySignal: CompletableDeferred<Unit>,
    ) {
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (!message.isNull("s")) lastSequence = message.optInt("s")

        when (message.optInt("op", -1)) {
            GatewayOp.HELLO -> {
                val interval = message.optJSONObject("d")?.optLong("heartbeat_interval") ?: return
                startHeartbeat(interval)
                sendIdentify(accessToken)
            }

            GatewayOp.HEARTBEAT -> sendHeartbeat(force = true)
            GatewayOp.HEARTBEAT_ACK -> awaitingAck = false
            GatewayOp.RECONNECT, GatewayOp.INVALID_SESSION -> {
                incoming.trySend(Frame.Closed(4000, "server asked to reconnect"))
            }

            GatewayOp.DISPATCH -> {
                if (message.optString("t") == "READY") {
                    ready = true
                    DiagnosticsLog.event(category = "discord", name = "gateway.ready")
                    readySignal.complete(Unit)
                }
            }
        }
    }

    /**
     * No `presence` here, and it has to stay that way. 0.1.80 put the session's presence (status,
     * `afk`, `since`, no `activities`) into IDENTIFY, and every connection was then refused before
     * READY — "Gateway closed 4000 before ready" every one to five seconds, on a TV that had shown
     * cards on 0.1.79. The presence goes out as op 3 the moment READY arrives, with the activity in
     * it, which is what worked.
     */
    private fun sendIdentify(accessToken: String) {
        val properties = JSONObject()
            .put("os", "Android")
            .put("browser", "Anilili")
            .put("device", "Android")
            .put("browser_user_agent", "Anilili")
            .put("browser_version", "1.0")
            .put("client_version", "1.0")
            .put("client_build_number", 1)
            .put("native_build_number", 1)
            .put("release_channel", "unknown")
        val payload = JSONObject()
            .put("token", "Bearer $accessToken")
            .put("capabilities", CAPABILITIES)
            .put("intents", INTENTS)
            .put("properties", properties)
        send(GatewayOp.IDENTIFY, payload)
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        awaitingAck = false
        heartbeatJob = scope.launch {
            // Discord asks for the first beat at a random point inside the interval so a fleet of
            // clients reconnecting together does not arrive as one spike.
            delay((intervalMs * Random.nextDouble()).toLong())
            while (isActive) {
                sendHeartbeat()
                delay(intervalMs)
            }
        }
    }

    private fun sendHeartbeat(force: Boolean = false) {
        if (!force && awaitingAck) {
            // The previous beat was never acknowledged: the socket is open but nothing is on the
            // other end of it. Ending it is what gets a working connection back.
            incoming.trySend(Frame.Closed(4009, "heartbeat not acknowledged"))
            return
        }
        awaitingAck = true
        send(GatewayOp.HEARTBEAT, lastSequence)
    }

    private fun send(op: Int, data: Any?): Boolean {
        val active = socket ?: return false
        val frame = JSONObject().put("op", op)
        when (data) {
            null -> frame.put("d", JSONObject.NULL)
            is JSONObject -> frame.put("d", data)
            else -> frame.put("d", data)
        }
        return runCatching { active.send(frame.toString()) }.getOrDefault(false)
    }

    private fun teardown(cancelPump: Boolean) {
        shutDown = true
        ready = false
        socket = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        if (cancelPump) {
            pumpJob?.cancel()
            pumpJob = null
        }
    }
}
