package com.anilili.data.settings

import com.anilili.data.remote.AppDns
import com.anilili.data.remote.DohProvider
import com.anilili.data.remote.normalizeSubtitleLanguages
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.anilili.data.AppGraph
import com.anilili.data.device.isTelevision
import com.anilili.diagnostics.CrashReporter
import java.io.IOException
import java.util.Locale
import com.anilili.data.model.WatchedFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.anilili.diagnostics.DiagnosticsLog
import okio.buffer
import okio.source
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

enum class MenuLanguage(val storedValue: String) {
    SYSTEM("system"),
    ENGLISH("en"),
    SPANISH("es");

    fun usesSpanish(systemLanguage: String = Locale.getDefault().language): Boolean =
        this == SPANISH || (this == SYSTEM && systemLanguage.equals("es", ignoreCase = true))

    companion object {
        fun fromStored(value: String?): MenuLanguage = entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

/**
 * How a time of day is written wherever the app prints one — the television's top-bar clock and
 * Schedule's air times.
 *
 * The two used to disagree and neither asked: the clock was a hard-coded `HH:mm` and Schedule a
 * hard-coded `h:mm a`, so one app showed 18:09 in the corner and 6:09 PM two screens away.
 * [SYSTEM] is the default and the right answer for nearly everyone, because a device set to a
 * 12-hour clock has already said so once; the explicit choices are for the televisions that offer
 * no such setting of their own, and for anyone whose box reports the wrong one.
 */
enum class ClockFormat(val storedValue: String, val label: String) {
    SYSTEM("system", "System"),
    TWELVE_HOUR("12", "12-hour"),
    TWENTY_FOUR_HOUR("24", "24-hour");

    /**
     * @param systemUses24Hour what the device's own clock setting says. Passed in rather than read
     *   here so this stays a plain value with no Context to hold: only [SYSTEM] consults it.
     */
    fun uses24Hour(systemUses24Hour: Boolean): Boolean = when (this) {
        SYSTEM -> systemUses24Hour
        TWELVE_HOUR -> false
        TWENTY_FOUR_HOUR -> true
    }

    companion object {
        fun fromStored(value: String?): ClockFormat =
            entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

/**
 * The status the Discord session announces while an episode is on the card.
 *
 * [storedValue] is also Discord's own wire value for it. Invisible is deliberately absent: Discord
 * hides an invisible user's activities, so it would publish a card nobody can see.
 */
enum class DiscordStatus(val storedValue: String, val label: String) {
    ONLINE("online", "Online"),
    IDLE("idle", "Idle"),
    DO_NOT_DISTURB("dnd", "Do Not Disturb");

    companion object {
        fun fromStored(value: String?): DiscordStatus =
            entries.firstOrNull { it.storedValue == value } ?: ONLINE
    }
}

/** How an episode list is drawn: rich rows with stills, or compact number chips. */
enum class EpisodeLayout(val storedValue: String) {
    LIST("list"),
    GRID("grid");

    fun toggled(): EpisodeLayout = if (this == LIST) GRID else LIST

    companion object {
        fun fromStored(value: String?): EpisodeLayout =
            entries.firstOrNull { it.storedValue == value } ?: LIST
    }
}

/**
 * How the detail page's Related tab draws its shelves: rows with cover art, or bare titles with
 * their format, year and episode count — a franchise of forty entries reads faster as text.
 */
enum class RelatedLayout(val storedValue: String) {
    LIST("list"),
    TITLES("titles");

    fun toggled(): RelatedLayout = if (this == LIST) TITLES else LIST

    companion object {
        fun fromStored(value: String?): RelatedLayout =
            entries.firstOrNull { it.storedValue == value } ?: LIST
    }
}

/**
 * Which end of a season the detail page's episode list starts from. Oldest first reads a series in
 * order; newest first puts this week's episode at the top of a long-runner.
 */
enum class EpisodeOrder(val storedValue: String) {
    OLDEST_FIRST("oldest"),
    NEWEST_FIRST("newest");

    fun toggled(): EpisodeOrder = if (this == OLDEST_FIRST) NEWEST_FIRST else OLDEST_FIRST

    companion object {
        fun fromStored(value: String?): EpisodeOrder =
            entries.firstOrNull { it.storedValue == value } ?: OLDEST_FIRST
    }
}

/**
 * How large the whole interface is drawn, as a multiplier on the display density.
 *
 * A television is viewed from across a room by whoever happens to be in it, so the right size is
 * the viewer's to pick rather than ours to guess. The multiplier reaches every dp and sp in the
 * app at once — cards, the hero, buttons, badges, text — because scaling a handful of named tokens
 * would have grown the cards and left the hero's own hard-coded sizes behind, which reads as a
 * broken layout rather than a smaller one.
 *
 * The range is deliberately narrow. [LARGE] is a clear step up without pushing the hero past what
 * a 720p panel can show, and [SMALL] stops short of shrinking focus targets below what a remote
 * can comfortably land on.
 */
enum class UiScale(
    val storedValue: String,
    val label: String,
    val factor: Float,
) {
    SMALL("small", "Small", 0.9f),
    MEDIUM("medium", "Medium", 1f),
    LARGE("large", "Large", 1.15f);

    companion object {
        fun fromStored(value: String?): UiScale =
            entries.firstOrNull { it.storedValue == value } ?: MEDIUM
    }
}

/**
 * Which layout to draw, when the device's own answer is wrong.
 *
 * [AUTOMATIC] is every install that has never opened the setting: `resolveAppDeviceProfile` reads
 * the ui mode and, where that says nothing, a short list of television signals. The other two exist
 * because that list cannot be made exhaustive. A cheap set-top box can ship a stock handheld AOSP
 * image — `UI_MODE_TYPE_NORMAL`, no leanback feature, a touchscreen it does not physically have —
 * and be indistinguishable from a large tablet while sitting under a television. One of those was
 * reported showing the tablet layout on a 1080p panel, and there was nothing its owner could do
 * about it. This is that something.
 *
 * It cuts both ways deliberately: [HANDHELD] is the escape for a tablet that trips one of the
 * fallbacks and gets a ten-foot interface it does not want.
 */
enum class FormFactorPreference(val storedValue: String, val label: String) {
    AUTOMATIC("automatic", "Automatic"),
    TELEVISION("television", "TV"),
    HANDHELD("handheld", "Phone or tablet");

    companion object {
        fun fromStored(value: String?): FormFactorPreference =
            entries.firstOrNull { it.storedValue == value } ?: AUTOMATIC
    }
}

/**
 * Which dub to take when a stream carries several, as many multi-audio sources do.
 *
 * [AUTOMATIC] is the default and changes nothing: the player keeps choosing for itself, so a
 * viewer who never opens this setting sees exactly the behaviour they had. Anything else is a
 * standing request, honoured on every stream that happens to carry that language and quietly
 * ignored on the ones that do not.
 *
 * The codes are the three-letter ones these manifests actually declare. Media3 normalises both
 * two- and three-letter forms before matching, so either would work; using the vocabulary the
 * files are written in is simply the least surprising thing to read here.
 */
enum class PreferredAudioLanguage(
    val storedValue: String,
    val label: String,
    /**
     * The two-letter form of the same language, because that is what Media3 hands back on a track
     * it has already parsed — the matching media3 does for itself is by code, but reading a chosen
     * track's language to see whether it answers this preference has to be done in its vocabulary.
     */
    val shortCode: String = "",
) {
    AUTOMATIC("", "Automatic"),
    JAPANESE("jpn", "Japanese", "ja"),
    ENGLISH("eng", "English", "en"),
    HINDI("hin", "Hindi", "hi"),
    TAMIL("tam", "Tamil", "ta"),
    TELUGU("tel", "Telugu", "te"),
    BENGALI("ben", "Bengali", "bn"),
    MALAYALAM("mal", "Malayalam", "ml"),
    KANNADA("kan", "Kannada", "kn"),
    MARATHI("mar", "Marathi", "mr"),
    KOREAN("kor", "Korean", "ko"),
    CHINESE("zho", "Chinese", "zh"),
    SPANISH("spa", "Spanish", "es"),
    PORTUGUESE("por", "Portuguese", "pt"),
    ARABIC("ara", "Arabic", "ar"),
    FRENCH("fra", "French", "fr"),
    GERMAN("deu", "German", "de");

    companion object {
        fun fromStored(value: String?): PreferredAudioLanguage =
            entries.firstOrNull { it.storedValue == value } ?: AUTOMATIC
    }
}

/**
 * Which subtitle track to take when a stream carries several, the way [PreferredAudioLanguage]
 * picks a dub.
 *
 * This is about the tracks a stream already has — the ones the provider attaches and the ones
 * baked into a manifest — and not about [SubtitleSearchLanguage], which is the language the
 * OpenSubtitles *search* asks for. A viewer can reasonably want both: Spanish tracks when a source
 * offers them, and Spanish files when it does not.
 *
 * [AUTOMATIC] keeps the historic behaviour, which is that the first track wins. Anything else is a
 * standing preference and not an override: it is honoured on streams that declare that language,
 * silently ignored on the ones that do not, and the player's own subtitle button still wins for
 * the episode the viewer uses it on. It does not turn subtitles on — "Show subtitles by default"
 * stays the switch for that.
 *
 * Three-letter codes, matching [PreferredAudioLanguage], because that is the vocabulary media
 * tracks are written in. The two-letter ones on [SubtitleSearchLanguage] are the OpenSubtitles
 * API's, and the two must not be confused.
 */
enum class PreferredSubtitleLanguage(
    val storedValue: String,
    val label: String,
) {
    AUTOMATIC("", "Automatic"),
    ENGLISH("eng", "English"),
    JAPANESE("jpn", "Japanese"),
    SPANISH("spa", "Spanish"),
    PORTUGUESE("por", "Portuguese"),
    ARABIC("ara", "Arabic"),
    FRENCH("fra", "French"),
    GERMAN("deu", "German"),
    ITALIAN("ita", "Italian"),
    TURKISH("tur", "Turkish"),
    RUSSIAN("rus", "Russian"),
    POLISH("pol", "Polish"),
    INDONESIAN("ind", "Indonesian"),
    HINDI("hin", "Hindi"),
    CHINESE("zho", "Chinese"),
    KOREAN("kor", "Korean");

    companion object {
        fun fromStored(value: String?): PreferredSubtitleLanguage =
            entries.firstOrNull { it.storedValue == value } ?: AUTOMATIC
    }
}

/**
 * Which language the in-player OpenSubtitles search asks for.
 *
 * Two-letter codes, because that is what the OpenSubtitles API takes — unlike the three-letter
 * ones [PreferredAudioLanguage] uses for media tracks, which is a different vocabulary for a
 * different job and must not be confused with this one.
 *
 * [FOLLOW_DEVICE] is the historic behaviour and stays the default: derive it from the system
 * locale each time. The list is the common cases only; the panel's own field still accepts
 * anything, including several codes at once, and a value set there that is not listed here is
 * shown as itself rather than being forced back into this list.
 */
enum class SubtitleSearchLanguage(
    val storedValue: String,
    val label: String,
) {
    FOLLOW_DEVICE("", "Follow device"),
    ARABIC("ar", "Arabic"),
    ENGLISH("en", "English"),
    SPANISH("es", "Spanish"),
    PORTUGUESE("pt", "Portuguese"),
    FRENCH("fr", "French"),
    GERMAN("de", "German"),
    ITALIAN("it", "Italian"),
    TURKISH("tr", "Turkish"),
    RUSSIAN("ru", "Russian"),
    POLISH("pl", "Polish"),
    INDONESIAN("id", "Indonesian"),
    HINDI("hi", "Hindi"),
    CHINESE("zh", "Chinese"),
    KOREAN("ko", "Korean"),
    JAPANESE("ja", "Japanese");

    companion object {
        fun fromStored(value: String?): SubtitleSearchLanguage? =
            entries.firstOrNull { it.storedValue == value.orEmpty() }

        /** What to show for a stored value, including one typed in the player's own field. */
        fun describe(value: String): String =
            fromStored(value)?.label ?: value.uppercase()
    }
}

/**
 * Languages a viewer wants offered in the server picker's language row.
 *
 * This is deliberately separate from audio/subtitle track preference: those choose a track after
 * a server opens, while this list keeps the server picker compact and helps choose the server in
 * the first place. An empty selection means "show every language discovered", preserving the
 * picker that existed before this setting was added.
 */
enum class SourceFilterLanguage(
    val storedValue: String,
    val label: String,
    val tag: String,
) {
    ARABIC("ara", "Arabic", "AR"),
    BENGALI("ben", "Bengali", "BN"),
    CHINESE("zho", "Chinese", "ZH"),
    DUTCH("nld", "Dutch", "NL"),
    ENGLISH("eng", "English", "EN"),
    FRENCH("fra", "French", "FR"),
    GERMAN("deu", "German", "DE"),
    HINDI("hin", "Hindi", "HI"),
    INDONESIAN("ind", "Indonesian", "ID"),
    ITALIAN("ita", "Italian", "IT"),
    JAPANESE("jpn", "Japanese", "JA"),
    KANNADA("kan", "Kannada", "KN"),
    KOREAN("kor", "Korean", "KO"),
    MALAYALAM("mal", "Malayalam", "ML"),
    MARATHI("mar", "Marathi", "MR"),
    PERSIAN("fas", "Persian", "FA"),
    POLISH("pol", "Polish", "PL"),
    PORTUGUESE("por", "Portuguese", "PT"),
    RUSSIAN("rus", "Russian", "RU"),
    SPANISH("spa", "Spanish", "ES"),
    TAGALOG("tgl", "Tagalog", "TL"),
    TAMIL("tam", "Tamil", "TA"),
    TELUGU("tel", "Telugu", "TE"),
    THAI("tha", "Thai", "TH"),
    TURKISH("tur", "Turkish", "TR"),
    UKRAINIAN("ukr", "Ukrainian", "UK"),
    VIETNAMESE("vie", "Vietnamese", "VI");

    companion object {
        fun fromStored(value: String): SourceFilterLanguage? =
            entries.firstOrNull { it.storedValue == value }

        fun fromLabel(value: String): SourceFilterLanguage? =
            entries.firstOrNull { it.label.equals(value, ignoreCase = true) }
    }
}

internal fun encodeSourceFilterLanguages(languages: Set<SourceFilterLanguage>): String =
    SourceFilterLanguage.entries.filter { it in languages }.joinToString(",") { it.storedValue }

internal fun decodeSourceFilterLanguages(value: String?): Set<SourceFilterLanguage> =
    value.orEmpty().split(',')
        .mapNotNull { SourceFilterLanguage.fromStored(it.trim()) }
        .toSet()

/**
 * Everything a poster card draws besides the artwork itself, as things a viewer can switch off.
 *
 * Every one of these was added because it answers a question somebody has about a title — is there
 * a dub, is it any good, how far in am I — and none of them answers a question *everybody* has. A
 * viewer who never watches dubbed anime reads "DUB" on half the catalogue as noise, and one who
 * deliberately avoids scores before watching is being told one on every row. The artwork is the
 * part nobody wants removed, so it is the part that is not listed here.
 *
 * The entries are deliberately coarse. [SCORE] covers the average rating and your own score in the
 * same switch because they occupy the same corner and mean the same kind of thing; splitting them
 * would give the setting eleven rows to make the same decision with.
 */
enum class PosterElement(
    val storedValue: String,
    val label: String,
    val description: String,
) {
    SCORE("score", "Score", "The star rating, and your own score in My library"),
    DUB("dub", "Dub marker", "The DUB tag on titles with an English dub"),
    NEW_EPISODES("new_episodes", "New episodes", "The \"3 NEW\" count on a library title that has aired past you"),
    PROGRESS("progress", "Episode progress", "How far into a library title you are, as 5/12"),
    ADULT("adult", "Adult marker", "The 18+ tag on adult titles"),
    TITLE("title", "Title", "The name under the artwork"),
    DETAILS("details", "Details line", "Format, year and episode count under the title");

    companion object {
        fun fromStored(value: String): PosterElement? =
            entries.firstOrNull { it.storedValue == value }
    }
}

/**
 * Stored as the elements that are *off*, for the reason [SettingsStore.disabledProviders] is: this
 * list will be appended to, and a stored "what is on" written by an older release would silently
 * hide every element added after it.
 */
internal fun encodeHiddenPosterElements(elements: Set<PosterElement>): String =
    PosterElement.entries.filter { it in elements }.joinToString(",") { it.storedValue }

internal fun decodeHiddenPosterElements(value: String?): Set<PosterElement> =
    value.orEmpty().split(',')
        .mapNotNull { PosterElement.fromStored(it.trim()) }
        .toSet()

enum class DefaultQuality(
    val storedValue: String,
    val label: String,
    val maxHeight: Int?,
) {
    AUTO("auto", "Auto", null),
    HIGHEST("highest", "Highest", null),
    P1080("1080", "1080p", 1080),
    P720("720", "720p", 720),
    P480("480", "480p", 480),
    P360("360", "360p · Data Saver", 360),
    P240("240", "240p", 240),
    P144("144", "144p", 144);

    /** Best matching height from [heights], or null to leave adaptive selection alone. */
    fun pickHeight(heights: List<Int>): Int? = when (this) {
        AUTO -> null
        HIGHEST -> heights.maxOrNull()
        // Closest height without going over. If every real rendition is above the requested cap,
        // use the lowest one so playback still starts instead of rejecting the source.
        else -> {
            val target = requireNotNull(maxHeight)
            heights.filter { it <= target }.maxOrNull() ?: heights.minOrNull()
        }
    }

    companion object {
        fun fromStored(value: String?): DefaultQuality =
            entries.firstOrNull { it.storedValue == value } ?: HIGHEST
    }
}

/** How native video is sized inside the player window. */
enum class PlayerContentScale(
    val storedValue: String,
    val label: String,
    /** Forced frame ratio, or null to use the stream's native size (Fit). */
    val forcedRatio: Float? = null,
) {
    FIT("fit", "Auto (native)", null),
    RATIO_16_9("16_9", "16:9", 16f / 9f),
    RATIO_4_3("4_3", "4:3", 4f / 3f),
    RATIO_1_1("1_1", "1:1", 1f),
    CROP("crop", "Crop (zoom)", null),
    FILL("fill", "Stretch", null);

    companion object {
        fun fromStored(value: String?): PlayerContentScale =
            entries.firstOrNull { it.storedValue == value } ?: FIT
    }
}

/**
 * How much of an episode the player holds ahead of the picture.
 *
 * The device policy behind [AUTOMATIC] is tuned for memory, not for the network, and it is
 * deliberately meanest on the devices with the worst connections: a television is assumed to be a
 * cheap box and gets the smallest buffer of all. That is the wrong trade for someone on distant
 * Wi-Fi or a slow server, where the bandwidth is there and only the steadiness is missing — the
 * stream stutters through a gap a longer runway would have covered. Nothing here can be inferred
 * from the device, so it is asked.
 *
 * The seconds are the intent, not a promise: a byte ceiling scaled to the heap still applies, so a
 * high-bitrate stream reaches the chosen size later than a small one, or not at all.
 */
enum class PlaybackBufferSize(
    val storedValue: String,
    val label: String,
    val description: String,
) {
    AUTOMATIC("auto", "Automatic", "Follows the device"),
    SMALL("small", "Small", "About 15 seconds"),
    MEDIUM("medium", "Medium", "About 40 seconds"),
    LARGE("large", "Large", "About 90 seconds"),
    VERY_LARGE("very_large", "Very large", "Up to 4 minutes");

    companion object {
        fun fromStored(value: String?): PlaybackBufferSize =
            entries.firstOrNull { it.storedValue == value } ?: AUTOMATIC
    }
}

/**
 * How much already-streamed video is kept on disk, for seeking back and re-opening an episode.
 *
 * This is storage, not memory — [PlaybackBufferSize] is the memory side. [bytes] is what was asked
 * for; `videoCacheBudget` decides what the device actually gets, and AUTOMATIC has no figure of its
 * own because it is entirely that decision.
 */
enum class VideoCacheSize(
    val storedValue: String,
    val label: String,
    val bytes: Long?,
) {
    AUTOMATIC("auto", "Automatic", null),
    OFF("off", "Off", 0L),
    MB_256("256", "256 MB", 256L * 1024 * 1024),
    MB_512("512", "512 MB", 512L * 1024 * 1024),
    GB_1("1024", "1 GB", 1024L * 1024 * 1024),
    GB_2("2048", "2 GB", 2048L * 1024 * 1024);

    companion object {
        fun fromStored(value: String?): VideoCacheSize =
            entries.firstOrNull { it.storedValue == value } ?: AUTOMATIC
    }
}

/** Maximum video rendition kept in an offline download. */
enum class DownloadQuality(
    val storedValue: String,
    val label: String,
    val maxHeight: Int?,
) {
    BEST("best", "Best available", null),
    P1080("1080", "1080p", 1080),
    P720("720", "720p", 720),
    P480("480", "480p", 480),
    P360("360", "360p", 360),
    P240("240", "240p", 240),
    P144("144", "144p", 144);

    companion object {
        fun fromStored(value: String?): DownloadQuality =
            entries.firstOrNull { it.storedValue == value } ?: BEST
    }
}

/**
 * Where a downloaded episode ends up.
 *
 * Downloads-folder copies are always built from a library download that is then rewrapped into a
 * single MP4, so `DEVICE_ONLY` does not skip the library — it drops the cached copy afterwards.
 */
enum class DownloadDestination(val storedValue: String, val label: String) {
    APP_ONLY("app", "Anilili library"),
    DEVICE_ONLY("device", "Device Downloads"),
    BOTH("both", "Both");

    val includesApp: Boolean get() = this != DEVICE_ONLY
    val includesDevice: Boolean get() = this != APP_ONLY

    companion object {
        /**
         * Downloads become an MP4 in the device's Downloads folder unless the viewer says
         * otherwise: an episode that only exists as Media3 cache segments is useful to this app
         * and nothing else, and the offline library keeps working either way because it plays the
         * exported file directly.
         */
        fun fromStored(value: String?): DownloadDestination =
            entries.firstOrNull { it.storedValue == value } ?: DEVICE_ONLY
    }
}

/**
 * The quality to use before the viewer has chosen one. TV sticks start adaptive: pinning the
 * highest rendition turns ABR off, so a hardware decoder that gets preempted or falls behind has
 * no lower rung to drop to and the picture simply dies. Low-memory phones need the same escape
 * hatch. An explicit viewer selection still wins over this first-run default.
 */
internal fun defaultQualityForDevice(
    isTv: Boolean,
    isLowRamDevice: Boolean,
    memoryClassMb: Int,
): DefaultQuality = if (isTv || isLowRamDevice || memoryClassMb in 1..192) {
    DefaultQuality.AUTO
} else {
    DefaultQuality.HIGHEST
}

private fun deviceDefaultQuality(): DefaultQuality = defaultQualityForDevice(
    isTv = AppGraph.isTv,
    isLowRamDevice = AppGraph.isLowRamDevice,
    memoryClassMb = AppGraph.memoryClassMb,
)

/** No global server has been chosen yet; the launch route supplies the initial server. */
const val DEFAULT_PREFERRED_PROVIDER = "auto"

/** Shared safety limit for manually or persistently shifted captions. */
const val MAX_CAPTION_DELAY_MS = 30_000L

/** How many servers the user can rank. Beyond three, the catalog's own order takes over anyway. */
const val MAX_SERVER_PRIORITY = 3

/**
 * How much of an episode has to play before it counts as watched.
 *
 * 80% is roughly where a viewer skips the credits, and it is the figure every list service's own
 * clients settle around. The ceiling stops short of 100 on purpose: encodes routinely end a second
 * or two before the reported duration, so a threshold of 100% would never be reached and nothing
 * would ever be marked watched.
 */
const val WATCHED_THRESHOLD_DEFAULT_PERCENT = 80
const val WATCHED_THRESHOLD_MIN_PERCENT = 50
const val WATCHED_THRESHOLD_MAX_PERCENT = 95

/**
 * Overscan ceiling, per side. A box that crops 10% off every edge has lost 20% of the picture's
 * width; past that the fault is a setting on the box or the TV, and an app squeezed into the middle
 * two-thirds of the panel would be unusable anyway.
 */
const val MAX_TV_OVERSCAN_PERCENT = 10

/** The player's manual skip jump: a standard TV anime opening, adjustable to any show's cut. */
const val SKIP_BUTTON_DEFAULT_SECONDS = 85
const val SKIP_BUTTON_MIN_SECONDS = 5
const val SKIP_BUTTON_MAX_SECONDS = 180

/**
 * The speed every episode starts at, before the player's own speed control is touched.
 *
 * The bounds are the ends of the player's speed list (`PlaybackSpeeds`), kept here as plain numbers
 * so the settings layer does not have to reach into the UI for them; `PlaybackSpeedBoundsTest`
 * fails if the two ever drift apart. A stored value outside them is clamped rather than dropped —
 * the alternative silently resets a viewer's choice to normal, which reads as the setting not
 * working at all.
 */
const val DEFAULT_PLAYBACK_SPEED = 1f
const val DEFAULT_HOLD_PLAYBACK_SPEED = 2f
const val MIN_PLAYBACK_SPEED = 0.25f
const val MAX_PLAYBACK_SPEED = 4f

/** Shared by the setter and the stored read so neither can hand a player a speed it cannot take. */
internal fun coercePlaybackSpeed(speed: Float): Float = if (speed.isNaN()) {
    DEFAULT_PLAYBACK_SPEED
} else {
    speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
}

/**
 * The player's brightness, as the window override the brightness swipe writes.
 *
 * [PLAYER_BRIGHTNESS_FOLLOW_SYSTEM] is the window's own "no override" value, so an install that has
 * never used the gesture behaves exactly as it did before: the player is as bright as the rest of
 * the phone. [MIN_PLAYER_BRIGHTNESS] is a floor rather than zero because a fully black screen is
 * indistinguishable from a dead one, and the only control that could undo it is invisible on it.
 */
const val PLAYER_BRIGHTNESS_FOLLOW_SYSTEM = -1f
const val MIN_PLAYER_BRIGHTNESS = 0.02f

/** Shared by the setter and the stored read so a saved level can never black the screen out. */
internal fun coercePlayerBrightness(value: Float): Float = when {
    value.isNaN() || value < 0f -> PLAYER_BRIGHTNESS_FOLLOW_SYSTEM
    else -> value.coerceIn(MIN_PLAYER_BRIGHTNESS, 1f)
}

/**
 * The value to write into the new hold-to-speed switch on first run, or null to leave it unset.
 *
 * An install that had already opted out of the combined gesture switch keeps hold-to-speed off;
 * everyone else keeps it on. This has to be a one-time seed rather than a read-time fallback:
 * `applyPreferences` re-runs on *every* settings write, so falling back to the old key each time
 * would drag hold-to-speed off the moment the viewer turned the brightness and volume swipes off —
 * which is precisely the coupling the dedicated switch exists to break.
 */
internal fun holdToSpeedSeed(stored: Boolean?, legacyPlayerGestures: Boolean?): Boolean? =
    if (stored != null) null else legacyPlayerGestures

/** Transactional DataStore preferences shared by playback and the Library settings UI. */
object SettingsStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: DataStore<Preferences>

    /** Held only to re-ask the television question once the persisted half of it has been read. */
    @Volatile private var appContext: Context? = null

    private val _autoplay = MutableStateFlow(true)
    val autoplay = _autoplay.asStateFlow()

    /**
     * Whether opening an episode starts it, or leaves it waiting on the play button.
     *
     * Viewers who open the player to read episode titles or to queue a download were made to watch
     * the thing regardless, which also seeded a Continue Watching row they then had to clear by
     * hand. Off, playback is theirs to start; an episode the player moves to on its own — the next
     * one after the credits — is unaffected, since that is [autoplay]'s business.
     */
    private val _playOnOpen = MutableStateFlow(true)
    val playOnOpen = _playOnOpen.asStateFlow()

    /**
     * Hands a selected episode straight to another installed video player after its stream has
     * resolved. The watch route still does the source work because the receiving app needs the
     * resolved URL, request headers, subtitles and saved resume point; it simply never mounts
     * Anilili's own player when this is on.
     */
    private val _openEpisodesInExternalPlayer = MutableStateFlow(false)
    val openEpisodesInExternalPlayer = _openEpisodesInExternalPlayer.asStateFlow()

    /**
     * Which installed player receives the episode, or blank to let the system chooser ask.
     *
     * Blank stays the default because it is the honest one on a device this app cannot see the
     * shape of. It is a poor default on a television, though: the chooser there remembers "Always"
     * permanently and offers no way back from inside this app, so one mis-pressed row can send
     * every future episode to a player built for a touchscreen. Naming a package here is how that
     * is undone, and it is also the only way to reach a player the chooser declines to show.
     */
    private val _externalPlayerPackage = MutableStateFlow("")
    val externalPlayerPackage = _externalPlayerPackage.asStateFlow()

    /**
     * Whether leaving the app shrinks the episode into the system's floating window, or ends it.
     *
     * The mini player is the only way playback survives leaving the app: `MainActivity.onStop`
     * pauses the episode, and picture-in-picture is what keeps that from ever being reached. So a
     * viewer who does not want an episode following them out of the app cannot get there by
     * pressing pause first — the window is entered as they leave, whatever the player was doing.
     * Off, Home ends the episode where it stands and the position is kept for Continue Watching.
     */
    private val _pictureInPicture = MutableStateFlow(true)
    val pictureInPicture = _pictureInPicture.asStateFlow()

    /**
     * Whether pausing bills the episode over the frame, or just stops the picture.
     *
     * The panel answers "what am I part-way through" without leaving the player, but it darkens the
     * left of the frame to do it — and a viewer who paused to look *at* the picture is the one
     * person it is in the way of. That is what `PAUSE_OVERLAY_DELAY_MS` is for; this switch is the
     * answer for anyone who does not want the panel at any delay. Native playback only: an embed
     * page is the provider's own player and has no frame of ours to draw on.
     */
    private val _pauseDetailsOverlay = MutableStateFlow(true)
    val pauseDetailsOverlay = _pauseDetailsOverlay.asStateFlow()

    private val _autoSyncAniList = MutableStateFlow(true)
    val autoSyncAniList = _autoSyncAniList.asStateFlow()

    private val _preferDub = MutableStateFlow(false)
    val preferDub = _preferDub.asStateFlow()

    private val _releaseNotifications = MutableStateFlow(true)
    val releaseNotifications = _releaseNotifications.asStateFlow()

    /**
     * Signed broadcasts from the project (see [com.anilili.data.announce.AnnouncementSource]).
     *
     * On by default and honoured absolutely. The messages this carries are the ones a sideloaded
     * app has no other way to send — a moved download host, a release that must not be installed —
     * so the temptation is to make an "emergency" ignore the switch. It does not: someone who
     * turned this off said so, and a channel that overrides its own opt-out is one the system
     * notification settings get used to silence instead, which loses the urgent messages too.
     */
    private val _announcements = MutableStateFlow(false)
    val announcements = _announcements.asStateFlow()

    private val _syncSavedToAniList = MutableStateFlow(true)
    val syncSavedToAniList = _syncSavedToAniList.asStateFlow()

    private val _autoSkipIntroOutro = MutableStateFlow(false)
    val autoSkipIntroOutro = _autoSkipIntroOutro.asStateFlow()

    /**
     * Moving on from an episode passes over the filler ones after it: Next, auto-play and the
     * up-next card all land on the next episode that is not filler. Opening a filler episode by
     * hand still plays it; its player then offers a one-press skip either way.
     */
    private val _autoSkipFiller = MutableStateFlow(false)
    val autoSkipFiller = _autoSkipFiller.asStateFlow()

    /**
     * A one-press jump forward shown on the player whenever no timed "Skip Intro" window applies.
     *
     * Intro timings come from providers or AniSkip and are missing or wrong often enough that
     * viewers were left scrubbing by hand. [SKIP_BUTTON_DEFAULT_SECONDS] is the length of a
     * standard TV anime opening.
     */
    private val _showSkipButton = MutableStateFlow(true)
    val showSkipButton = _showSkipButton.asStateFlow()

    private val _skipButtonSeconds = MutableStateFlow(SKIP_BUTTON_DEFAULT_SECONDS)
    val skipButtonSeconds = _skipButtonSeconds.asStateFlow()

    /**
     * The share of an episode that has to play before it is treated as watched — by Continue
     * Watching, by the watched markers in every episode picker, and by the AniList/MyAnimeList
     * progress sync. One number drives all three so local history and the account never disagree
     * about what has been seen.
     */
    private val _watchedThresholdPercent = MutableStateFlow(WATCHED_THRESHOLD_DEFAULT_PERCENT)
    val watchedThresholdPercent = _watchedThresholdPercent.asStateFlow()

    /**
     * The speed each episode opens at, on both players.
     *
     * The player's own speed slider writes here too, so the control a viewer actually reaches for
     * is the one that sticks: set 1.1x once and the rest of the binge stays at 1.1x. It was
     * episode-scoped before, on the theory that slowing one scene down to read a sign should not
     * outlive the scene — but that traded a rare case against the common one, and the common one
     * was people re-setting the speed on every single episode. Hold-to-speed is still momentary:
     * it bypasses the slider and restores whatever was in force when the hold began.
     */
    private val _defaultPlaybackSpeed = MutableStateFlow(DEFAULT_PLAYBACK_SPEED)
    val defaultPlaybackSpeed = _defaultPlaybackSpeed.asStateFlow()

    /** A stationary long press temporarily uses this speed, independently of the swipe gestures. */
    private val _holdToSpeedEnabled = MutableStateFlow(true)
    val holdToSpeedEnabled = _holdToSpeedEnabled.asStateFlow()

    private val _holdPlaybackSpeed = MutableStateFlow(DEFAULT_HOLD_PLAYBACK_SPEED)
    val holdPlaybackSpeed = _holdPlaybackSpeed.asStateFlow()

    private val _hideAdultContent = MutableStateFlow(true)
    val hideAdultContent = _hideAdultContent.asStateFlow()

    private val _blurEpisodeImages = MutableStateFlow(false)
    val blurEpisodeImages = _blurEpisodeImages.asStateFlow()

    /**
     * Whether Home's Continue Watching row steps aside for a caught-up title until its next episode
     * is out — the whole week for a weekly show, not only the day before.
     *
     * A caught-up row points at the episode just finished, and that episode is still there to open
     * onto — so the row is not wrong, it is simply finished with, and it holds a slot on the row
     * until the viewer removes it by hand. On, it comes back on its own once the episode is out.
     * The name predates that: it once covered only titles airing tomorrow, and the stored key is
     * kept so the switch keeps its position.
     *
     * Home only. My List and the account sheet keep drawing the title, because that row is a record
     * of what the viewer is watching rather than a row of things to press play on.
     */
    private val _hideAiringTomorrowFromContinue = MutableStateFlow(false)
    val hideAiringTomorrowFromContinue = _hideAiringTomorrowFromContinue.asStateFlow()

    /** Whether a series is billed by its logo (title page, player) or by its name set in type. */
    private val _showSeriesLogos = MutableStateFlow(true)
    val showSeriesLogos = _showSeriesLogos.asStateFlow()

    /**
     * Brightness and volume gestures over the picture. Off leaves hold-to-speed and drag-to-seek
     * alone; each has its own switch because they carry different accidental-touch costs. TV never
     * had these gestures.
     */
    private val _playerGestures = MutableStateFlow(true)
    val playerGestures = _playerGestures.asStateFlow()

    /**
     * Drag left and right over the picture to scrub, held apart from [playerGestures] because it is
     * the one of the drags that goes wrong on its own: a thumb that moves sideways on the way to a
     * tap moves the episode with it, and losing your place is worse than the gesture is worth.
     * Turning it off used to mean giving up brightness and volume as well.
     */
    private val _playerSeekGesture = MutableStateFlow(true)
    val playerSeekGesture = _playerSeekGesture.asStateFlow()

    /**
     * The brightness the player opens at, written by the brightness swipe itself.
     *
     * It used to last exactly as long as one episode: the window override was cleared when the
     * player went away, so every episode — and every return from the episode list — came back at
     * the phone's own brightness, and a viewer watching in the dark dimmed the picture again at
     * each opening. The level is the viewer's answer to "how bright do you want this", which does
     * not change between two episodes of the same show, so it is kept like [defaultPlaybackSpeed]:
     * the control they already reach for is the one that sticks.
     *
     * Only the player window carries it. The rest of the app, and everything outside it, stays on
     * the system brightness — a level chosen for a night episode is not a level chosen for the
     * browse screen. [playerGestures] gates it as well as sets it: switching the swipes off puts
     * the player back on the system brightness, which is the way back for anyone who left it dark.
     */
    private val _playerBrightness = MutableStateFlow(PLAYER_BRIGHTNESS_FOLLOW_SYSTEM)
    val playerBrightness = _playerBrightness.asStateFlow()

    /**
     * Whether the status bar rides along with the player's controls, so the clock, signal and
     * battery are one tap away mid-episode. Off keeps the picture alone with its own chrome, which
     * is what viewers who want nothing but the video over a full screen were asking for. TV has no
     * status bar to show either way.
     */
    private val _playerStatusBar = MutableStateFlow(true)
    val playerStatusBar = _playerStatusBar.asStateFlow()

    /**
     * The wall clock and the episode's finishing time, over the player's own chrome.
     *
     * It rides with the controls rather than sitting on the picture, so it costs a viewer who does
     * not want it nothing until they raise the bar — which is why it is on by default. The one place
     * it outlives the controls is a pause, because a paused episode is the one being decided about.
     *
     * [playerStatusBar] is not the same offer: it shows the system clock, says nothing about when
     * the episode ends, and a television has no status bar to show at all.
     */
    private val _playerClock = MutableStateFlow(true)
    val playerClock = _playerClock.asStateFlow()

    /**
     * Television only: a playing episode takes a second Back within a moment to leave.
     *
     * On by default because the controls hide on their own timer, and a Back meant to close them
     * that lands just after they went used to take the viewer out of the episode. Off is for
     * viewers who would rather one press always leave.
     */
    private val _tvDoubleBackToExit = MutableStateFlow(true)
    val tvDoubleBackToExit = _tvDoubleBackToExit.asStateFlow()

    /**
     * What to fall back to when the first choice is not in the stream.
     *
     * Media3 ranks an ordered list of languages, so a second entry costs nothing to honour and
     * answers the case the single preference could not: someone who wants Japanese audio but will
     * take English over whatever the file happens to list first. [AUTOMATIC] means "no second
     * choice" and leaves the list one long, which is exactly what it used to be.
     */
    private val _secondaryAudioLanguage = MutableStateFlow(PreferredAudioLanguage.AUTOMATIC)
    val secondaryAudioLanguage = _secondaryAudioLanguage.asStateFlow()

    private val _secondarySubtitleLanguage = MutableStateFlow(PreferredSubtitleLanguage.AUTOMATIC)
    val secondarySubtitleLanguage = _secondarySubtitleLanguage.asStateFlow()

    /**
     * The dimmed panel naming the episode while its source resolves. Off leaves the picture — or
     * the black before it — alone, for viewers who would rather see nothing than a scrim.
     */
    private val _showLoadingOverlay = MutableStateFlow(true)
    val showLoadingOverlay = _showLoadingOverlay.asStateFlow()

    private val _subtitlesWithDub = MutableStateFlow(false)
    val subtitlesWithDub = _subtitlesWithDub.asStateFlow()

    /**
     * Whether a dub with both a signs track and a dialogue track opens with the two merged into one
     * ("English CC (Dub) + signs"), and the last answer the player's "Show signs too" switch gave.
     */
    private val _subtitleSignsWithDialogue = MutableStateFlow(true)
    val subtitleSignsWithDialogue = _subtitleSignsWithDialogue.asStateFlow()

    /**
     * Whether a player should show captions on an episode nobody has chosen a track for.
     *
     * The caption switch inside both players writes here too, through
     * `captionDefaultsAfterChoice` — the track policy is cleared for every new item by design, so
     * without that an "off" lasted one episode and the next one came back with text on. This is the
     * same trade [defaultPlaybackSpeed] made: the control the viewer actually reaches for is the one
     * that sticks, and Settings shows what the player was last told.
     */
    private val _subtitlesByDefault = MutableStateFlow(true)
    val subtitlesByDefault = _subtitlesByDefault.asStateFlow()

    private val _updateCheckOnLaunch = MutableStateFlow(true)
    val updateCheckOnLaunch = _updateCheckOnLaunch.asStateFlow()

    /**
     * Pins the top bar and the bottom navigation on screen instead of letting a scroll tuck them
     * away. Off by default, which keeps the roomier YouTube-style behaviour; on for anyone who
     * finds chrome that comes and goes under the thumb more distracting than the space it saves.
     */
    private val _staticAppChrome = MutableStateFlow(false)
    val staticAppChrome = _staticAppChrome.asStateFlow()

    /** Live frosted glass, per part of the phone interface; all on by default. See [GlassSection]. */
    private val _glass = GlassSection.entries.associateWith { MutableStateFlow(true) }
    val glass: Map<GlassSection, StateFlow<Boolean>> = _glass.mapValues { it.value.asStateFlow() }

    /**
     * Search answers each keystroke: title suggestions from the bundled snapshot, then a live
     * search that rebuilds the results grid. On by default. Off, typing does no work at all and the
     * search runs once on the keyboard's search key — for a Fire TV Stick class box, where that
     * per-letter work made the keyboard itself lag.
     */
    private val _searchAsYouType = MutableStateFlow(true)
    val searchAsYouType = _searchAsYouType.asStateFlow()

    /**
     * TV flavor only: open on the video intro (`res/raw/launch_intro.mp4`, since v0.1.80). Off, the
     * app opens on the water-fill mark it had before, the one the phone still uses.
     */
    private val _videoLaunchIntro = MutableStateFlow(true)
    val videoLaunchIntro = _videoLaunchIntro.asStateFlow()

    /** The phone's launcher icon takes the accent preset's colour ([AppIcon]); off keeps the default. */
    private val _appIconMatchesTheme = MutableStateFlow(true)
    val appIconMatchesTheme = _appIconMatchesTheme.asStateFlow()

    /**
     * Whether the episode being watched is published to Discord as a status.
     *
     * This is the one setting in the app whose effect is visible to *other people*, so it defaults
     * to off and no update can start it: it is turned on by the act of connecting a Discord
     * account, which is what an account is connected *for*, and disconnecting turns it back off.
     */
    private val _discordRichPresence = MutableStateFlow(false)
    val discordRichPresence = _discordRichPresence.asStateFlow()

    /** What the Discord session reports as the viewer's status while it shows an episode. */
    private val _discordStatus = MutableStateFlow(DiscordStatus.ONLINE)
    val discordStatus = _discordStatus.asStateFlow()

    // Kept as one compound value rather than a flow per field: both players and the editor read
    // the whole style at once, and a partial style is never meaningful.
    private val _captionStyle = MutableStateFlow(CaptionStyle())
    val captionStyle = _captionStyle.asStateFlow()

    /** Opted-in subtitle delays keyed by AniList anime ID (which also scopes separate seasons). */
    private val _persistentCaptionDelays = MutableStateFlow<Map<Int, Long>>(emptyMap())

    private val _menuLanguage = MutableStateFlow(MenuLanguage.SYSTEM)
    val menuLanguage = _menuLanguage.asStateFlow()

    private val _clockFormat = MutableStateFlow(ClockFormat.SYSTEM)
    val clockFormat = _clockFormat.asStateFlow()

    /**
     * The home catalogue tab the viewer last chose, held as its plain enum name.
     *
     * Stored as a String rather than the enum itself to keep the settings layer from depending on
     * a UI type. Null means "never chosen", which is what lets Home fall back to its own default
     * instead of this store having to know what that default is.
     */
    private val _homeTab = MutableStateFlow<String?>(null)
    val homeTab = _homeTab.asStateFlow()

    /**
     * Whether Schedule draws a month at a time instead of the strip of days around today.
     *
     * Off by default: the strip answers "what is on tonight", which is what the screen is opened
     * for, and it costs one day's fetch. The month is for planning, and set once it is expected to
     * stick — the viewer who plans a season does not want to re-open the calendar on every visit.
     */
    private val _scheduleCalendarView = MutableStateFlow(false)
    val scheduleCalendarView = _scheduleCalendarView.asStateFlow()

    private val _defaultQuality = MutableStateFlow(deviceDefaultQuality())
    val defaultQuality = _defaultQuality.asStateFlow()

    private val _preferredAudioLanguage = MutableStateFlow(PreferredAudioLanguage.AUTOMATIC)
    val preferredAudioLanguage = _preferredAudioLanguage.asStateFlow()

    private val _preferredSubtitleLanguage = MutableStateFlow(PreferredSubtitleLanguage.AUTOMATIC)
    val preferredSubtitleLanguage = _preferredSubtitleLanguage.asStateFlow()

    /**
     * Which languages the in-player OpenSubtitles search asks for, as the codes the API takes.
     *
     * Blank means "follow the device", which is the right default and was the only behaviour: the
     * panel derived its languages from the system locale every time it opened. That is correct for
     * someone whose phone is set to the language they read subtitles in, and wrong for everyone
     * else — an Arabic reader on an English phone retyped `ar` for every episode, because the field
     * lived and died with the panel.
     */
    private val _subtitleSearchLanguages = MutableStateFlow("")
    val subtitleSearchLanguages = _subtitleSearchLanguages.asStateFlow()

    private val _playerContentScale = MutableStateFlow(PlayerContentScale.FIT)
    val playerContentScale = _playerContentScale.asStateFlow()

    /** Read by the playback service at every prepare, so a change lands on the next episode. */
    private val _playbackBufferSize = MutableStateFlow(PlaybackBufferSize.AUTOMATIC)
    val playbackBufferSize = _playbackBufferSize.asStateFlow()

    /** Watched by [com.anilili.playback.MediaCache], which resizes the running cache on a change. */
    private val _videoCacheSize = MutableStateFlow(VideoCacheSize.AUTOMATIC)
    val videoCacheSize = _videoCacheSize.asStateFlow()

    /**
     * Interface size. Only television reads this — phones and tablets are held at arm's length and
     * already follow the system's own display and font size settings, which a TV does not offer.
     */
    private val _uiScale = MutableStateFlow(UiScale.MEDIUM)
    val uiScale = _uiScale.asStateFlow()

    /**
     * Overscan: how far, as a percentage of the screen on each side, the whole app is pulled in
     * from the edges. 0 is off. Some boxes scale the picture past the panel and cut off 5–10% on
     * every side, and neither the box nor the app can see that happen — only the viewer can, so the
     * viewer sets it. Television only, like [uiScale].
     */
    private val _tvOverscanPercent = MutableStateFlow(0)
    val tvOverscanPercent = _tvOverscanPercent.asStateFlow()

    /**
     * The Appearance section's theme: canvas, seed accent, Material 3 scheme style and contrast.
     * Read at the one composition root (`MiruroTheme`), so every screen follows a change at once.
     */
    private val _themeBackground = MutableStateFlow(ThemeBackground.AMOLED)
    val themeBackground = _themeBackground.asStateFlow()
    private val _themeAccent = MutableStateFlow(ThemeAccent.MONOCHROME)
    val themeAccent = _themeAccent.asStateFlow()
    private val _themeStyle = MutableStateFlow(ThemeStyle.VIVID)
    val themeStyle = _themeStyle.asStateFlow()
    private val _themeContrast = MutableStateFlow(ThemeContrast.STANDARD)
    val themeContrast = _themeContrast.asStateFlow()

    /** The colour behind [ThemeAccent.CUSTOM], kept even while another accent is chosen. */
    private val _themeCustomSeed = MutableStateFlow(DEFAULT_CUSTOM_SEED)
    val themeCustomSeed = _themeCustomSeed.asStateFlow()

    /** Whether the player's panels and seek bar take the accent too. Off keeps them monochrome. */
    private val _playerAccent = MutableStateFlow(false)
    val playerAccent = _playerAccent.asStateFlow()

    /** Whether an anime's page takes that show's own colour (AniList's cover colour) as its accent. */
    private val _showColourPages = MutableStateFlow(true)
    val showColourPages = _showColourPages.asStateFlow()

    /**
     * The picture drawn where the canvas colour used to be, and how. The anime backdrop and the
     * viewer's own image are kept apart so switching between them keeps both choices.
     */
    private val _appBackdropSource = MutableStateFlow(AppBackdropSource.NONE)
    val appBackdropSource = _appBackdropSource.asStateFlow()
    private val _appBackdropAnimeUrl = MutableStateFlow<String?>(null)
    val appBackdropAnimeUrl = _appBackdropAnimeUrl.asStateFlow()
    private val _appBackdropAnimeTitle = MutableStateFlow<String?>(null)
    val appBackdropAnimeTitle = _appBackdropAnimeTitle.asStateFlow()
    private val _appBackdropImage = MutableStateFlow<String?>(null)
    val appBackdropImage = _appBackdropImage.asStateFlow()
    private val _appBackdropDim = MutableStateFlow(AppBackdropDim.MEDIUM)
    val appBackdropDim = _appBackdropDim.asStateFlow()
    private val _appBackdropBlur = MutableStateFlow(true)
    val appBackdropBlur = _appBackdropBlur.asStateFlow()

    /** Layout override for devices whose reported form factor is wrong. */
    private val _formFactorPreference = MutableStateFlow(FormFactorPreference.AUTOMATIC)
    val formFactorPreference = _formFactorPreference.asStateFlow()

    /**
     * Whether this device has ever launched the app from a television home screen.
     *
     * A device fact rather than a preference, and remembered rather than re-read, because the
     * launch that proves it is not every launch: a start from a notification or a Watch Next tile
     * carries no category at all, and letting that turn the interface back into a tablet halfway
     * through an evening would be worse than never having detected the television.
     */
    private val _leanbackLauncherSeen = MutableStateFlow(false)
    val leanbackLauncherSeen = _leanbackLauncherSeen.asStateFlow()

    private val _downloadQuality = MutableStateFlow(DownloadQuality.BEST)
    val downloadQuality = _downloadQuality.asStateFlow()

    /** When true, up to two episodes resolve/download at once instead of strictly one-by-one. */
    private val _parallelDownloads = MutableStateFlow(false)
    val parallelDownloads = _parallelDownloads.asStateFlow()

    private val _downloadDestination = MutableStateFlow(DownloadDestination.DEVICE_ONLY)
    val downloadDestination = _downloadDestination.asStateFlow()

    /**
     * Downloads from the anime page take only servers with the subtitles burned into the picture.
     * Remembered because it is a taste, not a per-batch choice: a viewer who wants a file that
     * shows its subtitles in any player wants that every time.
     */
    private val _downloadHardSubsOnly = MutableStateFlow(false)
    val downloadHardSubsOnly = _downloadHardSubsOnly.asStateFlow()

    // Set once and expected to stick: the viewer who wants dense number chips for a long-runner
    // wants them on the next screen too, not just until they navigate away.
    private val _episodeLayout = MutableStateFlow(EpisodeLayout.LIST)
    val episodeLayout = _episodeLayout.asStateFlow()

    // Sticks for the same reason as the layout: someone following a weekly show wants the newest
    // episode on top of every series they open, not just the one they set it on.
    private val _episodeOrder = MutableStateFlow(EpisodeOrder.OLDEST_FIRST)
    val episodeOrder = _episodeOrder.asStateFlow()

    // Sticks like the episode layout: someone who prefers the bare titles wants them on every
    // series they open. The rows with artwork stay the default.
    private val _relatedLayout = MutableStateFlow(RelatedLayout.LIST)
    val relatedLayout = _relatedLayout.asStateFlow()

    /**
     * Whether the phone's Browse header is folded down to its title row. Expanded, the search box,
     * category chips and active-filter row take about a quarter of a phone's height every time the
     * header slides back in mid-scroll; someone who browses by scrolling can keep that room for
     * results.
     */
    private val _browseHeaderCollapsed = MutableStateFlow(false)
    val browseHeaderCollapsed = _browseHeaderCollapsed.asStateFlow()

    /**
     * Browse's "already watched" filter, kept between visits. Unlike Browse's other filters, which
     * describe one search, this describes the viewer — someone hiding what they have seen wants it
     * hidden the next time they go exploring too.
     */
    private val _browseHideWatched = MutableStateFlow(WatchedFilter.OFF)
    val browseHideWatched = _browseHideWatched.asStateFlow()

    /**
     * The in-player episode drawer's own list/grid choice; null until the viewer picks one, which
     * leaves the drawer on its automatic rule (a number grid past 25 episodes). Kept apart from
     * [episodeLayout] because that one defaults to the list, and a hundred-episode season as a
     * list is exactly what the drawer's grid exists to avoid.
     */
    private val _episodeDrawerLayout = MutableStateFlow<EpisodeLayout?>(null)
    val episodeDrawerLayout = _episodeDrawerLayout.asStateFlow()

    /**
     * The Miruro mirror that last loaded successfully on this network. ISPs block the mirrors
     * piecemeal, and rediscovering that from scratch each launch costs the whole failover walk.
     */
    private val _lastWorkingPipeOrigin = MutableStateFlow("")
    val lastWorkingPipeOrigin = _lastWorkingPipeOrigin.asStateFlow()

    /**
     * The user's servers in the order they should be tried, first choice first, at most
     * [MAX_SERVER_PRIORITY] of them. Empty means "no pinned choice" — the catalog's own order.
     */
    private val _serverPriority = MutableStateFlow<List<String>>(emptyList())
    val serverPriority = _serverPriority.asStateFlow()

    /**
     * The servers the viewer has switched off. Everything not named here is on.
     *
     * Stored as the servers that are *off* rather than the ones that are on, because the catalogue
     * gains and loses members between releases. An enabled-list written today would silently
     * exclude every server added after it, and each upgrade would need a trip back into Settings to
     * regain one; a disabled list only ever names servers the viewer actually rejected.
     */
    private val _disabledProviders = MutableStateFlow<Set<String>>(emptySet())
    val disabledProviders = _disabledProviders.asStateFlow()

    /**
     * MyAnimeList tags the viewer does not want shown as custom lists.
     *
     * A MAL account's lists *are* its tags, so every tag the viewer ever used for their own
     * bookkeeping turns up as a list here. A hidden one is simply not shown: it stays on MAL,
     * untouched, and a tag write for a title that carries it writes it back unchanged.
     */
    private val _hiddenMalTags = MutableStateFlow<Set<String>>(emptySet())
    val hiddenMalTags = _hiddenMalTags.asStateFlow()

    fun setHiddenMalTags(value: Set<String>) {
        val normalized = value.map(String::trim).filterTo(sortedSetOf()) { it.isNotEmpty() }
        _hiddenMalTags.value = normalized
        scope.launch { store.edit { it[HIDDEN_MAL_TAGS] = normalized.joinToString(",") } }
    }

    /** Empty keeps every discovered language visible; otherwise only this shortlist gets chips. */
    private val _preferredSourceLanguages = MutableStateFlow<Set<SourceFilterLanguage>>(emptySet())
    val preferredSourceLanguages = _preferredSourceLanguages.asStateFlow()

    /**
     * The poster badges and captions the viewer has switched off. Empty — the default — draws every
     * one of them, which is the card every install has always had.
     */
    private val _hiddenPosterElements = MutableStateFlow<Set<PosterElement>>(emptySet())
    val hiddenPosterElements = _hiddenPosterElements.asStateFlow()

    /**
     * Home's rows as the viewer arranged them, encoded by `HomeLayout.encode`; null until they first
     * customise, so an untouched Home keeps following the app's default as it changes.
     */
    private val _homeLayout = MutableStateFlow<String?>(null)
    val homeLayout = _homeLayout.asStateFlow()

    /** Who resolves hostnames, for a network whose own resolver refuses to. See [AppDns]. */
    private val _dohProvider = MutableStateFlow(DohProvider.NONE)
    val dohProvider = _dohProvider.asStateFlow()

    /**
     * Head of [serverPriority], or "auto" when nothing is pinned. Kept as its own flow because the
     * player's server sheet, the ★ badge, and the watch route all read a single preferred name.
     */
    private val _preferredProvider = MutableStateFlow(DEFAULT_PREFERRED_PROVIDER)
    val preferredProvider = _preferredProvider.asStateFlow()
    private val loaded = MutableStateFlow(false)

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        store = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { app.preferencesDataStoreFile("anilili_settings") },
        )
        scope.launch {
            // Before the first read is applied, so a switched-to profile never shows the previous
            // profile's copy of these for a frame.
            com.anilili.data.profiles.Profiles.outgoingSettingsFile?.let { outgoing ->
                runCatching { carryDeviceSettings(outgoing) }
                    .onFailure { CrashReporter.logNonFatal("Device settings carry-over failed", it) }
            }
            runCatching { migrateLegacyPreferences(app) }
                .onFailure { CrashReporter.logNonFatal("Settings migration failed", it) }
            runCatching { seedHoldToSpeedFromLegacyGestures() }
                .onFailure { CrashReporter.logNonFatal("Hold-to-speed seed failed", it) }
            store.data
                .catch { error ->
                    // A settings read must never take the process down; fall back to defaults.
                    if (error !is IOException) CrashReporter.logNonFatal("Settings read failed", error)
                    emit(emptyPreferences())
                }
                .collect(::applyPreferences)
        }
    }

    fun setAutoplay(value: Boolean) = save(AUTOPLAY, value, _autoplay)
    fun setPlayOnOpen(value: Boolean) = save(PLAY_ON_OPEN, value, _playOnOpen)
    fun setOpenEpisodesInExternalPlayer(value: Boolean) =
        save(OPEN_EPISODES_IN_EXTERNAL_PLAYER, value, _openEpisodesInExternalPlayer)
    fun setPictureInPicture(value: Boolean) = save(PICTURE_IN_PICTURE, value, _pictureInPicture)
    fun setBrowseHeaderCollapsed(value: Boolean) = save(BROWSE_HEADER_COLLAPSED, value, _browseHeaderCollapsed)

    fun setBrowseHideWatched(value: WatchedFilter) {
        _browseHideWatched.value = value
        scope.launch { store.edit { it[BROWSE_HIDE_WATCHED] = value.storedValue } }
    }
    fun setPauseDetailsOverlay(value: Boolean) = save(PAUSE_DETAILS_OVERLAY, value, _pauseDetailsOverlay)
    fun setAutoSyncAniList(value: Boolean) = save(AUTO_SYNC, value, _autoSyncAniList)
    fun setPreferDub(value: Boolean) = save(PREFER_DUB, value, _preferDub)
    fun setReleaseNotifications(value: Boolean) = save(RELEASE_NOTIFICATIONS, value, _releaseNotifications)
    fun setAnnouncements(value: Boolean) = save(ANNOUNCEMENTS, value, _announcements)
    fun setSyncSavedToAniList(value: Boolean) = save(SYNC_SAVED_TO_ANILIST, value, _syncSavedToAniList)
    fun setAutoSkipIntroOutro(value: Boolean) = save(AUTO_SKIP_INTRO_OUTRO, value, _autoSkipIntroOutro)
    fun setAutoSkipFiller(value: Boolean) = save(AUTO_SKIP_FILLER, value, _autoSkipFiller)
    fun setShowSkipButton(value: Boolean) = save(SHOW_SKIP_BUTTON, value, _showSkipButton)

    fun setSkipButtonSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(SKIP_BUTTON_MIN_SECONDS, SKIP_BUTTON_MAX_SECONDS)
        _skipButtonSeconds.value = clamped
        scope.launch { store.edit { it[SKIP_BUTTON_SECONDS] = clamped } }
    }
    fun setWatchedThresholdPercent(percent: Int) {
        val clamped = percent.coerceIn(WATCHED_THRESHOLD_MIN_PERCENT, WATCHED_THRESHOLD_MAX_PERCENT)
        _watchedThresholdPercent.value = clamped
        scope.launch { store.edit { it[WATCHED_THRESHOLD_PERCENT] = clamped } }
    }
    fun setDefaultPlaybackSpeed(speed: Float) {
        val clamped = coercePlaybackSpeed(speed)
        _defaultPlaybackSpeed.value = clamped
        scope.launch { store.edit { it[DEFAULT_PLAYBACK_SPEED_KEY] = clamped } }
    }
    fun setHoldToSpeedEnabled(value: Boolean) = save(HOLD_TO_SPEED_ENABLED, value, _holdToSpeedEnabled)
    fun setHoldPlaybackSpeed(speed: Float) {
        val clamped = coercePlaybackSpeed(speed)
        _holdPlaybackSpeed.value = clamped
        scope.launch { store.edit { it[HOLD_PLAYBACK_SPEED] = clamped } }
    }
    fun setHideAdultContent(value: Boolean) = save(HIDE_ADULT_CONTENT, value, _hideAdultContent)
    fun setBlurEpisodeImages(value: Boolean) = save(BLUR_EPISODE_IMAGES, value, _blurEpisodeImages)
    fun setHideAiringTomorrowFromContinue(value: Boolean) =
        save(HIDE_AIRING_TOMORROW_FROM_CONTINUE, value, _hideAiringTomorrowFromContinue)
    fun setShowSeriesLogos(value: Boolean) = save(SHOW_SERIES_LOGOS, value, _showSeriesLogos)
    fun setSecondaryAudioLanguage(value: PreferredAudioLanguage) {
        _secondaryAudioLanguage.value = value
        scope.launch { store.edit { it[SECONDARY_AUDIO_LANGUAGE] = value.storedValue } }
    }

    fun setSecondarySubtitleLanguage(value: PreferredSubtitleLanguage) {
        _secondarySubtitleLanguage.value = value
        scope.launch { store.edit { it[SECONDARY_SUBTITLE_LANGUAGE] = value.storedValue } }
    }

    fun setShowLoadingOverlay(value: Boolean) = save(SHOW_LOADING_OVERLAY, value, _showLoadingOverlay)
    fun setSubtitlesWithDub(value: Boolean) = save(SUBTITLES_WITH_DUB, value, _subtitlesWithDub)
    fun setSubtitleSignsWithDialogue(value: Boolean) =
        save(SUBTITLE_SIGNS_WITH_DIALOGUE, value, _subtitleSignsWithDialogue)
    fun setSubtitlesByDefault(value: Boolean) = save(SUBTITLES_BY_DEFAULT, value, _subtitlesByDefault)

    /**
     * Both subtitle defaults in one write, for the caption switch inside the player: the two are one
     * answer there, and writing them separately would leave a moment — and a settings screen — where
     * text is on for dubs but off for everything.
     */
    fun setSubtitleDefaults(subtitlesByDefault: Boolean, subtitlesWithDub: Boolean) {
        if (
            _subtitlesByDefault.value == subtitlesByDefault &&
            _subtitlesWithDub.value == subtitlesWithDub
        ) {
            return
        }
        _subtitlesByDefault.value = subtitlesByDefault
        _subtitlesWithDub.value = subtitlesWithDub
        scope.launch {
            store.edit { prefs ->
                prefs[SUBTITLES_BY_DEFAULT] = subtitlesByDefault
                prefs[SUBTITLES_WITH_DUB] = subtitlesWithDub
            }
        }
    }

    fun setUpdateCheckOnLaunch(value: Boolean) = save(UPDATE_CHECK_ON_LAUNCH, value, _updateCheckOnLaunch)
    fun setStaticAppChrome(value: Boolean) = save(STATIC_APP_CHROME, value, _staticAppChrome)
    fun setGlass(section: GlassSection, value: Boolean) =
        save(GLASS_KEYS.getValue(section), value, _glass.getValue(section))

    /** Every [GlassSection] at once, in one write, for the section's master switch. */
    fun setAllGlass(value: Boolean) {
        _glass.values.forEach { it.value = value }
        scope.launch { store.edit { prefs -> GLASS_KEYS.values.forEach { prefs[it] = value } } }
    }
    fun setSearchAsYouType(value: Boolean) = save(SEARCH_AS_YOU_TYPE, value, _searchAsYouType)
    fun setVideoLaunchIntro(value: Boolean) = save(VIDEO_LAUNCH_INTRO, value, _videoLaunchIntro)
    fun setAppIconMatchesTheme(value: Boolean) = save(APP_ICON_MATCHES_THEME, value, _appIconMatchesTheme)
    fun setDiscordRichPresence(value: Boolean) = save(DISCORD_RICH_PRESENCE, value, _discordRichPresence)

    fun setDiscordStatus(value: DiscordStatus) {
        _discordStatus.value = value
        scope.launch { store.edit { it[DISCORD_STATUS] = value.storedValue } }
    }

    fun setCaptionBackgroundOpacity(percent: Int) =
        editCaptionStyle { it.copy(backgroundOpacityPercent = percent.coerceIn(0, 100)) }
    fun setCaptionBackgroundColor(value: CaptionBackgroundColor) =
        editCaptionStyle { it.copy(backgroundColor = value) }
    fun setCaptionTextScale(percent: Int) =
        editCaptionStyle { it.copy(textScalePercent = percent.coerceIn(CaptionStyle.MIN_TEXT_SCALE_PERCENT, CaptionStyle.MAX_TEXT_SCALE_PERCENT)) }
    /** Takes the size control's own 0–100 reading; [setCaptionTextScale] takes the font scale. */
    fun setCaptionTextSize(value: Int) =
        setCaptionTextScale(CaptionStyle.textScaleForSize(value.coerceIn(CaptionStyle.MIN_TEXT_SIZE, CaptionStyle.MAX_TEXT_SIZE)))
    fun setCaptionTextOpacity(percent: Int) =
        editCaptionStyle { it.copy(textOpacityPercent = percent.coerceIn(0, 100)) }
    fun setCaptionBold(value: Boolean) = editCaptionStyle { it.copy(boldText = value) }
    fun setCaptionFont(value: CaptionFont) = editCaptionStyle { it.copy(font = value) }
    fun setCaptionEdgeMargin(percent: Int) =
        editCaptionStyle { it.copy(edgeMarginPercent = percent.coerceIn(CaptionStyle.MIN_EDGE_MARGIN_PERCENT, CaptionStyle.MAX_EDGE_MARGIN_PERCENT)) }
    fun setCaptionHorizontalAnchor(percent: Int) =
        editCaptionStyle {
            it.copy(
                horizontalAnchorPercent = percent.coerceIn(
                    CaptionStyle.MIN_HORIZONTAL_ANCHOR_PERCENT,
                    CaptionStyle.MAX_HORIZONTAL_ANCHOR_PERCENT,
                ),
            )
        }
    /**
     * Moves the captions without saving, for the drag in the appearance editor.
     *
     * [editCaptionStyle] writes the whole caption block to the datastore, and every write re-emits
     * `store.data` — so routing a drag through it would put a preferences write and a full
     * preference re-read on every pointer event. The live style still updates, because that is
     * what both players and the preview read; [commitCaptionPlacement] persists it once the finger
     * comes off. A drag abandoned by process death is therefore forgotten, which is the right way
     * round for a gesture nobody finished.
     */
    fun previewCaptionPlacement(horizontalAnchorPercent: Int, edgeMarginPercent: Int) {
        _captionStyle.value = _captionStyle.value.copy(
            horizontalAnchorPercent = horizontalAnchorPercent.coerceIn(
                CaptionStyle.MIN_HORIZONTAL_ANCHOR_PERCENT,
                CaptionStyle.MAX_HORIZONTAL_ANCHOR_PERCENT,
            ),
            edgeMarginPercent = edgeMarginPercent.coerceIn(
                CaptionStyle.MIN_EDGE_MARGIN_PERCENT,
                CaptionStyle.MAX_EDGE_MARGIN_PERCENT,
            ),
        )
    }

    /** Saves whatever [previewCaptionPlacement] left in place. */
    fun commitCaptionPlacement() = editCaptionStyle { it }

    fun setCaptionPosition(value: CaptionPosition) = editCaptionStyle { it.copy(position = value) }
    fun setCaptionTextColor(value: CaptionTextColor) = editCaptionStyle { it.copy(textColor = value) }
    fun setCaptionEdgeStyle(value: CaptionEdgeStyle) = editCaptionStyle { it.copy(edgeStyle = value) }
    fun resetCaptionStyle() = editCaptionStyle { CaptionStyle() }

    fun persistentCaptionDelay(animeId: Int): Long? = _persistentCaptionDelays.value[animeId]

    /** A missing entry means carryover is off for this anime/season; zero is a valid saved delay. */
    fun setPersistentCaptionDelay(animeId: Int, delayMs: Long?) {
        if (animeId <= 0) return
        val next = _persistentCaptionDelays.value.toMutableMap().apply {
            if (delayMs == null) remove(animeId)
            else put(animeId, delayMs.coerceIn(-MAX_CAPTION_DELAY_MS, MAX_CAPTION_DELAY_MS))
        }.toMap()
        _persistentCaptionDelays.value = next
        scope.launch { store.edit { it[PERSISTENT_CAPTION_DELAYS] = encodePersistentCaptionDelays(next) } }
    }

    fun setScheduleCalendarView(value: Boolean) =
        save(SCHEDULE_CALENDAR_VIEW, value, _scheduleCalendarView)

    fun setHomeTab(value: String) {
        _homeTab.value = value
        scope.launch { store.edit { it[HOME_TAB] = value } }
    }

    fun setDefaultQuality(value: DefaultQuality) {
        _defaultQuality.value = value
        scope.launch { store.edit { it[DEFAULT_QUALITY] = value.storedValue } }
    }

    fun setPreferredAudioLanguage(value: PreferredAudioLanguage) {
        _preferredAudioLanguage.value = value
        scope.launch { store.edit { it[PREFERRED_AUDIO_LANGUAGE] = value.storedValue } }
    }

    fun setPreferredSubtitleLanguage(value: PreferredSubtitleLanguage) {
        _preferredSubtitleLanguage.value = value
        scope.launch { store.edit { it[PREFERRED_SUBTITLE_LANGUAGE] = value.storedValue } }
    }

    /** Blank restores "follow the device"; anything else is normalised the way the API wants it. */
    fun setSubtitleSearchLanguages(value: String) {
        val normalized = normalizeSubtitleLanguages(value)
        _subtitleSearchLanguages.value = normalized
        scope.launch { store.edit { it[SUBTITLE_SEARCH_LANGUAGES] = normalized } }
    }

    fun setPlayerContentScale(value: PlayerContentScale) {
        _playerContentScale.value = value
        scope.launch { store.edit { it[PLAYER_CONTENT_SCALE] = value.storedValue } }
    }

    fun setPlaybackBufferSize(value: PlaybackBufferSize) {
        _playbackBufferSize.value = value
        scope.launch { store.edit { it[PLAYBACK_BUFFER_SIZE] = value.storedValue } }
    }

    fun setVideoCacheSize(value: VideoCacheSize) {
        _videoCacheSize.value = value
        scope.launch { store.edit { it[VIDEO_CACHE_SIZE] = value.storedValue } }
    }

    fun setUiScale(value: UiScale) {
        _uiScale.value = value
        scope.launch { store.edit { it[UI_SCALE] = value.storedValue } }
    }

    fun setTvOverscanPercent(value: Int) {
        val clamped = value.coerceIn(0, MAX_TV_OVERSCAN_PERCENT)
        _tvOverscanPercent.value = clamped
        scope.launch { store.edit { it[TV_OVERSCAN_PERCENT] = clamped } }
    }

    fun setThemeBackground(value: ThemeBackground) {
        _themeBackground.value = value
        scope.launch { store.edit { it[THEME_BACKGROUND] = value.storedValue } }
    }

    fun setThemeAccent(value: ThemeAccent) {
        _themeAccent.value = value
        scope.launch { store.edit { it[THEME_ACCENT] = value.storedValue } }
    }

    fun setThemeStyle(value: ThemeStyle) {
        _themeStyle.value = value
        scope.launch { store.edit { it[THEME_STYLE] = value.storedValue } }
    }

    fun setThemeContrast(value: ThemeContrast) {
        _themeContrast.value = value
        scope.launch { store.edit { it[THEME_CONTRAST] = value.storedValue } }
    }

    fun setPlayerAccent(value: Boolean) = save(PLAYER_ACCENT, value, _playerAccent)

    fun setShowColourPages(value: Boolean) = save(SHOW_COLOUR_PAGES, value, _showColourPages)

    /** Chooses which kind of background is drawn; the stored anime and image are left as they are. */
    fun setAppBackdropSource(value: AppBackdropSource) {
        _appBackdropSource.value = value
        scope.launch { store.edit { it[APP_BACKDROP_SOURCE] = value.storedValue } }
    }

    /** Stores [url] (one of [title]'s backdrops) and makes the anime backdrop the background. */
    fun setAppBackdropAnime(url: String, title: String?) {
        _appBackdropAnimeUrl.value = url
        _appBackdropAnimeTitle.value = title
        _appBackdropSource.value = AppBackdropSource.ANIME
        scope.launch {
            store.edit {
                it[APP_BACKDROP_ANIME_URL] = url
                if (title == null) it.remove(APP_BACKDROP_ANIME_TITLE) else it[APP_BACKDROP_ANIME_TITLE] = title
                it[APP_BACKDROP_SOURCE] = AppBackdropSource.ANIME.storedValue
            }
        }
    }

    /** Stores [url] — a `file://` picture the app already copied — and makes it the background. */
    fun setAppBackdropImage(url: String) {
        _appBackdropImage.value = url
        _appBackdropSource.value = AppBackdropSource.IMAGE
        scope.launch {
            store.edit {
                it[APP_BACKDROP_IMAGE] = url
                it[APP_BACKDROP_SOURCE] = AppBackdropSource.IMAGE.storedValue
            }
        }
    }

    fun setAppBackdropDim(value: AppBackdropDim) {
        _appBackdropDim.value = value
        scope.launch { store.edit { it[APP_BACKDROP_DIM] = value.storedValue } }
    }

    fun setAppBackdropBlur(value: Boolean) = save(APP_BACKDROP_BLUR, value, _appBackdropBlur)

    /** The active profile's look, from the settings in effect right now. */
    fun currentLook(): ProfileLook = ProfileLook(
        accent = accentSeedOf(_themeAccent.value, _themeCustomSeed.value),
        backdropUrl = backdropUrlOf(_appBackdropSource.value, _appBackdropAnimeUrl.value, _appBackdropImage.value),
        dim = _appBackdropDim.value.alpha,
        blur = _appBackdropBlur.value,
    )

    /**
     * Another profile's look, read from its saved settings [file] — parsed directly rather than
     * opened as a DataStore, because the file is only ever read here and DataStore allows one open
     * store per file. Null when it cannot be read; the picker then shows that profile plainly.
     */
    suspend fun lookIn(file: java.io.File): ProfileLook? = withContext(Dispatchers.IO) {
        runCatching {
            file.source().buffer().use { androidx.datastore.preferences.core.PreferencesSerializer.readFrom(it) }
        }
            .onFailure { DiagnosticsLog.throwable("Profile look unreadable: ${file.parentFile?.name}", it) }
            .getOrNull()
            ?.let { prefs ->
                ProfileLook(
                    accent = accentSeedOf(
                        ThemeAccent.fromStored(prefs[THEME_ACCENT]),
                        prefs[THEME_CUSTOM_SEED] ?: DEFAULT_CUSTOM_SEED,
                    ),
                    backdropUrl = backdropUrlOf(
                        AppBackdropSource.fromStored(prefs[APP_BACKDROP_SOURCE]),
                        prefs[APP_BACKDROP_ANIME_URL],
                        prefs[APP_BACKDROP_IMAGE],
                    ),
                    dim = AppBackdropDim.fromStored(prefs[APP_BACKDROP_DIM]).alpha,
                    blur = prefs[APP_BACKDROP_BLUR] ?: true,
                )
            }
    }

    /** Stores [argb] as the custom colour and makes it the accent. */
    fun setThemeCustomSeed(argb: Int) {
        val opaque = argb or 0xFF000000.toInt()
        _themeCustomSeed.value = opaque
        _themeAccent.value = ThemeAccent.CUSTOM
        scope.launch {
            store.edit {
                it[THEME_CUSTOM_SEED] = opaque
                it[THEME_ACCENT] = ThemeAccent.CUSTOM.storedValue
            }
        }
    }

    /**
     * Applies [preset]'s four choices in one write, so the theme — and every screen reading it —
     * changes once rather than four times on the way there.
     */
    fun applyThemePreset(preset: ThemePreset) {
        _themeBackground.value = preset.background
        _themeAccent.value = preset.accent
        _themeStyle.value = preset.style
        _themeContrast.value = preset.contrast
        scope.launch {
            store.edit {
                it[THEME_BACKGROUND] = preset.background.storedValue
                it[THEME_ACCENT] = preset.accent.storedValue
                it[THEME_STYLE] = preset.style.storedValue
                it[THEME_CONTRAST] = preset.contrast.storedValue
            }
        }
    }

    /** Puts the theme back to AMOLED black and monochrome, at Material's standard contrast. */
    fun resetTheme() {
        _themeBackground.value = ThemeBackground.AMOLED
        _themeAccent.value = ThemeAccent.MONOCHROME
        _themeStyle.value = ThemeStyle.VIVID
        _themeContrast.value = ThemeContrast.STANDARD
        _playerAccent.value = false
        scope.launch {
            store.edit {
                it.remove(THEME_BACKGROUND)
                it.remove(THEME_ACCENT)
                it.remove(THEME_STYLE)
                it.remove(THEME_CONTRAST)
                it.remove(PLAYER_ACCENT)
            }
        }
    }

    fun setFormFactorPreference(value: FormFactorPreference) {
        _formFactorPreference.value = value
        scope.launch { store.edit { it[FORM_FACTOR_PREFERENCE] = value.storedValue } }
    }

    /**
     * The resolver is switched here rather than left to [applyPreferences], which would only reach
     * it once the write has been round-tripped through disk. The picker stays open on purpose so a
     * viewer can try resolvers against a source that was failing, and a choice that took a moment
     * to take effect would have them blaming the wrong one.
     */
    fun setDohProvider(value: DohProvider) {
        _dohProvider.value = value
        AppDns.select(value)
        scope.launch { store.edit { it[DOH_PROVIDER] = value.storedValue } }
    }

    /**
     * Records that the app was started from a television home screen. Write-once: this only ever
     * turns on, and re-writing it on every launch would wake the whole settings pipeline — which
     * re-applies every preference on each write — for a value that has not changed.
     */
    fun recordLeanbackLaunch() {
        if (_leanbackLauncherSeen.value) return
        _leanbackLauncherSeen.value = true
        scope.launch { store.edit { it[LEANBACK_LAUNCHER_SEEN] = true } }
    }

    fun setDownloadHardSubsOnly(value: Boolean) = save(DOWNLOAD_HARD_SUBS_ONLY, value, _downloadHardSubsOnly)

    fun setDownloadQuality(value: DownloadQuality) {
        _downloadQuality.value = value
        scope.launch { store.edit { it[DOWNLOAD_QUALITY] = value.storedValue } }
    }

    fun setParallelDownloads(value: Boolean) {
        _parallelDownloads.value = value
        scope.launch { store.edit { it[PARALLEL_DOWNLOADS] = value } }
    }

    fun setExternalPlayerPackage(value: String) {
        _externalPlayerPackage.value = value
        scope.launch { store.edit { it[EXTERNAL_PLAYER_PACKAGE] = value } }
    }

    fun setDownloadDestination(value: DownloadDestination) {
        _downloadDestination.value = value
        scope.launch { store.edit { it[DOWNLOAD_DESTINATION] = value.storedValue } }
    }

    fun setEpisodeLayout(value: EpisodeLayout) {
        _episodeLayout.value = value
        scope.launch { store.edit { it[EPISODE_LAYOUT] = value.storedValue } }
    }

    fun setEpisodeOrder(value: EpisodeOrder) {
        _episodeOrder.value = value
        scope.launch { store.edit { it[EPISODE_ORDER] = value.storedValue } }
    }

    fun setRelatedLayout(value: RelatedLayout) {
        _relatedLayout.value = value
        scope.launch { store.edit { it[RELATED_LAYOUT] = value.storedValue } }
    }

    fun setEpisodeDrawerLayout(value: EpisodeLayout) {
        _episodeDrawerLayout.value = value
        scope.launch { store.edit { it[EPISODE_DRAWER_LAYOUT] = value.storedValue } }
    }

    fun setMenuLanguage(value: MenuLanguage) {
        _menuLanguage.value = value
        scope.launch { store.edit { it[MENU_LANGUAGE] = value.storedValue } }
    }

    fun setClockFormat(value: ClockFormat) {
        _clockFormat.value = value
        scope.launch { store.edit { it[CLOCK_FORMAT] = value.storedValue } }
    }
    /** Normalises, de-duplicates and caps a priority list, then mirrors the head into [preferredProvider]. */
    private fun applyServerPriority(value: List<String>) {
        val clean = value.map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it != DEFAULT_PREFERRED_PROVIDER }
            .distinct()
            .take(MAX_SERVER_PRIORITY)
        _serverPriority.value = clean
        _preferredProvider.value = clean.firstOrNull() ?: DEFAULT_PREFERRED_PROVIDER
    }

    /** Normalises a set of server names to the lowercase, de-duplicated form the catalog keys on. */
    private fun applyDisabledProviders(value: Collection<String>): Set<String> {
        val clean = value.mapNotNullTo(linkedSetOf()) { it.trim().lowercase().ifBlank { null } }
        _disabledProviders.value = clean
        return clean
    }

    fun setPlayerGestures(value: Boolean) {
        _playerGestures.value = value
        scope.launch { store.edit { it[PLAYER_GESTURES] = value } }
    }

    fun setPlayerSeekGesture(value: Boolean) {
        _playerSeekGesture.value = value
        scope.launch { store.edit { it[PLAYER_SEEK_GESTURE] = value } }
    }

    /**
     * Records where the brightness swipe was let go. Called once per drag rather than per frame:
     * every write re-emits the whole preference file, and a drag is hundreds of touch events.
     */
    fun setPlayerBrightness(value: Float) {
        val clamped = coercePlayerBrightness(value)
        _playerBrightness.value = clamped
        scope.launch { store.edit { it[PLAYER_BRIGHTNESS] = clamped } }
    }

    fun setPlayerStatusBar(value: Boolean) {
        _playerStatusBar.value = value
        scope.launch { store.edit { it[PLAYER_STATUS_BAR] = value } }
    }

    fun setPlayerClock(value: Boolean) {
        _playerClock.value = value
        scope.launch { store.edit { it[PLAYER_CLOCK] = value } }
    }

    fun setTvDoubleBackToExit(value: Boolean) {
        _tvDoubleBackToExit.value = value
        scope.launch { store.edit { it[TV_DOUBLE_BACK_TO_EXIT] = value } }
    }

    fun setLastWorkingPipeOrigin(value: String) {
        if (_lastWorkingPipeOrigin.value == value) return
        _lastWorkingPipeOrigin.value = value
        scope.launch { store.edit { it[LAST_PIPE_ORIGIN] = value } }
    }

    fun setServerPriority(value: List<String>) {
        applyServerPriority(value)
        val stored = _serverPriority.value.joinToString(",")
        scope.launch { store.edit { it[SERVER_PRIORITY] = stored } }
    }

    /**
     * Switches servers off, and drops them from [serverPriority] in the same write.
     *
     * A ranked server that can never appear in a catalogue is not a preference — it is a claim the
     * rest of the app keeps repeating: the picker's ★, the player's "Settings pins X" line and
     * every queued download would go on naming a server that has been told not to answer, and the
     * resolver would try it first on every episode to learn that again.
     *
     * Both keys go in one `edit` because `applyPreferences` re-runs on every write. Two edits would
     * publish an intermediate state in between where the priority still held the name just turned
     * off, and the flows would visibly settle backwards before settling forwards.
     */
    fun setDisabledProviders(value: Set<String>) {
        val disabled = applyDisabledProviders(value)
        applyServerPriority(_serverPriority.value.filterNot { it in disabled })
        val storedDisabled = disabled.joinToString(",")
        val storedPriority = _serverPriority.value.joinToString(",")
        scope.launch {
            store.edit {
                it[DISABLED_PROVIDERS] = storedDisabled
                it[SERVER_PRIORITY] = storedPriority
            }
        }
    }

    fun setPreferredSourceLanguages(value: Set<SourceFilterLanguage>) {
        val normalized = SourceFilterLanguage.entries.filterTo(linkedSetOf()) { it in value }
        _preferredSourceLanguages.value = normalized
        val stored = encodeSourceFilterLanguages(normalized)
        scope.launch { store.edit { it[PREFERRED_SOURCE_LANGUAGES] = stored } }
    }

    fun setHiddenPosterElements(value: Set<PosterElement>) {
        val normalized = PosterElement.entries.filterTo(linkedSetOf()) { it in value }
        _hiddenPosterElements.value = normalized
        val stored = encodeHiddenPosterElements(normalized)
        scope.launch { store.edit { it[HIDDEN_POSTER_ELEMENTS] = stored } }
    }

    /** Null resets Home to the app's default order, and keeps following it. */
    fun setHomeLayout(value: String?) {
        val normalized = value?.takeIf(String::isNotBlank)
        _homeLayout.value = normalized
        scope.launch {
            store.edit { if (normalized == null) it.remove(HOME_LAYOUT) else it[HOME_LAYOUT] = normalized }
        }
    }

    /**
     * Promotes [value] to first choice, keeping the rest of the order behind it. Picking "auto"
     * clears the whole list — with no first choice there is nothing for the fallbacks to fall back
     * from, and leaving them would silently promote one the user never chose.
     */
    fun setPreferredProvider(value: String) {
        val name = value.trim().lowercase().ifBlank { DEFAULT_PREFERRED_PROVIDER }
        if (name == DEFAULT_PREFERRED_PROVIDER) {
            setServerPriority(emptyList())
        } else {
            setServerPriority(listOf(name) + _serverPriority.value.filterNot { it == name })
        }
    }

    /** Guarantees cold-start consumers see the persisted preference instead of the in-memory default. */
    suspend fun awaitLoaded() {
        loaded.first { it }
    }

    /**
     * Every backed-up preference this device has actually set, as plain JSON.
     *
     * Read from the store rather than from the flows above so an untouched setting stays *absent*
     * instead of being written out as whatever this device's default happened to be — that is what
     * lets [restoreSettings] tell "the source chose 720p" apart from "the source never chose".
     */
    suspend fun backupSettings(): JsonObject {
        val prefs = store.data.first()
        return buildJsonObject {
            BACKUP_BOOLEAN_KEYS.forEach { key -> prefs[key]?.let { put(key.name, it) } }
            BACKUP_INT_KEYS.forEach { key -> prefs[key]?.let { put(key.name, it) } }
            BACKUP_FLOAT_KEYS.forEach { key -> prefs[key]?.let { put(key.name, it) } }
            BACKUP_STRING_KEYS.forEach { key -> prefs[key]?.let { put(key.name, it) } }
        }
    }

    /**
     * Make this device's settings the ones in [values], and report how many landed.
     *
     * A restore reproduces the source device rather than merging with this one: every backed-up key
     * is cleared first, so a preference the file does not carry goes back to its default here
     * instead of keeping whatever this device happened to have. Half of "restore my settings" is
     * the settings you deliberately left alone.
     *
     * Nothing outside [backupKeys] is written, and a value of the wrong type is dropped rather than
     * stored — a hand-edited file that says `autoplay: "yes"` would otherwise persist a String
     * under a Boolean key, and every later read of it would throw. Values that are the right type
     * but out of range are left to [applyPreferences], which already clamps them.
     */
    suspend fun restoreSettings(values: JsonObject): Int {
        var applied = 0
        store.edit { prefs ->
            backupKeys.forEach { prefs -= it }
            BACKUP_BOOLEAN_KEYS.forEach { key ->
                val value = values.primitive(key.name)?.booleanOrNull ?: return@forEach
                prefs[key] = value
                applied++
            }
            BACKUP_INT_KEYS.forEach { key ->
                val value = values.primitive(key.name)?.intOrNull ?: return@forEach
                prefs[key] = value
                applied++
            }
            BACKUP_FLOAT_KEYS.forEach { key ->
                val value = values.primitive(key.name)?.floatOrNull ?: return@forEach
                prefs[key] = value
                applied++
            }
            BACKUP_STRING_KEYS.forEach { key ->
                val value = values.primitive(key.name)?.takeIf { it.isString }?.content
                    ?: return@forEach
                prefs[key] = value
                applied++
            }
        }
        return applied
    }

    private fun JsonObject.primitive(name: String): JsonPrimitive? = this[name] as? JsonPrimitive

    private fun save(key: Preferences.Key<Boolean>, value: Boolean, state: MutableStateFlow<Boolean>) {
        state.value = value
        scope.launch { store.edit { it[key] = value } }
    }

    private fun editCaptionStyle(transform: (CaptionStyle) -> CaptionStyle) {
        val next = transform(_captionStyle.value)
        _captionStyle.value = next
        scope.launch {
            store.edit { prefs ->
                prefs[CAPTION_BACKGROUND_OPACITY] = next.backgroundOpacityPercent
                prefs[CAPTION_BACKGROUND_COLOR] = next.backgroundColor.storedValue
                prefs[CAPTION_TEXT_SCALE] = next.textScalePercent
                prefs[CAPTION_TEXT_OPACITY] = next.textOpacityPercent
                prefs[CAPTION_BOLD_TEXT] = next.boldText
                prefs[CAPTION_FONT] = next.font.storedValue
                prefs[CAPTION_EDGE_MARGIN] = next.edgeMarginPercent
                prefs[CAPTION_HORIZONTAL_ANCHOR] = next.horizontalAnchorPercent
                prefs[CAPTION_POSITION] = next.position.storedValue
                prefs[CAPTION_TEXT_COLOR] = next.textColor.storedValue
                prefs[CAPTION_EDGE_STYLE] = next.edgeStyle.storedValue
            }
        }
    }

    internal fun readCaptionStyle(prefs: Preferences): CaptionStyle = CaptionStyle(
        backgroundOpacityPercent = prefs[CAPTION_BACKGROUND_OPACITY]?.coerceIn(0, 100)
            ?: CaptionStyle.DEFAULT_BACKGROUND_OPACITY_PERCENT,
        backgroundColor = CaptionBackgroundColor.fromStored(prefs[CAPTION_BACKGROUND_COLOR]),
        textScalePercent = prefs[CAPTION_TEXT_SCALE]?.coerceIn(CaptionStyle.MIN_TEXT_SCALE_PERCENT, CaptionStyle.MAX_TEXT_SCALE_PERCENT)
            ?: CaptionStyle.DEFAULT_TEXT_SCALE_PERCENT,
        textOpacityPercent = prefs[CAPTION_TEXT_OPACITY]?.coerceIn(0, 100)
            ?: CaptionStyle.DEFAULT_TEXT_OPACITY_PERCENT,
        boldText = prefs[CAPTION_BOLD_TEXT] ?: CaptionStyle.DEFAULT_BOLD_TEXT,
        font = CaptionFont.fromStored(prefs[CAPTION_FONT]),
        edgeMarginPercent = prefs[CAPTION_EDGE_MARGIN]?.coerceIn(CaptionStyle.MIN_EDGE_MARGIN_PERCENT, CaptionStyle.MAX_EDGE_MARGIN_PERCENT)
            ?: CaptionStyle.DEFAULT_EDGE_MARGIN_PERCENT,
        horizontalAnchorPercent = prefs[CAPTION_HORIZONTAL_ANCHOR]
            ?.coerceIn(CaptionStyle.MIN_HORIZONTAL_ANCHOR_PERCENT, CaptionStyle.MAX_HORIZONTAL_ANCHOR_PERCENT)
            ?: CaptionStyle.DEFAULT_HORIZONTAL_ANCHOR_PERCENT,
        position = CaptionPosition.fromStored(prefs[CAPTION_POSITION]),
        textColor = CaptionTextColor.fromStored(prefs[CAPTION_TEXT_COLOR]),
        edgeStyle = CaptionEdgeStyle.fromStored(prefs[CAPTION_EDGE_STYLE]),
    )

    private fun applyPreferences(prefs: Preferences) {
        _autoplay.value = prefs[AUTOPLAY] ?: true
        _playOnOpen.value = prefs[PLAY_ON_OPEN] ?: true
        _openEpisodesInExternalPlayer.value = prefs[OPEN_EPISODES_IN_EXTERNAL_PLAYER] ?: false
        _externalPlayerPackage.value = prefs[EXTERNAL_PLAYER_PACKAGE].orEmpty()
        _pictureInPicture.value = prefs[PICTURE_IN_PICTURE] ?: true
        _pauseDetailsOverlay.value = prefs[PAUSE_DETAILS_OVERLAY] ?: true
        _autoSyncAniList.value = prefs[AUTO_SYNC] ?: true
        _preferDub.value = prefs[PREFER_DUB] ?: false
        _releaseNotifications.value = prefs[RELEASE_NOTIFICATIONS] ?: true
        _announcements.value = prefs[ANNOUNCEMENTS] ?: true
        _syncSavedToAniList.value = prefs[SYNC_SAVED_TO_ANILIST] ?: true
        _autoSkipIntroOutro.value = prefs[AUTO_SKIP_INTRO_OUTRO] ?: false
        _autoSkipFiller.value = prefs[AUTO_SKIP_FILLER] ?: false
        _showSkipButton.value = prefs[SHOW_SKIP_BUTTON] ?: true
        _skipButtonSeconds.value = (prefs[SKIP_BUTTON_SECONDS] ?: SKIP_BUTTON_DEFAULT_SECONDS)
            .coerceIn(SKIP_BUTTON_MIN_SECONDS, SKIP_BUTTON_MAX_SECONDS)
        _watchedThresholdPercent.value = (prefs[WATCHED_THRESHOLD_PERCENT] ?: WATCHED_THRESHOLD_DEFAULT_PERCENT)
            .coerceIn(WATCHED_THRESHOLD_MIN_PERCENT, WATCHED_THRESHOLD_MAX_PERCENT)
        _defaultPlaybackSpeed.value =
            coercePlaybackSpeed(prefs[DEFAULT_PLAYBACK_SPEED_KEY] ?: DEFAULT_PLAYBACK_SPEED)
        _holdToSpeedEnabled.value = prefs[HOLD_TO_SPEED_ENABLED] ?: true
        _holdPlaybackSpeed.value =
            coercePlaybackSpeed(prefs[HOLD_PLAYBACK_SPEED] ?: DEFAULT_HOLD_PLAYBACK_SPEED)
        _hideAdultContent.value = prefs[HIDE_ADULT_CONTENT] ?: true
        _blurEpisodeImages.value = prefs[BLUR_EPISODE_IMAGES] ?: false
        _hideAiringTomorrowFromContinue.value = prefs[HIDE_AIRING_TOMORROW_FROM_CONTINUE] ?: false
        _showSeriesLogos.value = prefs[SHOW_SERIES_LOGOS] ?: true
        _secondaryAudioLanguage.value = PreferredAudioLanguage.fromStored(prefs[SECONDARY_AUDIO_LANGUAGE])
        _secondarySubtitleLanguage.value =
            PreferredSubtitleLanguage.fromStored(prefs[SECONDARY_SUBTITLE_LANGUAGE])
        _showLoadingOverlay.value = prefs[SHOW_LOADING_OVERLAY] ?: true
        _subtitlesWithDub.value = prefs[SUBTITLES_WITH_DUB] ?: false
        _subtitleSignsWithDialogue.value = prefs[SUBTITLE_SIGNS_WITH_DIALOGUE] ?: true
        _subtitlesByDefault.value = prefs[SUBTITLES_BY_DEFAULT] ?: true
        _updateCheckOnLaunch.value = prefs[UPDATE_CHECK_ON_LAUNCH] ?: true
        _staticAppChrome.value = prefs[STATIC_APP_CHROME] ?: false
        GlassSection.entries.forEach { _glass.getValue(it).value = prefs[GLASS_KEYS.getValue(it)] ?: true }
        _searchAsYouType.value = prefs[SEARCH_AS_YOU_TYPE] ?: true
        _videoLaunchIntro.value = prefs[VIDEO_LAUNCH_INTRO] ?: true
        _appIconMatchesTheme.value = prefs[APP_ICON_MATCHES_THEME] ?: true
        _discordRichPresence.value = prefs[DISCORD_RICH_PRESENCE] ?: false
        _discordStatus.value = DiscordStatus.fromStored(prefs[DISCORD_STATUS])
        _captionStyle.value = readCaptionStyle(prefs)
        _persistentCaptionDelays.value = decodePersistentCaptionDelays(prefs[PERSISTENT_CAPTION_DELAYS])
        _menuLanguage.value = MenuLanguage.fromStored(prefs[MENU_LANGUAGE])
        _clockFormat.value = ClockFormat.fromStored(prefs[CLOCK_FORMAT])
        _homeTab.value = prefs[HOME_TAB]
        _scheduleCalendarView.value = prefs[SCHEDULE_CALENDAR_VIEW] ?: false
        _defaultQuality.value = prefs[DEFAULT_QUALITY]?.let(DefaultQuality::fromStored)
            ?: deviceDefaultQuality()
        _preferredAudioLanguage.value = PreferredAudioLanguage.fromStored(prefs[PREFERRED_AUDIO_LANGUAGE])
        _preferredSubtitleLanguage.value =
            PreferredSubtitleLanguage.fromStored(prefs[PREFERRED_SUBTITLE_LANGUAGE])
        _subtitleSearchLanguages.value = prefs[SUBTITLE_SEARCH_LANGUAGES].orEmpty()
        _playerContentScale.value = PlayerContentScale.fromStored(prefs[PLAYER_CONTENT_SCALE])
        _playbackBufferSize.value = PlaybackBufferSize.fromStored(prefs[PLAYBACK_BUFFER_SIZE])
        _videoCacheSize.value = VideoCacheSize.fromStored(prefs[VIDEO_CACHE_SIZE])
        _uiScale.value = UiScale.fromStored(prefs[UI_SCALE])
        _tvOverscanPercent.value = (prefs[TV_OVERSCAN_PERCENT] ?: 0).coerceIn(0, MAX_TV_OVERSCAN_PERCENT)
        _themeBackground.value = ThemeBackground.fromStored(prefs[THEME_BACKGROUND])
        _themeAccent.value = ThemeAccent.fromStored(prefs[THEME_ACCENT])
        _themeStyle.value = ThemeStyle.fromStored(prefs[THEME_STYLE])
        _themeContrast.value = ThemeContrast.fromStored(prefs[THEME_CONTRAST])
        _playerAccent.value = prefs[PLAYER_ACCENT] ?: false
        _themeCustomSeed.value = prefs[THEME_CUSTOM_SEED] ?: DEFAULT_CUSTOM_SEED
        _showColourPages.value = prefs[SHOW_COLOUR_PAGES] ?: true
        _appBackdropSource.value = AppBackdropSource.fromStored(prefs[APP_BACKDROP_SOURCE])
        _appBackdropAnimeUrl.value = prefs[APP_BACKDROP_ANIME_URL]
        _appBackdropAnimeTitle.value = prefs[APP_BACKDROP_ANIME_TITLE]
        _appBackdropImage.value = prefs[APP_BACKDROP_IMAGE]
        _appBackdropDim.value = AppBackdropDim.fromStored(prefs[APP_BACKDROP_DIM])
        _appBackdropBlur.value = prefs[APP_BACKDROP_BLUR] ?: true
        _formFactorPreference.value = FormFactorPreference.fromStored(prefs[FORM_FACTOR_PREFERENCE])
        _leanbackLauncherSeen.value = prefs[LEANBACK_LAUNCHER_SEEN] ?: false
        // Both of the above feed AppGraph.isTv, which was seeded from the device's signals alone on
        // the startup path — this file had not been read yet. Now it has.
        appContext?.let { AppGraph.refreshIsTv(isTelevision(it)) }
        _downloadQuality.value = DownloadQuality.fromStored(prefs[DOWNLOAD_QUALITY])
        _parallelDownloads.value = prefs[PARALLEL_DOWNLOADS] ?: false
        _downloadDestination.value = DownloadDestination.fromStored(prefs[DOWNLOAD_DESTINATION])
        _downloadHardSubsOnly.value = prefs[DOWNLOAD_HARD_SUBS_ONLY] ?: false
        _episodeLayout.value = EpisodeLayout.fromStored(prefs[EPISODE_LAYOUT])
        _episodeOrder.value = EpisodeOrder.fromStored(prefs[EPISODE_ORDER])
        _relatedLayout.value = RelatedLayout.fromStored(prefs[RELATED_LAYOUT])
        _browseHeaderCollapsed.value = prefs[BROWSE_HEADER_COLLAPSED] ?: false
        _browseHideWatched.value = WatchedFilter.fromStored(prefs[BROWSE_HIDE_WATCHED])
        _episodeDrawerLayout.value = prefs[EPISODE_DRAWER_LAYOUT]
            ?.let { stored -> EpisodeLayout.entries.firstOrNull { it.storedValue == stored } }
        // Installs that predate the ordered list still carry a single preferred server; seed the
        // list from it so an existing choice survives the upgrade rather than resetting to auto.
        applyServerPriority(
            prefs[SERVER_PRIORITY]?.takeIf(String::isNotBlank)?.split(",")
                ?: listOfNotNull(prefs[PREFERRED_PROVIDER]?.takeIf(String::isNotBlank)),
        )
        applyDisabledProviders(prefs[DISABLED_PROVIDERS]?.split(",").orEmpty())
        // Commas cannot occur inside a MAL tag — MAL itself stores them comma-separated.
        _hiddenMalTags.value = prefs[HIDDEN_MAL_TAGS]?.split(",")
            ?.map(String::trim)?.filterTo(sortedSetOf()) { it.isNotEmpty() }
            .orEmpty()
        _preferredSourceLanguages.value =
            decodeSourceFilterLanguages(prefs[PREFERRED_SOURCE_LANGUAGES])
        _hiddenPosterElements.value = decodeHiddenPosterElements(prefs[HIDDEN_POSTER_ELEMENTS])
        _homeLayout.value = prefs[HOME_LAYOUT]?.takeIf(String::isNotBlank)
        // Also the path that installs the stored resolver at startup, which is why AppDns holds the
        // system one until here. `select` ignores a repeat, so the re-run this method gets on every
        // other setting's write does not disturb a resolver that is already answering.
        _dohProvider.value = DohProvider.fromStored(prefs[DOH_PROVIDER]).also(AppDns::select)
        _playerGestures.value = prefs[PLAYER_GESTURES] ?: true
        _playerSeekGesture.value = prefs[PLAYER_SEEK_GESTURE] ?: true
        _playerBrightness.value =
            coercePlayerBrightness(prefs[PLAYER_BRIGHTNESS] ?: PLAYER_BRIGHTNESS_FOLLOW_SYSTEM)
        _playerStatusBar.value = prefs[PLAYER_STATUS_BAR] ?: true
        _playerClock.value = prefs[PLAYER_CLOCK] ?: true
        _tvDoubleBackToExit.value = prefs[TV_DOUBLE_BACK_TO_EXIT] ?: true
        _lastWorkingPipeOrigin.value = prefs[LAST_PIPE_ORIGIN].orEmpty()
        loaded.value = true
    }

    /** Copies [DEVICE_KEYS] from the profile just switched away from into the live file. */
    private suspend fun carryDeviceSettings(from: java.io.File) {
        if (!from.exists()) return
        val readScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val outgoing = try {
            PreferenceDataStoreFactory.create(scope = readScope, produceFile = { from }).data.first()
        } finally {
            readScope.cancel()
        }
        store.edit { live -> DEVICE_KEYS.forEach { key -> copyPreference(key, outgoing, live) } }
    }

    private fun <T> copyPreference(
        key: Preferences.Key<T>,
        from: Preferences,
        to: androidx.datastore.preferences.core.MutablePreferences,
    ) {
        val value = from[key]
        if (value == null) to.remove(key) else to[key] = value
    }

    private suspend fun migrateLegacyPreferences(context: Context) {
        val current = store.data.first()
        if (current[MIGRATED] == true) return
        val legacy = context.getSharedPreferences("anilili_settings", Context.MODE_PRIVATE)
        store.edit { prefs ->
            prefs[AUTOPLAY] = legacy.getBoolean("autoplay", true)
            prefs[AUTO_SYNC] = legacy.getBoolean("auto_sync_anilist", true)
            prefs[PREFER_DUB] = legacy.getBoolean("prefer_dub", false)
            prefs[RELEASE_NOTIFICATIONS] = true
            prefs[SYNC_SAVED_TO_ANILIST] = true
            prefs[AUTO_SKIP_INTRO_OUTRO] = false
            prefs[MIGRATED] = true
        }
        legacy.edit().clear().apply()
    }

    /**
     * Gives hold-to-speed its own stored answer before anything reads it, so the switch stops
     * following [playerGestures] around. Runs before the first [applyPreferences]; once the key
     * exists there is nothing left to seed, and an install that never touched the old switch keeps
     * both keys absent and both defaults on.
     */
    private suspend fun seedHoldToSpeedFromLegacyGestures() {
        val prefs = store.data.first()
        val seed = holdToSpeedSeed(prefs[HOLD_TO_SPEED_ENABLED], prefs[PLAYER_GESTURES]) ?: return
        store.edit { it[HOLD_TO_SPEED_ENABLED] = seed }
    }

    private val AUTOPLAY = booleanPreferencesKey("autoplay")
    private val PLAY_ON_OPEN = booleanPreferencesKey("play_on_open")
    private val OPEN_EPISODES_IN_EXTERNAL_PLAYER =
        booleanPreferencesKey("open_episodes_in_external_player")
    private val EXTERNAL_PLAYER_PACKAGE = stringPreferencesKey("external_player_package")
    private val PICTURE_IN_PICTURE = booleanPreferencesKey("picture_in_picture")
    private val PAUSE_DETAILS_OVERLAY = booleanPreferencesKey("pause_details_overlay")
    private val AUTO_SYNC = booleanPreferencesKey("auto_sync_anilist")
    private val PREFER_DUB = booleanPreferencesKey("prefer_dub")
    private val RELEASE_NOTIFICATIONS = booleanPreferencesKey("release_notifications")
    private val ANNOUNCEMENTS = booleanPreferencesKey("announcements")
    private val SYNC_SAVED_TO_ANILIST = booleanPreferencesKey("sync_saved_to_anilist")
    private val AUTO_SKIP_INTRO_OUTRO = booleanPreferencesKey("auto_skip_intro_outro")
    private val AUTO_SKIP_FILLER = booleanPreferencesKey("auto_skip_filler")
    private val SHOW_SKIP_BUTTON = booleanPreferencesKey("show_skip_button")
    private val SKIP_BUTTON_SECONDS = intPreferencesKey("skip_button_seconds")
    private val WATCHED_THRESHOLD_PERCENT = intPreferencesKey("watched_threshold_percent")
    private val DEFAULT_PLAYBACK_SPEED_KEY = floatPreferencesKey("default_playback_speed")
    private val HOLD_TO_SPEED_ENABLED = booleanPreferencesKey("hold_to_speed_enabled")
    private val HOLD_PLAYBACK_SPEED = floatPreferencesKey("hold_playback_speed")
    private val HIDE_ADULT_CONTENT = booleanPreferencesKey("hide_adult_content")
    private val BLUR_EPISODE_IMAGES = booleanPreferencesKey("blur_episode_images")
    private val HIDE_AIRING_TOMORROW_FROM_CONTINUE =
        booleanPreferencesKey("hide_airing_tomorrow_from_continue")
    private val SHOW_SERIES_LOGOS = booleanPreferencesKey("show_series_logos")
    private val SECONDARY_AUDIO_LANGUAGE = stringPreferencesKey("secondary_audio_language")
    private val SECONDARY_SUBTITLE_LANGUAGE = stringPreferencesKey("secondary_subtitle_language")
    private val SHOW_LOADING_OVERLAY = booleanPreferencesKey("show_loading_overlay")
    private val SUBTITLES_WITH_DUB = booleanPreferencesKey("subtitles_with_dub")
    private val SUBTITLE_SIGNS_WITH_DIALOGUE = booleanPreferencesKey("subtitle_signs_with_dialogue")
    private val SUBTITLES_BY_DEFAULT = booleanPreferencesKey("subtitles_by_default")
    private val UPDATE_CHECK_ON_LAUNCH = booleanPreferencesKey("update_check_on_launch")
    private val STATIC_APP_CHROME = booleanPreferencesKey("static_app_chrome")
    private val GLASS_KEYS = GlassSection.entries.associateWith { booleanPreferencesKey("glass_${it.name.lowercase()}") }
    private val SEARCH_AS_YOU_TYPE = booleanPreferencesKey("search_as_you_type")
    private val VIDEO_LAUNCH_INTRO = booleanPreferencesKey("video_launch_intro")
    private val APP_ICON_MATCHES_THEME = booleanPreferencesKey("app_icon_matches_theme")
    private val DISCORD_RICH_PRESENCE = booleanPreferencesKey("discord_rich_presence")
    private val DISCORD_STATUS = stringPreferencesKey("discord_status")
    private val CAPTION_BACKGROUND_OPACITY = intPreferencesKey("caption_background_opacity")
    private val CAPTION_BACKGROUND_COLOR = stringPreferencesKey("caption_background_color")
    private val CAPTION_TEXT_SCALE = intPreferencesKey("caption_text_scale")
    private val CAPTION_TEXT_OPACITY = intPreferencesKey("caption_text_opacity")
    private val CAPTION_BOLD_TEXT = booleanPreferencesKey("caption_bold_text")
    private val CAPTION_FONT = stringPreferencesKey("caption_font")
    // Stored under its old name so the margin an existing install already chose survives the
    // rename it took when captions learned to sit against the top edge too.
    private val CAPTION_EDGE_MARGIN = intPreferencesKey("caption_bottom_margin")
    private val CAPTION_HORIZONTAL_ANCHOR = intPreferencesKey("caption_horizontal_anchor")
    private val CAPTION_POSITION = stringPreferencesKey("caption_position")
    private val CAPTION_TEXT_COLOR = stringPreferencesKey("caption_text_color")
    private val CAPTION_EDGE_STYLE = stringPreferencesKey("caption_edge_style")
    private val PERSISTENT_CAPTION_DELAYS = stringPreferencesKey("persistent_caption_delays")
    private val MENU_LANGUAGE = stringPreferencesKey("menu_language")
    private val CLOCK_FORMAT = stringPreferencesKey("clock_format")
    private val HOME_TAB = stringPreferencesKey("home_tab")
    private val SCHEDULE_CALENDAR_VIEW = booleanPreferencesKey("schedule_calendar_view")
    private val DEFAULT_QUALITY = stringPreferencesKey("default_quality")
    private val PREFERRED_AUDIO_LANGUAGE = stringPreferencesKey("preferred_audio_language")
    private val PREFERRED_SUBTITLE_LANGUAGE = stringPreferencesKey("preferred_subtitle_language")
    private val SUBTITLE_SEARCH_LANGUAGES = stringPreferencesKey("subtitle_search_languages")
    private val PLAYER_CONTENT_SCALE = stringPreferencesKey("player_content_scale")
    private val PLAYBACK_BUFFER_SIZE = stringPreferencesKey("playback_buffer_size")
    private val VIDEO_CACHE_SIZE = stringPreferencesKey("video_cache_size")
    private val UI_SCALE = stringPreferencesKey("ui_scale")
    private val TV_OVERSCAN_PERCENT = intPreferencesKey("tv_overscan_percent")
    private val THEME_BACKGROUND = stringPreferencesKey("theme_background")
    private val THEME_ACCENT = stringPreferencesKey("theme_accent")
    private val THEME_STYLE = stringPreferencesKey("theme_style")
    private val THEME_CONTRAST = stringPreferencesKey("theme_contrast")
    private val PLAYER_ACCENT = booleanPreferencesKey("player_accent")
    private val THEME_CUSTOM_SEED = intPreferencesKey("theme_custom_seed")
    private val SHOW_COLOUR_PAGES = booleanPreferencesKey("show_colour_pages")
    private val APP_BACKDROP_SOURCE = stringPreferencesKey("app_backdrop_source")
    private val APP_BACKDROP_ANIME_URL = stringPreferencesKey("app_backdrop_anime_url")
    private val APP_BACKDROP_ANIME_TITLE = stringPreferencesKey("app_backdrop_anime_title")
    private val APP_BACKDROP_IMAGE = stringPreferencesKey("app_backdrop_image")
    private val APP_BACKDROP_DIM = stringPreferencesKey("app_backdrop_dim")
    private val APP_BACKDROP_BLUR = booleanPreferencesKey("app_backdrop_blur")
    private val FORM_FACTOR_PREFERENCE = stringPreferencesKey("form_factor_preference")
    private val LEANBACK_LAUNCHER_SEEN = booleanPreferencesKey("leanback_launcher_seen")
    private val DOWNLOAD_QUALITY = stringPreferencesKey("download_quality")
    private val PARALLEL_DOWNLOADS = booleanPreferencesKey("parallel_downloads")
    private val DOWNLOAD_DESTINATION = stringPreferencesKey("download_destination")
    private val DOWNLOAD_HARD_SUBS_ONLY = booleanPreferencesKey("download_hard_subs_only")
    private val EPISODE_LAYOUT = stringPreferencesKey("episode_layout")
    private val EPISODE_ORDER = stringPreferencesKey("episode_order")
    private val RELATED_LAYOUT = stringPreferencesKey("related_layout")
    private val BROWSE_HEADER_COLLAPSED = booleanPreferencesKey("browse_header_collapsed")
    private val BROWSE_HIDE_WATCHED = stringPreferencesKey("browse_hide_watched")
    private val EPISODE_DRAWER_LAYOUT = stringPreferencesKey("episode_drawer_layout")
    private val PREFERRED_PROVIDER = stringPreferencesKey("preferred_provider")
    private val SERVER_PRIORITY = stringPreferencesKey("server_priority")
    private val DISABLED_PROVIDERS = stringPreferencesKey("disabled_providers")
    private val HIDDEN_MAL_TAGS = stringPreferencesKey("hidden_mal_tags")
    private val PREFERRED_SOURCE_LANGUAGES = stringPreferencesKey("preferred_source_languages")
    private val HIDDEN_POSTER_ELEMENTS = stringPreferencesKey("hidden_poster_elements")
    private val HOME_LAYOUT = stringPreferencesKey("home_layout")
    private val DOH_PROVIDER = stringPreferencesKey("doh_provider")
    private val LAST_PIPE_ORIGIN = stringPreferencesKey("last_pipe_origin")
    private val PLAYER_GESTURES = booleanPreferencesKey("player_gestures")
    private val PLAYER_SEEK_GESTURE = booleanPreferencesKey("player_seek_gesture")
    private val PLAYER_BRIGHTNESS = floatPreferencesKey("player_brightness")
    private val PLAYER_STATUS_BAR = booleanPreferencesKey("player_status_bar")
    private val PLAYER_CLOCK = booleanPreferencesKey("player_clock")
    private val TV_DOUBLE_BACK_TO_EXIT = booleanPreferencesKey("tv_double_back_to_exit")
    private val MIGRATED = booleanPreferencesKey("migrated_from_shared_preferences")

    /**
     * Settings that describe the television rather than whoever is watching it. Every other setting
     * belongs to a profile (Profiles moves this whole file with it); these are copied across a
     * switch so they stay the TV's. The form-factor pair matters most: it picks which APK the
     * updater installs, and a profile that forgot the box is a TV would update it to the phone build.
     */
    private val DEVICE_KEYS: List<Preferences.Key<*>> = listOf(
        FORM_FACTOR_PREFERENCE,
        LEANBACK_LAUNCHER_SEEN,
        DOH_PROVIDER,
        LAST_PIPE_ORIGIN,
        DOWNLOAD_DESTINATION,
        VIDEO_CACHE_SIZE,
        PLAYBACK_BUFFER_SIZE,
        UPDATE_CHECK_ON_LAUNCH,
        APP_ICON_MATCHES_THEME,
        // How much this box's picture overshoots this panel. Every profile watches the same screen.
        TV_OVERSCAN_PERCENT,
    )

    // The backup registry. It sits at the bottom of the object because these lists are property
    // initialisers like any other and run in declaration order — written above the keys, they
    // would capture nulls.
    //
    // Grouped by stored type rather than kept as one list, because a backup has to be written and
    // read back at the right type: `Preferences.Key<*>` erases to nothing that could tell a
    // Boolean from an Int, and guessing from the JSON is how a String ends up under a Boolean key.

    private val BACKUP_BOOLEAN_KEYS = listOf(
        PARALLEL_DOWNLOADS,
        AUTOPLAY,
        BROWSE_HEADER_COLLAPSED,
        PLAY_ON_OPEN,
        OPEN_EPISODES_IN_EXTERNAL_PLAYER,
        PICTURE_IN_PICTURE,
        PAUSE_DETAILS_OVERLAY,
        AUTO_SYNC,
        PREFER_DUB,
        RELEASE_NOTIFICATIONS,
        ANNOUNCEMENTS,
        SYNC_SAVED_TO_ANILIST,
        AUTO_SKIP_INTRO_OUTRO,
        AUTO_SKIP_FILLER,
        SHOW_SKIP_BUTTON,
        HIDE_ADULT_CONTENT,
        BLUR_EPISODE_IMAGES,
        DOWNLOAD_HARD_SUBS_ONLY,
        HIDE_AIRING_TOMORROW_FROM_CONTINUE,
        SHOW_SERIES_LOGOS,
        SHOW_LOADING_OVERLAY,
        SUBTITLES_WITH_DUB,
        SUBTITLE_SIGNS_WITH_DIALOGUE,
        SUBTITLES_BY_DEFAULT,
        UPDATE_CHECK_ON_LAUNCH,
        STATIC_APP_CHROME,
        SEARCH_AS_YOU_TYPE,
        VIDEO_LAUNCH_INTRO,
        APP_ICON_MATCHES_THEME,
        PLAYER_ACCENT,
        SHOW_COLOUR_PAGES,
        APP_BACKDROP_BLUR,
        SCHEDULE_CALENDAR_VIEW,
        // Carried in a backup like every other switch. It cannot act on its own: a restore onto a
        // device that has not signed in to Discord leaves nothing for it to publish through.
        DISCORD_RICH_PRESENCE,
        CAPTION_BOLD_TEXT,
        HOLD_TO_SPEED_ENABLED,
        PLAYER_GESTURES,
        PLAYER_SEEK_GESTURE,
        PLAYER_STATUS_BAR,
        PLAYER_CLOCK,
        TV_DOUBLE_BACK_TO_EXIT,
    ) + GLASS_KEYS.values

    private val BACKUP_INT_KEYS = listOf(
        THEME_CUSTOM_SEED,
        SKIP_BUTTON_SECONDS,
        WATCHED_THRESHOLD_PERCENT,
        CAPTION_BACKGROUND_OPACITY,
        CAPTION_TEXT_SCALE,
        CAPTION_TEXT_OPACITY,
        CAPTION_EDGE_MARGIN,
        CAPTION_HORIZONTAL_ANCHOR,
    )

    // The brightness travels as a fraction of whatever range the screen has, so it means the same
    // thing on the device a backup lands on as it did on the one it left.
    private val BACKUP_FLOAT_KEYS =
        listOf(DEFAULT_PLAYBACK_SPEED_KEY, HOLD_PLAYBACK_SPEED, PLAYER_BRIGHTNESS)

    private val BACKUP_STRING_KEYS = listOf(
        EPISODE_DRAWER_LAYOUT,
        CAPTION_BACKGROUND_COLOR,
        CAPTION_POSITION,
        CAPTION_TEXT_COLOR,
        CAPTION_FONT,
        CAPTION_EDGE_STYLE,
        PERSISTENT_CAPTION_DELAYS,
        MENU_LANGUAGE,
        // Backed up like the menu language beside it: it is a decision about how the viewer reads a
        // clock, not an observation about one device, and SYSTEM — the default — restores as itself
        // and defers to whatever the next device says.
        CLOCK_FORMAT,
        DISCORD_STATUS,
        HOME_TAB,
        DEFAULT_QUALITY,
        PREFERRED_AUDIO_LANGUAGE,
        PREFERRED_SUBTITLE_LANGUAGE,
        SECONDARY_AUDIO_LANGUAGE,
        SECONDARY_SUBTITLE_LANGUAGE,
        SUBTITLE_SEARCH_LANGUAGES,
        PLAYER_CONTENT_SCALE,
        // Backed up despite reading as a hardware fact, because it is not one: it is a decision
        // about a connection, and someone who needed a longer buffer for their Wi-Fi is on the same
        // Wi-Fi on their next device. The size a device cannot afford is clamped by its own heap
        // when it is applied, so a television inheriting a phone's choice degrades rather than
        // breaks — and AUTOMATIC, which is the hardware answer, restores as itself.
        PLAYBACK_BUFFER_SIZE,
        UI_SCALE,
        // A taste, not a hardware fact: the viewer who chose blood red wants it on the next device.
        THEME_BACKGROUND,
        THEME_ACCENT,
        THEME_STYLE,
        THEME_CONTRAST,
        // How the viewer likes to browse, not a fact about this device.
        BROWSE_HIDE_WATCHED,
        // The anime backdrop is a URL, so it means the same picture on the next device. The
        // viewer's own image is a file on this one and stays behind (see [nonBackupKeys]); a
        // restored "Your own image" with no file draws the plain canvas until one is picked.
        APP_BACKDROP_SOURCE,
        APP_BACKDROP_ANIME_URL,
        APP_BACKDROP_ANIME_TITLE,
        APP_BACKDROP_DIM,
        DOWNLOAD_QUALITY,
        DOWNLOAD_DESTINATION,
        EPISODE_LAYOUT,
        EPISODE_ORDER,
        RELATED_LAYOUT,
        SERVER_PRIORITY,
        DISABLED_PROVIDERS,
        PREFERRED_SOURCE_LANGUAGES,
        // A decision about how the viewer wants a card to read, not an observation about this
        // device, so it travels with the rest of them.
        HIDDEN_POSTER_ELEMENTS,
        // How the viewer arranged Home: a taste, like the poster elements above.
        HOME_LAYOUT,
        // The viewer's decision about which of their MyAnimeList tags are lists, which holds for
        // the same account on the next device.
        HIDDEN_MAL_TAGS,
        // Backed up, unlike the mirror origin below, because it is a decision about who to trust
        // and not an observation about one network: someone whose ISP blocks a source still has
        // that ISP on their next device, and a restore that dropped this would read as the block
        // coming back. A resolver that turns out to be unreachable there costs nothing — AppDns
        // falls back to the system one rather than stranding the app.
        DOH_PROVIDER,
    )

    /** Everything a backup carries, in one list for the wipe that precedes a restore. */
    internal val backupKeys: List<Preferences.Key<*>> =
        BACKUP_BOOLEAN_KEYS + BACKUP_INT_KEYS + BACKUP_FLOAT_KEYS + BACKUP_STRING_KEYS

    /**
     * Preferences deliberately left out of a backup, named so `SettingsBackupKeyTest` can tell an
     * omission apart from an oversight — a setting added later and forgotten here fails that test
     * rather than silently going missing from everyone's backups.
     *
     * [LAST_PIPE_ORIGIN] is which mirror answered on *this* network, so carrying it to another
     * device would seed a failover walk with an origin that device has never reached.
     * [MIGRATED] is a marker for a migration that runs per install. [PREFERRED_PROVIDER] is the
     * single-server preference [SERVER_PRIORITY] replaced; it is read only to upgrade installs
     * that predate the list, and a restore writes the list itself.
     *
     * The two form-factor keys are the same kind of thing in a less obvious way: both describe the
     * hardware the app is running on, and a backup is most often carried *between* two pieces of
     * hardware. Restoring a phone's backup onto a television would pin it to the handheld layout —
     * the exact fault the override exists to undo — and restoring a television's would hand a phone
     * a leanback-launcher observation about a device it is not.
     *
     * [EXTERNAL_PLAYER_PACKAGE] joins them for the same reason: it names an app installed on *this*
     * device. Carried to another one it is at best inert — the launch falls back to the chooser —
     * and at worst it reports a player in settings that the viewer cannot see anywhere. Whether
     * episodes go out to another player at all is a preference and is backed up; *which* one is a
     * fact about the device and is not.
     *
     * [VIDEO_CACHE_SIZE] is disk space on *this* device. A phone with room for 2 GB of cache says
     * nothing about the 8 GB television the backup is restored onto, and AUTOMATIC — what a fresh
     * install gets — already sizes itself to whichever device it lands on.
     *
     * [APP_BACKDROP_IMAGE] is a `file://` path into *this* install's files; on another device it
     * points at nothing.
     *
     * [TV_OVERSCAN_PERCENT] measures how far *this* box's output overshoots *this* panel. The next
     * television may crop nothing, and an inset carried onto it would frame the app in a border
     * the viewer never asked for there.
     */
    internal val nonBackupKeys: List<Preferences.Key<*>> = listOf(
        APP_BACKDROP_IMAGE,
        LAST_PIPE_ORIGIN,
        MIGRATED,
        PREFERRED_PROVIDER,
        FORM_FACTOR_PREFERENCE,
        LEANBACK_LAUNCHER_SEEN,
        EXTERNAL_PLAYER_PACKAGE,
        VIDEO_CACHE_SIZE,
        TV_OVERSCAN_PERCENT,
    )
}

internal fun encodePersistentCaptionDelays(delays: Map<Int, Long>): String = delays
    .asSequence()
    .filter { (animeId, _) -> animeId > 0 }
    .sortedBy { (animeId, _) -> animeId }
    .joinToString(",") { (animeId, delayMs) ->
        "$animeId:${delayMs.coerceIn(-MAX_CAPTION_DELAY_MS, MAX_CAPTION_DELAY_MS)}"
    }

internal fun decodePersistentCaptionDelays(stored: String?): Map<Int, Long> = stored
    .orEmpty()
    .split(',')
    .mapNotNull { entry ->
        val separator = entry.indexOf(':')
        if (separator <= 0 || separator == entry.lastIndex) return@mapNotNull null
        val animeId = entry.substring(0, separator).toIntOrNull()?.takeIf { it > 0 }
            ?: return@mapNotNull null
        val delayMs = entry.substring(separator + 1).toLongOrNull()
            ?.coerceIn(-MAX_CAPTION_DELAY_MS, MAX_CAPTION_DELAY_MS)
            ?: return@mapNotNull null
        animeId to delayMs
    }
    .toMap()
