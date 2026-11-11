package com.anilili.ui.adaptive

import android.content.res.Configuration
import com.anilili.data.device.TelevisionSignal
import com.anilili.data.device.TelevisionSignals
import com.anilili.data.settings.FormFactorPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDeviceProfileTest {
    @Test
    fun `phone uses compact navigation`() {
        val profile = resolveAppDeviceProfile(Configuration.UI_MODE_TYPE_NORMAL, 412)
        assertEquals(AppFormFactor.PHONE, profile.formFactor)
        assertFalse(profile.useNavigationRail)
        assertEquals(5, profile.episodeColumns)
    }

    @Test
    fun `tablet uses navigation rail and wider episode grid`() {
        val profile = resolveAppDeviceProfile(Configuration.UI_MODE_TYPE_NORMAL, 800)
        assertEquals(AppFormFactor.TABLET, profile.formFactor)
        assertTrue(profile.useNavigationRail)
        assertEquals(6, profile.episodeColumns)
    }

    @Test
    fun `television wins regardless of reported dp width`() {
        val profile = resolveAppDeviceProfile(Configuration.UI_MODE_TYPE_TELEVISION, 540)
        assertEquals(AppFormFactor.TV, profile.formFactor)
        assertTrue(profile.useNavigationRail)
        assertEquals(8, profile.episodeColumns)
    }

    /**
     * The grids these counts feed give each cell a `weight(1f)`, so the count is the only thing
     * that decides how big a cell ends up. Leaving it fixed while the density grew would have left
     * every episode chip and library poster at exactly its old physical size — the one part of the
     * interface a viewer would notice had not changed.
     */
    @Test
    fun `a larger interface fits fewer television columns`() {
        val profile = resolveAppDeviceProfile(
            Configuration.UI_MODE_TYPE_TELEVISION,
            960,
            uiScale = 1.15f,
        )

        assertEquals(7, profile.episodeColumns)
        assertEquals(5, profile.posterColumns)
    }

    @Test
    fun `a smaller interface fits more television columns`() {
        val profile = resolveAppDeviceProfile(
            Configuration.UI_MODE_TYPE_TELEVISION,
            960,
            uiScale = 0.9f,
        )

        assertEquals(9, profile.episodeColumns)
        assertEquals(7, profile.posterColumns)
    }

    @Test
    fun `an extreme scale still leaves a usable grid`() {
        val huge = resolveAppDeviceProfile(Configuration.UI_MODE_TYPE_TELEVISION, 960, uiScale = 4f)
        val tiny = resolveAppDeviceProfile(Configuration.UI_MODE_TYPE_TELEVISION, 960, uiScale = 0.2f)

        assertEquals(3, huge.episodeColumns)
        assertEquals(12, tiny.episodeColumns)
    }

    /** Handhelds never scale, so their counts must not move even if a scale reaches them. */
    @Test
    fun `interface scale does not reach handheld grids`() {
        val phone = resolveAppDeviceProfile(Configuration.UI_MODE_TYPE_NORMAL, 412, uiScale = 1.15f)

        assertEquals(5, phone.episodeColumns)
        assertEquals(3, phone.posterColumns)
    }

    /**
     * A fixed 108 dp poster fit three-and-a-bit across a common phone but four and a half across one
     * with Display size turned down, which a viewer reported as too many cards on Home.
     */
    @Test
    fun `phone rail posters keep about three cards across whatever the width`() {
        fun width(dp: Int) = resolveAppDeviceProfile(Configuration.UI_MODE_TYPE_NORMAL, dp).posterWidth.value

        // The common phones keep the size they had.
        assertEquals(108f, width(360))
        assertEquals(108f, width(412))
        // Wider phones grow the card instead of fitting more of them.
        for (dp in listOf(480, 540, 590)) {
            val visible = (dp - 32 + 10) / (width(dp) + 10)
            assertTrue("$dp dp shows $visible cards", visible in 3f..3.6f)
        }
        // Tablets and television keep their own sizes.
        assertEquals(120f, width(700))
        assertEquals(150f, resolveAppDeviceProfile(Configuration.UI_MODE_TYPE_TELEVISION, 960).posterWidth.value)
    }

    @Test
    fun `low ram television uses low cost focus effects`() {
        val profile = resolveAppDeviceProfile(
            Configuration.UI_MODE_TYPE_TELEVISION,
            960,
            isLowRamDevice = true,
            memoryClassMb = 512,
        )

        assertTrue(profile.useLowCostTvEffects)
    }

    @Test
    fun `small heap television uses low cost focus effects even without low ram flag`() {
        val profile = resolveAppDeviceProfile(
            Configuration.UI_MODE_TYPE_TELEVISION,
            960,
            isLowRamDevice = false,
            memoryClassMb = 192,
        )

        assertTrue(profile.useLowCostTvEffects)
    }

    @Test
    fun `high memory television and handhelds keep standard effects`() {
        assertFalse(
            resolveAppDeviceProfile(
                Configuration.UI_MODE_TYPE_TELEVISION,
                960,
                memoryClassMb = 512,
            ).useLowCostTvEffects,
        )
        assertFalse(
            resolveAppDeviceProfile(
                Configuration.UI_MODE_TYPE_NORMAL,
                412,
                isLowRamDevice = true,
                memoryClassMb = 128,
            ).useLowCostTvEffects,
        )
    }

    /**
     * The report this fallback chain exists for: an Amlogic p383 on 5.1.1, 1920x1080 into a
     * television at 1280dp, launched from a leanback home screen — and `UI_MODE_TYPE_NORMAL`,
     * which is the only question the app used to ask. It got the tablet layout, navigation rail
     * and all.
     */
    @Test
    fun `a box that reports a normal ui mode is still a television`() {
        val profile = resolveAppDeviceProfile(
            Configuration.UI_MODE_TYPE_NORMAL,
            1_280,
            signals = TelevisionSignals(launchedFromLeanbackLauncher = true),
        )

        assertEquals(AppFormFactor.TV, profile.formFactor)
        assertEquals(TelevisionSignal.LEANBACK_LAUNCHER, profile.televisionSignal)
    }

    @Test
    fun `each device signal names itself and they are ranked`() {
        fun signalFor(signals: TelevisionSignals) = resolveAppDeviceProfile(
            Configuration.UI_MODE_TYPE_NORMAL,
            1_280,
            signals = signals,
        ).televisionSignal

        assertEquals(
            TelevisionSignal.UI_MODE,
            signalFor(TelevisionSignals(systemUiModeIsTelevision = true)),
        )
        assertEquals(
            TelevisionSignal.LEANBACK_FEATURE,
            signalFor(TelevisionSignals(hasLeanbackFeature = true)),
        )
        assertEquals(
            TelevisionSignal.TELEVISION_FEATURE,
            signalFor(TelevisionSignals(hasTelevisionFeature = true)),
        )
        assertEquals(
            TelevisionSignal.NO_TOUCHSCREEN,
            signalFor(TelevisionSignals(hasTouchscreen = false)),
        )
        // The ui mode outranks the guesses whenever it is the one that answered.
        assertEquals(
            TelevisionSignal.UI_MODE,
            signalFor(
                TelevisionSignals(
                    systemUiModeIsTelevision = true,
                    hasLeanbackFeature = true,
                    hasTouchscreen = false,
                ),
            ),
        )
    }

    /** A tablet is a device with a touchscreen and none of the signals, whatever its size. */
    @Test
    fun `a large tablet is left alone`() {
        val profile = resolveAppDeviceProfile(Configuration.UI_MODE_TYPE_NORMAL, 1_280)

        assertEquals(AppFormFactor.TABLET, profile.formFactor)
        assertNull(profile.televisionSignal)
    }

    /**
     * A missing touchscreen is the weakest signal and the only one that is not a device declaring
     * itself, so it is not allowed to reach a screen too small for the ten-foot layout to be the
     * better guess when it is wrong.
     */
    @Test
    fun `a narrow screen without a touchscreen stays a handheld`() {
        val profile = resolveAppDeviceProfile(
            Configuration.UI_MODE_TYPE_NORMAL,
            412,
            signals = TelevisionSignals(hasTouchscreen = false),
        )

        assertEquals(AppFormFactor.PHONE, profile.formFactor)
    }

    @Test
    fun `the viewer's choice outranks every device signal in both directions`() {
        val chosenTv = resolveAppDeviceProfile(
            Configuration.UI_MODE_TYPE_NORMAL,
            1_280,
            preference = FormFactorPreference.TELEVISION,
        )
        assertEquals(AppFormFactor.TV, chosenTv.formFactor)
        assertEquals(TelevisionSignal.CHOSEN, chosenTv.televisionSignal)

        // Including over the ui mode: a setting a device can overrule is not a setting.
        val chosenHandheld = resolveAppDeviceProfile(
            Configuration.UI_MODE_TYPE_TELEVISION,
            1_280,
            signals = TelevisionSignals(hasLeanbackFeature = true, hasTouchscreen = false),
            preference = FormFactorPreference.HANDHELD,
        )
        assertEquals(AppFormFactor.TABLET, chosenHandheld.formFactor)
        assertNull(chosenHandheld.televisionSignal)
    }
}
