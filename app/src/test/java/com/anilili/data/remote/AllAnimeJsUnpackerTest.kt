package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class AllAnimeJsUnpackerTest {

    /**
     * The regression that motivated the single-pass rewrite: chained replaces unescaped `\\` last,
     * so the trailing `\n` of a literal backslash-then-n was eaten as a newline first, leaving a
     * stray backslash behind. Neither ordering can be right, because each pass reads the previous
     * pass's output.
     */
    @Test
    fun escapedBackslashDoesNotSwallowTheCharacterAfterIt() {
        assertEquals("""\n""", AllAnimeJsUnpacker.unescape("""\\n"""))
        assertEquals("""\r""", AllAnimeJsUnpacker.unescape("""\\r"""))
        assertEquals("""C:\path\to""", AllAnimeJsUnpacker.unescape("""C:\\path\\to"""))
    }

    @Test
    fun realEscapesStillResolve() {
        assertEquals("line\nbreak", AllAnimeJsUnpacker.unescape("""line\nbreak"""))
        assertEquals("say \"hi\"", AllAnimeJsUnpacker.unescape("""say \"hi\""""))
        assertEquals("it's", AllAnimeJsUnpacker.unescape("""it\'s"""))
    }

    @Test
    fun unknownEscapesAreLeftAlone() {
        // Not something the packer emits; mangling it would corrupt a URL rather than fix one.
        assertEquals("""\q""", AllAnimeJsUnpacker.unescape("""\q"""))
        // A trailing backslash has nothing to escape and must survive as itself.
        assertEquals("""end\""", AllAnimeJsUnpacker.unescape("""end\"""))
    }

    /**
     * Guards the packer's base-95 table against the porting slip that produced 96 characters: a
     * single-quoted source writes the apostrophe as `\'`, and copying that escape literally adds a
     * backslash the table should not contain, shifting every symbol after it by one.
     */
    @Test
    fun base95AlphabetIsExactlyPrintableAscii() {
        val expected = (0x20..0x7E).map(Int::toChar).joinToString("")
        assertEquals(95, expected.length)
        assertEquals(expected, AllAnimeJsUnpacker.printableAsciiAlphabet)
    }
}
