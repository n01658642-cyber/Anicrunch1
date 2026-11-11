package com.anilili.ui.search

import android.content.res.Configuration
import androidx.compose.ui.unit.dp
import com.anilili.ui.adaptive.resolveAppDeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSearchLayoutPolicyTest {
    @Test
    fun searchColumnCountRespondsToAvailableTvWidth() {
        assertEquals(
            4,
            adaptiveColumnCount(
                availableWidth = 960.dp,
                horizontalPadding = 48.dp,
                minimumTileWidth = 200.dp,
                spacing = 18.dp,
            ),
        )
        assertEquals(
            3,
            adaptiveColumnCount(
                availableWidth = 800.dp,
                horizontalPadding = 48.dp,
                minimumTileWidth = 200.dp,
                spacing = 18.dp,
            ),
        )
    }

    /** A 360 dp phone used to get two outsized cards where a 384 dp one got three. */
    @Test
    fun everyCommonPhoneWidthGetsThreeResultColumns() {
        for (widthDp in listOf(350, 360, 384, 411)) {
            val phone = resolveAppDeviceProfile(uiModeType = Configuration.UI_MODE_TYPE_NORMAL, widthDp = widthDp)
            assertEquals(
                "width $widthDp",
                3,
                adaptiveColumnCount(
                    availableWidth = widthDp.dp,
                    horizontalPadding = phone.pagePadding,
                    minimumTileWidth = phone.gridMinWidth,
                    spacing = 9.dp,
                ),
            )
        }
    }

    @Test
    fun tvHeaderCanCollapseEvenWhileGridRemainsAtTop() {
        assertFalse(
            searchHeaderVisible(
                isTv = true,
                tvHeaderExpanded = false,
                scrollingUp = true,
            ),
        )
    }

    @Test
    fun phoneHeaderContinuesFollowingScrollDirection() {
        assertTrue(searchHeaderVisible(isTv = false, tvHeaderExpanded = false, scrollingUp = true))
        assertFalse(searchHeaderVisible(isTv = false, tvHeaderExpanded = true, scrollingUp = false))
    }

    /** Tucked away over its own reserved room, the bar left a blank band as tall as itself. */
    @Test
    fun phoneHeaderStaysWhileItsReservedRoomIsOnScreen() {
        assertTrue(
            searchHeaderVisible(isTv = false, tvHeaderExpanded = false, scrollingUp = false, headerRoomOnScreen = true),
        )
        assertFalse(
            searchHeaderVisible(isTv = true, tvHeaderExpanded = false, scrollingUp = false, headerRoomOnScreen = true),
        )
    }

    @Test
    fun onlyFirstGridRowReturnsToSearchControls() {
        assertTrue(isFirstTvSearchResultRow(index = 0, columnCount = 4))
        assertTrue(isFirstTvSearchResultRow(index = 3, columnCount = 4))
        assertFalse(isFirstTvSearchResultRow(index = 4, columnCount = 4))
        assertFalse(isFirstTvSearchResultRow(index = -1, columnCount = 4))
    }
}
