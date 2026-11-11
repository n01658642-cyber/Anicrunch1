package com.anilili

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.anilili.data.auth.SimklAuthManager
import com.anilili.diagnostics.DiagnosticsLog

/**
 * Receives `anilili://` links and hands them to [MainActivity], which reads and opens them.
 *
 * The filter lives here rather than on [MainActivity] because that activity is `singleTop`, not
 * `singleTask`. A link tapped in an app that starts it without `FLAG_ACTIVITY_NEW_TASK` — most chat
 * apps — would build a second copy of the whole app, player and all, inside that app's task. This
 * draws nothing and finishes at once, so the forwarded intent always lands in the app's own task:
 * the running activity takes it in `onNewIntent`, or a cold start opens on it.
 *
 * One `anilili://` address is not a link to open: `anilili://auth/simkl` is where the browser
 * returns from a Simkl sign-in. It is finished here, in the background, and the app is brought back
 * to whatever it was showing — the sign-in's code must never reach [MainActivity] as a link it would
 * refuse, nor a diagnostics line.
 */
class AnililiLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { link ->
            val simkl = SimklAuthManager.isRedirect(link.toString())
            if (simkl) {
                DiagnosticsLog.event(category = "auth", name = "simkl.callback")
                SimklAuthManager.handleRedirect(link.toString())
            } else {
                DiagnosticsLog.event("Link forwarded scheme=${link.scheme}")
            }
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    if (!simkl) {
                        action = Intent.ACTION_VIEW
                        data = link
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
            )
        }
        finish()
    }
}
