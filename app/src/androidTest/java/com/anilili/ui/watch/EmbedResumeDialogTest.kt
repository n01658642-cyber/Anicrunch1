package com.anilili.ui.watch

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbedResumeDialogTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    class DialogBridge {
        val visible = CountDownLatch(1)
        val dismissed = CountDownLatch(1)
        @Volatile var seen = false
        @JavascriptInterface fun onResumeDialog(frame: String, active: Boolean) {
            if (active) { seen = true; visible.countDown() }
            else if (seen) dismissed.countDown()
        }
    }

    private fun withPage(test: (WebView, DialogBridge) -> Unit) {
        lateinit var web: WebView
        val loaded = CountDownLatch(1)
        val bridge = DialogBridge()
        instrumentation.runOnMainSync {
            web = WebView(ApplicationProvider.getApplicationContext()).apply {
                settings.javaScriptEnabled = true
                layout(0, 0, 800, 600)
                addJavascriptInterface(bridge, "AniliProgress")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                }
                loadDataWithBaseURL("https://fixture.invalid/", "<html><body><video width='640' height='360'></video></body></html>", "text/html", "UTF-8", null)
            }
        }
        try {
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            evaluate(web, HIDE_PROVIDER_CHROME_JS)
            test(web, bridge)
        } finally { instrumentation.runOnMainSync { web.destroy() } }
    }

    private fun evaluate(web: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        return result
    }

    @Test
    fun lateDialogAndLateClickHandlerAreRetriedUntilPageDismissesIt() = withPage { web, bridge ->
        evaluate(web, "document.body.insertAdjacentHTML('beforeend', '<div id=checkresume_div>Resume playing?<button id=no_thanks>No thanks</button></div>');")
        assertTrue("dialog discovered after bootstrap", bridge.visible.await(5, TimeUnit.SECONDS))
        assertEquals("\"block\"", evaluate(web, "getComputedStyle(document.getElementById('checkresume_div')).display"))
        assertEquals("true", evaluate(web, "getComputedStyle(document.getElementById('no_thanks')).display !== 'none'"))
        evaluate(web, "document.getElementById('no_thanks').onclick=function(){document.getElementById('checkresume_div').style.display='none';};")
        assertTrue("late handler must run on a subsequent attempt", bridge.dismissed.await(6, TimeUnit.SECONDS))
    }

    @Test
    fun unknownResumeDialogKeepsItsButtonsAndRequestsPageInput() = withPage { web, bridge ->
        evaluate(web, "document.body.insertAdjacentHTML('beforeend', '<div role=dialog>Resume playing?<button id=resume>Resume</button><button>Start over</button></div>');")
        assertTrue(bridge.visible.await(5, TimeUnit.SECONDS))
        assertEquals("true", evaluate(web, "getComputedStyle(document.getElementById('resume')).display !== 'none'"))
        assertEquals("true", evaluate(web, "document.querySelector('[role=dialog]').getClientRects().length > 0"))
    }
}
