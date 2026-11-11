package com.anilili.ui.profile

import com.anilili.data.library.WatchlistEntry
import com.anilili.data.model.CoverImage
import com.anilili.data.model.Media
import com.anilili.data.model.MediaListEntry
import com.anilili.data.model.MediaTitle
import com.anilili.data.model.Viewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The library arrives in two waves — this device's saved titles, then the account's — and the
 * second wave reorders the first.
 *
 * This is the precondition behind the homing in `LibraryCarousel`: a keyed lazy row follows the key
 * of whatever was first in it, so a head that moves down the order drags an untouched carousel with
 * it, as far as the row can scroll. Reproduced on a phone as a first poster cut in half. If these
 * tests ever stop reordering, the homing has nothing left to defend against.
 */
private const val SAVED_AT_MS = 1_760_000_000_000L

private fun planning(id: Int, title: String, updatedAt: Long) = MediaListEntry(
    id = id,
    status = "PLANNING",
    updatedAt = updatedAt,
    media = Media(
        id = id,
        title = MediaTitle(english = title),
        coverImage = CoverImage(large = null),
        format = "TV",
    ),
)

private fun profileOf(vararg entries: MediaListEntry) = AniListProfile(
    viewer = Viewer(id = 1, name = "viewer"),
    watching = emptyList(),
    rewatching = emptyList(),
    planning = entries.toList(),
    paused = emptyList(),
    completed = emptyList(),
    dropped = emptyList(),
)

private fun ordered(cards: List<SavedAnimeCardData>) =
    libraryCards(cards, format = null, airing = null, titleFilter = "", sort = LibrarySort.LAST_MODIFIED)

class LibraryWaveOrderTest {
    @Test
    fun `a saved title moves down the order once the account's own timestamps land`() {
        // Saved on this device a moment ago, so before sign-in it is the newest thing there is.
        val local = listOf(
            WatchlistEntry(anilistId = 7, title = "Kagurabachi", cover = null, addedAt = SAVED_AT_MS),
        )

        val firstWave = ordered(buildCombinedWatchlist(profile = null, local = local, history = emptyList()))
        assertEquals(listOf(7), firstWave.map { it.id })

        // The account has it too, added long before, alongside two newer planning entries.
        val profile = profileOf(
            planning(7, "Kagurabachi", updatedAt = 1_600_000_000L),
            planning(1, "False Memory", updatedAt = 1_759_000_000L),
            planning(2, "The Strongest Magician", updatedAt = 1_758_000_000L),
        )
        val secondWave = ordered(buildCombinedWatchlist(profile, local, history = emptyList()))

        // The row was anchored to 7 and 7 is now last, which is the whole of the bug: the carousel
        // scrolls after it and stops at the far end of a rail nobody has touched.
        assertEquals(listOf(1, 2, 7), secondWave.map { it.id })
        assertNotEquals(firstWave.first().id, secondWave.first().id)
    }

    @Test
    fun `a head that does not move is not something the carousel has to correct`() {
        // Same two waves, but the account agrees the saved title is the newest: nothing reorders,
        // the anchor stays at index 0, and homing has nothing to do.
        val local = listOf(
            WatchlistEntry(anilistId = 7, title = "Kagurabachi", cover = null, addedAt = SAVED_AT_MS),
        )
        val profile = profileOf(
            planning(7, "Kagurabachi", updatedAt = 1_760_000_000L),
            planning(1, "False Memory", updatedAt = 1_700_000_000L),
        )

        val firstWave = ordered(buildCombinedWatchlist(profile = null, local = local, history = emptyList()))
        val secondWave = ordered(buildCombinedWatchlist(profile, local, history = emptyList()))

        assertEquals(7, firstWave.first().id)
        assertEquals(7, secondWave.first().id)
    }
}
