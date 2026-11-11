package com.anilili.playback

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri

/**
 * Naming the video players installed on this device, so one of them can be chosen for good.
 *
 * The hand-off itself has never needed this: [launchExternalPlayer] fires an implicit `ACTION_VIEW`
 * and the system decides who receives it. What that leaves the viewer with is the system chooser,
 * and on a television the chooser is the weakest part of the whole path — it is remembered as a
 * per-app default the moment "Always" is pressed, it cannot be un-remembered from inside this app,
 * and several TV builds render it badly or not at all. A viewer who wants every episode to open in
 * one particular player has no way to say so.
 *
 * So the players are enumerated here and the chosen one is launched by package, which skips the
 * chooser entirely. This is the part that needs `<queries>` in the manifest: `queryIntentActivities`
 * is subject to Android 11's package-visibility filtering even though `startActivity` is not, so
 * without the declaration this list is empty on every modern device.
 */
internal data class ExternalPlayerTarget(
    val packageName: String,
    val label: String,
    /** Set for players known to be built for a remote rather than a touchscreen. */
    val builtForTv: Boolean,
)

/**
 * The players this app knows something about beyond their name.
 *
 * Everything installed is offered whether or not it appears here; this only decides what gets
 * called out as a television player, which is worth doing because the difference is invisible in
 * a chooser and enormous in use — a phone player on a TV is a D-pad fighting a touch UI.
 *
 * [MPVNOVA_PACKAGE] is the reason this table exists. It is an Android-TV-first fork of mpv-android
 * and reads the same extras this app already sends (`title`, `position`, `subs`, `subs.name`,
 * `subs.enable`, `subtitles_location`), so nothing about the request needed changing for it —
 * it only needed to be selectable.
 */
internal const val MPVNOVA_PACKAGE = "app.mpvnova.player"

private val TV_PLAYER_PACKAGES = setOf(
    MPVNOVA_PACKAGE,
    "org.videolan.vlc",
    "com.mxtech.videoplayer.tv",
    "net.gtvbox.videoplayer", // Vimu
    "net.gtvbox.vimuhd",
)

/**
 * Packages that answer a media intent in order to report that nothing can play it.
 *
 * AOSP television images ship `frameworkpackagestubs`, which matches the wildcard video type at
 * priority -1 and exists only to raise "no app can perform this action" — it was sitting in the
 * resolver output beside mpvNova on the television image this was checked against. Listing it as
 * a player would be a row that leads nowhere, which is worse than a short list.
 */
private val STUB_PACKAGES = setOf(
    "com.android.tv.frameworkpackagestubs",
    "com.android.fallback",
)

/**
 * The address and type the probe below asks about.
 *
 * Both are stand-ins for a real hand-off rather than anything that gets fetched, so they only have
 * to carry its shape: a plain `http` address — every proxied stream is one, since the loopback
 * server has no certificate — and a type in the `video/` family, which is what
 * [externalPlayerMimeType] answers for a manifest and for anything it does not recognise. That is
 * nearly every episode, whether it comes straight from the CDN or through the proxy.
 */
internal const val EXTERNAL_PLAYER_PROBE_URL = "http://127.0.0.1/video"
internal const val EXTERNAL_PLAYER_PROBE_TYPE = "video/*"

/**
 * A stand-in for a real hand-off, used to ask the system who could receive one.
 *
 * The path deliberately carries no file extension, because a proxied URL is
 * `http://127.0.0.1:PORT/<token>` and has none either. Players that match only on an extension —
 * mpvNova has such a filter alongside its type one — must be listed on the strength of the type
 * alone, or the picker would offer a set of players wider than the set that actually receives
 * anything.
 */
internal fun externalPlayerProbeIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(Uri.parse(EXTERNAL_PLAYER_PROBE_URL), EXTERNAL_PLAYER_PROBE_TYPE)
    addCategory(Intent.CATEGORY_DEFAULT)
}

/** Every installed app that would accept an episode, best-first for this device. */
internal fun externalPlayerTargets(context: Context, isTv: Boolean): List<ExternalPlayerTarget> {
    val packageManager = context.packageManager
    val resolved = runCatching {
        packageManager.queryIntentActivities(externalPlayerProbeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
    }.getOrDefault(emptyList())
    val targets = resolved
        .mapNotNull { info -> info.toTarget(packageManager) }
        // One package can expose several matching activities; the picker names apps, not activities.
        .distinctBy(ExternalPlayerTarget::packageName)
        .filterNot { it.packageName == context.packageName || it.packageName in STUB_PACKAGES }
    return orderExternalPlayerTargets(targets, isTv)
}

private fun ResolveInfo.toTarget(packageManager: PackageManager): ExternalPlayerTarget? {
    val packageName = activityInfo?.packageName?.takeIf(String::isNotBlank) ?: return null
    val label = runCatching { loadLabel(packageManager).toString() }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: packageName
    return ExternalPlayerTarget(packageName, label, builtForTv = packageName in TV_PLAYER_PACKAGES)
}

/**
 * Television players first on a television, then alphabetically.
 *
 * Resolver order is priority then whatever the package manager happens to return, which on a TV
 * routinely puts a phone-only player above one built for the remote in the viewer's hand. Ordering
 * by hand is only a starting position — the choice is still the viewer's — but it is the difference
 * between the right answer being first and it being fourth.
 */
internal fun orderExternalPlayerTargets(
    targets: List<ExternalPlayerTarget>,
    isTv: Boolean,
): List<ExternalPlayerTarget> = targets.sortedWith(
    compareBy<ExternalPlayerTarget> { if (isTv && it.builtForTv) 0 else 1 }
        .thenBy { it.label.lowercase() },
)

/**
 * The name [packageName] shows under its icon, or null when it is not installed any more.
 *
 * Read separately from [externalPlayerTargets] so a settings row can name the current choice
 * without enumerating every player first — and so an uninstalled one reads as "Ask every time"
 * rather than as a package name the viewer has no way to recognise.
 */
internal fun externalPlayerLabel(context: Context, packageName: String): String? {
    if (packageName.isBlank()) return null
    val packageManager = context.packageManager
    return runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()?.takeIf(String::isNotBlank)
}

/**
 * Whether [packageName] is still installed and still willing to take [intent].
 *
 * Checked before the package is pinned onto a launch because an uninstalled choice would otherwise
 * turn into `ActivityNotFoundException` and a "no other video player is installed" message on a
 * device with several — the chooser is the better answer there, not an error.
 */
internal fun canReceiveExternalPlayback(
    context: Context,
    intent: Intent,
    packageName: String,
): Boolean = runCatching {
    context.packageManager
        .queryIntentActivities(Intent(intent).setPackage(packageName), PackageManager.MATCH_DEFAULT_ONLY)
        .isNotEmpty()
}.getOrDefault(false)
