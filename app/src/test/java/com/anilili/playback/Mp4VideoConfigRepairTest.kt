package com.anilili.playback

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the repair against a file shaped like the one Media3 1.8.1 actually produces: a stub
 * `avcC` describing nothing, and in-band picture parameter sets each a byte short.
 */
class Mp4VideoConfigRepairTest {

    @get:Rule val folder = TemporaryFolder()

    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x2a) + ByteArray(26) { 0x11 }
    private val pps = byteArrayOf(0x68.toByte(), 0xef.toByte(), 0x84.toByte(), 0xf2.toByte(), 0xc0.toByte())
    private val truncatedPps = pps.copyOf(pps.size - 1)

    @Test
    fun `the configuration record is built from the streams own parameter sets`() {
        val config = buildAvcConfig(sps, pps)
        assertTrue(avcConfigHasParameterSets(config))
        // version, then the profile/compatibility/level lifted straight off the SPS.
        assertArrayEquals(byteArrayOf(1, 0x64, 0x00, 0x2a), config.copyOfRange(0, 4))
        assertEquals(1, config[5].toInt() and 0x1f)
        assertEquals(sps.size, ((config[6].toInt() and 0xff) shl 8) or (config[7].toInt() and 0xff))
    }

    @Test
    fun `a stub configuration is replaced and the file still parses`() {
        val file = brokenExport()
        assertFalse(avcConfigHasParameterSets(readAvcConfig(file)!!))

        assertTrue(writeAvcConfig(file, buildAvcConfig(sps, pps)))

        val written = readAvcConfig(file)
        assertNotNull(written)
        assertTrue(avcConfigHasParameterSets(written!!))
        assertArrayEquals(buildAvcConfig(sps, pps), written)
    }

    @Test
    fun `truncated parameter sets are retyped to filler without moving a byte`() {
        val file = brokenExport()
        val before = file.length()

        val neutered = repairTruncatedPictureParameterSets(file, sps, pps)

        assertEquals(2, neutered)
        assertEquals("the repair must not resize the file", before, file.length())
        val bytes = file.readBytes()
        assertFalse("no PPS unit may survive", bytes.containsNal(0x68.toByte()))
        assertTrue("each one becomes filler data", bytes.containsNal(0x0c))
    }

    @Test
    fun `a healthy export is left alone`() {
        val file = brokenExport(withTruncatedPps = false)
        assertEquals(0, repairTruncatedPictureParameterSets(file, sps, pps))
    }

    /** Growing `moov` is only safe when it is last; anything else would strand the chunk offsets. */
    @Test
    fun `a file whose moov is not last is refused rather than corrupted`() {
        val file = brokenExport(moovLast = false)
        val before = file.readBytes()
        assertFalse(writeAvcConfig(file, buildAvcConfig(sps, pps)))
        assertArrayEquals("the file must be untouched", before, file.readBytes())
    }

    private fun ByteArray.containsNal(header: Byte): Boolean {
        val unit = byteArrayOf(0, 0, 0, truncatedPps.size.toByte(), header) +
            truncatedPps.copyOfRange(1, truncatedPps.size)
        outer@ for (i in 0..size - unit.size) {
            for (j in unit.indices) if (this[i + j] != unit[j]) continue@outer
            return true
        }
        return false
    }

    /** An MP4 with the structure the repair walks, and the defects it is meant to undo. */
    private fun brokenExport(withTruncatedPps: Boolean = true, moovLast: Boolean = true): File {
        val parameterSet = if (withTruncatedPps) truncatedPps else pps
        val sample = lengthPrefixed(byteArrayOf(0x09, 0xf0.toByte())) +
            lengthPrefixed(sps) + lengthPrefixed(parameterSet) +
            lengthPrefixed(byteArrayOf(0x65, 0x88.toByte()) + ByteArray(40) { 0x22 })
        val mdat = box("mdat", sample + sample)
        // The seven-byte stub: a codec described from a playlist string, with no parameter sets.
        val stub = box("avcC", byteArrayOf(1, 0x64, 0x00, 0x0d, 0xff.toByte(), 0xe0.toByte(), 0x00))
        val avc1 = box("avc1", ByteArray(78) + stub)
        val stsd = box("stsd", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1) + avc1)
        val moov = box("moov", box("trak", box("mdia", box("minf", box("stbl", stsd)))))
        val ftyp = box("ftyp", "isom".toByteArray() + ByteArray(8))
        val file = folder.newFile("export-${System.nanoTime()}.mp4")
        file.writeBytes(if (moovLast) ftyp + mdat + moov else ftyp + moov + mdat)
        return file
    }

    private fun lengthPrefixed(nal: ByteArray): ByteArray =
        byteArrayOf(0, 0, 0, nal.size.toByte()) + nal

    private fun box(type: String, body: ByteArray): ByteArray {
        val size = body.size + 8
        return byteArrayOf(
            (size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte(),
        ) + type.toByteArray(Charsets.US_ASCII) + body
    }
}
