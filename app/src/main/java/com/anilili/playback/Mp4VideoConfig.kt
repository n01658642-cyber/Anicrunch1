package com.anilili.playback

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Whether an `avcC` payload actually carries the parameter sets a decoder configures itself from.
 *
 * The payload opens with a version, the profile/compatibility/level triple and the NAL length size,
 * and only then the SPS and PPS the decoder needs. One that stops at the counts still *describes* a
 * codec — and a muxer can synthesise those four bytes from a playlist's declared `CODECS` string
 * without ever having seen the stream — but it hands the decoder nothing to configure itself with.
 * Every frame then arrives against the wrong picture size and profile, which is how a 1080p episode
 * ends up decoding as flat green while its audio and subtitles play on untouched.
 */
internal fun avcConfigHasParameterSets(avcC: ByteArray): Boolean {
    if (avcC.size < MIN_AVC_CONFIG_BYTES) return false
    val spsCount = avcC[5].toInt() and 0x1f
    if (spsCount == 0) return false
    var offset = 6
    repeat(spsCount) {
        val length = parameterSetLength(avcC, offset) ?: return false
        offset += 2 + length
    }
    if (offset >= avcC.size) return false
    val ppsCount = avcC[offset].toInt() and 0xff
    if (ppsCount == 0) return false
    offset++
    repeat(ppsCount) {
        val length = parameterSetLength(avcC, offset) ?: return false
        offset += 2 + length
    }
    return true
}

/** The declared size of the parameter set at [offset], or null when it runs past the payload. */
private fun parameterSetLength(avcC: ByteArray, offset: Int): Int? {
    if (offset + 2 > avcC.size) return null
    val length = ((avcC[offset].toInt() and 0xff) shl 8) or (avcC[offset + 1].toInt() and 0xff)
    return length.takeIf { it > 0 && offset + 2 + it <= avcC.size }
}

private const val MIN_AVC_CONFIG_BYTES = 7

/**
 * The AVC decoder configuration from [file]'s video sample entry, or null when it has none.
 *
 * Deliberately a plain box walk rather than a media extractor: this runs to decide whether a
 * freshly written export can be trusted, so it must not depend on the same stack that wrote it.
 */
internal fun readAvcConfig(file: File): ByteArray? = runCatching {
    RandomAccessFile(file, "r").use { it.channel.findAvcConfig(0L, it.length()) }
}.getOrNull()

/** Inspect the sample entry even when its decoder configuration is missing or unreadable. */
internal fun readMp4VideoCodec(file: File): String? = runCatching {
    RandomAccessFile(file, "r").use {
        it.channel.findAvcConfig(0L, it.length(), codecOnly = true)?.toString(Charsets.US_ASCII)
    }
}.getOrNull()

/** The same check for a file already handed to MediaStore, which the app only holds a [Uri] for. */
internal fun readAvcConfig(context: Context, uri: Uri): ByteArray? = runCatching {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).use { it.channel.findAvcConfig(0L, it.channel.size()) }
    }
}.getOrNull()

private fun FileChannel.readAt(position: Long, count: Int): ByteArray? {
    val buffer = ByteBuffer.allocate(count)
    var offset = position
    while (buffer.hasRemaining()) {
        val read = read(buffer, offset)
        if (read <= 0) return null
        offset += read
    }
    return buffer.array()
}

private fun FileChannel.findAvcConfig(start: Long, end: Long, codecOnly: Boolean = false): ByteArray? {
    var offset = start
    while (offset + BOX_HEADER_BYTES <= end) {
        val header = readAt(offset, BOX_HEADER_BYTES) ?: return null
        var size = header.beInt(0).toLong() and 0xFFFFFFFFL
        var headerBytes = BOX_HEADER_BYTES.toLong()
        when (size) {
            1L -> {
                size = (readAt(offset + BOX_HEADER_BYTES, 8) ?: return null).beLong(0)
                headerBytes = 16L
            }
            0L -> size = end - offset
        }
        if (size < headerBytes || size > end - offset) return null
        val type = String(header, 4, 4, Charsets.US_ASCII)
        if (codecOnly && type in KNOWN_VIDEO_SAMPLE_ENTRIES) return type.toByteArray(Charsets.US_ASCII)
        if (!codecOnly && type == "avcC") {
            if (size - headerBytes > 1024 * 1024) return null
            return readAt(offset + headerBytes, (size - headerBytes).toInt())
        }
        // Sample entries and the table that holds them carry fixed fields before their children.
        val childOffset = when (type) {
            in CONTAINER_BOXES -> headerBytes
            "stsd" -> headerBytes + STSD_PREAMBLE_BYTES
            in VIDEO_SAMPLE_ENTRIES -> headerBytes + VISUAL_SAMPLE_ENTRY_BYTES
            else -> null
        }
        if (childOffset != null) {
            findAvcConfig(offset + childOffset, offset + size, codecOnly)?.let { return it }
        }
        offset += size
    }
    return null
}

private fun ByteArray.beInt(at: Int): Int =
    (this[at].toInt() and 0xff shl 24) or (this[at + 1].toInt() and 0xff shl 16) or
        (this[at + 2].toInt() and 0xff shl 8) or (this[at + 3].toInt() and 0xff)

private fun ByteArray.beLong(at: Int): Long =
    (beInt(at).toLong() and 0xFFFFFFFFL shl 32) or (beInt(at + 4).toLong() and 0xFFFFFFFFL)

/** Builds the `avcC` payload a decoder configures itself from, given the stream's own parameter sets. */
internal fun buildAvcConfig(sps: ByteArray, pps: ByteArray): ByteArray {
    require(sps.size >= 4) { "An SPS shorter than its profile/level header cannot be described" }
    return byteArrayOf(1, sps[1], sps[2], sps[3], 0xFF.toByte(), 0xE1.toByte()) +
        byteArrayOf((sps.size shr 8).toByte(), sps.size.toByte()) + sps +
        byteArrayOf(1, (pps.size shr 8).toByte(), pps.size.toByte()) + pps
}

/**
 * Replaces the video sample entry's `avcC` with [config].
 *
 * The new record is longer than the stub it replaces, so every box containing it grows with it.
 * That is only safe while `moov` is the last box in the file — which is what this rewrap produces —
 * because then nothing after it moves and the chunk offsets in `stco`/`co64`, which point into the
 * `mdat` before it, stay true. A file laid out the other way round is refused rather than corrupted.
 */
internal fun writeAvcConfig(file: File, config: ByteArray): Boolean = runCatching {
    RandomAccessFile(file, "rw").use { raf ->
        val channel = raf.channel
        val moov = topLevelBox(channel, "moov") ?: return@use false
        if (moov.first + moov.second != channel.size()) return@use false
        val bytes = ByteArray(moov.second.toInt())
        channel.readAt(moov.first, bytes) ?: return@use false
        val ancestors = mutableListOf<Pair<Int, Long>>()
        val avcC = locateAvcConfig(bytes, 8, bytes.size, ancestors) ?: return@use false
        val size = config.size + 8
        val box = byteArrayOf(
            (size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte(),
            'a'.code.toByte(), 'v'.code.toByte(), 'c'.code.toByte(), 'C'.code.toByte(),
        ) + config
        val delta = box.size - avcC.second.toInt()
        val patched = bytes.copyOfRange(0, avcC.first) + box +
            bytes.copyOfRange(avcC.first + avcC.second.toInt(), bytes.size)
        // moov itself grows too, and it is the outermost of the boxes that contain the record.
        (ancestors + (0 to moov.second)).forEach { (offset, boxSize) ->
            val grown = boxSize + delta
            patched[offset] = (grown shr 24).toByte()
            patched[offset + 1] = (grown shr 16).toByte()
            patched[offset + 2] = (grown shr 8).toByte()
            patched[offset + 3] = grown.toByte()
        }
        raf.seek(moov.first)
        raf.write(patched)
        raf.setLength(moov.first + patched.size)
        true
    }
}.getOrDefault(false)

private fun topLevelBox(channel: FileChannel, type: String): Pair<Long, Long>? {
    var offset = 0L
    val header = ByteArray(16)
    while (offset + 8 <= channel.size()) {
        if (channel.readAt(offset, header) == null) return null
        var size = header.beInt(0).toLong() and 0xFFFFFFFFL
        if (size == 1L) size = header.beLong(8)
        if (size < 8) return null
        if (String(header, 4, 4, Charsets.US_ASCII) == type) return offset to size
        offset += size
    }
    return null
}

/** Finds the `avcC` inside a `moov` already in memory, recording every box that contains it. */
private fun locateAvcConfig(
    buf: ByteArray,
    start: Int,
    end: Int,
    ancestors: MutableList<Pair<Int, Long>>,
): Pair<Int, Long>? {
    var offset = start
    while (offset + 8 <= end) {
        var size = buf.beInt(offset).toLong() and 0xFFFFFFFFL
        var headerBytes = 8
        if (size == 1L) { size = buf.beLong(offset + 8); headerBytes = 16 }
        if (size < headerBytes || offset + size > end) return null
        val type = String(buf, offset + 4, 4, Charsets.US_ASCII)
        if (type == "avcC") return offset to size
        val childOffset = when (type) {
            in CONTAINER_BOXES -> headerBytes
            "stsd" -> headerBytes + STSD_PREAMBLE_BYTES.toInt()
            in VIDEO_SAMPLE_ENTRIES -> headerBytes + VISUAL_SAMPLE_ENTRY_BYTES.toInt()
            else -> null
        }
        if (childOffset != null) {
            ancestors.add(offset to size)
            locateAvcConfig(buf, offset + childOffset, (offset + size).toInt(), ancestors)
                ?.let { return it }
            ancestors.removeAt(ancestors.lastIndex)
        }
        offset += size.toInt()
    }
    return null
}

/**
 * NAL type for filler data, which every decoder is required to skip.
 *
 * Retyping a parameter set to filler is how a truncated one is taken out of play without moving a
 * single byte: the unit keeps its length, so no sample size, chunk offset or box size has to be
 * rewritten, and the decoder falls back to the configuration in `avcC`.
 */
private const val NAL_TYPE_FILLER = 0x0C

private const val NAL_TYPE_PPS = 8

/**
 * Repairs an export whose picture parameter sets were written a byte short.
 *
 * Media3 1.8.1's rewrap truncates every in-band PPS by one byte and writes an `avcC` carrying no
 * parameter sets at all. The missing byte holds the deblocking and constrained-intra flags, so the
 * result decodes as flat green from the first macroblock — see [avcConfigHasParameterSets].
 *
 * Both halves are fixed here: [pps] (read from the source, which still has it whole) replaces the
 * configuration record, and every truncated in-band copy is retyped to filler so it cannot override
 * that record. Returns the number of units neutered, or null when the file could not be rewritten.
 */
internal fun repairTruncatedPictureParameterSets(file: File, sps: ByteArray, pps: ByteArray): Int? =
    runCatching {
        RandomAccessFile(file, "rw").use { raf ->
            val channel = raf.channel
            val truncated = pps.copyOf(pps.size - 1)
            // The unit as the rewrap wrote it: a four-byte length, then the short parameter set.
            val needle = ByteArray(4).also {
                it[3] = truncated.size.toByte()
                it[2] = (truncated.size shr 8).toByte()
            } + truncated
            var neutered = 0
            var position = 0L
            val window = ByteArray(1 shl 20)
            while (position < channel.size()) {
                val read = channel.readAt(position, window) ?: break
                var index = 0
                while (index + needle.size <= read) {
                    if (window.matchesAt(index, needle)) {
                        val headerAt = position + index + 4
                        raf.seek(headerAt)
                        val header = raf.read()
                        if (header and 0x1f == NAL_TYPE_PPS) {
                            raf.seek(headerAt)
                            // Written whole rather than keeping the old nal_ref_idc: filler data is
                            // non-reference by definition, and this exact byte is the one verified
                            // to decode cleanly against a real export.
                            raf.write(NAL_TYPE_FILLER)
                            neutered++
                        }
                        index += needle.size
                    } else {
                        index++
                    }
                }
                // Overlap by one unit so a match straddling two windows is not missed.
                position += (read - needle.size).coerceAtLeast(1)
            }
            neutered
        }
    }.getOrNull()

private fun java.nio.channels.FileChannel.readAt(position: Long, into: ByteArray): Int? {
    val buffer = ByteBuffer.wrap(into)
    var offset = position
    while (buffer.hasRemaining()) {
        val read = read(buffer, offset)
        if (read <= 0) break
        offset += read
    }
    return buffer.position().takeIf { it > 0 }
}

private fun ByteArray.matchesAt(index: Int, needle: ByteArray): Boolean {
    for (i in needle.indices) if (this[index + i] != needle[i]) return false
    return true
}

private val CONTAINER_BOXES = setOf("moov", "trak", "mdia", "minf", "stbl")
private val VIDEO_SAMPLE_ENTRIES = setOf("avc1", "avc3")
private val KNOWN_VIDEO_SAMPLE_ENTRIES = VIDEO_SAMPLE_ENTRIES + setOf("hvc1", "hev1", "av01", "vp09", "mp4v")
private const val BOX_HEADER_BYTES = 8
private const val STSD_PREAMBLE_BYTES = 8L
private const val VISUAL_SAMPLE_ENTRY_BYTES = 78L
