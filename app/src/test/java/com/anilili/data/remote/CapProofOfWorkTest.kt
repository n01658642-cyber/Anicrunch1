package com.anilili.data.remote

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expected values here were produced by running Cap's own JavaScript derivation against a fixed
 * token, so this pins the Kotlin port to the algorithm the server checks against rather than to
 * itself. A drift in [capSeededHex] would not fail on its own — it would derive perfectly
 * self-consistent salts that BabaStream rejects after the work has already been done.
 */
class CapProofOfWorkTest {
    private val token = "anilili-test-token"

    @Test
    fun `salt derivation matches Cap's own`() {
        assertEquals("3976fa0320ebca186cb43f4c80e496a2", capSeededHex("${token}1", 32))
        assertEquals("1a7383dc2b0eedd8200746c2d333370d", capSeededHex("${token}7", 32))
    }

    @Test
    fun `target derivation matches Cap's own`() {
        assertEquals("5871", capSeededHex("${token}1d", 4))
        assertEquals("bdb0", capSeededHex("${token}7d", 4))
    }

    /** The seed runs before any character is consumed, so an empty value still yields a stream. */
    @Test
    fun `edge-case inputs match Cap's own`() {
        assertEquals("4622a677", capSeededHex("", 8))
        assertEquals("441aaeb82879", capSeededHex("a", 12))
    }

    /** Cap indexes by UTF-16 code unit; walking encoded bytes instead would derive a different salt. */
    @Test
    fun `non-latin input is indexed the way Cap indexes it`() {
        assertEquals("cd230cb0", capSeededHex("日本", 8))
    }

    @Test
    fun `output is exactly as long as asked, across block boundaries`() {
        listOf(1, 4, 7, 8, 9, 32, 33).forEach { length ->
            assertEquals(length, capSeededHex(token, length).length)
        }
    }

    @Test
    fun `proofs are derived from a one-based counter`() {
        val proofs = capProofs(token, count = 7, saltLength = 32, difficulty = 4)
        assertEquals(7, proofs.size)
        // Counter 1 is the first proof, not counter 0 — off by one here is silently rejected.
        assertEquals("3976fa0320ebca186cb43f4c80e496a2", proofs.first().salt)
        assertEquals("5871", proofs.first().target)
        assertEquals("1a7383dc2b0eedd8200746c2d333370d", proofs.last().salt)
        assertEquals("bdb0", proofs.last().target)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), proofs.map { it.index })
    }

    @Test
    fun `solving finds the counter Cap's own solver finds`() {
        val proofs = capProofs(token, count = 7, saltLength = 32, difficulty = 4)
        assertEquals(13477L, solveCapProof(proofs.first()))
        assertEquals(6438L, solveCapProof(proofs.last()))
    }

    /**
     * `d` is a count of hex characters, not bytes, so an odd one leaves half a byte to compare.
     * Not seen live — BabaStream sends 4 — but getting it wrong would only show up as a challenge
     * that never verifies after several seconds of work.
     */
    @Test
    fun `an odd-length target compares the trailing nibble`() {
        val salt = capSeededHex("${token}3", 32)
        assertEquals(8807L, solveCapProof(CapProof(0, salt, capSeededHex("${token}3d", 3))))
    }

    @Test
    fun `a solved counter really does produce the target digest`() {
        val proof = capProofs(token, count = 1, saltLength = 32, difficulty = 4).single()
        val nonce = solveCapProof(proof)
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("${proof.salt}$nonce".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertTrue("$hash should start with ${proof.target}", hash.startsWith(proof.target))
    }

    /**
     * The guard that decides whether a challenge is worth starting. BabaStream's live parameters
     * were `c=80, d=4` on 2026-09-09 — 5.2 million digests — and a server that raised `d` to 6
     * would ask for 256 times that with no visible change to the payload.
     */
    @Test
    fun `the work a challenge asks for is measured before it is accepted`() {
        assertEquals(5_242_880L, CapProofOfWork.expectedDigests(count = 80, difficulty = 4))
        assertEquals(1_342_177_280L, CapProofOfWork.expectedDigests(count = 80, difficulty = 6))
    }
}
