package com.anilili.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.anilili.data.settings.ThemeAccent
import com.anilili.data.settings.ThemeBackground
import com.anilili.data.settings.ThemeContrast
import com.anilili.data.settings.ThemePreset
import com.anilili.data.settings.ThemeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppColorSchemeTest {

    private fun colors(spec: ThemeSpec) = appColors(spec, wallpaperSeed = null)

    @Test
    fun `the default is AMOLED black and monochrome`() {
        val scheme = colors(ThemeSpec()).scheme
        assertEquals(Color.Black, scheme.background)
        assertEquals(Color.Black, scheme.surface)
        assertEquals(Color.White, scheme.primary)
        assertTrue(scheme.onPrimary.luminance() < 0.1f)
    }

    @Test
    fun `vivid keeps the exact colour that was picked`() {
        // Stock M3 draws dark primary at tone 80: blood red would arrive salmon.
        val red = colors(ThemeSpec(accent = ThemeAccent.BLOOD_RED)).scheme
        assertEquals(Color(ThemeAccent.BLOOD_RED.seed!!), red.primary)
        assertEquals(Color.White, red.onPrimary)
        val green = colors(ThemeSpec(accent = ThemeAccent.NEON_GREEN)).scheme
        assertEquals(Color(ThemeAccent.NEON_GREEN.seed!!), green.primary)
        assertTrue("dark copy on neon green", green.onPrimary.luminance() < 0.2f)
    }

    @Test
    fun `the Material You styles soften the accent as M3 intends`() {
        val tonal = colors(ThemeSpec(accent = ThemeAccent.BLOOD_RED, style = ThemeStyle.TONAL)).scheme
        assertNotEquals(Color(ThemeAccent.BLOOD_RED.seed!!), tonal.primary)
    }

    @Test
    fun `AMOLED keeps a container ladder above pure black`() {
        val scheme = colors(ThemeSpec(accent = ThemeAccent.PURPLE)).scheme
        val ladder = listOf(
            scheme.surfaceContainerLowest,
            scheme.surfaceContainerLow,
            scheme.surfaceContainer,
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
        ).map { it.luminance() }
        assertEquals(0f, ladder.first())
        assertEquals(ladder.sorted(), ladder)
        assertTrue(ladder.last() > ladder.first())
    }

    @Test
    fun `dark uses Material's own tone rather than black`() {
        val scheme = colors(ThemeSpec(background = ThemeBackground.DARK)).scheme
        assertNotEquals(Color.Black, scheme.background)
    }

    @Test
    fun `higher contrast pulls the quiet text further from the canvas`() {
        val standard = colors(ThemeSpec(accent = ThemeAccent.OCEAN_BLUE))
        val high = colors(ThemeSpec(accent = ThemeAccent.OCEAN_BLUE, contrast = ThemeContrast.HIGH))
        assertTrue(high.text3.luminance() > standard.text3.luminance())
        assertTrue(high.line2.luminance() > standard.line2.luminance())
    }

    @Test
    fun `the player stays white unless the viewer opts in`() {
        val off = colors(ThemeSpec(accent = ThemeAccent.PURPLE))
        assertEquals(Color.White, off.playerAccent)
        val on = colors(ThemeSpec(accent = ThemeAccent.PURPLE, playerAccent = true))
        assertEquals(on.scheme.primary, on.playerAccent)
    }

    @Test
    fun `wallpaper without a wallpaper seed falls back to monochrome`() {
        val scheme = colors(ThemeSpec(accent = ThemeAccent.WALLPAPER)).scheme
        assertEquals(Color.White, scheme.primary)
    }

    @Test
    fun `a custom accent uses the viewer's colour, lifted only when too dark to read`() {
        val teal = 0xFF14B8A6.toInt()
        val scheme = colors(ThemeSpec(accent = ThemeAccent.CUSTOM, customSeed = teal)).scheme
        assertEquals(Color(teal), scheme.primary)
        val nearBlack = 0xFF101820.toInt()
        val dark = colors(ThemeSpec(accent = ThemeAccent.CUSTOM, customSeed = nearBlack)).scheme
        assertNotEquals(Color(nearBlack), dark.primary)
        assertTrue("lifted primary reads on black", dark.primary.luminance() > 0.1f)
    }

    @Test
    fun `every preset matches its own settings and only its own`() {
        ThemePreset.entries.forEach { preset ->
            val matching = ThemePreset.entries.filter {
                it.matches(preset.background, preset.accent, preset.style, preset.contrast)
            }
            assertEquals(listOf(preset), matching)
        }
        // Monochrome ignores the style the viewer cannot see.
        assertTrue(
            ThemePreset.MIDNIGHT.matches(
                ThemeBackground.AMOLED, ThemeAccent.MONOCHROME, ThemeStyle.VIBRANT, ThemeContrast.STANDARD,
            ),
        )
    }
}
