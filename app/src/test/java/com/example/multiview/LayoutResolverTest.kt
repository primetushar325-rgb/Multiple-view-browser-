package com.example.multiview

import com.example.multiview.panes.Cell
import com.example.multiview.panes.LayoutResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LayoutResolverTest {

    @Test fun fullScreenIsOneCellSpanningEverything() {
        val a = LayoutResolver.resolve(1, "1x1")
        assertEquals(1, a.rows)
        assertEquals(1, a.cols)
        assertEquals(listOf(Cell(0, 0, 1, 1)), a.cells)
    }

    @Test fun sideBySideIsOneRowTwoColumns() {
        val a = LayoutResolver.resolve(2, "1x2")
        assertEquals(1, a.rows)
        assertEquals(2, a.cols)
        assertEquals(listOf(Cell(0, 0), Cell(0, 1)), a.cells)
    }

    @Test fun stackedIsTwoRowsOneColumn() {
        val a = LayoutResolver.resolve(2, "2x1")
        assertEquals(2, a.rows)
        assertEquals(1, a.cols)
        assertEquals(listOf(Cell(0, 0), Cell(1, 0)), a.cells)
    }

    @Test fun threeColumnsIsOneRowThreeCells() {
        val a = LayoutResolver.resolve(3, "1x3")
        assertEquals(1, a.rows)
        assertEquals(3, a.cols)
        assertEquals(3, a.cells.size)
        assertEquals(listOf(0, 1, 2), a.cells.map { it.col })
    }

    @Test fun grid2x2FillsRowByRow() {
        val a = LayoutResolver.resolve(4, "2x2")
        assertEquals(2, a.rows)
        assertEquals(2, a.cols)
        assertEquals(listOf(Cell(0, 0), Cell(0, 1), Cell(1, 0), Cell(1, 1)), a.cells)
    }

    @Test fun oneTopTwoBottomUsesAColumnSpan() {
        val a = LayoutResolver.resolve(3, "1t2b")
        assertEquals(2, a.rows)
        assertEquals(2, a.cols)
        // The top pane stretches across both columns.
        assertEquals(Cell(0, 0, 1, 2), a.cells[0])
        assertEquals(listOf(Cell(1, 0), Cell(1, 1)), a.cells.drop(1))
    }

    @Test fun grid3x3CapsAtEightPanes() {
        val a = LayoutResolver.resolve(8, "3x3")
        assertEquals(3, a.rows)
        assertEquals(3, a.cols)
        assertEquals(8, a.cells.size)
        // The ninth slot stays empty rather than breaking the global cap.
        assertEquals(8, LayoutResolver.MAX_PANES)
    }

    @Test fun smallerPaneCountsTruncateTheLayout() {
        assertEquals(1, LayoutResolver.resolve(1, "2x2").cells.size)
        assertEquals(3, LayoutResolver.resolve(3, "3x3").cells.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroPanes() {
        LayoutResolver.resolve(0, "1x1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPaneCountAboveCap() {
        LayoutResolver.resolve(LayoutResolver.MAX_PANES + 1, "3x3")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownLayout() {
        LayoutResolver.resolve(2, "7x9")
    }

    @Test fun rejectsNegativePaneCount() {
        try {
            LayoutResolver.resolve(-1, "1x1")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("paneCount"))
        }
    }

    @Test fun capacityMatchesThePickerLabels() {
        assertEquals(1, LayoutResolver.capacity("1x1"))
        assertEquals(2, LayoutResolver.capacity("1x2"))
        assertEquals(2, LayoutResolver.capacity("2x1"))
        assertEquals(3, LayoutResolver.capacity("1x3"))
        assertEquals(4, LayoutResolver.capacity("2x2"))
        assertEquals(3, LayoutResolver.capacity("1t2b"))
        assertEquals(8, LayoutResolver.capacity("3x3"))
    }

    @Test fun everyAdvertisedLayoutResolves() {
        LayoutResolver.LAYOUTS.forEach { (id, _) ->
            assertTrue("$id should be known", LayoutResolver.isKnown(id))
            LayoutResolver.resolve(1, id)
            LayoutResolver.resolve(LayoutResolver.capacity(id), id)
        }
    }

    @Test fun layoutChangeKeepsPaneOrderStable() {
        // Pane N must land in cell N in every layout, otherwise switching
        // layouts would re-parent the wrong WebView into the wrong slot.
        val counts = 1..LayoutResolver.MAX_PANES
        counts.forEach { n ->
            LayoutResolver.LAYOUTS.forEach { (id, _) ->
                val a = LayoutResolver.resolve(n, id)
                a.cells.forEachIndexed { paneIndex, cell ->
                    assertTrue("pane $paneIndex in $id out of range", cell.row < a.rows && cell.col < a.cols)
                }
                // Cells appear in ascending pane order.
                val flat = a.cells.map { it.row * 100 + it.col }
                assertEquals("cells for $id/$n must stay in pane order", flat.sorted(), flat)
            }
        }
    }

    @Test fun unknownLayoutFallsBackToOneColumn() {
        assertEquals(1, LayoutResolver.columns("nope"))
        assertEquals(1, LayoutResolver.rows("nope"))
        assertEquals(LayoutResolver.LAYOUTS[0].second, LayoutResolver.labelOf("nope"))
    }
}
