package com.example.multiview

import com.example.multiview.panes.DeviceCapability
import com.example.multiview.panes.LayoutResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pane ceiling is a promise to the user: open this many and the app will
 * cope. These tests pin the thresholds so a future edit cannot quietly make a
 * 2 GB phone attempt twelve live WebViews.
 */
class DeviceCapabilityTest {

    @Test fun capsFollowPhysicalRam() {
        assertEquals(4, DeviceCapability.paneCapFor(1_024))
        assertEquals(4, DeviceCapability.paneCapFor(2_047))
        assertEquals(6, DeviceCapability.paneCapFor(2_048))
        assertEquals(6, DeviceCapability.paneCapFor(3_071))
        assertEquals(8, DeviceCapability.paneCapFor(3_072))
        assertEquals(8, DeviceCapability.paneCapFor(4_095))
        assertEquals(10, DeviceCapability.paneCapFor(4_096))
        assertEquals(10, DeviceCapability.paneCapFor(6_143))
        assertEquals(12, DeviceCapability.paneCapFor(6_144))
        assertEquals(12, DeviceCapability.paneCapFor(16_384))
    }

    @Test fun capNeverExceedsWhatTheGridCanDraw() {
        // A future device with absurd RAM must not be handed a cap the layout
        // engine cannot render.
        for (ram in listOf(8_192L, 32_768L, 131_072L)) {
            assertTrue(
                "cap for $ram MB must stay within MAX_PANES",
                DeviceCapability.effectiveCap(ram) <= LayoutResolver.MAX_PANES,
            )
        }
        assertEquals(LayoutResolver.MAX_PANES, DeviceCapability.effectiveCap(131_072))
    }

    @Test fun everyCapCanBeDisplayedBySomeLayout() {
        // A cap does not need a layout of exactly that size; it needs a layout
        // with room for that many panes. 3x4 (12) covers every cap we issue.
        val maxCapacity = LayoutResolver.LAYOUTS.maxOf { LayoutResolver.capacity(it.first) }
        for (ram in listOf(1_024L, 2_048L, 4_096L, 6_144L, 16_384L)) {
            val cap = DeviceCapability.effectiveCap(ram)
            assertTrue(
                "cap $cap (from ${ram}MB) must fit the largest layout ($maxCapacity)",
                cap <= maxCapacity,
            )
        }
    }

    @Test fun pressureIsProportionalNotAbsolute() {
        // Same RATIO of free RAM must give the same verdict on any device size:
        // ~20% free is comfortable everywhere, ~5% free is pressure everywhere.
        assertFalse(DeviceCapability.isMemoryPressure(1_600, 8_192))
        assertFalse(DeviceCapability.isMemoryPressure(800, 4_096))
        assertTrue(DeviceCapability.isMemoryPressure(400, 8_192))
        assertTrue(DeviceCapability.isMemoryPressure(200, 4_096))
    }

    @Test fun noPressureWhenPlentyIsFree() {
        assertFalse(DeviceCapability.isMemoryPressure(4_096, 8_192))
        assertFalse(DeviceCapability.isMemoryPressure(1_500, 4_096))
    }

    @Test fun pressureTriggersNearTheThreshold() {
        // 12% of 4 GB is 480 MB: just above is fine, just below is pressure.
        assertFalse(DeviceCapability.isMemoryPressure(500, 4_096))
        assertTrue(DeviceCapability.isMemoryPressure(400, 4_096))
    }

    @Test fun unknownTotalRamDoesNotReportPressure() {
        // A failed ActivityManager read must not nag the user.
        assertFalse(DeviceCapability.isMemoryPressure(0, 0))
        assertFalse(DeviceCapability.isMemoryPressure(100, 0))
    }
}
