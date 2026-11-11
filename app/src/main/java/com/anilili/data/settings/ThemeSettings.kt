package com.anilili.data.settings

/**
 * The choices the Appearance section offers, stored as plain values.
 *
 * Only names live here. What each one *looks like* is `ui/theme/AppColorScheme.kt`'s business —
 * the Material 3 scheme generation — so the data layer can persist and back these up without
 * reaching into Compose.
 */

/** The canvas: what shows where nothing else is drawn. */
enum class ThemeBackground(val storedValue: String, val label: String, val description: String) {
    AMOLED("amoled", "AMOLED black", "Pure black; OLED screens switch those pixels off"),
    DARK("dark", "Dark", "Material's dark grey, a little softer in a lit room");

    companion object {
        fun fromStored(value: String?): ThemeBackground =
            entries.firstOrNull { it.storedValue == value } ?: AMOLED
    }
}

/**
 * The seed every colour in the scheme is generated from.
 *
 * [MONOCHROME] is the default and the app's long-standing look: white is the accent, which carries
 * further across a room than any hue and never clashes with the artwork behind it. [WALLPAPER] is
 * Material You — the seed the system extracted from the wallpaper — and only exists on Android 12+.
 * [CUSTOM] is whatever the viewer mixed in the custom colour dialog.
 */
enum class ThemeAccent(val storedValue: String, val label: String, val seed: Long?) {
    MONOCHROME("monochrome", "Monochrome", null),
    WALLPAPER("wallpaper", "From wallpaper", null),
    /** The viewer's own colour, stored beside it as `theme_custom_seed`. */
    CUSTOM("custom", "Custom", null),
    PURPLE("purple", "Purple", 0xFF8B5CF6),
    NEON_GREEN("neon_green", "Neon green", 0xFF39FF14),
    BLOOD_RED("blood_red", "Blood red", 0xFFD1102B),
    OCEAN_BLUE("ocean_blue", "Ocean blue", 0xFF3B82F6),
    CYAN("cyan", "Cyan", 0xFF22D3EE),
    SAKURA_PINK("sakura_pink", "Sakura pink", 0xFFF472B6),
    SUNSET_ORANGE("sunset_orange", "Sunset orange", 0xFFF97316),
    GOLD("gold", "Gold", 0xFFFACC15),
    EMERALD("emerald", "Emerald", 0xFF10B981);

    companion object {
        fun fromStored(value: String?): ThemeAccent =
            entries.firstOrNull { it.storedValue == value } ?: MONOCHROME
    }
}

/**
 * How Material 3 spreads the seed across the scheme.
 *
 * [VIVID] is the default because it is the only one that keeps the accent the viewer picked: every
 * stock M3 dark scheme draws `primary` at tone 80, which turns blood red salmon and neon green
 * pastel. Vivid generates with Fidelity and then pins `primary` to the seed itself.
 */
enum class ThemeStyle(val storedValue: String, val label: String, val description: String) {
    VIVID("vivid", "Vivid", "The exact colour you picked, surfaces lightly tinted"),
    TONAL("tonal", "Tonal", "Material You's default: a softer, pastel accent"),
    NEUTRAL("neutral", "Neutral", "Mostly grey, with the accent used sparingly"),
    VIBRANT("vibrant", "Vibrant", "Saturated colour on surfaces as well as controls");

    companion object {
        fun fromStored(value: String?): ThemeStyle =
            entries.firstOrNull { it.storedValue == value } ?: VIVID
    }
}

/** Material 3's contrast level, which moves every tone pair further apart as it rises. */
enum class ThemeContrast(
    val storedValue: String,
    val label: String,
    val description: String,
    val level: Double,
) {
    STANDARD("standard", "Standard", "Material's default contrast", 0.0),
    MEDIUM("medium", "Medium", "Brighter text and firmer outlines", 0.5),
    HIGH("high", "High", "The strongest separation, for glare or low vision", 1.0);

    companion object {
        fun fromStored(value: String?): ThemeContrast =
            entries.firstOrNull { it.storedValue == value } ?: STANDARD
    }
}

/** The custom accent a fresh install starts from: the purple the app used to be. */
const val DEFAULT_CUSTOM_SEED: Int = 0xFF8B5CF6.toInt()

/**
 * A named look: canvas, accent, style and contrast chosen together, the way a shadcn theme is one
 * pick rather than four. Applying one writes the four settings the rows below it show, so a preset
 * is a starting point and every row stays free to fine-tune it; the player switch is left alone,
 * since it is a choice about the player rather than about a look.
 */
enum class ThemePreset(
    val label: String,
    val background: ThemeBackground,
    val accent: ThemeAccent,
    val style: ThemeStyle,
    val contrast: ThemeContrast = ThemeContrast.STANDARD,
) {
    MIDNIGHT("Midnight", ThemeBackground.AMOLED, ThemeAccent.MONOCHROME, ThemeStyle.VIVID),
    CRIMSON_NIGHT("Crimson Night", ThemeBackground.AMOLED, ThemeAccent.BLOOD_RED, ThemeStyle.VIVID),
    TOXIC("Toxic", ThemeBackground.AMOLED, ThemeAccent.NEON_GREEN, ThemeStyle.VIBRANT),
    ROYAL("Royal", ThemeBackground.AMOLED, ThemeAccent.PURPLE, ThemeStyle.VIVID),
    DEEP_SEA("Deep Sea", ThemeBackground.AMOLED, ThemeAccent.OCEAN_BLUE, ThemeStyle.VIVID),
    CYBER("Cyber", ThemeBackground.AMOLED, ThemeAccent.CYAN, ThemeStyle.VIBRANT),
    SAKURA("Sakura", ThemeBackground.DARK, ThemeAccent.SAKURA_PINK, ThemeStyle.TONAL),
    EMBER("Ember", ThemeBackground.AMOLED, ThemeAccent.SUNSET_ORANGE, ThemeStyle.VIVID),
    GILDED("Gilded", ThemeBackground.AMOLED, ThemeAccent.GOLD, ThemeStyle.NEUTRAL),
    FOREST("Forest", ThemeBackground.DARK, ThemeAccent.EMERALD, ThemeStyle.TONAL),
    HIGH_CONTRAST(
        "High contrast", ThemeBackground.AMOLED, ThemeAccent.MONOCHROME, ThemeStyle.VIVID, ThemeContrast.HIGH,
    );

    fun matches(
        background: ThemeBackground,
        accent: ThemeAccent,
        style: ThemeStyle,
        contrast: ThemeContrast,
    ): Boolean =
        // Monochrome has no hue for a style to spread, so its style row is disabled and a preset
        // must not stop matching because of a value the viewer cannot see or change.
        this.background == background && this.accent == accent && this.contrast == contrast &&
            (accent == ThemeAccent.MONOCHROME || this.style == style)
}

/**
 * What the app draws where it used to draw the plain canvas colour.
 *
 * [ANIME] is a backdrop picked from a title's artwork (TMDB, TheTVDB or AniList), stored as its URL;
 * [IMAGE] is the viewer's own picture, copied into the app's files when it was picked. Each keeps its
 * own value, so going back to None and then to the other kind does not lose what was chosen before.
 */
enum class AppBackdropSource(val storedValue: String, val label: String, val description: String) {
    NONE("none", "None", "The plain background colour above"),
    ANIME("anime", "From an anime", "Pick a title, then one of its backdrops"),
    IMAGE("image", "Your own image", "A picture from this device");

    companion object {
        fun fromStored(value: String?): AppBackdropSource =
            entries.firstOrNull { it.storedValue == value } ?: NONE
    }
}

/**
 * How far a background image is darkened. Posters and text sit on top of it, so even the lightest
 * setting keeps white copy readable over a bright frame.
 */
enum class AppBackdropDim(val storedValue: String, val label: String, val description: String, val alpha: Float) {
    LIGHT("light", "Light", "More of the picture, for calm or dark artwork", 0.5f),
    MEDIUM("medium", "Medium", "A balance of picture and readable text", 0.65f),
    STRONG("strong", "Strong", "Mostly dark, for busy or bright artwork", 0.8f);

    companion object {
        fun fromStored(value: String?): AppBackdropDim =
            entries.firstOrNull { it.storedValue == value } ?: MEDIUM
    }
}

/**
 * How one profile's app looks, as far as the TV's "Who's watching?" screen shows it: the background
 * picture it chose, darkened and blurred as it asked, and its accent colour for when it chose none.
 * [accent] is null for Monochrome (and for "From wallpaper", which a television does not have).
 */
data class ProfileLook(
    val accent: Int?,
    val backdropUrl: String?,
    val dim: Float,
    val blur: Boolean,
)

internal fun accentSeedOf(accent: ThemeAccent, customSeed: Int): Int? = when (accent) {
    ThemeAccent.MONOCHROME, ThemeAccent.WALLPAPER -> null
    ThemeAccent.CUSTOM -> customSeed or 0xFF000000.toInt()
    else -> accent.seed?.toInt()
}

internal fun backdropUrlOf(source: AppBackdropSource, animeUrl: String?, imageUrl: String?): String? = when (source) {
    AppBackdropSource.NONE -> null
    AppBackdropSource.ANIME -> animeUrl
    AppBackdropSource.IMAGE -> imageUrl
}
