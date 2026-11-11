package com.anilili.ui.adaptive

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.IconButtonColors
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusFillTest {
    @Test
    fun focusOverridesSelectionAndRestoresItWhenFocusLeaves() {
        val fill = FocusFillState()
        val selected = Color(0xFF7744CC)
        val label = Color(0xFFCCCCCC)
        assertEquals(selected, fill.background(selected))
        assertEquals(label, fill.content(label))

        fill.focused = true
        assertEquals(Color.White, fill.background(selected))
        assertEquals(Color.Black, fill.content(label))

        fill.focused = false
        assertEquals(selected, fill.background(selected))
        assertEquals(label, fill.content(label))
    }

    @Test
    fun focusingButtonPreservesDisabledColors() {
        val rest = ButtonColors(Color.Blue, Color.Yellow, Color.DarkGray, Color.Gray)
        val fill = FocusFillState()
        fill.focused = true
        val focused = fill.buttonColors(rest)
        assertEquals(Color.White, focused.containerColor)
        assertEquals(Color.Black, focused.contentColor)
        assertEquals(rest.disabledContainerColor, focused.disabledContainerColor)
        assertEquals(rest.disabledContentColor, focused.disabledContentColor)
        fill.focused = false
        assertEquals(rest, fill.buttonColors(rest))
    }

    @Test
    fun iconButtonRestoresTransparentBackgroundAndSemanticTint() {
        val rest = IconButtonColors(Color.Transparent, Color.Red, Color.Transparent, Color.Gray)
        val fill = FocusFillState()
        fill.focused = true
        val focused = fill.iconButtonColors(rest)
        assertEquals(Color.White, focused.containerColor)
        assertEquals(Color.Black, focused.contentColor)
        assertEquals(rest.disabledContainerColor, focused.disabledContainerColor)
        assertEquals(rest.disabledContentColor, focused.disabledContentColor)
        fill.focused = false
        assertEquals(rest, fill.iconButtonColors(rest))
    }
}
