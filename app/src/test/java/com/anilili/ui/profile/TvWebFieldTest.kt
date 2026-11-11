package com.anilili.ui.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvWebFieldTest {
    @Test
    fun emailPlaceholderUsesEmailKeyboardEvenWhenWebsiteDeclaresTextInput() {
        val field = TvWebField(
            id = "1",
            label = "Email",
            type = "text",
            value = "",
            hasNext = true,
        )

        assertTrue(field.isEmail)
        assertFalse(field.isPassword)
    }

    @Test
    fun passwordInputUsesMaskedEditor() {
        val field = TvWebField(
            id = "2",
            label = "Password",
            type = "password",
            value = "secret",
            hasNext = false,
        )

        assertTrue(field.isPassword)
        assertFalse(field.isEmail)
    }

    /**
     * The walker only opens the native editor for a focus change that armed
     * `__miruroTvOpenOnFocus` first. "Next" is one of the two paths allowed to do that, so the
     * advance branch has to arm it *before* moving focus — otherwise pressing Next drops the
     * viewer onto the following field with no keyboard.
     */
    @Test
    fun advancingToTheNextFieldAsksForTheEditorBeforeMovingFocus() {
        val script = tvWebFieldValueScript(fieldId = "1", value = "example", advance = true)

        assertTrue(guardBefore(script, ADVANCE_BODY))
        val arm = script.indexOf("__miruroTvOpenOnFocus = true")
        val move = script.indexOf("next.focus()")
        assertTrue("the advance branch must arm the editor request", arm >= 0)
        assertTrue("focus must move only after the request is armed", move > arm)
    }

    /**
     * Backing out of the keyboard writes the value through but must not move focus. Advancing on
     * cancel is what put MyAnimeList's TV sign-in into a loop: Back landed on the form's Login
     * button, Up walked straight back onto a field, and the social-login buttons above the form —
     * "Sign in with Google" among them — could never be reached.
     */
    @Test
    fun cancellingTheEditorKeepsTheValueWithoutMovingFocus() {
        val script = tvWebFieldValueScript(fieldId = "1", value = "example", dispatchChange = true)

        assertTrue("the typed value is committed", script.contains("\"example\""))
        assertTrue("the page is told the value settled", script.contains("new Event('change'"))
        assertFalse("cancelling must not advance to the next field", guardBefore(script, ADVANCE_BODY))
        assertFalse("cancelling must not jump to the form's submit", guardBefore(script, BLUR_BODY))
    }

    /** Done is the opposite: it commits and hands focus on to the form's submit. */
    @Test
    fun finishingTheEditorHandsFocusToTheFormAction() {
        val script = tvWebFieldValueScript(
            fieldId = "1",
            value = "example",
            dispatchChange = true,
            blur = true,
        )

        assertTrue(guardBefore(script, BLUR_BODY))
        assertFalse("Done submits rather than typing on", guardBefore(script, ADVANCE_BODY))
    }

    private companion object {
        const val ADVANCE_BODY = "var next = fields["
        const val BLUR_BODY = "window.__miruroTvFocusAfter(el)"

        /**
         * Both optional branches are always emitted; what turns them on is the `if (true)` /
         * `if (false)` literal the builder writes in front of them. Asserting on the branch body
         * alone would pass for a script that never runs it, so read the guard that owns it: the
         * last boolean-literal `if` before the marker.
         */
        val GUARD = Regex("""if \((true|false)\) \{""")

        fun guardBefore(script: String, marker: String): Boolean {
            val at = script.indexOf(marker)
            require(at >= 0) { "marker $marker is no longer in the generated script" }
            val guard = GUARD.findAll(script.take(at)).lastOrNull()
                ?: error("no boolean guard precedes $marker")
            return guard.groupValues[1].toBoolean()
        }
    }
}
