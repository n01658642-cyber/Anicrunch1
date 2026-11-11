package com.anilili.data.profiles

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import com.anilili.MainActivity

/**
 * Restarts the app into the profile [Profiles.switchTo] just selected.
 *
 * Declared in a process of its own (`:profile_restart`), because the thing being restarted is the
 * process that asked: it kills the app's main process, then launches the main activity, which comes
 * up in a fresh one where [Profiles.init] moves the new profile's files into place before anything
 * reads them. Started from a visible activity, so the launch it makes in turn is a foreground one
 * and is not caught by Android's background activity-start limits the way an alarm would be.
 */
class ProfileRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_PROFILE_SWITCHED, true),
        )
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        const val EXTRA_PID = "com.anilili.extra.PROFILE_RESTART_PID"
        const val EXTRA_PROFILE_SWITCHED = "com.anilili.extra.PROFILE_SWITCHED"
    }
}
