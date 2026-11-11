package com.anilili.data.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.anilili.diagnostics.DiagnosticsLog

/**
 * The phone's launcher icon in the theme's accent colour.
 *
 * A launcher icon cannot be recoloured at runtime, so each accent preset is its own
 * `activity-alias` of MainActivity in the mobile manifest, and this enables the one that matches
 * and disables the rest. Monochrome (the default accent, so most phones) gets a white mark; From
 * wallpaper and Custom have no fixed colour to ship and keep the default purple art, which is also
 * what "Match app icon to theme" turned off falls back to.
 *
 * Switched only while the app is in the background ([MiruroApp] calls [sync] on process stop):
 * some launchers redraw their whole grid, or drop a pinned icon, when the enabled entry moves, and
 * that should not happen under the viewer's thumb. The TV flavor declares none of these aliases
 * and keeps its launcher entry on MainActivity, so there [sync] finds nothing to do.
 */
object AppIcon {
    private const val DEFAULT = "com.anilili.IconDefault"

    private val byAccent = mapOf(
        ThemeAccent.MONOCHROME to "com.anilili.IconMonochrome",
        ThemeAccent.PURPLE to "com.anilili.IconPurple",
        ThemeAccent.NEON_GREEN to "com.anilili.IconNeonGreen",
        ThemeAccent.BLOOD_RED to "com.anilili.IconBloodRed",
        ThemeAccent.OCEAN_BLUE to "com.anilili.IconOceanBlue",
        ThemeAccent.CYAN to "com.anilili.IconCyan",
        ThemeAccent.SAKURA_PINK to "com.anilili.IconSakuraPink",
        ThemeAccent.SUNSET_ORANGE to "com.anilili.IconSunsetOrange",
        ThemeAccent.GOLD to "com.anilili.IconGold",
        ThemeAccent.EMERALD to "com.anilili.IconEmerald",
    )

    private val all = listOf(DEFAULT) + byAccent.values

    internal fun aliasFor(accent: ThemeAccent, matchTheme: Boolean): String =
        if (matchTheme) byAccent[accent] ?: DEFAULT else DEFAULT

    fun sync(
        context: Context,
        accent: ThemeAccent = SettingsStore.themeAccent.value,
        matchTheme: Boolean = SettingsStore.appIconMatchesTheme.value,
    ) {
        val pm = context.packageManager
        val pkg = context.packageName
        // Only the mobile flavor declares the aliases; resolving one is the flavor check.
        if (runCatching { pm.getActivityInfo(ComponentName(pkg, DEFAULT), PackageManager.MATCH_DISABLED_COMPONENTS) }.isFailure) {
            return
        }
        val wanted = aliasFor(accent, matchTheme)
        if (isEnabled(pm, pkg, wanted)) {
            if (all.none { it != wanted && isEnabled(pm, pkg, it) }) return
        }
        runCatching {
            // The new entry first, then the old ones off, so there is never a moment with no
            // launcher entry at all. DONT_KILL_APP: the aliases are not the running activity.
            set(pm, pkg, wanted, true)
            all.filter { it != wanted }.forEach { set(pm, pkg, it, false) }
            DiagnosticsLog.event("App icon switched to ${wanted.substringAfterLast('.')}")
        }.onFailure { DiagnosticsLog.event("App icon switch failed: ${it.javaClass.simpleName}") }
    }

    private fun isEnabled(pm: PackageManager, pkg: String, name: String): Boolean =
        when (pm.getComponentEnabledSetting(ComponentName(pkg, name))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            // Not yet touched: whatever the manifest says, which is on for the default only.
            else -> name == DEFAULT
        }

    private fun set(pm: PackageManager, pkg: String, name: String, enabled: Boolean) {
        if (isEnabled(pm, pkg, name) == enabled) return
        pm.setComponentEnabledSetting(
            ComponentName(pkg, name),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}
