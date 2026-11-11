package com.anilili.diagnostics

import android.content.Context
import android.os.Build
import android.provider.Settings

/** The AOSP WebView. Unlike the Google and Amazon builds it is part of the system image and is
 *  never updated through a store, so on an old device it stays at the Chromium it shipped with. */
internal const val AOSP_WEBVIEW_PACKAGE = "com.android.webview"

/**
 * Chromium milestone above which a pre-O WebView is treated as maintained rather than abandoned.
 *
 * The boundary itself is not measured — what is measured is either side of it. A 2016 Chromium 51
 * on Android 7.0 cannot restart in-process (see [chromiumRestartIsFatal]); a Fire TV stick on the
 * same API level with Chromium 118 restarted 23 times without a single loss. Anything that far
 * behind is a system image nobody has touched in years, so the exact cut only decides how a device
 * between the two is treated, and this one errs towards leaving current behaviour alone.
 */
private const val MODERN_CHROMIUM_MILESTONE = 60

private val KNOWN_WEBVIEW_PROVIDERS = listOf(
    "com.amazon.webview",
    "com.google.android.webview",
    AOSP_WEBVIEW_PACKAGE,
    "com.google.android.webview.beta",
    "com.google.android.webview.dev",
    "com.google.android.webview.canary",
)

/** Which WebView implementation this device is using, and how old it is. */
internal data class SystemWebViewProvider(
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long = 0L,
) {
    /**
     * Leading integer of the version name, which is the Chromium milestone for every provider seen
     * in the field: `51.0.2704.91`, `151.0.7922.199`, `39 (20181207-arm)` and Amazon's
     * `118.amazon-webview-v118-5993-tv.5993.155.160` all lead with it.
     */
    val majorVersion: Int? get() = versionName?.takeWhile(Char::isDigit)?.toIntOrNull()
}

/**
 * Reads the selected WebView provider from package metadata.
 *
 * `WebView.getCurrentWebViewPackage()` looks like a metadata query but WebView 150 can fully
 * initialize Chromium and launch a sandbox renderer; on a low-memory TV that diagnostic call alone
 * consumed ~122 MB before any resolver existed. Android's selected-provider setting plus the
 * package manager answers the same question without starting a browser, so callers that run before
 * (or instead of) any WebView can use it freely.
 */
internal fun readSystemWebViewProvider(context: Context?): SystemWebViewProvider {
    val configuredPackage = context?.let {
        runCatching { Settings.Global.getString(it.contentResolver, "webview_provider") }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }
    @Suppress("DEPRECATION")
    val pkg = context?.let { app ->
        buildList {
            configuredPackage?.let(::add)
            addAll(KNOWN_WEBVIEW_PROVIDERS)
        }.distinct().firstNotNullOfOrNull { candidate ->
            runCatching { app.packageManager.getPackageInfo(candidate, 0) }
                .getOrNull()
                ?.takeIf { it.applicationInfo?.enabled != false }
        }
    }
    return SystemWebViewProvider(
        packageName = pkg?.packageName ?: configuredPackage,
        versionName = pkg?.versionName,
        versionCode = pkg?.let(::providerVersionCode) ?: 0L,
    )
}

@Suppress("DEPRECATION")
private fun providerVersionCode(info: android.content.pm.PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

/**
 * Whether standing Chromium back up in a process that has already let it go is likely to kill that
 * process.
 *
 * Below API 26 the renderer is not a separate process ([rendererCrashIsFatal]), so everything
 * Chromium does happens in ours — including whatever it does *not* fully undo when the last WebView
 * is destroyed. Two independent reports from one Android 7.0 box (`com.android.webview` 51.0.2704.91,
 * app 0.1.75 and 0.1.76) show what that costs: of 44 pipe WebViews built across them, every one of
 * the 6 built after the process had gone more than ~100 seconds with no WebView alive killed the
 * process, and only 1 of the other 38 did (Fisher exact p ≈ 1e-6). The first build in a fresh
 * process never failed, 10 for 10, and a rebuild seconds after a teardown was almost always fine —
 * so it is the *gap*, not the building, that is fatal. There is no Java trace either way: it is
 * Chromium's SIGSEGV, so it arrives as a dead process and a "has stopped" dialog.
 *
 * Callers use this to keep a resolver WebView resident rather than pay a rebuild they cannot
 * survive. It deliberately does not fire on a pre-O device whose WebView is merely old but still
 * maintained — a Fire TV stick on API 25 with Chromium 118 rebuilt 23 times without incident, so
 * making it hold a browser through every episode would cost memory for nothing.
 */
internal fun chromiumRestartIsFatal(
    sdkInt: Int,
    providerPackage: String?,
    majorVersion: Int?,
): Boolean = rendererCrashIsFatal(sdkInt) &&
    providerPackage == AOSP_WEBVIEW_PACKAGE &&
    (majorVersion == null || majorVersion < MODERN_CHROMIUM_MILESTONE)

/** [chromiumRestartIsFatal] for the running device. Reads package metadata, never starts Chromium. */
internal fun chromiumRestartIsFatal(context: Context?): Boolean {
    val provider = readSystemWebViewProvider(context)
    return chromiumRestartIsFatal(
        sdkInt = Build.VERSION.SDK_INT,
        providerPackage = provider.packageName,
        majorVersion = provider.majorVersion,
    )
}
