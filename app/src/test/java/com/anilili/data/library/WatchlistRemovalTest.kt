package com.anilili.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Saved list used to sync only outwards: unsaving a title deleted it from the account and left
 * it on every other device, with no way to clear it but unsaving again on each one.
 *
 * A removal is a *disappearance* from the account — known before, unknown now — never a mere
 * absence, because an absence is also what a save that has not been pushed yet looks like.
 */
class WatchlistRemovalTest {
    private fun entry(id: Int, title: String = "Title $id", addedAt: Long = 10) =
        WatchlistEntry(anilistId = id, title = title, cover = null, addedAt = addedAt)

    @Test
    fun `unsaving on another device clears the save here`() {
        val merged = mergeWatchlistEntries(
            local = listOf(entry(1), entry(2)),
            fromAniList = listOf(entry(2)),
            knownRemoteIds = setOf(2),
            previousRemoteIds = setOf(1, 2),
        )

        assertEquals(listOf(2), merged.map { it.anilistId })
    }

    @Test
    fun `a save the account has never seen is never dropped`() {
        // Saved while signed out. It has no remote counterpart until the push carries it up, and
        // deleting it in the meantime would lose the viewer's own action.
        val merged = mergeWatchlistEntries(
            local = listOf(entry(1), entry(99, title = "Saved offline")),
            fromAniList = listOf(entry(1)),
            knownRemoteIds = setOf(1),
            previousRemoteIds = setOf(1),
        )

        assertEquals(listOf(1, 99), merged.map { it.anilistId })
    }

    @Test
    fun `the first refresh after a fresh install can only add`() {
        val merged = mergeWatchlistEntries(
            local = listOf(entry(1), entry(2)),
            fromAniList = listOf(entry(3)),
            knownRemoteIds = setOf(3),
            previousRemoteIds = emptySet(),
        )

        assertEquals(listOf(1, 2, 3), merged.map { it.anilistId })
    }

    @Test
    fun `a title moved to another list keeps its save`() {
        // Planning -> Watching on another device. The account still knows the title, so the viewer
        // never unsaved it and the heart must not clear itself.
        val merged = mergeWatchlistEntries(
            local = listOf(entry(1)),
            fromAniList = emptyList(),
            knownRemoteIds = setOf(1),
            previousRemoteIds = setOf(1),
        )

        assertEquals(listOf(1), merged.map { it.anilistId })
    }

    @Test
    fun `an empty snapshot is treated as no information, not as an emptied account`() {
        // A degraded or partial fetch must never be read as "the viewer deleted everything".
        val merged = mergeWatchlistEntries(
            local = listOf(entry(1), entry(2)),
            fromAniList = emptyList(),
            knownRemoteIds = emptySet(),
            previousRemoteIds = setOf(1, 2),
        )

        assertEquals(listOf(1, 2), merged.map { it.anilistId })
    }

    @Test
    fun `hanime saves survive because the account never knew them`() {
        // Catalogue-native ids are negative and deliberately never posted to any list service.
        val merged = mergeWatchlistEntries(
            local = listOf(entry(-4242, title = "Catalogue native"), entry(1)),
            fromAniList = emptyList(),
            knownRemoteIds = setOf(7),
            previousRemoteIds = setOf(1, 7),
        )

        assertEquals(listOf(-4242), merged.map { it.anilistId })
    }

    @Test
    fun `adding still works alongside removing`() {
        val merged = mergeWatchlistEntries(
            local = listOf(entry(1), entry(2)),
            fromAniList = listOf(entry(2), entry(3, title = "Added elsewhere")),
            knownRemoteIds = setOf(2, 3),
            previousRemoteIds = setOf(1, 2),
            addedAt = 99,
        )

        assertEquals(listOf(2, 3), merged.map { it.anilistId })
        assertEquals(99, merged.last().addedAt)
    }
}
