package com.anilili.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4VideoConfigTest {
    @Test
    fun `empty parameter sets cannot configure a video`() {
        assertFalse(avcConfigHasParameterSets(avcConfig(ByteArray(0), byteArrayOf(8))))
        assertFalse(avcConfigHasParameterSets(avcConfig(byteArrayOf(7), ByteArray(0))))
    }

    @Test
    fun `all declared PPS entries must be present`() {
        val config = avcConfig(byteArrayOf(7), byteArrayOf(8))
        config[9] = 2 // two PPS entries declared, only one encoded
        assertFalse(avcConfigHasParameterSets(config))
    }

    @Test
    fun `a configuration carrying one SPS and one PPS is usable`() {
        assertTrue(avcConfigHasParameterSets(avcConfig(sps = ByteArray(29) { 1 }, pps = ByteArray(3) { 2 })))
    }

    /** The shape a rewrap produced from a playlist's declared codec string: described, not configured. */
    @Test
    fun `a configuration with no parameter sets is rejected`() {
        val stub = byteArrayOf(0x01, 0x64, 0x00, 0x0d, 0xff.toByte(), 0xe0.toByte(), 0x00)
        assertFalse(avcConfigHasParameterSets(stub))
    }

    @Test
    fun `an SPS with no PPS after it is rejected`() {
        val spsOnly = byteArrayOf(0x01, 0x4d, 0x4c, 0x28, 0xff.toByte(), 0xe1.toByte(), 0x00, 0x02, 0x67, 0x64) +
            byteArrayOf(0x00) // pps count of zero
        assertFalse(avcConfigHasParameterSets(spsOnly))
    }

    @Test
    fun `a payload truncated mid parameter set is rejected rather than trusted`() {
        // Declares a 29-byte SPS but only supplies four bytes of it.
        val truncated = byteArrayOf(0x01, 0x4d, 0x4c, 0x28, 0xff.toByte(), 0xe1.toByte(), 0x00, 0x1d, 0x67, 0x64, 0x00, 0x28)
        assertFalse(avcConfigHasParameterSets(truncated))
    }

    @Test
    fun `a payload too short to hold the counts is rejected`() {
        assertFalse(avcConfigHasParameterSets(byteArrayOf(0x01, 0x64, 0x00, 0x0d)))
        assertFalse(avcConfigHasParameterSets(ByteArray(0)))
    }

    @Test
    fun `several SPS entries are walked before the PPS count is read`() {
        val two = byteArrayOf(0x01, 0x4d, 0x4c, 0x28, 0xff.toByte(), 0xe2.toByte()) +
            parameterSet(ByteArray(10) { 7 }) +
            parameterSet(ByteArray(12) { 7 }) +
            byteArrayOf(0x01) + parameterSet(ByteArray(4) { 8 })
        assertTrue(avcConfigHasParameterSets(two))
    }

    private fun avcConfig(sps: ByteArray, pps: ByteArray): ByteArray =
        byteArrayOf(0x01, 0x4d, 0x4c, 0x28, 0xff.toByte(), 0xe1.toByte()) +
            parameterSet(sps) +
            byteArrayOf(0x01) +
            parameterSet(pps)

    private fun parameterSet(body: ByteArray): ByteArray =
        byteArrayOf((body.size shr 8).toByte(), body.size.toByte()) + body
}
