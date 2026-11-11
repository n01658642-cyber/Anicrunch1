package com.anilili.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterElementTest {
    @Test
    fun `hidden elements round trip in picker order`() {
        val hidden = setOf(PosterElement.DUB, PosterElement.SCORE)

        val stored = encodeHiddenPosterElements(hidden)

        assertEquals("score,dub", stored)
        assertEquals(hidden, decodeHiddenPosterElements(stored))
    }

    /**
     * The absent key is every install that has never opened the setting, and it has to mean "draw
     * everything" — the card every one of them already has.
     */
    @Test
    fun `nothing saved hides nothing`() {
        assertEquals(emptySet<PosterElement>(), decodeHiddenPosterElements(null))
        assertEquals(emptySet<PosterElement>(), decodeHiddenPosterElements(""))
    }

    /**
     * An element retired between releases leaves its name in everyone's stored value, and a viewer
     * who downgrades reads one written by a version that knew more elements than this one.
     */
    @Test
    fun `unknown saved names are ignored`() {
        assertEquals(
            setOf(PosterElement.DUB),
            decodeHiddenPosterElements("dub, future-element, dub"),
        )
    }

    @Test
    fun `stored names identify one element each`() {
        val all = PosterElement.entries
        assertEquals(all.size, all.mapTo(mutableSetOf()) { it.storedValue }.size)
        assertEquals(all.size, all.mapTo(mutableSetOf()) { it.label }.size)
    }

    /**
     * The stored names travel in backups and outlive the enum constants, so a rename here is a
     * silent reset of the setting on every device that restores one.
     */
    @Test
    fun `stored names are the ones already in the field`() {
        assertEquals(
            listOf("score", "dub", "new_episodes", "progress", "adult", "title", "details"),
            PosterElement.entries.map { it.storedValue },
        )
    }

    @Test
    fun `every element explains itself in the picker`() {
        assertTrue(PosterElement.entries.all { it.label.isNotBlank() && it.description.isNotBlank() })
    }
}
