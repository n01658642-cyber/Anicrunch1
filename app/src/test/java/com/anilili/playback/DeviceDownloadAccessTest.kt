package com.anilili.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDownloadAccessTest {
    @Test
    fun `runtime storage permission is only required on Android 6 through 9`() {
        assertFalse(DeviceDownloadAccess.requiresLegacyWritePermission(22))
        assertTrue(DeviceDownloadAccess.requiresLegacyWritePermission(23))
        assertTrue(DeviceDownloadAccess.requiresLegacyWritePermission(28))
        assertFalse(DeviceDownloadAccess.requiresLegacyWritePermission(29))
        assertFalse(DeviceDownloadAccess.requiresLegacyWritePermission(36))
    }
}
