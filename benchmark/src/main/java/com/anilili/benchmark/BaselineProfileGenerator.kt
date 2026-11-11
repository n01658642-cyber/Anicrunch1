package com.anilili.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateTvCriticalJourneys() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
        // Each pass plays an episode, so the default of up to fifteen passes costs a quarter of an
        // hour for rules that have stopped changing after the second.
        maxIterations = 5,
        stableIterations = 2,
    ) {
        pressHome()
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), APP_WAIT_MS)) {
            "Anilili root window did not appear"
        }
        device.waitForIdle()

        // Compile the Compose focus/navigation paths that every remote-driven TV session uses.
        repeat(5) { device.pressDPadRight() }
        repeat(5) { device.pressDPadLeft() }
        repeat(3) { device.pressDPadDown() }
        repeat(8) { device.pressDPadRight() }
        repeat(4) { device.pressDPadLeft() }
        device.waitForIdle()

        // The title page, then the player: its first frame, the controls over a playing video and
        // one panel. These are the paths a television runs interpreted until the JIT catches up —
        // the ones where a dropped frame is noticed — and the home screen alone never reached them.
        // The route extra is the same one the app's own deep links use.
        startActivityAndWait { intent -> intent.putExtra(ROUTE_EXTRA, PROFILE_TITLE_ROUTE) }
        device.waitForIdle()
        Thread.sleep(TITLE_SETTLE_MS)
        device.pressDPadCenter() // the title page's primary action: play or continue
        Thread.sleep(PLAYBACK_SETTLE_MS)
        device.pressDPadDown() // raise the controls
        Thread.sleep(CONTROL_STEP_MS)
        repeat(4) {
            device.pressDPadRight()
            Thread.sleep(CONTROL_STEP_MS)
        }
        device.pressDPadCenter() // the servers panel
        Thread.sleep(PANEL_SETTLE_MS)
        device.pressBack()
        Thread.sleep(CONTROL_STEP_MS)
        device.pressBack()
        device.waitForIdle()
    }
}

internal const val TARGET_PACKAGE = "com.miruronative"
internal const val APP_WAIT_MS = 15_000L

private const val ROUTE_EXTRA = "com.miruronative.extra.ROUTE"

/** A long-finished series every source carries, so the journey does not depend on a new episode. */
private const val PROFILE_TITLE_ROUTE = "detail/166873"

private const val TITLE_SETTLE_MS = 6_000L
private const val PLAYBACK_SETTLE_MS = 25_000L
private const val PANEL_SETTLE_MS = 3_000L
private const val CONTROL_STEP_MS = 500L
