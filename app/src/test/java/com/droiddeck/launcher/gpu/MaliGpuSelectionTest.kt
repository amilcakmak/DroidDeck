package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.gpu.GpuInfo.Family
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MaliGpuSelectionTest {
    private val mali = GpuInfo("Mali-G615 MC6", 615, Family.MALI, "MT6897", false)

    @Test fun maliNeverSelectsAnAdrenoDriver() {
        assertNull(DriverPairs.recommendedKey(mali, emptyList()))
        val asset = TurnipReleases.Asset("DroidDeck", "DD-Turnip-v0.1.0", "DD-Turnip.zip", "https://example.invalid/driver", 1,
            false, "test", pair = DriverPairs.ddTurnip("DD-Turnip-v0.1.0"), bundle = true)
        val pairs = DriverPairs.from(TurnipReleases.Check(listOf(asset), emptyList(), emptyList(), 0))
        assertFalse(pairs.single().suits(mali))
    }

    @Test fun familyDetectionDistinguishesMaliFromOtherNonAdrenoGpus() {
        assertEquals(Family.MALI, GpuInfo.familyOf(false, true, 615))
        assertEquals(Family.NOT_ADRENO, GpuInfo.familyOf(false, false, 0))
        assertEquals(Family.A7XX, GpuInfo.familyOf(true, true, 740))
    }

    @Test fun panvkSupportRequiresAndroid15AndRemainsExperimental() {
        assertEquals(GpuInfo.Support.UNSUPPORTED, GpuInfo.maliSupport(34))
        assertEquals(GpuInfo.Support.UNTESTED, GpuInfo.maliSupport(35))
    }
}
