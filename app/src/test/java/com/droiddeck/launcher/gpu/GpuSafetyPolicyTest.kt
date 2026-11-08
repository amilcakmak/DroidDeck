package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuSafetyPolicyTest {
    @Test fun autoKeepsMaliOutOfGuestAndLeavesAdrenoAlone() {
        assertTrue(GpuSafety.software(GpuSafety.AUTO, mali = true))
        assertFalse(GpuSafety.software(GpuSafety.AUTO, mali = false))
    }

    @Test fun explicitModesSelectTheExpectedDriverProfile() {
        assertTrue(GpuSafety.software(GpuSafety.SOFTWARE, mali = false))
        assertFalse(GpuSafety.software(GpuSafety.MALI, mali = false))
        assertTrue(GpuSafety.software(GpuSafety.MALI, mali = true, panvkSupported = false))
        assertTrue(GpuSafety.software(GpuSafety.MALI, mali = true, panvkInstalled = false))
        assertFalse(GpuSafety.software(GpuSafety.MALI, mali = true))
        assertEquals(GpuSafety.MALI, GpuSafety.profileForDriver(maliDriver = true, softwareDriver = false))
        assertEquals(GpuSafety.SOFTWARE, GpuSafety.profileForDriver(maliDriver = false, softwareDriver = true))
        assertEquals(GpuSafety.AUTO, GpuSafety.profileForDriver(maliDriver = false, softwareDriver = false))
    }
}