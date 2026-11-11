package com.anilili.ui.watch

import com.anilili.data.model.Category
import com.anilili.data.model.SourceVariant

/**
 * One side of the player's sub/dub switch: the audio the viewer would get, and the server that
 * would actually play it.
 *
 * Sub and dub are almost never two audio tracks of one stream — providers publish them as separate
 * server entries — so choosing the other one is a source change, not a track change. That is why a
 * choice carries a [provider] and a [variant] rather than a track index.
 */
data class PlayerAudioChoice(
    val category: Category,
    val provider: String,
    val variant: SourceVariant,
) {
    val label: String get() = if (category == Category.DUB) "Dub" else "Sub"
}

/** The sub/dub switch as the player draws it: both sides, and the one playing now. */
data class PlayerAudioSwitch(
    val choices: List<PlayerAudioChoice>,
    val selected: PlayerAudioChoice,
) {
    /** The side that is not playing, or null when the episode only has one. */
    val other: PlayerAudioChoice? get() = choices.firstOrNull { it != selected }
}

/**
 * The sub/dub switch for an episode, or null when it has only one of the two.
 *
 * Reaching the dub used to mean opening the sources panel and reading a `DUB` suffix off a list of
 * server names — the switch someone wants is "the other audio", and which server carries it is an
 * implementation detail they were being asked to resolve themselves. This picks that server for
 * them: the one already playing if it also has the other category (no reload of a server that was
 * working), otherwise the first that does, which is the viewer's own priority order.
 *
 * Only rows carrying the episode on screen count. A server that has the dub for the season but not
 * for episode 12 cannot answer the question being asked at episode 12.
 */
internal fun resolvePlayerAudioSwitch(
    options: List<WatchSourceOption>,
    provider: String,
    variant: SourceVariant,
): PlayerAudioSwitch? {
    val playable = options.filter(WatchSourceOption::hasCurrentEpisode)
    val categories = playable.mapTo(mutableSetOf(), WatchSourceOption::category)
    if (!categories.containsAll(listOf(Category.SUB, Category.DUB))) return null

    val choices = listOf(Category.SUB, Category.DUB).map { category ->
        if (category == variant.category) {
            // The playing side is the playing source, exactly — never a re-pick that could point
            // the selected tab at a different server than the one on screen.
            PlayerAudioChoice(category, provider, variant)
        } else {
            val inCategory = playable.filter { it.category == category }
            val target = inCategory.firstOrNull { it.provider == provider } ?: inCategory.first()
            PlayerAudioChoice(category, target.provider, target.variant)
        }
    }
    return PlayerAudioSwitch(choices, choices.first { it.category == variant.category })
}
