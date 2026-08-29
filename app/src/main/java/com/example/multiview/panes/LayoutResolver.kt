package com.example.multiview.panes

/** One slot in a grid: which row/column it starts at and how far it stretches. */
data class Cell(val row: Int, val col: Int, val rowSpan: Int = 1, val colSpan: Int = 1)

/** A resolved grid: its shape plus one [Cell] per visible pane, in pane order. */
data class Arrangement(val rows: Int, val cols: Int, val cells: List<Cell>)

/**
 * Maps (paneCount, layoutId) to a concrete grid.
 *
 * Pure and allocation-light so it can be exhaustively unit-tested. Cells are
 * produced in pane order, which is what makes a layout switch safe: pane N
 * always keeps index N, so existing WebView instances are merely re-parented
 * and never rebuilt or reloaded.
 */
object LayoutResolver {

    const val MAX_PANES = 8

    /** Every layout the picker offers, in display order. */
    val LAYOUTS: List<Pair<String, String>> = listOf(
        "1x1" to "Full screen (1 pane)",
        "1x2" to "Split left / right (2 panes)",
        "2x1" to "Split top / bottom (2 panes)",
        "1x3" to "Three columns (3 panes)",
        "2x2" to "Grid 2x2 (4 panes)",
        "1t2b" to "One top + two bottom (3 panes)",
        "3x3" to "Grid 3x3 (up to 8 panes)",
    )

    fun labelOf(layoutId: String): String =
        LAYOUTS.firstOrNull { it.first == layoutId }?.second ?: LAYOUTS[0].second

    /** Slot templates per layout. Order == pane order. */
    private val SLOTS: Map<String, Pair<Int, List<Cell>>> = mapOf(
        "1x1" to (1 to listOf(Cell(0, 0))),
        "1x2" to (1 to listOf(Cell(0, 0), Cell(0, 1))),
        "2x1" to (2 to listOf(Cell(0, 0), Cell(1, 0))),
        "1x3" to (1 to listOf(Cell(0, 0), Cell(0, 1), Cell(0, 2))),
        "2x2" to (2 to listOf(Cell(0, 0), Cell(0, 1), Cell(1, 0), Cell(1, 1))),
        "1t2b" to (2 to listOf(Cell(0, 0, 1, 2), Cell(1, 0), Cell(1, 1))),
        "3x3" to (3 to listOf(
            Cell(0, 0), Cell(0, 1), Cell(0, 2),
            Cell(1, 0), Cell(1, 1), Cell(1, 2),
            Cell(2, 0), Cell(2, 1), Cell(2, 2),
        )),
    )

    fun isKnown(layoutId: String): Boolean = layoutId in SLOTS

    /** How many panes a layout can show at once (3x3 shows 9 slots, cap still 8). */
    fun capacity(layoutId: String): Int {
        val slots = (SLOTS[layoutId] ?: SLOTS.getValue("1x1")).second
        return minOf(slots.size, MAX_PANES)
    }

    fun columns(layoutId: String): Int = when (layoutId) {
        "1x1", "2x1" -> 1
        "1x2" -> 2
        "1x3" -> 3
        "2x2", "1t2b" -> 2
        "3x3" -> 3
        else -> 1
    }

    fun rows(layoutId: String): Int = SLOTS[layoutId]?.first ?: 1

    /**
     * @throws IllegalArgumentException when [paneCount] is outside 1..[MAX_PANES]
     *   or the layout id is unknown.
     */
    fun resolve(paneCount: Int, layoutId: String): Arrangement {
        require(paneCount in 1..MAX_PANES) { "paneCount must be 1..$MAX_PANES but was $paneCount" }
        val slots = SLOTS[layoutId] ?: throw IllegalArgumentException("Unknown layoutId: $layoutId")
        val visible = minOf(paneCount, capacity(layoutId))
        return Arrangement(rows = slots.first, cols = columns(layoutId), cells = slots.second.take(visible))
    }
}
