package com.anilili.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieUiPolicyTest {
    @Test
    fun movieFormatIsDetectedWithoutDependingOnApiCasing() {
        assertTrue(isMovieFormat("MOVIE"))
        assertTrue(isMovieFormat("movie"))
        assertFalse(isMovieFormat("TV"))
        assertFalse(isMovieFormat(null))
    }

    @Test
    fun moviePrimaryActionNeverUsesEpisodeLanguage() {
        assertEquals("Watch movie", movieAwareWatchLabel("MOVIE", hasResume = false))
        assertEquals("Continue movie", movieAwareWatchLabel("MOVIE", hasResume = true))
        assertEquals("Watch", movieAwareWatchLabel("TV", hasResume = false))
        assertEquals("Continue", movieAwareWatchLabel("TV", hasResume = true))
    }
}

