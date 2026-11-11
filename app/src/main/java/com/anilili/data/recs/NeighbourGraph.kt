package com.anilili.data.recs

import java.io.InputStream

/**
 * Every title's nearest neighbours — "people who liked this also liked" — as published by
 * `scripts/recs_build.py` at `data/recs/neighbours-v1.tsv.gz`.
 *
 * The heavy part of recommending happens once a day on the build machine: it blends AniList's
 * community recommendation votes with tag similarity into a short weighted list per title. The
 * device only adds those lists up against what the viewer has watched, which is milliseconds and
 * needs no account, no server and no upload of anyone's list.
 *
 * The format is line-based text rather than JSON on purpose. The file covers the whole catalogue
 * (~20,000 titles), and a JSON tree of that size is a multi-second parse on the television boxes
 * this app runs on; scanning digits out of a byte array is not.
 *
 * ```
 * #anilili-neighbours<TAB>1<TAB><generated>
 * <id><TAB><franchise root><TAB><flags><TAB><id>:<weight>,<id>:<weight>,…
 * ```
 *
 * Weights are 1..100, best first. The root is the first entry of the title's prequel/sequel chain,
 * so "watched season 2" can rule out recommending season 1. Flags are [FLAG_ADULT] and
 * [FLAG_UNRELEASED].
 *
 * Held as parallel primitive arrays (CSR) rather than maps of lists: ~600,000 edges as boxed
 * objects would cost tens of megabytes of heap on a device with a 192 MB ceiling.
 */
class NeighbourGraph private constructor(
    private val ids: IntArray,
    private val roots: IntArray,
    private val flags: ByteArray,
    private val offsets: IntArray,
    private val neighbourIds: IntArray,
    private val weights: ByteArray,
    /** When the build wrote the file, as it wrote it; for diagnostics. */
    val generated: String?,
) {
    /** Titles in the graph. */
    val size: Int get() = ids.size

    /** Edges in the graph. */
    val edgeCount: Int get() = neighbourIds.size

    private fun indexOf(id: Int): Int = ids.binarySearch(id)

    /** The first entry of [id]'s franchise, or [id] itself when the graph does not know it. */
    fun rootOf(id: Int): Int {
        val index = indexOf(id)
        return if (index >= 0) roots[index] else id
    }

    fun isAdult(id: Int): Boolean = hasFlag(id, FLAG_ADULT)

    fun isUnreleased(id: Int): Boolean = hasFlag(id, FLAG_UNRELEASED)

    private fun hasFlag(id: Int, flag: Int): Boolean {
        val index = indexOf(id)
        return index >= 0 && flags[index].toInt() and flag != 0
    }

    /** Calls [action] with each neighbour of [id] and its weight (1..100), best first. */
    inline fun forEachNeighbour(id: Int, action: (neighbour: Int, weight: Int) -> Unit) {
        val range = neighbourRange(id) ?: return
        for (edge in range) action(neighbourAt(edge), weightAt(edge))
    }

    /** The neighbours of [id], best first, as (id, weight) pairs. */
    fun neighbours(id: Int): List<Pair<Int, Int>> = buildList {
        forEachNeighbour(id) { neighbour, weight -> add(neighbour to weight) }
    }

    @PublishedApi
    internal fun neighbourRange(id: Int): IntRange? {
        val index = indexOf(id)
        if (index < 0) return null
        return offsets[index] until offsets[index + 1]
    }

    @PublishedApi
    internal fun neighbourAt(edge: Int): Int = neighbourIds[edge]

    @PublishedApi
    internal fun weightAt(edge: Int): Int = weights[edge].toInt() and 0xFF

    companion object {
        const val FLAG_ADULT = 1
        const val FLAG_UNRELEASED = 2
        const val HEADER = "#anilili-neighbours"
        const val FORMAT_VERSION = 1

        /**
         * Parses the (already decompressed) file. Throws on a header it does not recognise — a
         * mis-served body, or a future format this build cannot read — so a caller keeps the copy
         * it had rather than replacing it with an empty graph.
         *
         * Malformed lines are skipped, not fatal: one bad row should cost one title.
         */
        fun parse(input: InputStream): NeighbourGraph = parse(input.readBytes())

        fun parse(bytes: ByteArray): NeighbourGraph {
            val reader = Scanner(bytes)
            val header = reader.line()
            val fields = header.split('\t')
            require(fields.firstOrNull() == HEADER) { "Not a neighbour file" }
            val version = fields.getOrNull(1)?.toIntOrNull()
            require(version == FORMAT_VERSION) { "Unsupported neighbour file version $version" }
            val generated = fields.getOrNull(2)

            var ids = IntArray(INITIAL_TITLES)
            var roots = IntArray(INITIAL_TITLES)
            var flags = ByteArray(INITIAL_TITLES)
            var offsets = IntArray(INITIAL_TITLES + 1)
            var neighbourIds = IntArray(INITIAL_EDGES)
            var weights = ByteArray(INITIAL_EDGES)
            var count = 0
            var edges = 0
            var sorted = true

            while (reader.hasMore()) {
                val lineStart = edges
                val id = reader.int()
                if (id == null || !reader.skip('\t')) {
                    reader.skipLine()
                    continue
                }
                val root = reader.int() ?: id
                reader.skip('\t')
                val flag = reader.int() ?: 0
                reader.skip('\t')
                while (true) {
                    val neighbour = reader.int() ?: break
                    if (!reader.skip(':')) break
                    val weight = reader.int() ?: break
                    if (edges == neighbourIds.size) {
                        neighbourIds = neighbourIds.copyOf(edges * 2)
                        weights = weights.copyOf(edges * 2)
                    }
                    neighbourIds[edges] = neighbour
                    weights[edges] = weight.coerceIn(1, 100).toByte()
                    edges++
                    if (!reader.skip(',')) break
                }
                reader.skipLine()
                if (count == ids.size) {
                    ids = ids.copyOf(count * 2)
                    roots = roots.copyOf(count * 2)
                    flags = flags.copyOf(count * 2)
                    offsets = offsets.copyOf(count * 2 + 1)
                }
                if (count > 0 && ids[count - 1] >= id) sorted = false
                ids[count] = id
                roots[count] = root
                flags[count] = flag.toByte()
                offsets[count] = lineStart
                count++
            }
            offsets[count] = edges

            if (!sorted) {
                return sortedCopy(count, ids, roots, flags, offsets, neighbourIds, weights, generated)
            }
            return NeighbourGraph(
                ids = ids.copyOf(count),
                roots = roots.copyOf(count),
                flags = flags.copyOf(count),
                offsets = offsets.copyOf(count + 1),
                neighbourIds = neighbourIds.copyOf(edges),
                weights = weights.copyOf(edges),
                generated = generated,
            )
        }

        /**
         * The build writes ids in order, so this is the rare path: a hand-edited or future file.
         * A repeated id keeps its first row — binary search needs one row per id.
         */
        private fun sortedCopy(
            count: Int,
            ids: IntArray,
            roots: IntArray,
            flags: ByteArray,
            offsets: IntArray,
            neighbourIds: IntArray,
            weights: ByteArray,
            generated: String?,
        ): NeighbourGraph {
            val order = (0 until count).sortedBy { ids[it] }.distinctBy { ids[it] }
            val total = order.sumOf { offsets[it + 1] - offsets[it] }
            val newIds = IntArray(order.size)
            val newRoots = IntArray(order.size)
            val newFlags = ByteArray(order.size)
            val newOffsets = IntArray(order.size + 1)
            val newNeighbours = IntArray(total)
            val newWeights = ByteArray(total)
            var cursor = 0
            order.forEachIndexed { slot, old ->
                newIds[slot] = ids[old]
                newRoots[slot] = roots[old]
                newFlags[slot] = flags[old]
                newOffsets[slot] = cursor
                for (edge in offsets[old] until offsets[old + 1]) {
                    newNeighbours[cursor] = neighbourIds[edge]
                    newWeights[cursor] = weights[edge]
                    cursor++
                }
            }
            newOffsets[order.size] = cursor
            return NeighbourGraph(newIds, newRoots, newFlags, newOffsets, newNeighbours, newWeights, generated)
        }

        private const val INITIAL_TITLES = 1 shl 15
        private const val INITIAL_EDGES = 1 shl 19
    }

    /** A cursor over ASCII bytes that reads unsigned integers without allocating. */
    private class Scanner(private val bytes: ByteArray) {
        private var pos = 0

        fun hasMore(): Boolean {
            while (pos < bytes.size && (bytes[pos] == '\n'.code.toByte() || bytes[pos] == '\r'.code.toByte())) pos++
            return pos < bytes.size
        }

        fun line(): String {
            val start = pos
            while (pos < bytes.size && bytes[pos] != '\n'.code.toByte()) pos++
            val text = String(bytes, start, pos - start, Charsets.UTF_8).trimEnd('\r')
            if (pos < bytes.size) pos++
            return text
        }

        fun int(): Int? {
            var value = 0L
            var digits = 0
            while (pos < bytes.size) {
                val digit = bytes[pos] - '0'.code.toByte()
                if (digit !in 0..9) break
                value = value * 10 + digit
                if (value > Int.MAX_VALUE) return null
                digits++
                pos++
            }
            return if (digits == 0) null else value.toInt()
        }

        fun skip(char: Char): Boolean {
            if (pos < bytes.size && bytes[pos] == char.code.toByte()) {
                pos++
                return true
            }
            return false
        }

        fun skipLine() {
            while (pos < bytes.size && bytes[pos] != '\n'.code.toByte()) pos++
            if (pos < bytes.size) pos++
        }
    }
}
