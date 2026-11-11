package com.anilili.ui.profile

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class LoginLoadProblemTest {
    @Test
    fun missingWebViewExplainsTheDeviceFix() {
        val problem = LoginLoadProblem.WEBVIEW_UNAVAILABLE

        assertTrue(problem.title.contains("isn't available"))
        assertTrue(problem.guidance.contains("Android System WebView"))
    }

    @Test
    fun networkProblemMentionsDnsBecauseAniListCanBeRegionallyBlocked() {
        assertTrue(LoginLoadProblem.NETWORK.guidance.contains("private DNS"))
    }

    @Test
    fun pageProgressBecomesUsableBeforeSlowBackgroundResourcesFinish() {
        assertFalse(isLoginPageUsableProgress(64))
        assertTrue(isLoginPageUsableProgress(65))
    }

    @Test
    fun aLoginPageRunningABotCheckCountsAsUsable() {
        // AniList's sign-in runs a Cloudflare challenge, which holds connections open and parks
        // the load at 70% indefinitely — six attempts in one device report, every one at 70. The
        // old 85% bar called that broken and showed "taking too long" over a page that was fine.
        assertTrue(isLoginPageUsableProgress(70))
    }
}
