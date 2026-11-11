package com.anilili.ui.watch

import com.anilili.data.model.SkipTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSeekMarkersTest {
    private val episodeDurationMs = 24 * 60_000L

    private fun skip(
        introStart: Double? = null,
        introEnd: Double? = null,
        outroStart: Double? = null,
        outroEnd: Double? = null,
        recapStart: Double? = null,
        recapEnd: Double? = null,
    ) = SkipTimes(
        introStart = introStart,
        introEnd = introEnd,
        outroStart = outroStart,
        outroEnd = outroEnd,
        recapStart = recapStart,
        recapEnd = recapEnd,
    )

    @Test
    fun `the opening and the credits become bands, in the order they play`() {
        val markers = playerSeekMarkers(
            skip = skip(introStart = 90.0, introEnd = 180.0, outroStart = 1300.0, outroEnd = 1380.0),
            durationMs = episodeDurationMs,
        )
        assertEquals(
            listOf(
                PlayerSeekMarker("Intro", 90_000L, 180_000L),
                PlayerSeekMarker("Outro", 1_300_000L, 1_380_000L),
            ),
            markers,
        )
    }

    @Test
    fun `a recap is drawn ahead of the opening it runs into`() {
        val markers = playerSeekMarkers(
            skip = skip(introStart = 60.0, introEnd = 150.0, recapStart = 0.0, recapEnd = 60.0),
            durationMs = episodeDurationMs,
        )
        assertEquals(listOf("Recap", "Intro"), markers.map { it.label })
    }

    @Test
    fun `a window with no start begins at the top of the file`() {
        // The same reading both players take of a bare end (see PlayerSurface's `introStart ?: 0`),
        // so the band and the Skip button cannot disagree about where the opening is.
        val markers = playerSeekMarkers(skip(introEnd = 85.0), episodeDurationMs)
        assertEquals(listOf(PlayerSeekMarker("Intro", 0L, 85_000L)), markers)
    }

    @Test
    fun `credits with no end run to the end of the file`() {
        val markers = playerSeekMarkers(skip(outroStart = 1_380.0), episodeDurationMs)
        assertEquals(listOf(PlayerSeekMarker("Outro", 1_380_000L, episodeDurationMs)), markers)
    }

    @Test
    fun `nothing is drawn without timings or a timeline to place them on`() {
        assertTrue(playerSeekMarkers(skip = null, durationMs = episodeDurationMs).isEmpty())
        // The empty `skipTimes: {}` some providers send, which is what hasUsableWindow() rejects.
        assertTrue(playerSeekMarkers(skip(), episodeDurationMs).isEmpty())
        // A live or still-resolving stream: no duration means no bar to place a window against.
        assertTrue(playerSeekMarkers(skip(introStart = 90.0, introEnd = 180.0), 0L).isEmpty())
        // An opening with no end is a broken row, not an episode that is all opening.
        assertTrue(playerSeekMarkers(skip(introStart = 90.0), episodeDurationMs).isEmpty())
        // An end at or before its own start describes nothing.
        assertTrue(playerSeekMarkers(skip(introStart = 180.0, introEnd = 180.0), episodeDurationMs).isEmpty())
    }

    @Test
    fun `timings cut for a longer encode stay on the bar`() {
        // Provider timings are routinely taken from a different encode of the same episode, so an
        // outro can start after this file has already ended.
        val markers = playerSeekMarkers(
            skip = skip(introStart = 60.0, introEnd = 2_000.0, outroStart = 2_100.0, outroEnd = 2_200.0),
            durationMs = episodeDurationMs,
        )
        assertEquals(listOf(PlayerSeekMarker("Intro", 60_000L, episodeDurationMs)), markers)
    }

    @Test
    fun `an outro nothing else believes is not drawn either`() {
        // AniSkip's Bleach 274 row: an `ed` in the fifth minute of a full-length episode. The
        // opening beside it is fine and stays.
        val markers = playerSeekMarkers(
            skip = skip(introStart = 24.08, introEnd = 114.08, outroStart = 286.665, outroEnd = 376.665),
            durationMs = episodeDurationMs,
        )
        assertEquals(listOf(PlayerSeekMarker("Intro", 24_080L, 114_080L)), markers)
    }

    @Test
    fun `each window lands where it plays along the bar`() {
        val fractions = playerSeekMarkerFractions(
            markers = listOf(PlayerSeekMarker("Intro", 100_000L, 300_000L)),
            durationMs = 1_000_000L,
        )
        assertEquals(1, fractions.size)
        assertEquals(0.1f, fractions.single().start, 0.0001f)
        assertEquals(0.3f, fractions.single().endInclusive, 0.0001f)
    }

    @Test
    fun `an unknown duration places nothing`() {
        assertTrue(
            playerSeekMarkerFractions(
                markers = listOf(PlayerSeekMarker("Intro", 0L, 85_000L)),
                durationMs = 0L,
            ).isEmpty(),
        )
    }
}
