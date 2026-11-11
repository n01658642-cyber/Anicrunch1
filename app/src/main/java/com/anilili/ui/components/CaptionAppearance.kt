package com.anilili.ui.components

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anilili.data.settings.CaptionBackgroundColor
import com.anilili.data.settings.CaptionEdgeStyle
import com.anilili.data.settings.CaptionFont
import com.anilili.data.settings.CaptionPosition
import com.anilili.data.settings.CaptionStyle
import com.anilili.data.settings.CaptionTextColor
import com.anilili.data.settings.SettingsStore
import com.anilili.ui.dialogs.MidnightDialogFooter
import com.anilili.ui.dialogs.MidnightDialogHost
import com.anilili.ui.dialogs.MidnightHelper
import com.anilili.ui.dialogs.MidnightMediaHeader
import com.anilili.ui.dialogs.MidnightSectionLabel
import com.anilili.ui.dialogs.MidnightStepperRow
import com.anilili.ui.dialogs.MidnightUnderlineSelector
import kotlin.math.roundToInt

private const val PREVIEW_SAMPLE = "The quick brown fox jumps"

/**
 * The sample's height as a fraction of the pad's, so changing the size setting moves the preview
 * by the proportion it will move the real caption.
 *
 * Deliberately larger than media3's own `DEFAULT_TEXT_SIZE_FRACTION` (0.0533): the pad is a few
 * hundred pixels tall where the picture is a thousand, and at the true fraction the small end of
 * the size range came out as an illegible smear that showed nothing about placement.
 */
private const val PREVIEW_TEXT_HEIGHT_FRACTION = 0.09f

/**
 * How wide the pad is allowed to get.
 *
 * It has to be 16:9 to be an honest picture, and 16:9 across the full width of the dialog is a pad
 * more than half the height of a television screen — the settings under it were pushed off the
 * bottom, one visible row at a time, because the pad is pinned above the scrolling body. Capped and
 * centred instead, which leaves it comfortably draggable and the rows reachable.
 */
private val PAD_MAX_WIDTH = 340.dp

/**
 * Editor for [CaptionStyle], shared by Settings and both players' in-playback menus so the two
 * entry points can never drift apart.
 */
@Composable
fun CaptionAppearanceEditor(
    modifier: Modifier = Modifier,
    footnote: String? = null,
    showPreview: Boolean = true,
) {
    val style by SettingsStore.captionStyle.collectAsState()
    Column(modifier.fillMaxWidth()) {
        if (showPreview) CaptionPlacementPad(style)
        CaptionChoiceRow(
            label = "Position",
            options = CaptionPosition.entries,
            selected = style.position,
            labelOf = CaptionPosition::label,
            onSelect = SettingsStore::setCaptionPosition,
            // One control takes the D-pad cursor when the editor opens; the rest must not fight
            // it for the same frame.
            claimFocus = true,
        )
        CaptionChoiceRow(
            // The margin holds whichever edge the captions were sent to, so the label follows them
            // rather than claiming to be a bottom gap while the captions sit at the top.
            label = if (style.position == CaptionPosition.TOP) "Top margin" else "Bottom margin",
            options = CaptionStyle.EDGE_MARGIN_STEPS,
            selected = style.edgeMarginPercent,
            labelOf = { "$it%" },
            onSelect = SettingsStore::setCaptionEdgeMargin,
        )
        MidnightStepperRow(
            label = "Fine tune margin",
            value = style.edgeMarginPercent.marginLabel(),
            onDecrease = {
                SettingsStore.setCaptionEdgeMargin(
                    style.edgeMarginPercent - CaptionStyle.PLACEMENT_FINE_STEP_PERCENT,
                )
            },
            onIncrease = {
                SettingsStore.setCaptionEdgeMargin(
                    style.edgeMarginPercent + CaptionStyle.PLACEMENT_FINE_STEP_PERCENT,
                )
            },
            decreaseLabel = "−${CaptionStyle.PLACEMENT_FINE_STEP_PERCENT}%",
            increaseLabel = "+${CaptionStyle.PLACEMENT_FINE_STEP_PERCENT}%",
            decreaseEnabled = style.edgeMarginPercent > CaptionStyle.MIN_EDGE_MARGIN_PERCENT,
            increaseEnabled = style.edgeMarginPercent < CaptionStyle.MAX_EDGE_MARGIN_PERCENT,
        )
        CaptionChoiceRow(
            label = "Across the picture",
            options = CaptionStyle.HORIZONTAL_ANCHOR_STEPS,
            selected = style.horizontalAnchorPercent,
            labelOf = { it.horizontalAnchorLabel() },
            onSelect = SettingsStore::setCaptionHorizontalAnchor,
        )
        MidnightStepperRow(
            label = "Fine tune across",
            value = "${style.horizontalAnchorPercent}%",
            onDecrease = {
                SettingsStore.setCaptionHorizontalAnchor(
                    style.horizontalAnchorPercent - CaptionStyle.PLACEMENT_FINE_STEP_PERCENT,
                )
            },
            onIncrease = {
                SettingsStore.setCaptionHorizontalAnchor(
                    style.horizontalAnchorPercent + CaptionStyle.PLACEMENT_FINE_STEP_PERCENT,
                )
            },
            decreaseLabel = "◂ ${CaptionStyle.PLACEMENT_FINE_STEP_PERCENT}%",
            increaseLabel = "${CaptionStyle.PLACEMENT_FINE_STEP_PERCENT}% ▸",
            decreaseEnabled = style.horizontalAnchorPercent > CaptionStyle.MIN_HORIZONTAL_ANCHOR_PERCENT,
            increaseEnabled = style.horizontalAnchorPercent < CaptionStyle.MAX_HORIZONTAL_ANCHOR_PERCENT,
        )
        CaptionChoiceRow(
            label = "Text size",
            options = CaptionStyle.TEXT_SIZE_STEPS,
            selected = style.textSizeValue,
            labelOf = { "$it" },
            onSelect = SettingsStore::setCaptionTextSize,
        )
        // The seven offered sizes are the common ones, not the only ones — a television at three
        // metres wants a size a ladder of seven will always miss. Same presets-plus-nudge shape as
        // Skip button length and Watched at, so there is one way to fine-tune a number in this app.
        MidnightStepperRow(
            label = "Fine tune size",
            value = "${style.textSizeValue}",
            onDecrease = {
                SettingsStore.setCaptionTextSize(style.textSizeValue - CaptionStyle.TEXT_SIZE_FINE_STEP)
            },
            onIncrease = {
                SettingsStore.setCaptionTextSize(style.textSizeValue + CaptionStyle.TEXT_SIZE_FINE_STEP)
            },
            decreaseLabel = "−${CaptionStyle.TEXT_SIZE_FINE_STEP}",
            increaseLabel = "+${CaptionStyle.TEXT_SIZE_FINE_STEP}",
            decreaseEnabled = style.textSizeValue > CaptionStyle.MIN_TEXT_SIZE,
            increaseEnabled = style.textSizeValue < CaptionStyle.MAX_TEXT_SIZE,
        )
        CaptionChoiceRow(
            label = "Text opacity",
            options = CaptionStyle.TEXT_OPACITY_STEPS,
            selected = style.textOpacityPercent,
            labelOf = { if (it == 0) "Clear" else "$it%" },
            onSelect = SettingsStore::setCaptionTextOpacity,
        )
        CaptionChoiceRow(
            label = "Text color",
            options = CaptionTextColor.entries,
            selected = style.textColor,
            labelOf = CaptionTextColor::label,
            onSelect = SettingsStore::setCaptionTextColor,
        )
        CaptionChoiceRow(
            label = "Font",
            options = CaptionFont.entries,
            selected = style.font,
            labelOf = CaptionFont::label,
            onSelect = SettingsStore::setCaptionFont,
        )
        CaptionChoiceRow(
            label = "Text weight",
            options = listOf(false, true),
            selected = style.boldText,
            labelOf = { if (it) "Bold" else "Regular" },
            onSelect = SettingsStore::setCaptionBold,
        )
        CaptionChoiceRow(
            label = "Edge style",
            options = CaptionEdgeStyle.entries,
            selected = style.edgeStyle,
            labelOf = CaptionEdgeStyle::label,
            onSelect = SettingsStore::setCaptionEdgeStyle,
        )
        CaptionChoiceRow(
            label = "Background opacity",
            options = CaptionStyle.BACKGROUND_OPACITY_STEPS,
            selected = style.backgroundOpacityPercent,
            labelOf = { if (it == 0) "Off" else "$it%" },
            onSelect = SettingsStore::setCaptionBackgroundOpacity,
        )
        CaptionChoiceRow(
            label = "Background color",
            options = CaptionBackgroundColor.entries,
            selected = style.backgroundColor,
            labelOf = CaptionBackgroundColor::label,
            onSelect = SettingsStore::setCaptionBackgroundColor,
        )
        if (footnote != null) {
            Text(
                footnote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** [CaptionAppearanceEditor] as a dialog; dialogs take D-pad focus reliably on TV. */
@Composable
fun CaptionAppearanceDialog(
    onDismiss: () -> Unit,
    footnote: String? = null,
) {
    val style by SettingsStore.captionStyle.collectAsState()
    MidnightDialogHost(
        onDismiss = onDismiss,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = "Subtitles",
                title = "Caption appearance",
                metadata = null,
                onDismiss = onDismiss.takeIf { showClose },
            )
            // Pinned outside the scrolling body so every option change is visible immediately
            // instead of requiring a swipe back up to the preview.
            CaptionPlacementPad(style, Modifier.padding(top = 14.dp))
        },
        footer = {
            MidnightDialogFooter(
                dangerText = "Reset",
                onDanger = SettingsStore::resetCaptionStyle,
                quietText = "Close",
                onQuiet = onDismiss,
            )
        },
    ) {
        CaptionAppearanceEditor(footnote = footnote, showPreview = false)
    }
}

/**
 * The preview, and the quickest way to place a caption: drag the sample anywhere in the picture.
 *
 * Shaped 16:9 and sized from its own height so what is drawn here is what the player will draw —
 * the ladders and nudges below set the same two numbers, and are what a remote uses, since a drag
 * needs a finger.
 *
 * The drag writes through [SettingsStore.previewCaptionPlacement], which moves the live style
 * without touching the datastore, and persists once on release. Saving per pointer event would put
 * a whole preferences write on every frame of the drag, and every one of those re-runs the
 * settings collector for the whole app.
 */
@Composable
private fun CaptionPlacementPad(style: CaptionStyle, modifier: Modifier = Modifier) {
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var captionSize by remember { mutableStateOf(IntSize.Zero) }
    // Carried as floats across the whole gesture so a slow drag moves by fractions of a percent
    // instead of jumping a full one per pointer event.
    var acrossDrag by remember { mutableFloatStateOf(0f) }
    var marginDrag by remember { mutableFloatStateOf(0f) }
    val picture = Modifier
        .widthIn(max = PAD_MAX_WIDTH)
        .fillMaxWidth()
        .aspectRatio(16f / 9f)
        .clip(RoundedCornerShape(8.dp))
        // Stands in for video: a flat swatch would hide what a translucent background does.
        .background(Brush.linearGradient(listOf(Color(0xFF3A4E6B), Color(0xFF0E1116))))
        .onSizeChanged { padSize = it }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = {
                    val current = SettingsStore.captionStyle.value
                    acrossDrag = current.horizontalAnchorPercent.toFloat()
                    marginDrag = current.edgeMarginPercent.toFloat()
                },
                onDragEnd = { SettingsStore.commitCaptionPlacement() },
                onDragCancel = { SettingsStore.commitCaptionPlacement() },
            ) { change, drag ->
                change.consume()
                val width = size.width.toFloat().coerceAtLeast(1f)
                val height = size.height.toFloat().coerceAtLeast(1f)
                // The margin is a gap from the anchored edge, so dragging towards that edge closes
                // it: down shrinks a bottom margin and grows a top one.
                val towardsTheEdge =
                    if (SettingsStore.captionStyle.value.position == CaptionPosition.TOP) 1f else -1f
                acrossDrag = (acrossDrag + drag.x / width * 100f).coerceIn(
                    CaptionStyle.MIN_HORIZONTAL_ANCHOR_PERCENT.toFloat(),
                    CaptionStyle.MAX_HORIZONTAL_ANCHOR_PERCENT.toFloat(),
                )
                marginDrag = (marginDrag + towardsTheEdge * drag.y / height * 100f).coerceIn(
                    CaptionStyle.MIN_EDGE_MARGIN_PERCENT.toFloat(),
                    CaptionStyle.MAX_EDGE_MARGIN_PERCENT.toFloat(),
                )
                SettingsStore.previewCaptionPlacement(
                    horizontalAnchorPercent = acrossDrag.roundToInt(),
                    edgeMarginPercent = marginDrag.roundToInt(),
                )
            }
        }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(picture) {
            CaptionSample(
                style = style,
                padHeightPx = padSize.height,
                modifier = Modifier
                    .onSizeChanged { captionSize = it }
                    .offset { captionOffset(style, padSize, captionSize) },
            )
            if (style.edgeMarginPercent >= CaptionStyle.MAX_EDGE_MARGIN_PERCENT) {
                // At the very end of the range media3 stops drawing entirely, so say so rather than
                // leaving a viewer to conclude their subtitles have broken. The sample stays put and
                // grabbable; it is the only way to drag the captions back.
                MidnightHelper("Off the picture", Modifier.align(Alignment.Center))
            }
        }
    }
}

/**
 * Where the sample sits in the pad, clamped to stay inside it.
 *
 * The clamp is not cosmetic on either axis. Sideways it is what media3 does — `SubtitlePainter`
 * pins `textLeft` inside the parent box — so a line pushed to the edge stops there in the pad for
 * the same reason it stops there on screen. Vertically the real caption *can* leave the picture,
 * and if the sample left with it there would be nothing left to drag back.
 */
private fun captionOffset(style: CaptionStyle, pad: IntSize, caption: IntSize): IntOffset {
    if (pad.width == 0 || pad.height == 0) return IntOffset.Zero
    val gap = pad.height * style.edgeMarginPercent / 100f
    val x = pad.width * style.horizontalAnchorPercent / 100f - caption.width / 2f
    val y = if (style.position == CaptionPosition.TOP) gap else pad.height - caption.height - gap
    return IntOffset(
        x.coerceIn(0f, (pad.width - caption.width).coerceAtLeast(0).toFloat()).roundToInt(),
        y.coerceIn(0f, (pad.height - caption.height).coerceAtLeast(0).toFloat()).roundToInt(),
    )
}

@Composable
private fun CaptionSample(style: CaptionStyle, padHeightPx: Int, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val fontSize = with(density) {
        (padHeightPx * PREVIEW_TEXT_HEIGHT_FRACTION * style.textScalePercent / 100f).toSp()
    }
    val fontFamily = remember(style.font) { FontFamily(style.font.typeface(bold = false)) }
    Box(
        modifier
            .background(Color(style.backgroundArgb), RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        if (fontSize.value <= 0f) return@Box
        if (style.edgeStyle == CaptionEdgeStyle.OUTLINE) {
            Text(
                PREVIEW_SAMPLE,
                color = Color(style.edgeArgb),
                fontSize = fontSize,
                fontFamily = fontFamily,
                fontWeight = if (style.boldText) FontWeight.Bold else FontWeight.Normal,
                style = TextStyle(drawStyle = Stroke(width = 5f)),
            )
        }
        Text(
            PREVIEW_SAMPLE,
            color = Color(style.textArgb),
            fontSize = fontSize,
            fontFamily = fontFamily,
            fontWeight = if (style.boldText) FontWeight.Bold else FontWeight.Normal,
            style = if (style.edgeStyle == CaptionEdgeStyle.DROP_SHADOW) {
                TextStyle(shadow = Shadow(Color(style.edgeArgb), Offset(2f, 2f), blurRadius = 4f))
            } else {
                TextStyle.Default
            },
        )
    }
}

/**
 * The typeface both the native player and the preview draw [CaptionFont] with.
 *
 * The default keeps the exact typefaces captions used before the font was choosable. Every other
 * family goes through `Typeface.create`, which hands back the default sans for a name the device's
 * `fonts.xml` does not list — so a stripped-down box shows its usual caption, not a blank one.
 */
internal fun CaptionFont.typeface(bold: Boolean): Typeface {
    val family = systemFamily
        ?: return if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    return Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)
}

/** The end of the margin range is not a gap any more, it is the captions leaving the picture. */
private fun Int.marginLabel(): String =
    if (this >= CaptionStyle.MAX_EDGE_MARGIN_PERCENT) "Off" else "$this%"

private fun Int.horizontalAnchorLabel(): String = when {
    this <= CaptionStyle.MIN_HORIZONTAL_ANCHOR_PERCENT -> "Left"
    this >= CaptionStyle.MAX_HORIZONTAL_ANCHOR_PERCENT -> "Right"
    this == CaptionStyle.DEFAULT_HORIZONTAL_ANCHOR_PERCENT -> "Centre"
    this < CaptionStyle.DEFAULT_HORIZONTAL_ANCHOR_PERCENT -> "Mid-left"
    else -> "Mid-right"
}

/**
 * One caption setting as the shared underline selector (§12): short comparable values, selection
 * as an underline rather than a wall of pills.
 */
@Composable
private fun <T> CaptionChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    claimFocus: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        MidnightSectionLabel(label)
        MidnightUnderlineSelector(
            options = options,
            selected = selected,
            onSelect = onSelect,
            tabLabel = labelOf,
            claimInitialFocus = claimFocus,
        )
    }
}
