package com.anilili.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Media3 scores text tracks the way a player does, so a download prepared with only video and audio
 * parameters writes no subtitle segments to disk at all. That is not a preference that can be
 * corrected later — it is what made soft-sub servers whose subtitles ride inside the HLS manifest
 * download as raw unsubtitled video.
 */
class DownloadTextSelectionTest {
    private fun textFormat(label: String, language: String?, default: Boolean = false): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .setLabel(label)
            .setLanguage(language)
            .setSelectionFlags(if (default) C.SELECTION_FLAG_DEFAULT else 0)
            .build()

    private fun tracksOf(vararg formats: Format, support: Int = C.FORMAT_HANDLED): Tracks = Tracks(
        ImmutableList.copyOf(
            formats.map { format ->
                Tracks.Group(
                    TrackGroup(format),
                    /* adaptiveSupported= */ false,
                    intArrayOf(support),
                    // Nothing is selected: this is the manifest as prepared, before any parameters
                    // are applied, which is exactly the state the bug lived in.
                    booleanArrayOf(false),
                )
            },
        ),
    )

    private val base: TrackSelectionParameters = TrackSelectionParameters.Builder().build()

    private fun selectedLabels(tracks: Tracks): List<String?> =
        textTrackSelections(tracks, base).map { it.overrides.values.single().mediaTrackGroup.getFormat(0).label }

    @Test
    fun `every text rendition is selected, not only the best scoring one`() {
        val tracks = tracksOf(
            textFormat("English", "en"),
            textFormat("Spanish", "es"),
            textFormat("Arabic", "ar"),
        )

        assertEquals(listOf("English", "Spanish", "Arabic"), selectedLabels(tracks))
    }

    /**
     * The shape that produced the bug: no preferred language set, no DEFAULT flag on any track. A
     * player selects nothing here, and so did the downloader.
     */
    @Test
    fun `unflagged tracks in an unlabelled language are still taken`() {
        val tracks = tracksOf(textFormat("Subtitles", null), textFormat("Full", null))

        assertEquals(2, textTrackSelections(tracks, base).size)
    }

    /**
     * Each track needs its own selection because [androidx.media3.exoplayer.offline.DownloadHelper]
     * resolves one selection per renderer and there is a single text renderer. One parameters object
     * carrying three overrides would still download one track.
     */
    @Test
    fun `each track gets its own selection with a single override`() {
        val selections = textTrackSelections(
            tracksOf(textFormat("English", "en"), textFormat("Spanish", "es")),
            base,
        )

        assertEquals(2, selections.size)
        selections.forEach { assertEquals(1, it.overrides.size) }
    }

    /**
     * The video and audio tracks were chosen by the pass before this one. Leaving them enabled would
     * repeat their stream keys in the request once per subtitle track.
     */
    @Test
    fun `video and audio are disabled so their keys are not repeated`() {
        val selection = textTrackSelections(tracksOf(textFormat("English", "en")), base).single()

        assertTrue(selection.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO))
        assertTrue(selection.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO))
        assertTrue(!selection.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
    }

    /**
     * A device that cannot render a track still exports it: the flattened VTT beside the MP4 is read
     * by whatever plays it next, not by this device's decoders.
     */
    @Test
    fun `a track this device cannot render is downloaded anyway`() {
        val tracks = tracksOf(textFormat("Thai", "th"), support = C.FORMAT_EXCEEDS_CAPABILITIES)

        assertEquals(1, textTrackSelections(tracks, base).size)
    }

    @Test
    fun `a manifest with no text renditions asks for nothing`() {
        val audioOnly = Tracks(
            ImmutableList.of(
                Tracks.Group(
                    TrackGroup(
                        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build(),
                    ),
                    false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(true),
                ),
            ),
        )

        assertTrue(textTrackSelections(audioOnly, base).isEmpty())
    }
}
