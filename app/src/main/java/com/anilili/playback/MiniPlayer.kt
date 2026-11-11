package com.anilili.playback

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Whether this device can shrink a playing episode into the system's floating window.
 *
 * Asked in two places that would otherwise answer it differently: the activity, which has to decide
 * whether to publish picture-in-picture params at all, and Settings, which should not offer a switch
 * for a window the device will never draw. Televisions and Android Go builds routinely omit the
 * feature, and the platform did not have it before API 26 at all.
 */
internal fun supportsMiniPlayer(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
