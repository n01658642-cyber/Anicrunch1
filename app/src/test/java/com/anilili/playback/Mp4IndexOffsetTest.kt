package com.anilili.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Mp4IndexOffsetTest {

    /** `[size:uint32][type]` — the eight bytes a top-level box announces itself with. */
    private fun box(type: String, size: Long): ByteArray {
        val header = ByteArray(8)
        for (offset in 0 until 4) header[3 - offset] = ((size shr (offset * 8)) and 0xFF).toByte()
        type.forEachIndexed { index, char -> header[4 + index] = char.code.toByte() }
        return header
    }

    /** The 64-bit form: a size of 1 in the small field, the real size in the eight bytes after. */
    private fun largeBox(type: String, size: Long): ByteArray {
        val header = ByteArray(16)
        header[3] = 1
        type.forEachIndexed { index, char -> header[4 + index] = char.code.toByte() }
        for (offset in 0 until 8) header[15 - offset] = ((size shr (offset * 8)) and 0xFF).toByte()
        return header
    }

    /**
     * The real layout of a Lili episode, read off `play.xfvod.pro` on 2026-09-15:
     * `ftyp@0(32) free@32(8) mdat@40(571763357) moov@571763397(738576)`.
     */
    @Test
    fun `finds the index a non-faststart file keeps at the end`() {
        val head = box("ftyp", 32) + ByteArray(24) + box("free", 8) + box("mdat", 571_763_357)
        assertEquals(571_763_397L, mp4IndexOffset(head, 572_501_973L))
    }

    @Test
    fun `finds an index the muxer put at the front`() {
        val head = box("ftyp", 32) + ByteArray(24) + box("moov", 4_096)
        assertEquals(32L, mp4IndexOffset(head, 10_000_000L))
    }

    @Test
    fun `steps over a media box that needs the 64-bit size form`() {
        val head = box("ftyp", 16) + ByteArray(8) + largeBox("mdat", 5_000_000_000L)
        assertEquals(5_000_000_016L, mp4IndexOffset(head, 5_000_100_000L))
    }

    @Test
    fun `declines a box that would run past the end of the file`() {
        val head = box("ftyp", 32) + ByteArray(24) + box("mdat", 900_000_000)
        assertNull(mp4IndexOffset(head, 572_501_973L))
    }

    /** Size zero means "to the end", so nothing follows it and there is no index to warm. */
    @Test
    fun `declines a box that claims the rest of the file`() {
        val head = box("ftyp", 32) + ByteArray(24) + box("mdat", 0)
        assertNull(mp4IndexOffset(head, 572_501_973L))
    }

    /** A box too small to hold its own header would loop forever if it were believed. */
    @Test
    fun `declines an impossibly small box`() {
        assertNull(mp4IndexOffset(box("ftyp", 4) + ByteArray(32), 572_501_973L))
    }

    @Test
    fun `declines bytes that are not an mp4 at all`() {
        assertNull(mp4IndexOffset(ByteArray(64), 572_501_973L))
    }

    /** Nothing after the media data: the walk lands on the end of the file, so there is no index. */
    @Test
    fun `declines a file whose media data runs to the very end`() {
        val head = box("ftyp", 32) + ByteArray(24) + box("mdat", 571_763_357)
        assertNull(mp4IndexOffset(head, 32L + 571_763_357L))
    }

    /** Past a point the thing at the end is not a sample table, and fetching it costs more than it saves. */
    @Test
    fun `declines a trailing box too large to be an index`() {
        val head = box("ftyp", 32) + ByteArray(24) + box("mdat", 100_000_000)
        assertNull(mp4IndexOffset(head, 100_000_032L + 64L * 1024 * 1024))
    }
}
