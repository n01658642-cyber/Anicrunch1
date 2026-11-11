package com.anilili.data.remote

import java.security.MessageDigest

/**
 * The client half of a [Cap](https://capjs.js.org) proof-of-work challenge, as BabaStream uses it.
 *
 * A challenge is a token plus three numbers: how many proofs to produce (`c`), how long each salt
 * is (`s`), and how many leading hex characters of the SHA-256 digest have to match (`d`). Salt and
 * target are not sent — both sides derive them from the token with [capSeededHex], so the wire
 * payload stays tiny and the work does not. Solving one proof means finding a counter whose
 * `sha256(salt + counter)` starts with the target.
 *
 * The cost is deliberate and it is the whole reason this file exists rather than a one-line fetch.
 * BabaStream's live parameters on 2026-09-09 were `c=80, s=32, d=4`: eighty proofs of roughly 65,536
 * digests each, about 5.1 million SHA-256 operations for one episode. That is a few seconds of a
 * phone's whole CPU, so it must never be started because someone pressed play — see
 * [BabaStreamTokenPool], which pays it in the background and hands over a finished token.
 */
object CapProofOfWork {
    /**
     * The digest work one challenge asks for, used to decide whether to accept it at all.
     *
     * Multiplying out rather than trusting the shape: a server that raised `d` by one would ask for
     * sixteen times the work with no visible change to the payload.
     */
    fun expectedDigests(count: Int, difficulty: Int): Long =
        count.toLong() * Math.pow(16.0, difficulty.toDouble()).toLong()
}

/**
 * Cap's own string derivation: an FNV-1a-flavoured mix of [value] seeding an xorshift32 stream,
 * emitted as [length] hex characters.
 *
 * Ported from the widget's JavaScript, where the accumulator is a Number that is only forced back
 * to 32 bits by the next `^=`. Every operation involved agrees modulo 2^32, so unsigned arithmetic
 * reproduces it exactly; the intermediate sums stay well inside a double's integer range, so
 * nothing was being lost there either.
 *
 * Cap indexes the string by UTF-16 code unit, so this walks `Char`s rather than encoded bytes — a
 * non-Latin token would otherwise derive a different salt here than on the server.
 */
internal fun capSeededHex(value: String, length: Int): String {
    var state = 2166136261u
    for (char in value) {
        state = state xor char.code.toUInt()
        state += (state shl 1) + (state shl 4) + (state shl 7) + (state shl 8) + (state shl 24)
    }
    val out = StringBuilder(length + 8)
    while (out.length < length) {
        state = state xor (state shl 13)
        state = state xor (state shr 17)
        state = state xor (state shl 5)
        out.append(state.toString(16).padStart(8, '0'))
    }
    return out.substring(0, length)
}

/** One proof to solve: the [salt] to hash under and the hex prefix the digest must start with. */
internal data class CapProof(val index: Int, val salt: String, val target: String)

/**
 * The proofs a challenge asks for, derived from its token exactly as the widget derives them.
 *
 * The counter is one-based; deriving it from zero produces perfectly valid proofs that the server
 * rejects, which is an expensive way to be wrong.
 */
internal fun capProofs(token: String, count: Int, saltLength: Int, difficulty: Int): List<CapProof> =
    (1..count).map { counter ->
        CapProof(
            index = counter - 1,
            salt = capSeededHex("$token$counter", saltLength),
            target = capSeededHex("$token${counter}d", difficulty),
        )
    }

/**
 * The counter whose `sha256(salt + counter)` digest starts with [CapProof.target].
 *
 * The digest is compared as bytes rather than hex-encoded per attempt. At ~65,000 attempts a proof
 * and eighty proofs a challenge, formatting each digest into a `String` to call `startsWith` on it
 * would allocate five million short-lived strings per episode — enough garbage to be felt on the
 * devices this most needs to be cheap on.
 */
internal fun solveCapProof(proof: CapProof, digest: MessageDigest = MessageDigest.getInstance("SHA-256")): Long {
    val wholeBytes = proof.target.length / 2
    val expected = ByteArray(wholeBytes) {
        proof.target.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
    // An odd-length target ends on a nibble, which is half of the byte that follows the whole ones.
    val hasHalfByte = proof.target.length % 2 == 1
    val halfByte = if (hasHalfByte) proof.target.last().digitToInt(16) else 0

    var counter = 0L
    while (true) {
        digest.reset()
        val hash = digest.digest("${proof.salt}$counter".toByteArray(Charsets.UTF_8))
        var matches = true
        for (index in 0 until wholeBytes) {
            if (hash[index] != expected[index]) {
                matches = false
                break
            }
        }
        if (matches && hasHalfByte) {
            matches = (hash[wholeBytes].toInt() ushr 4 and 0xF) == halfByte
        }
        if (matches) return counter
        counter++
    }
}
