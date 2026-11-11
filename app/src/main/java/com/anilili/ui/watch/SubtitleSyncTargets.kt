package com.anilili.ui.watch

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.anilili.data.model.SubtitleItem

/** The language of the text track currently playing, or null when subtitles are off. */
@OptIn(UnstableApi::class)
internal fun Player.selectedTextLanguage(): String? = currentTracks.groups
    .firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
    ?.let { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }
    ?.language


/**
 * Which sidecar file the sync-by-capture flow should read.
 *
 * The player selects tracks by media3's own identity, not by the [SubtitleItem] they came from, so
 * the file has to be found again. An attached file is unambiguous — it was chosen by URL. Otherwise
 * the selected track's language is the only handle there is, and if that names nothing then a lone
 * sidecar is a safe assumption and several are not: reading the wrong one would derive a shift from
 * lines that were never on screen.
 */
internal fun syncableSubtitle(
    subtitles: List<SubtitleItem>,
    selectedAttachedUrl: String?,
    selectedLanguage: String?,
): SubtitleItem? {
    if (selectedAttachedUrl != null) {
        return subtitles.firstOrNull { it.url == selectedAttachedUrl }
    }
    val language = iso3Language(selectedLanguage)
    if (language != null) {
        subtitles.firstOrNull { iso3Language(it.language) == language }?.let { return it }
    }
    return subtitles.singleOrNull()
}

/**
 * A language code as its three-letter form, so `es` and `spa` compare equal.
 *
 * Media tracks and provider metadata disagree about which of the two vocabularies to use, and the
 * two are not prefixes of one another — `eng` happens to start with `en`, `spa` does not start with
 * `es` — so comparing them as strings matches some languages and silently fails on others. An
 * unrecognised code is returned as itself rather than discarded, since an exact match on it is
 * still worth having.
 */
internal fun iso3Language(raw: String?): String? {
    val value = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.length != 2) return value
    return runCatching { java.util.Locale.forLanguageTag(value).isO3Language }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
        ?: value
}
