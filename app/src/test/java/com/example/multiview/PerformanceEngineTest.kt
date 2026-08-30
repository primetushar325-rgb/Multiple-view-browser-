package com.example.multiview

import com.example.multiview.browser.PerformanceEngine
import com.example.multiview.browser.PerformanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The performance modes are a trade-off between fidelity and background cost,
 * so the rules are pinned here: the focused pane must never be degraded, and
 * BALANCED must never suppress anything.
 */
class PerformanceEngineTest {

    @Test fun focusedPaneIsNeverDegradedInAnyMode() {
        PerformanceMode.values().forEach { mode ->
            assertFalse("$mode must not block images on the focused pane",
                PerformanceEngine.shouldBlockImages(mode, isFocused = true))
            assertFalse("$mode must not pause the focused pane",
                PerformanceEngine.shouldThrottle(mode, isFocused = true, isVisible = true))
        }
    }

    @Test fun balancedSuppressesNothing() {
        assertFalse(PerformanceEngine.shouldBlockImages(PerformanceMode.BALANCED, isFocused = false))
        assertFalse(
            PerformanceEngine.shouldThrottle(
                PerformanceMode.BALANCED, isFocused = false, isVisible = false,
            ),
        )
    }

    @Test fun fastBlocksImagesOnlyOffFocus() {
        assertFalse(PerformanceEngine.shouldBlockImages(PerformanceMode.FAST, isFocused = true))
        assertTrue(PerformanceEngine.shouldBlockImages(PerformanceMode.FAST, isFocused = false))
    }

    /**
     * A visible neighbour must keep running in FAST, otherwise a YouTube pane
     * would freeze the moment the user tapped a different pane.
     */
    @Test fun fastKeepsVisibleNeighboursRunning() {
        assertFalse(
            PerformanceEngine.shouldThrottle(
                PerformanceMode.FAST, isFocused = false, isVisible = true,
            ),
        )
        assertTrue(
            PerformanceEngine.shouldThrottle(
                PerformanceMode.FAST, isFocused = false, isVisible = false,
            ),
        )
    }

    @Test fun maxPausesEverythingThatIsNotFocused() {
        assertTrue(
            PerformanceEngine.shouldThrottle(
                PerformanceMode.MAX, isFocused = false, isVisible = true,
            ),
        )
        assertTrue(
            PerformanceEngine.shouldThrottle(
                PerformanceMode.MAX, isFocused = false, isVisible = false,
            ),
        )
    }

    @Test fun unknownNameFallsBackToFastRatherThanThrowing() {
        assertEquals(PerformanceMode.FAST, PerformanceEngine.fromName(null))
        assertEquals(PerformanceMode.FAST, PerformanceEngine.fromName(""))
        assertEquals(PerformanceMode.FAST, PerformanceEngine.fromName("TURBO"))
        assertEquals(PerformanceMode.MAX, PerformanceEngine.fromName("MAX"))
        assertEquals(PerformanceMode.BALANCED, PerformanceEngine.fromName("BALANCED"))
    }
}
