package com.anilili.ui.watch

/**
 * Which disk a download's space verdict came from.
 *
 * A download bound for a memory card touches two volumes, and a gauge can only show one. Naming the
 * one it is showing is the difference between a number a viewer can act on and the bug this type
 * exists to stop: a phone with a 128 GB card and 4.5 GB of internal storage was told a 27-episode
 * season was "4 GB short", because the dialog asked the internal volume how much room the card had.
 */
internal enum class DownloadSpaceVolume {
    /** One volume holds everything, so there is nothing to disambiguate. */
    ONLY,

    /**
     * Internal storage, which every download passes through even when it is bound elsewhere: an
     * episode is fetched into the app's library cache and only then rewrapped onto the destination.
     */
    LIBRARY_CACHE,

    /** The chosen save location — the card, the USB drive, the picked folder. */
    SAVE_LOCATION,
}

/** The deciding volume for a download, and the numbers the gauge should show for it. */
internal data class DownloadSpacePlan(
    val verdict: DownloadSpaceVerdict,
    val volume: DownloadSpaceVolume,
    /** Free bytes on that volume; 0 when it could not be measured. */
    val freeBytes: Long,
    /** What that volume has to hold, which is not the batch size once the two volumes differ. */
    val neededBytes: Long,
    /**
     * The volume's name, for the sentences that have to say which disk; null for
     * [DownloadSpaceVolume.ONLY], where there is no other disk to confuse it with.
     */
    val volumeLabel: String?,
) {
    val wontFit: Boolean get() = verdict == DownloadSpaceVerdict.WONT_FIT
    val tight: Boolean get() = verdict == DownloadSpaceVerdict.TIGHT
}

/** Mid-sentence, because it is read as "4.5 GB free on internal storage". */
internal const val LIBRARY_CACHE_VOLUME_LABEL = "internal storage"

/**
 * How a batch of episodes compares with the room on every volume it actually needs.
 *
 * Two things were wrong with measuring one volume, and only the first is obvious. Asking
 * `filesDir` how much room a memory card has answers about the wrong disk entirely — that is the
 * refusal viewers hit, and why the same season at a lower resolution went through and landed on the
 * card exactly as asked. The second is that internal storage does not hold the batch at all when
 * the destination is "Device Downloads": each episode is cached there, rewrapped into an MP4, and
 * the cached copy is dropped again, so what has to fit is one episode, not twenty-seven. Charging
 * internal storage for the whole season would keep refusing card-bound downloads even after the
 * free-space figure was read from the right volume.
 *
 * [deviceFreeBytes] is null when the destination's volume cannot be measured at all — a folder
 * picked through the system picker can sit on a provider with no filesystem path behind it. That is
 * left unjudged rather than guessed at: the export checks the destination again with the real
 * episode size before it writes, and refusing here on a measurement that was never taken would
 * strand someone with a card full of room and no way to proceed.
 */
internal fun downloadSpacePlan(
    episodeCount: Int,
    perEpisodeBytes: Long,
    /** Whether the finished episodes stay in the app's offline library. */
    keepsLibraryCopy: Boolean,
    /** Whether MP4s are written to the chosen save location. */
    savesToDevice: Boolean,
    libraryFreeBytes: Long,
    deviceFreeBytes: Long?,
    deviceVolumeLabel: String,
    /** Whether the save location sits on the internal volume, so the two claims add up. */
    deviceSharesLibraryVolume: Boolean,
    headroomBytes: Long,
): DownloadSpacePlan {
    val count = episodeCount.coerceAtLeast(0).toLong()
    val batchBytes = perEpisodeBytes * count
    val libraryNeed = if (keepsLibraryCopy) batchBytes else minOf(batchBytes, perEpisodeBytes)
    // The MP4 is built as a scratch file on the destination's own volume before it is published, so
    // that volume briefly holds one episode more than the batch itself.
    val deviceNeed = if (savesToDevice && count > 0) batchBytes + perEpisodeBytes else 0L

    fun onOneVolume(need: Long) = DownloadSpacePlan(
        verdict = downloadSpaceVerdict(libraryFreeBytes, need, headroomBytes),
        volume = DownloadSpaceVolume.ONLY,
        freeBytes = libraryFreeBytes,
        neededBytes = need,
        volumeLabel = null,
    )

    if (!savesToDevice || deviceSharesLibraryVolume) return onOneVolume(libraryNeed + deviceNeed)
    // An unmeasurable destination is not charged to internal storage — that would re-create the
    // very refusal this exists to fix, on the one destination nothing here can check.
    if (deviceFreeBytes == null) return onOneVolume(libraryNeed)

    val device = DownloadSpacePlan(
        verdict = downloadSpaceVerdict(deviceFreeBytes, deviceNeed, headroomBytes),
        volume = DownloadSpaceVolume.SAVE_LOCATION,
        freeBytes = deviceFreeBytes,
        neededBytes = deviceNeed,
        volumeLabel = deviceVolumeLabel,
    )
    val library = DownloadSpacePlan(
        verdict = downloadSpaceVerdict(libraryFreeBytes, libraryNeed, headroomBytes),
        volume = DownloadSpaceVolume.LIBRARY_CACHE,
        freeBytes = libraryFreeBytes,
        neededBytes = libraryNeed,
        volumeLabel = LIBRARY_CACHE_VOLUME_LABEL,
    )
    // The gauge shows whichever volume decides. Ties go to the save location: it is the disk the
    // viewer went looking for, and the one they can do something about.
    return if (library.verdict.severity > device.verdict.severity) library else device
}

/**
 * Declared rather than taken from the enum's ordinal, so reordering [DownloadSpaceVerdict] for
 * readability cannot silently invert which volume a dialog reports.
 */
private val DownloadSpaceVerdict.severity: Int
    get() = when (this) {
        DownloadSpaceVerdict.FITS -> 0
        DownloadSpaceVerdict.TIGHT -> 1
        DownloadSpaceVerdict.WONT_FIT -> 2
    }
