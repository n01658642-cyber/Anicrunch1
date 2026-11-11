package com.anilili.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anilili.data.settings.SettingsStore
import com.anilili.data.settings.ThemePreset
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.dialogs.MidnightDialogFooter
import com.anilili.ui.dialogs.MidnightDialogHost
import com.anilili.ui.dialogs.MidnightHelper
import com.anilili.ui.dialogs.MidnightMediaHeader
import com.anilili.ui.dialogs.MidnightSectionLabel
import com.anilili.ui.dialogs.MidnightStepperRow
import com.anilili.ui.dialogs.midnightInitialFocus
import com.anilili.ui.theme.AppColors
import com.anilili.ui.theme.LocalAppColors
import com.anilili.ui.theme.Midnight
import com.anilili.ui.theme.ThemeSpec
import com.anilili.ui.theme.appColors
import com.materialkolor.hct.Hct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One card per [ThemePreset], each drawn in its own generated colours, the selected one ringed.
 *
 * The eleven schemes are generated off the main thread. One costs ~0.3 ms on a desktop JVM, which
 * on a slow television box is plausibly tens of times that; eleven at once in composition would be
 * a dropped second of frames on exactly the devices least able to spare it. Until they land, each
 * card shows its accent on black, which is what most of them are anyway.
 *
 * A lazy row, so a remote walks it with Left/Right and Compose scrolls the focused card into view;
 * each card lifts and rims under the cursor with the same [focusHighlight] posters use.
 */
@Composable
internal fun ThemePresetRow(
    title: String,
    selected: ThemePreset?,
    onSelect: (ThemePreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val previews by produceState(initialValue = emptyMap<ThemePreset, AppColors>()) {
        value = withContext(Dispatchers.Default) {
            ThemePreset.entries.associateWith { preset ->
                appColors(
                    ThemeSpec(preset.background, preset.accent, preset.style, preset.contrast),
                    wallpaperSeed = null,
                )
            }
        }
    }
    Column(modifier.fillMaxWidth()) {
    MidnightSectionLabel(title, Modifier.padding(top = 8.dp))
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(ThemePreset.entries, key = { it.name }) { preset ->
            ThemePresetCard(
                preset = preset,
                colors = previews[preset],
                selected = preset == selected,
                onClick = { onSelect(preset) },
            )
        }
    }
    }
}

@Composable
private fun ThemePresetCard(
    preset: ThemePreset,
    colors: AppColors?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val canvas = colors?.scheme?.background ?: Color.Black
    val accent = colors?.scheme?.primary ?: preset.accent.seed?.let { Color(it) } ?: Color.White
    val raised = colors?.scheme?.surfaceContainerHigh ?: Color(0xFF1A1A1A)
    val ring = LocalAppColors.current.scheme.primary
    Column(
        modifier = Modifier
            .width(112.dp)
            .semantics {
                contentDescription = "${preset.label} theme"
                this.selected = selected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .focusHighlight(shape)
                .clip(shape)
                .background(canvas, shape)
                .border(if (selected) 2.dp else 1.dp, if (selected) ring else LocalAppColors.current.line2, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.width(44.dp).height(12.dp).clip(CircleShape).background(accent))
                Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(raised))
                Box(Modifier.width(56.dp).height(7.dp).clip(CircleShape).background(raised))
            }
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = if (accent.luminance() > 0.4f) Color.Black else Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Text(
            text = preset.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.W700 else FontWeight.W500,
            color = if (selected) Midnight.Text else Midnight.Text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Mixes a custom accent, built for a remote first: twelve hue shortcuts, then steppers for hue,
 * vividness and brightness, each an Left/Right pair a D-pad lands on, and a hex field for anyone
 * who arrives with a colour already in mind.
 *
 * Worked in HCT — Material's own colour space — so "vividness" and "brightness" mean what they say
 * at every hue; stepping hue in RGB swings brightness wildly between yellow and blue. The three are
 * kept as the viewer set them rather than read back from the colour, so a hue out of gamut at the
 * current vividness does not quietly lower the vividness for the next step.
 *
 * The dialog previews locally and writes the setting 300 ms after the last change. Every write
 * regenerates the theme and recomposes the whole app behind the dialog; holding a stepper down on a
 * television would otherwise do that at key-repeat rate.
 */
@Composable
internal fun ThemeCustomColourDialog(
    initialArgb: Int,
    onDismiss: () -> Unit,
) {
    val start = remember { Hct.fromInt(initialArgb) }
    var hue by remember { mutableIntStateOf(start.hue.toInt()) }
    var chroma by remember { mutableIntStateOf(start.chroma.toInt().coerceIn(CHROMA_MIN, CHROMA_MAX)) }
    var tone by remember { mutableIntStateOf(start.tone.toInt().coerceIn(TONE_MIN, TONE_MAX)) }
    var edited by remember { mutableStateOf(false) }
    val argb = remember(hue, chroma, tone) { Hct.from(hue.toDouble(), chroma.toDouble(), tone.toDouble()).toInt() }
    var hexText by remember { mutableStateOf(argb.toHex()) }
    var hexError by remember { mutableStateOf(false) }

    fun set(newHue: Int = hue, newChroma: Int = chroma, newTone: Int = tone) {
        hue = Math.floorMod(newHue, 360)
        chroma = newChroma.coerceIn(CHROMA_MIN, CHROMA_MAX)
        tone = newTone.coerceIn(TONE_MIN, TONE_MAX)
        edited = true
        hexText = Hct.from(hue.toDouble(), chroma.toDouble(), tone.toDouble()).toInt().toHex()
        hexError = false
    }

    LaunchedEffect(argb, edited) {
        if (!edited) return@LaunchedEffect
        delay(APPLY_DEBOUNCE_MS)
        SettingsStore.setThemeCustomSeed(argb)
    }

    MidnightDialogHost(
        onDismiss = {
            if (edited) SettingsStore.setThemeCustomSeed(argb)
            onDismiss()
        },
        header = { showClose ->
            MidnightMediaHeader(
                kicker = "Appearance",
                title = "Custom colour",
                metadata = null,
                onDismiss = onDismiss.takeIf { showClose },
            )
        },
        footer = {
            MidnightDialogFooter(
                quietText = "Done",
                onQuiet = {
                    if (edited) SettingsStore.setThemeCustomSeed(argb)
                    onDismiss()
                },
            )
        },
    ) {
        val swatch = Color(argb)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(swatch)
                    .border(1.dp, Midnight.Line2, RoundedCornerShape(14.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = argb.toHex(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.W800,
                    color = Midnight.Text,
                )
                Text(
                    text = "Applies as you change it",
                    style = MaterialTheme.typography.labelSmall,
                    color = Midnight.Text3,
                )
            }
        }

        MidnightSectionLabel("Quick hues")
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val nearest = (Math.round(hue / 30.0).toInt() % 12)
            repeat(12) { index ->
                val quickHue = index * 30
                val quick = remember(quickHue) { Color(Hct.from(quickHue.toDouble(), 80.0, 60.0).toInt()) }
                Box(
                    Modifier
                        .size(if (LocalAppDeviceProfile.current.isTv) 26.dp else 22.dp)
                        .midnightInitialFocus(index == nearest)
                        .focusHighlight(CircleShape)
                        .clip(CircleShape)
                        .background(quick)
                        .border(1.dp, Midnight.Line2, CircleShape)
                        .clickable(role = Role.Button) { set(newHue = quickHue, newChroma = maxOf(chroma, 60)) }
                        .semantics { contentDescription = "Hue $quickHue degrees" },
                )
            }
        }

        MidnightStepperRow(
            label = "Hue",
            value = "$hue°",
            onDecrease = { set(newHue = hue - HUE_STEP) },
            onIncrease = { set(newHue = hue + HUE_STEP) },
        )
        MidnightStepperRow(
            label = "Vividness",
            value = "$chroma",
            onDecrease = { set(newChroma = chroma - CHROMA_STEP) },
            onIncrease = { set(newChroma = chroma + CHROMA_STEP) },
            decreaseEnabled = chroma > CHROMA_MIN,
            increaseEnabled = chroma < CHROMA_MAX,
        )
        MidnightStepperRow(
            label = "Brightness",
            value = "$tone",
            onDecrease = { set(newTone = tone - TONE_STEP) },
            onIncrease = { set(newTone = tone + TONE_STEP) },
            decreaseEnabled = tone > TONE_MIN,
            increaseEnabled = tone < TONE_MAX,
        )

        OutlinedTextField(
            value = hexText,
            onValueChange = { typed ->
                hexText = typed.take(7)
                val parsed = parseHexColour(hexText)
                hexError = parsed == null && hexText.trimStart('#').length >= 6
                if (parsed != null) {
                    val hct = Hct.fromInt(parsed)
                    hue = hct.hue.toInt()
                    chroma = hct.chroma.toInt().coerceIn(CHROMA_MIN, CHROMA_MAX)
                    tone = hct.tone.toInt().coerceIn(TONE_MIN, TONE_MAX)
                    edited = true
                }
            },
            label = { Text("Hex") },
            isError = hexError,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        MidnightHelper("Very dark colours are lifted a little so buttons stay readable on black.")
    }
}

/** `#RRGGBB` or `RRGGBB`, case-insensitive; anything else is null. */
internal fun parseHexColour(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6 || digits.any { it.digitToIntOrNull(16) == null }) return null
    return (0xFF000000.toInt()) or digits.toInt(16)
}

private fun Int.toHex(): String = "#%06X".format(this and 0xFFFFFF)

private const val HUE_STEP = 10
private const val CHROMA_STEP = 10
private const val CHROMA_MIN = 10
private const val CHROMA_MAX = 120
private const val TONE_STEP = 5
private const val TONE_MIN = 25
private const val TONE_MAX = 90
private const val APPLY_DEBOUNCE_MS = 300L
