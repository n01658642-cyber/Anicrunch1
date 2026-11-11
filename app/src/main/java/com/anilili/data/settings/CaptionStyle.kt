package com.anilili.data.settings

/**
 * Subtitle appearance shared by the native (Media3) and embed (WebView) players.
 *
 * Media3's own fallback is white text on `Color.BLACK` with no alpha — an opaque box across the
 * picture — and it only yields to the device's caption settings when the user has switched those
 * on in Accessibility, which almost nobody does. These defaults keep the familiar white-on-dark
 * look but let the video through, and every field is user-overridable.
 */
data class CaptionStyle(
    val backgroundOpacityPercent: Int = DEFAULT_BACKGROUND_OPACITY_PERCENT,
    val backgroundColor: CaptionBackgroundColor = CaptionBackgroundColor.BLACK,
    /**
     * The font scale the players actually use, 50–200. The editor shows [textSizeValue] instead —
     * see [textScaleForSize] for why the stored number and the shown one are not the same.
     */
    val textScalePercent: Int = DEFAULT_TEXT_SCALE_PERCENT,
    val textOpacityPercent: Int = DEFAULT_TEXT_OPACITY_PERCENT,
    val boldText: Boolean = DEFAULT_BOLD_TEXT,
    val font: CaptionFont = CaptionFont.SANS,
    /** Gap between the captions and whichever screen edge [position] holds them against. */
    val edgeMarginPercent: Int = DEFAULT_EDGE_MARGIN_PERCENT,
    /** Where across the picture the caption sits: 0 is the left edge, 50 centred, 100 the right. */
    val horizontalAnchorPercent: Int = DEFAULT_HORIZONTAL_ANCHOR_PERCENT,
    val position: CaptionPosition = CaptionPosition.BOTTOM,
    val textColor: CaptionTextColor = CaptionTextColor.WHITE,
    val edgeStyle: CaptionEdgeStyle = CaptionEdgeStyle.NONE,
) {
    /** Background tint with the chosen opacity folded into the alpha channel. */
    val backgroundArgb: Int get() = argbOf(backgroundColor.rgb, backgroundOpacityPercent)

    val textArgb: Int get() = argbOf(textColor.rgb, textOpacityPercent)

    /**
     * The outline or shadow, faded with the text it draws around.
     *
     * Media3 takes the edge colour separately from the text colour, so leaving it opaque while the
     * text faded left a hollow black tracing of every letter behind — which reads as a *different*
     * caption rather than a fainter one, and makes the text opacity setting look broken at the
     * bottom of its range.
     */
    val edgeArgb: Int get() = argbOf(0x000000, textOpacityPercent)

    /** The size control's own 0–100 reading of [textScalePercent]. */
    val textSizeValue: Int get() = textSizeForScale(textScalePercent)

    /** Media3 expresses the safe-area gap as a fraction of the subtitle view's height. */
    val edgePaddingFraction: Float get() = edgeMarginPercent.coerceIn(0, 100) / 100f

    /** The horizontal anchor as media3 wants it: a fraction of the picture's width. */
    val horizontalCuePosition: Float get() = horizontalAnchorPercent.coerceIn(0, 100) / 100f

    /** True while the captions sit where media3 and every embed player would put them anyway. */
    val horizontallyCentred: Boolean get() = horizontalAnchorPercent == DEFAULT_HORIZONTAL_ANCHOR_PERCENT

    /** `rgba(...)` form of [backgroundArgb] for the WebView players' injected stylesheet. */
    fun backgroundCssRgba(): String = cssRgba(backgroundColor.rgb, backgroundOpacityPercent)

    /** `rgba(...)` form of [textArgb]; a hex colour could not carry the text opacity. */
    fun textCssRgba(): String = cssRgba(textColor.rgb, textOpacityPercent)

    companion object {
        const val DEFAULT_BACKGROUND_OPACITY_PERCENT = 60
        const val DEFAULT_TEXT_OPACITY_PERCENT = 100
        const val DEFAULT_TEXT_SCALE_PERCENT = 100
        const val DEFAULT_BOLD_TEXT = true
        const val DEFAULT_EDGE_MARGIN_PERCENT = 12
        const val DEFAULT_HORIZONTAL_ANCHOR_PERCENT = 50

        /**
         * Discrete steps rather than a slider: the player menu has to be drivable by a TV remote,
         * where a Slider is close to unusable.
         */
        val BACKGROUND_OPACITY_STEPS = listOf(0, 25, 40, 60, 80, 100)

        /**
         * The text's own alpha, all the way down to nothing.
         *
         * Fully transparent text is a real choice and not a way of switching captions off: an
         * outline with no fill is legible over bright animation in a way solid white is not, and
         * a viewer learning the language can leave the line invisible until they want it.
         */
        val TEXT_OPACITY_STEPS = listOf(0, 25, 50, 75, 90, 100)

        /**
         * What the size control shows: one plain 0–100 scale rather than a font percentage.
         *
         * "100%" meant *unchanged*, which read as the top of the range, so the two steps above it
         * looked like a mistake and the ones below like a discount. The rungs are unchanged in what
         * they do — [TEXT_SCALE_STEPS] is the same ladder in the units the players use — they are
         * just numbered by how far along the range they sit.
         */
        val TEXT_SIZE_STEPS = listOf(0, 20, 40, 50, 60, 80, 100)

        /**
         * [TEXT_SIZE_STEPS] in the font scale the players actually apply, rung for rung.
         *
         * The floor is half size: 75% used to be the smallest and was still too large on a
         * television, where the caption is a fraction of a very tall picture. The ladder stays
         * seven wide because the selector drawing it is a fixed row of equal cells with nowhere to
         * scroll; [TEXT_SIZE_FINE_STEP] reaches everything between the rungs.
         */
        val TEXT_SCALE_STEPS = TEXT_SIZE_STEPS.map { textScaleForSize(it) }

        /** What one press of the size fine-tune is worth, on the shown scale. */
        const val TEXT_SIZE_FINE_STEP = 5

        /** 0 is a real choice: captions flat against the edge, with no gap held clear at all. */
        val EDGE_MARGIN_STEPS = listOf(0, 4, 8, 12, 20, 35, 60)

        /** Named rungs for the horizontal anchor; the nudge and the drag reach the rest. */
        val HORIZONTAL_ANCHOR_STEPS = listOf(0, 25, 50, 75, 100)

        /** One press of either placement nudge, as a percentage of the picture. */
        const val PLACEMENT_FINE_STEP_PERCENT = 5

        const val MIN_TEXT_SIZE = 0
        const val MAX_TEXT_SIZE = 100
        const val MIN_TEXT_SCALE_PERCENT = 50
        const val MAX_TEXT_SCALE_PERCENT = 200
        const val MIN_EDGE_MARGIN_PERCENT = 0

        /**
         * A margin may take the whole picture, which walks the captions off the edge they are
         * anchored against and out of sight — asked for, and the only way to keep a hard-subbed
         * encode's own burnt-in line company without a second one over it. Media3 stops drawing
         * once the padding leaves no canvas (`CanvasSubtitleOutput` returns early), so nothing is
         * half-clipped on the way out.
         */
        const val MAX_EDGE_MARGIN_PERCENT = 100
        const val MIN_HORIZONTAL_ANCHOR_PERCENT = 0
        const val MAX_HORIZONTAL_ANCHOR_PERCENT = 100

        /**
         * The shown 0–100 size to the font scale the players take.
         *
         * Two segments rather than one, so the midpoint of the scale is the unchanged size: the
         * range is lopsided (half size below, double above), and a single linear mapping would have
         * put "normal" at 33 and made the default look like an arbitrary setting rather than the
         * middle of the dial.
         */
        fun textScaleForSize(value: Int): Int {
            val shown = value.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
            val half = MAX_TEXT_SIZE / 2
            return if (shown <= half) {
                MIN_TEXT_SCALE_PERCENT + shown
            } else {
                DEFAULT_TEXT_SCALE_PERCENT + (shown - half) * 2
            }
        }

        /** [textScaleForSize] backwards, for reading a stored font scale onto the shown scale. */
        fun textSizeForScale(percent: Int): Int {
            val scale = percent.coerceIn(MIN_TEXT_SCALE_PERCENT, MAX_TEXT_SCALE_PERCENT)
            val half = MAX_TEXT_SIZE / 2
            return if (scale <= DEFAULT_TEXT_SCALE_PERCENT) {
                scale - MIN_TEXT_SCALE_PERCENT
            } else {
                half + (scale - DEFAULT_TEXT_SCALE_PERCENT) / 2
            }
        }

        private fun argbOf(rgb: Int, opacityPercent: Int): Int {
            val alpha = opacityPercent.coerceIn(0, 100) * 255 / 100
            return (alpha shl 24) or (rgb and 0xFFFFFF)
        }

        private fun cssRgba(rgb: Int, opacityPercent: Int): String {
            val alpha = opacityPercent.coerceIn(0, 100) / 100.0
            return "rgba(${(rgb shr 16) and 0xFF}, ${(rgb shr 8) and 0xFF}, ${rgb and 0xFF}, " +
                "${String.format(java.util.Locale.US, "%.2f", alpha)})"
        }
    }
}

/**
 * Which edge of the picture the captions are held against.
 *
 * [TOP] is for the encodes that burn their own subtitles across the bottom of the frame, and for
 * the players who would rather read above the action than over it. It moves only the lines that
 * sit at the default bottom anchor; a sign the file placed itself stays with the thing it labels.
 */
enum class CaptionPosition(val storedValue: String, val label: String) {
    BOTTOM("bottom", "Bottom"),
    TOP("top", "Top");

    companion object {
        fun fromStored(value: String?): CaptionPosition =
            entries.firstOrNull { it.storedValue == value } ?: BOTTOM
    }
}

enum class CaptionTextColor(val storedValue: String, val label: String, val rgb: Int) {
    WHITE("white", "White", 0xFFFFFF),
    YELLOW("yellow", "Yellow", 0xFFEB3B),
    CYAN("cyan", "Cyan", 0x4DD0E1),
    GREEN("green", "Green", 0x81C784),
    MAGENTA("magenta", "Magenta", 0xFF80AB);

    companion object {
        fun fromStored(value: String?): CaptionTextColor =
            entries.firstOrNull { it.storedValue == value } ?: WHITE
    }
}

enum class CaptionBackgroundColor(val storedValue: String, val label: String, val rgb: Int) {
    BLACK("black", "Black", 0x000000),
    GRAY("gray", "Gray", 0x4A4A4A),
    NAVY("navy", "Navy", 0x102A43),
    WHITE("white", "White", 0xFFFFFF);

    companion object {
        fun fromStored(value: String?): CaptionBackgroundColor =
            entries.firstOrNull { it.storedValue == value } ?: BLACK
    }
}

/**
 * The caption typeface, drawn only from families every Android build already carries in its
 * `fonts.xml` — no font is bundled. A box whose vendor stripped one of them (cheap TV images
 * sometimes drop the decorative ones) resolves the name to the default sans, so a missing family
 * degrades to the caption that was there before rather than to nothing.
 *
 * [cssFamily] is null for [SANS] so the embed players keep their own face, exactly as they did
 * before this setting existed; every one of them already draws captions in a sans.
 */
enum class CaptionFont(
    val storedValue: String,
    val label: String,
    /** The `fonts.xml` family the native player asks `Typeface.create` for; null is the default. */
    val systemFamily: String?,
    val cssFamily: String?,
) {
    SANS("sans", "Sans", null, null),
    SERIF("serif", "Serif", "serif", "serif"),
    MONOSPACE("mono", "Mono", "monospace", "monospace"),
    CONDENSED("condensed", "Narrow", "sans-serif-condensed", "'sans-serif-condensed', 'Roboto Condensed', sans-serif"),
    CASUAL("casual", "Casual", "casual", "casual, 'Coming Soon', 'Comic Sans MS', cursive"),
    CURSIVE("cursive", "Script", "cursive", "'Dancing Script', cursive");

    companion object {
        fun fromStored(value: String?): CaptionFont =
            entries.firstOrNull { it.storedValue == value } ?: SANS
    }
}

enum class CaptionEdgeStyle(val storedValue: String, val label: String) {
    NONE("none", "None"),
    OUTLINE("outline", "Outline"),
    DROP_SHADOW("shadow", "Shadow");

    companion object {
        fun fromStored(value: String?): CaptionEdgeStyle =
            entries.firstOrNull { it.storedValue == value } ?: NONE
    }
}
