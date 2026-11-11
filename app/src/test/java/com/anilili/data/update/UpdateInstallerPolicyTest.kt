package com.anilili.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateInstallerPolicyTest {
    @Test
    fun `android 5 and 6 use an externally readable file uri`() {
        assertEquals(UpdateInstallerUriKind.LEGACY_FILE, updateInstallerPolicy(22).uriKind)
        assertEquals(UpdateInstallerUriKind.LEGACY_FILE, updateInstallerPolicy(23).uriKind)
    }

    @Test
    fun `android 7 and newer use the file provider`() {
        assertEquals(UpdateInstallerUriKind.FILE_PROVIDER, updateInstallerPolicy(24).uriKind)
        assertEquals(UpdateInstallerUriKind.FILE_PROVIDER, updateInstallerPolicy(36).uriKind)
    }

    @Test
    fun `every platform has the package-specific action fallback`() {
        val expected = listOf(
            UpdateInstallerAction.VIEW,
            UpdateInstallerAction.INSTALL_PACKAGE,
        )
        assertEquals(expected, updateInstallerPolicy(23).actions)
        assertEquals(expected, updateInstallerPolicy(24).actions)
    }
}
