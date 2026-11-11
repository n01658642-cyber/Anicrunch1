package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val MB = 1024L * 1024
private const val GB = 1024L * 1024 * 1024
private const val HEADROOM = 1_000L * MB

/** 720p, which is what a "315 MB" episode in the dialog actually is. */
private const val PER_EPISODE = 300L * MB

private fun plan(
    episodeCount: Int = 27,
    perEpisodeBytes: Long = PER_EPISODE,
    keepsLibraryCopy: Boolean = false,
    savesToDevice: Boolean = true,
    libraryFreeBytes: Long = 4_608L * MB,
    deviceFreeBytes: Long? = 60 * GB,
    deviceVolumeLabel: String = "SD card",
    deviceSharesLibraryVolume: Boolean = false,
) = downloadSpacePlan(
    episodeCount = episodeCount,
    perEpisodeBytes = perEpisodeBytes,
    keepsLibraryCopy = keepsLibraryCopy,
    savesToDevice = savesToDevice,
    libraryFreeBytes = libraryFreeBytes,
    deviceFreeBytes = deviceFreeBytes,
    deviceVolumeLabel = deviceVolumeLabel,
    deviceSharesLibraryVolume = deviceSharesLibraryVolume,
    headroomBytes = HEADROOM,
)

class DownloadSpacePlanTest {

    /**
     * The reported bug, with its real numbers: 27 episodes at 720p, a phone holding 4.5 GB
     * internally and a memory card selected as the save location. The dialog said "8.5 GB / 4.5 GB
     * free / 4.0 GB short" and disabled the button, having measured the volume the app runs from
     * rather than the one the episodes were going to.
     */
    @Test
    fun `a card-bound season is judged on the card, not on internal storage`() {
        val plan = plan()
        assertEquals(DownloadSpaceVerdict.FITS, plan.verdict)
        assertEquals(DownloadSpaceVolume.SAVE_LOCATION, plan.volume)
        assertEquals(60 * GB, plan.freeBytes)
        assertEquals("SD card", plan.volumeLabel)
    }

    /**
     * The second half of the same bug. Even measured on the right volume, internal storage was
     * being charged for the whole batch — but "Device Downloads" drops each cached copy as its MP4
     * is written, so what has to fit there is one episode. 1.5 GB of internal storage is nowhere
     * near 8.5 GB and is plenty for this download.
     */
    @Test
    fun `internal storage is charged one episode when the library copy is dropped`() {
        val plan = plan(libraryFreeBytes = 1_536L * MB)
        assertEquals(DownloadSpaceVerdict.FITS, plan.verdict)
        assertEquals(DownloadSpaceVolume.SAVE_LOCATION, plan.volume)
    }

    /** Keeping the library copy is the case where internal storage really does hold the batch. */
    @Test
    fun `internal storage is charged the whole batch when the library copy is kept`() {
        val plan = plan(keepsLibraryCopy = true)
        assertEquals(DownloadSpaceVerdict.WONT_FIT, plan.verdict)
        assertEquals(DownloadSpaceVolume.LIBRARY_CACHE, plan.volume)
        assertEquals(4_608L * MB, plan.freeBytes)
        assertEquals(27 * PER_EPISODE, plan.neededBytes)
    }

    /** The MP4 is built beside the finished file, so the destination briefly holds one extra. */
    @Test
    fun `the destination is charged one episode more than the batch for the scratch copy`() {
        assertEquals(28 * PER_EPISODE, plan().neededBytes)
    }

    /** A full card is refused even with a roomy phone behind it — the old check never saw this. */
    @Test
    fun `a full card is refused even when internal storage has room`() {
        val plan = plan(libraryFreeBytes = 200 * GB, deviceFreeBytes = 1 * GB)
        assertEquals(DownloadSpaceVerdict.WONT_FIT, plan.verdict)
        assertEquals(DownloadSpaceVolume.SAVE_LOCATION, plan.volume)
        assertEquals("SD card", plan.volumeLabel)
    }

    /**
     * Internal storage still has to hold the episode passing through it, and when that is what
     * fails the dialog has to say so — otherwise a card-bound download is refused over the phone's
     * storage with nothing on screen to connect the two.
     */
    @Test
    fun `a full internal volume decides even when the card is empty`() {
        val plan = plan(libraryFreeBytes = 200 * MB, deviceFreeBytes = 200 * GB)
        assertEquals(DownloadSpaceVerdict.WONT_FIT, plan.verdict)
        assertEquals(DownloadSpaceVolume.LIBRARY_CACHE, plan.volume)
        assertEquals(LIBRARY_CACHE_VOLUME_LABEL, plan.volumeLabel)
    }

    /** Saving to the primary volume's Downloads folder: one disk, so the two claims add up. */
    @Test
    fun `a destination on the internal volume adds its claim to the cache's`() {
        val plan = plan(libraryFreeBytes = 12 * GB, deviceSharesLibraryVolume = true)
        assertEquals(DownloadSpaceVerdict.FITS, plan.verdict)
        assertEquals(DownloadSpaceVolume.ONLY, plan.volume)
        assertNull(plan.volumeLabel)
        // The batch, the scratch file it is rewrapped through, and the cached copy of whichever
        // episode is in flight all sit on the one disk at the same moment.
        assertEquals(29 * PER_EPISODE, plan.neededBytes)

        // The same batch against a volume that holds it with nothing to spare.
        assertEquals(
            DownloadSpaceVerdict.TIGHT,
            plan(libraryFreeBytes = 9 * GB, deviceSharesLibraryVolume = true).verdict,
        )
    }

    /**
     * A picked folder can sit on a provider with no filesystem path to measure. Charging its batch
     * to internal storage would re-create the refusal this whole thing exists to fix, on the one
     * destination nothing here can check — the export re-checks it with the real episode size.
     */
    @Test
    fun `an unmeasurable destination is not charged to internal storage`() {
        val plan = plan(libraryFreeBytes = 2 * GB, deviceFreeBytes = null)
        assertEquals(DownloadSpaceVerdict.FITS, plan.verdict)
        assertEquals(DownloadSpaceVolume.ONLY, plan.volume)
        assertEquals(PER_EPISODE, plan.neededBytes)
    }

    /** "Anilili library" never touches the card, so the card's state cannot block it. */
    @Test
    fun `an app-only download ignores the save location entirely`() {
        val plan = plan(keepsLibraryCopy = true, savesToDevice = false, libraryFreeBytes = 60 * GB, deviceFreeBytes = 0L)
        assertEquals(DownloadSpaceVerdict.FITS, plan.verdict)
        assertEquals(DownloadSpaceVolume.ONLY, plan.volume)
        assertEquals(27 * PER_EPISODE, plan.neededBytes)
    }

    /** Nothing selected yet is not a storage problem, and must not price a phantom episode. */
    @Test
    fun `an empty selection needs nothing`() {
        val plan = plan(episodeCount = 0)
        assertEquals(DownloadSpaceVerdict.FITS, plan.verdict)
        assertEquals(0L, plan.neededBytes)
    }
}
