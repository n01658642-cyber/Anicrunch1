package com.anilili.data.remote

import android.webkit.WebView
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anilili.MainActivity
import com.anilili.data.AppGraph
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaheBridgeTest {
    /** Exercise the production host: a cancelled caller must not blank another owner's page. */
    @Test
    fun cancelledSessionKeepsOtherOwnersPageAndWindowViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            runBlocking {
                val started = CompletableDeferred<Unit>()
                val owner = launch(Dispatchers.Default) {
                    PaheBridge.session {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
                try {
                    started.await()
                    var view: WebView? = null
                    withTimeout(10_000) {
                        while (view == null) {
                            scenario.onActivity { activity ->
                                view = findWebView(activity.window.decorView)
                            }
                            delay(50)
                        }
                    }
                    val browser = requireNotNull(view)
                    instrumentation.runOnMainSync {
                        assertTrue("Challenge has a tiny layout viewport", browser.width > 100 && browser.height > 100)
                        browser.loadDataWithBaseURL(
                            PaheBridge.ORIGIN,
                            "<html><head><title>session-owner</title></head><body>held page</body></html>",
                            "text/html", "UTF-8", null,
                        )
                    }
                    suspend fun title(): String {
                        val result = CompletableDeferred<String>()
                        instrumentation.runOnMainSync {
                            browser.evaluateJavascript("document.title") { result.complete(it) }
                        }
                        return withTimeout(1_000) { result.await() }
                    }
                    withTimeout(5_000) {
                        while (title() != "\"session-owner\"") delay(50)
                    }
                    withTimeoutOrNull(50) { PaheBridge.session { delay(1_000) } }
                    // Give the cancelled caller's asynchronous main-thread cleanup time to run.
                    delay(300)
                    assertEquals("Cancelled caller blanked an active owner's page", "\"session-owner\"", title())
                } finally {
                    owner.cancelAndJoin()
                }
            }
        }
    }

    private fun findWebView(view: android.view.View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    /** A page-driven redirect must stay in the resolver, including on a cold WebView. */
    @Test
    fun pageRedirectStaysInsideResolver() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: WebView
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        ServerSocket(0).use { server ->
            val arrived = CountDownLatch(1)
            val worker = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val request = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
                        val body = "<html><body>resolver destination</body></html>".toByteArray()
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            write(body)
                            flush()
                        }
                        if (request.contains("/destination")) arrived.countDown()
                    }
                }
            }
            try {
                scenario.onActivity { activity ->
                    view = WebView(activity)
                    activity.addContentView(view, ViewGroup.LayoutParams(1, 1))
                    PaheBridge.attach(view)
                    view.loadDataWithBaseURL(
                        "http://127.0.0.1:${server.localPort}/start",
                        "<script>location.replace('/destination')</script>",
                        "text/html", "UTF-8", null,
                    )
                }
                assertTrue("Redirect never reached its destination", arrived.await(10, TimeUnit.SECONDS))
                var location: String? = null
                // Receiving the HTTP request precedes the renderer committing its new document.
                // Wait for that commit before judging which WebView followed the redirect.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (location != "\"/destination\"" && System.nanoTime() < deadline) {
                    val finished = CountDownLatch(1)
                    instrumentation.runOnMainSync {
                        view.evaluateJavascript("document.readyState === 'complete' ? location.pathname : ''") {
                            location = it
                            finished.countDown()
                        }
                    }
                    finished.await(1, TimeUnit.SECONDS)
                    if (location != "\"/destination\"") Thread.sleep(50)
                }
                assertEquals("Redirect left the resolver WebView", "\"/destination\"", location)
            } finally {
                instrumentation.runOnMainSync {
                    PaheBridge.detach(view)
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.stopLoading()
                    view.destroy()
                }
                server.close()
                worker.join(1_000)
                scenario.close()
            }
        }
    }

    /** Opt-in: the live site and CDN must be reachable from the test device. */
    @Test
    fun reportedEpisodesResolveOnDevice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("paheLive") == "true")
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val json = Json { ignoreUnknownKeys = true }
                val provider = PaheProvider(json)
                val episodes = mapOf(194829 to 10, 184356 to 8, 197715 to 10, 178789 to 11, 177699 to 10, 185874 to 7)
                val media = AppGraph.repository.mediaByIds(episodes.keys)
                assertEquals("Missing title metadata", episodes.keys, media.map { it.id }.toSet())
                for (title in media) {
                    val episode = episodes.getValue(title.id)
                    withTimeout(45_000) {
                        val availability = provider.episodeAvailability(title)
                        assertTrue("${title.id}: episode $episode absent", episode in availability.sub)
                        val sources = provider.sources(title, "sub", episode)
                        assertTrue("${title.id}: no HLS", sources.streams.any { it.type == "hls" })
                        android.util.Log.i("PaheLiveTest", "id=${title.id} episode=$episode hls=${sources.streams.size}")
                    }
                }
            }
        }
    }
}
