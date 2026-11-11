package com.anilili.data.discord

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Where a browser hand-off comes back to.
 *
 * A replay buffer of one is the point rather than a detail: the app may have been evicted while
 * the viewer was away in Chrome, so the redirect can land before anything is listening for it.
 * The settings screen collects this when it comes back up and finds the answer waiting.
 */
object DiscordRedirectBus {
    private val _redirects = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)
    val redirects = _redirects.asSharedFlow()

    fun publish(url: String) {
        _redirects.tryEmit(url)
    }

    /** Drops a handled redirect so re-entering settings cannot process it a second time. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun consume() {
        _redirects.resetReplayCache()
    }
}

/**
 * Catches `discord-<application id>:/authorize/callback` from the system browser and hands it to
 * [DiscordRedirectBus]. It draws nothing and finishes immediately, so the browser closes back onto
 * whatever the viewer was doing.
 */
class DiscordOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        val url = intent?.data?.toString()
        if (url == null) {
            DiagnosticsLog.event(category = "auth", name = "discord.callback_empty")
            return
        }
        DiagnosticsLog.event(
            category = "auth",
            name = "discord.callback",
            // Never the query: it carries the authorization code.
            attributes = mapOf("answered" to DiscordAuthManager.isRedirect(url)),
        )
        DiscordRedirectBus.publish(url)
    }
}
