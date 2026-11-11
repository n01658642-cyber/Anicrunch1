package com.anilili.ui.components

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.anilili.data.settings.ClockFormat
import java.time.format.DateTimeFormatter

// One instance of each rather than one per call site: a DateTimeFormatter is immutable and thread
// safe, and compiling the pattern is the expensive half — the television's clock re-formats every
// thirty seconds for as long as the app is open.
private val TwelveHourClock: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val TwentyFourHourClock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * How to write a bare time of day, resolved once at the composition root.
 *
 * A local rather than a `collectAsState` per call site because Schedule prints a time for every
 * slot *and* every card in it, and the system half of the answer has to be watched rather than
 * read — see [rememberSystemUses24Hour]. Resolving it per row would put a collector and a binder
 * registration on each one, which is the shape of per-row work that has already cost this app a
 * television in a list.
 *
 * The default is only reached by previews and tests; the root always provides the real answer.
 */
val LocalTimeOfDayFormatter = staticCompositionLocalOf { TwentyFourHourClock }

/** What the device's own clock setting says, for a screen that has to name it. */
val LocalSystemUses24Hour = staticCompositionLocalOf { true }

fun timeOfDayFormatter(format: ClockFormat, systemUses24Hour: Boolean): DateTimeFormatter =
    if (format.uses24Hour(systemUses24Hour)) TwentyFourHourClock else TwelveHourClock

/**
 * Whether the device's own clock setting says 24 hours, kept current while the app is open.
 *
 * That choice is a `Settings.System` row and not part of a `Configuration`, so nothing recomposes
 * when it changes. Read once and remembered, [ClockFormat.SYSTEM] would keep the old format until
 * the next launch — and anyone who flipped their television's clock to see this setting work would
 * read that as the setting being broken. So it is watched the way the platform's own `TextClock`
 * watches it. Called once, at the root that provides [LocalTimeOfDayFormatter].
 */
@Composable
fun rememberSystemUses24Hour(): Boolean {
    val context = LocalContext.current
    var uses24Hour by remember(context) { mutableStateOf(DateFormat.is24HourFormat(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                uses24Hour = DateFormat.is24HourFormat(context)
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.TIME_12_24),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return uses24Hour
}
