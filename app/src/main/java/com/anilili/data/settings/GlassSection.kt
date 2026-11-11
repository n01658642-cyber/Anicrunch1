package com.anilili.data.settings

/**
 * The parts of the phone interface that draw live frosted glass, each switchable on its own.
 *
 * Per part because the costs differ: the navigation bar blurs every frame of every tab, while
 * Details only blurs once its header is back over the page. Someone on a phone that stutters can
 * drop the expensive part and keep the rest. Appearance also carries a master switch over all of
 * them ([SettingsStore.setAllGlass]) for someone who wants none of it.
 *
 * The persisted key is derived from [name], so renaming a constant resets that switch for everyone.
 */
enum class GlassSection(val label: String, val description: String) {
    NAVIGATION("Navigation bar", "Blur the page behind the bottom menu"),
    HOME("Home", "Genre pills, spotlight tags, card buttons and the header"),
    DETAILS("Details", "The header once you scroll down a title"),
    BROWSE("Browse", "The search header over the results"),
    POPUPS("Popups", "Blur the screen behind popups and sheets"),
    REFRESH("Refresh spinner", "The circle you pull down to reload a page"),
}
