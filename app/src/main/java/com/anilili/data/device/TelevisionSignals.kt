package com.anilili.data.device

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.anilili.data.settings.FormFactorPreference
import com.anilili.data.settings.SettingsStore

/**
 * Whether the app is running on a television, for everything that has to know.
 *
 * This lives in `data` rather than beside `AppDeviceProfile` because the layout is not the only
 * thing that turns on the answer. The playback service sizes its buffers by it, the WebView
 * renderer policy kills a process by it, the image caches are sized by it and — the one with teeth
 * — the updater picks which APK to download by it. Each of those used to ask the question its own
 * way, straight off `UiModeManager`, and on a device that answers badly they all got it wrong
 * together.
 *
 * Which is not hypothetical: an Amlogic p383 on Android 5.1.1 reported `UI_MODE_TYPE_NORMAL` while
 * plugged into a television at 1920x1080, so the app gave it the tablet layout at 1280dp, phone
 * playback buffers, and would have updated it to the phone build.
 */

/**
 * Which fact made a device a television, in the order [televisionSignal] trusts them.
 *
 * Carried on the device profile and written into every diagnostics bundle. A report that says
 * "television" without saying why is unfalsifiable, and the report that prompted all of this could
 * not distinguish "the device never claimed to be a television" from "the app ignored what it
 * claimed" — the bundle recorded the ui mode and nothing else that fed the decision.
 */
enum class TelevisionSignal { CHOSEN, UI_MODE, LEANBACK_FEATURE, TELEVISION_FEATURE, LEANBACK_LAUNCHER, NO_TOUCHSCREEN }

/**
 * What a device says about itself besides its ui mode, for the boxes whose ui mode is a lie.
 *
 * `Configuration.UI_MODE_TYPE_TELEVISION` is the answer wherever there is one, and on anything
 * Google certified there always is. Cheap set-top boxes are the exception: they ship a stock
 * *handheld* AOSP image with a leanback launcher dropped on top, and that image reports
 * `UI_MODE_TYPE_NORMAL` for the life of the device.
 *
 * None of the rest is conclusive on its own, which is why they are ordered rather than counted:
 * each is a thing a television has and a tablet does not, so the first that answers is the most
 * reliable one that device has to offer.
 */
data class TelevisionSignals(
    /** `android.software.leanback`, declared by every Android TV system image. */
    val hasLeanbackFeature: Boolean = false,
    /** `android.hardware.type.television`, the pre-leanback spelling some old boxes still carry. */
    val hasTelevisionFeature: Boolean = false,
    /**
     * `UiModeManager.getCurrentModeType`. Usually the same answer as the configuration's, but it is
     * the *device's* mode rather than the one baked into the resources this process resolved
     * against, so a repackaged or compat-scaled build can disagree with itself here.
     */
    val systemUiModeIsTelevision: Boolean = false,
    /**
     * The app was started from a `LEANBACK_LAUNCHER` intent — the device's home screen is a
     * television launcher. Sticky, because it is a fact about the device and not about the launch:
     * a later start from a notification or a Watch Next tile carries no category at all, and
     * letting that flip the whole interface back to tablet mid-use would be worse than never
     * having detected the television.
     */
    val launchedFromLeanbackLauncher: Boolean = false,
    /**
     * Runs the fallback in reverse: rather than a television declaring itself, a handheld declares
     * a touchscreen and this device has none.
     */
    val hasTouchscreen: Boolean = true,
)

/**
 * The first signal that says "television", or null for a handheld.
 *
 * The viewer's own choice outranks every device signal in both directions: [FormFactorPreference
 * .HANDHELD] is what a large tablet that trips one of the fallbacks needs, and it has to win even
 * over `UI_MODE_TYPE_TELEVISION` or the setting would be a suggestion.
 */
fun televisionSignal(
    uiModeType: Int,
    widthDp: Int,
    signals: TelevisionSignals,
    preference: FormFactorPreference,
): TelevisionSignal? = when {
    preference == FormFactorPreference.HANDHELD -> null
    preference == FormFactorPreference.TELEVISION -> TelevisionSignal.CHOSEN
    uiModeType == Configuration.UI_MODE_TYPE_TELEVISION -> TelevisionSignal.UI_MODE
    signals.systemUiModeIsTelevision -> TelevisionSignal.UI_MODE
    signals.hasLeanbackFeature -> TelevisionSignal.LEANBACK_FEATURE
    signals.hasTelevisionFeature -> TelevisionSignal.TELEVISION_FEATURE
    signals.launchedFromLeanbackLauncher -> TelevisionSignal.LEANBACK_LAUNCHER
    // A touchscreen is the one thing every phone and tablet has and no television does, so its
    // absence is a strong signal — but it is also true of a headless test rig and of a desktop
    // Android container, so it only counts on a screen wide enough that the ten-foot layout is
    // still the better of the two answers in the cases where this guesses wrong.
    !signals.hasTouchscreen && widthDp >= TELEVISION_MIN_WIDTH_DP -> TelevisionSignal.NO_TOUCHSCREEN
    else -> null
}

/**
 * The whole decision, for callers outside a composition.
 *
 * Reads the persisted signals through [SettingsStore]'s current values rather than by suspending:
 * anything that asks this on the startup path cannot wait for a disk read, and the device signals
 * — which need none — are what carry the decision there.
 */
fun isTelevision(context: Context): Boolean {
    val configuration = context.applicationContext.resources.configuration
    val signals = readTelevisionSignals(context).copy(
        launchedFromLeanbackLauncher = SettingsStore.leanbackLauncherSeen.value,
    )
    return televisionSignal(
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK,
        configuration.screenWidthDp,
        signals,
        SettingsStore.formFactorPreference.value,
    ) != null
}

/**
 * The device's own answers — everything but the remembered launcher observation, which is settings.
 *
 * Every failure in here is a device answering the question badly, which is the situation this whole
 * file is about; a thrown exception must not become one more way to end up in the tablet layout.
 */
fun readTelevisionSignals(context: Context): TelevisionSignals = runCatching {
    val app = context.applicationContext
    val packages = app.packageManager
    val uiModeManager = app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    TelevisionSignals(
        hasLeanbackFeature = packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packages.hasSystemFeature(FEATURE_LEANBACK_ONLY),
        hasTelevisionFeature = packages.hasSystemFeature(FEATURE_TELEVISION),
        systemUiModeIsTelevision =
            uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION,
        hasTouchscreen = app.resources.configuration.touchscreen != Configuration.TOUCHSCREEN_NOTOUCH,
    )
}.getOrElse { TelevisionSignals() }

/**
 * Whether the intent that started the app came from a television home screen.
 *
 * The app publishes both a `LAUNCHER` and a `LEANBACK_LAUNCHER` entry, so which category arrives
 * says which of the two home screens the device actually runs.
 */
fun isLeanbackLaunch(categories: Set<String>?): Boolean =
    categories.orEmpty().contains(Intent.CATEGORY_LEANBACK_LAUNCHER)

/** `PackageManager.FEATURE_LEANBACK_ONLY`, spelled out because the constant needs API 26. */
private const val FEATURE_LEANBACK_ONLY = "android.software.leanback_only"

/**
 * `PackageManager.FEATURE_TELEVISION`, spelled out because the constant is deprecated — the devices
 * that still declare it are exactly the old ones this fallback is for, so it is read anyway.
 */
private const val FEATURE_TELEVISION = "android.hardware.type.television"

/**
 * The narrowest screen the touchscreen fallback may call a television, which is also where a
 * handheld stops being a phone. Deliberately the same number: below it, the app has a layout built
 * for one hand, and nothing that is not a certified television should be taken out of it on a guess.
 */
const val TELEVISION_MIN_WIDTH_DP = 600
