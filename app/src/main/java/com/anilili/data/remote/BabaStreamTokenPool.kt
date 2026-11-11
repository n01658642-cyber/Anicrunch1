package com.anilili.data.remote

import com.anilili.diagnostics.DiagnosticsLog
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** A redeemed Cap token and the wall-clock moment it stops being accepted. */
internal data class BabaStreamToken(val value: String, val expiresAtMs: Long)

/**
 * Whether a challenge is worth starting, given what it would cost.
 *
 * The parameters arrive from the server on every challenge, so the work is theirs to set: raising
 * `d` by two would ask for 256 times the digests with nothing about the payload looking different.
 * A budget here means that shows up as one provider declining rather than as a device pinned at
 * 100% CPU for minutes while somebody waits for an episode.
 */
internal fun capChallengeIsAffordable(count: Int, difficulty: Int, budgetDigests: Long): Boolean =
    count in 1..MAX_CAP_PROOFS &&
        difficulty in 1..MAX_CAP_DIFFICULTY &&
        CapProofOfWork.expectedDigests(count, difficulty) <= budgetDigests

private const val MAX_CAP_PROOFS = 512
private const val MAX_CAP_DIFFICULTY = 8

/**
 * Keeps solved Cap tokens ready so no viewer waits behind a proof of work.
 *
 * BabaStream's challenge is about 5.1 million SHA-256 digests, its tokens are single-use, and its
 * session id is fresh on every page load — so one episode is one full solve, and doing that when
 * somebody presses play would put seconds of pinned CPU in front of playback. The pool therefore
 * pays the cost ahead of time and hands over a finished token; a take that finds the shelf empty
 * refills it behind itself, so the first episode of a session is the only one that can wait.
 *
 * Tokens live about two hours, which is far longer than the pool needs them to, so [POOL_TARGET] is
 * deliberately small: holding more would burn a device's battery solving work for episodes nobody
 * asked for.
 */
class BabaStreamTokenPool(
    private val client: OkHttpClient,
    private val json: Json,
    private val scope: CoroutineScope,
    /**
     * Whether this device should solve at all. False on low-RAM hardware — a TV box would spend
     * far longer on the same work than a phone, and 2Dhive there simply keeps the embed it already
     * had, which is what it was offering before any of this existed.
     */
    private val solvingAllowed: Boolean,
    private val parallelism: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
    private val budgetDigests: Long = DEFAULT_BUDGET_DIGESTS,
) {
    private val shelf = ArrayDeque<BabaStreamToken>()
    private val gate = Mutex()

    /**
     * A token for [capBase], solving one now if the shelf is empty.
     *
     * The lock is held across the solve on purpose: two episodes resolving at once should queue for
     * one token rather than start two full challenges and compete for the same cores.
     */
    suspend fun take(capBase: String): String? {
        if (!solvingAllowed) return null
        val token = gate.withLock {
            dropExpired()
            shelf.removeFirstOrNull() ?: solve(capBase)
        }
        if (token != null) warm(capBase)
        return token?.value
    }

    /** Solves a spare token in the background if the shelf has room, and returns immediately. */
    fun warm(capBase: String) {
        if (!solvingAllowed) return
        scope.launch {
            gate.withLock {
                dropExpired()
                while (shelf.size < POOL_TARGET) {
                    val token = solve(capBase) ?: return@withLock
                    shelf.addLast(token)
                }
            }
        }
    }

    private fun dropExpired() {
        val now = System.currentTimeMillis()
        shelf.removeAll { it.expiresAtMs - EXPIRY_MARGIN_MS <= now }
    }

    private suspend fun solve(capBase: String): BabaStreamToken? = try {
        solveChallenge(capBase)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DiagnosticsLog.event("BabaStream cap challenge failed: ${e.javaClass.simpleName}")
        null
    }

    private suspend fun solveChallenge(capBase: String): BabaStreamToken? {
        val challenge = postJson(join(capBase, "challenge"), "{}", capBase) ?: return null
        val token = (challenge["token"] as? JsonPrimitive)?.contentOrNull
        val solutions = solutionsFor(challenge, token) ?: return null

        val redeemed = postJson(
            join(capBase, "redeem"),
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("token", token.orEmpty())
                    put("solutions", solutions)
                },
            ),
            capBase,
        ) ?: return null
        if ((redeemed["success"] as? JsonPrimitive)?.booleanOrNull != true) return null
        val value = (redeemed["token"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        val expires = (redeemed["expires"] as? JsonPrimitive)?.longOrNull
            ?: (System.currentTimeMillis() + DEFAULT_TOKEN_LIFETIME_MS)
        return BabaStreamToken(value, expires)
    }

    /**
     * The solved payload for whichever challenge form the server sent.
     *
     * The counted form — a token plus `c`/`s`/`d`, with both sides deriving salt and target from it
     * — is what BabaStream serves, verified live 2026-09-09. Cap also defines a form that sends the
     * salts and targets outright; that branch is written to spec and has never been exercised here,
     * so it is deliberately the fallback rather than the thing tried first.
     */
    private suspend fun solutionsFor(challenge: JsonObject, token: String?): JsonArray? {
        val counted = challenge["challenge"] as? JsonObject
        if (counted != null && token != null) {
            val count = (counted["c"] as? JsonPrimitive)?.intOrNull ?: return null
            val saltLength = (counted["s"] as? JsonPrimitive)?.intOrNull ?: return null
            val difficulty = (counted["d"] as? JsonPrimitive)?.intOrNull ?: return null
            if (!capChallengeIsAffordable(count, difficulty, budgetDigests)) {
                DiagnosticsLog.event("BabaStream cap challenge refused: c=$count d=$difficulty")
                return null
            }
            val nonces = solveAll(capProofs(token, count, saltLength, difficulty))
            return buildJsonArray { nonces.forEach { add(JsonPrimitive(it)) } }
        }

        val listed = challenge["challenges"] as? JsonArray ?: return null
        val proofs = listed.mapIndexedNotNull { index, element ->
            val payload = (element as? JsonObject)?.get("payload") as? JsonObject ?: return@mapIndexedNotNull null
            val salt = (payload["salt"] as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
            val target = (payload["target"] as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
            CapProof(index, salt, target)
        }
        if (proofs.size != listed.size || proofs.isEmpty()) return null
        if (!capChallengeIsAffordable(proofs.size, proofs.first().target.length, budgetDigests)) {
            DiagnosticsLog.event("BabaStream cap challenge refused: listed=${proofs.size}")
            return null
        }
        val nonces = solveAll(proofs)
        return buildJsonArray {
            nonces.forEach { nonce -> add(buildJsonObject { put("nonce", nonce) }) }
        }
    }

    /**
     * Solves every proof, spread across the CPU.
     *
     * Proofs are independent and take wildly different numbers of attempts, so they are dealt out
     * round-robin rather than sliced into contiguous blocks — a slice that happened to collect the
     * unlucky ones would hold up every other core.
     */
    private suspend fun solveAll(proofs: List<CapProof>): List<Long> = withContext(Dispatchers.Default) {
        val startedAt = System.nanoTime()
        val lanes = proofs.groupBy { it.index % parallelism }.values
        val solved = coroutineScope {
            lanes.map { lane ->
                async {
                    // One digest per lane, reset between attempts: allocating a MessageDigest per
                    // attempt would cost more than the hashing does.
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    lane.map { proof ->
                        // Solving one proof is a tight loop with no suspension point in it, so a
                        // viewer who navigates away mid-solve would otherwise leave every lane
                        // hashing to the end of its list. Checking between proofs bounds the
                        // overrun to the one already in flight.
                        ensureActive()
                        proof.index to solveCapProof(proof, digest)
                    }
                }
            }.awaitAll()
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        DiagnosticsLog.event("BabaStream cap solved proofs=${proofs.size} lanes=$parallelism ms=$elapsedMs")
        solved.flatten().sortedBy { it.first }.map { it.second }
    }

    private fun join(base: String, path: String): String =
        if (base.endsWith("/")) "$base$path" else "$base/$path"

    private suspend fun postJson(url: String, body: String, referer: String): JsonObject? {
        val request = Request.Builder().url(url)
            .header("User-Agent", CAP_USER_AGENT)
            .header("Accept", "application/json,*/*")
            .header("Referer", referer)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val text = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val payload = response.use {
                            if (!it.isSuccessful) throw IOException("HTTP ${it.code} fetching $url")
                            it.body?.string().orEmpty()
                        }
                        continuation.resumeWith(Result.success(payload))
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }
        return runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    }

    private companion object {
        /** One spare. Tokens last two hours, so a deeper shelf only wastes battery. */
        const val POOL_TARGET = 1

        /** A token handed over this close to expiry would likely die in flight. */
        const val EXPIRY_MARGIN_MS = 60_000L
        const val DEFAULT_TOKEN_LIFETIME_MS = 30 * 60 * 1000L

        /**
         * Roughly four times BabaStream's live challenge (5.2 million digests, 2026-09-09), which
         * leaves room for the service to make its challenge meaningfully harder without leaving room
         * for it to ask a phone for minutes of work.
         */
        const val DEFAULT_BUDGET_DIGESTS = 20_000_000L
        const val CAP_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
