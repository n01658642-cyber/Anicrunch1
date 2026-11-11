package com.anilili.ui.watch

import com.anilili.data.model.Category
import com.anilili.data.model.SourceVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerAudioSwitchTest {

    private fun option(
        provider: String,
        variant: SourceVariant,
        hasCurrentEpisode: Boolean = true,
    ) = WatchSourceOption(
        provider = provider,
        category = variant.category,
        hasCurrentEpisode = hasCurrentEpisode,
        episodeCount = 12,
        variant = variant,
    )

    @Test
    fun `an episode with only one audio has nothing to switch`() {
        val subsOnly = listOf(
            option("kiwi", SourceVariant.GENERIC_SUB),
            option("bonk", SourceVariant.SOFT_SUB),
        )
        assertNull(resolvePlayerAudioSwitch(subsOnly, "kiwi", SourceVariant.GENERIC_SUB))
    }

    @Test
    fun `the dub stays on the server already playing when that server has one`() {
        val options = listOf(
            option("kiwi", SourceVariant.GENERIC_SUB),
            option("bonk", SourceVariant.DUB),
            option("kiwi", SourceVariant.DUB),
        )
        val switch = resolvePlayerAudioSwitch(options, "kiwi", SourceVariant.GENERIC_SUB)!!
        assertEquals(Category.SUB, switch.selected.category)
        // Not bonk, though bonk's dub is listed first: a server that is working keeps the episode.
        assertEquals(PlayerAudioChoice(Category.DUB, "kiwi", SourceVariant.DUB), switch.other)
    }

    @Test
    fun `the dub falls to the first server that has one when the current server has none`() {
        val options = listOf(
            option("kiwi", SourceVariant.GENERIC_SUB),
            option("bonk", SourceVariant.DUB),
            option("anizone", SourceVariant.DUB),
        )
        val switch = resolvePlayerAudioSwitch(options, "kiwi", SourceVariant.GENERIC_SUB)!!
        assertEquals(PlayerAudioChoice(Category.DUB, "bonk", SourceVariant.DUB), switch.other)
    }

    @Test
    fun `a server that lacks this episode cannot answer for its audio`() {
        val options = listOf(
            option("kiwi", SourceVariant.GENERIC_SUB),
            option("bonk", SourceVariant.DUB, hasCurrentEpisode = false),
        )
        // The season has a dub; episode 12 does not, which is the only question being asked.
        assertNull(resolvePlayerAudioSwitch(options, "kiwi", SourceVariant.GENERIC_SUB))
    }

    @Test
    fun `the selected side is the exact source on screen, not a re-pick of its category`() {
        val options = listOf(
            option("bonk", SourceVariant.GENERIC_SUB),
            option("bonk", SourceVariant.HARD_SUB),
            option("bonk", SourceVariant.DUB),
        )
        val switch = resolvePlayerAudioSwitch(options, "bonk", SourceVariant.HARD_SUB)!!
        // Generic sub is listed first; the tab must still point at the burnt-in stream playing.
        assertEquals(PlayerAudioChoice(Category.SUB, "bonk", SourceVariant.HARD_SUB), switch.selected)
        assertEquals(switch.selected, switch.choices.first { it.category == Category.SUB })
    }

    @Test
    fun `switching back from a dub lands on a sub the same server carries`() {
        val options = listOf(
            option("kiwi", SourceVariant.GENERIC_SUB),
            option("bonk", SourceVariant.SOFT_SUB),
            option("bonk", SourceVariant.DUB),
        )
        val switch = resolvePlayerAudioSwitch(options, "bonk", SourceVariant.DUB)!!
        assertEquals(Category.DUB, switch.selected.category)
        assertEquals(PlayerAudioChoice(Category.SUB, "bonk", SourceVariant.SOFT_SUB), switch.other)
    }

    @Test
    fun `sub always reads before dub so the tabs never swap places between episodes`() {
        val options = listOf(
            option("bonk", SourceVariant.DUB),
            option("kiwi", SourceVariant.GENERIC_SUB),
        )
        val switch = resolvePlayerAudioSwitch(options, "bonk", SourceVariant.DUB)!!
        assertEquals(listOf("Sub", "Dub"), switch.choices.map(PlayerAudioChoice::label))
    }
}
