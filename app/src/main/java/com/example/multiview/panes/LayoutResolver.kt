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

    /**
     * Upper bound the grid can show. Devices with little RAM get a smaller cap
     * from DeviceCapability; this is only the ceiling the layout maths allows.
     */
    const val MAX_PANES = 12

    /** Every layout the picker offers, in display order. */
    val LAYOUTS: List<Pair<String, String>> = listOf(
        "1x1" to "Full screen (1 pane)",
        "1x2" to "Split left / right (2 panes)",
        "2x1" to "Split top / bottom (2 panes)",
        "1x3" to "Three columns (3 panes)",
        "2x2" to "Grid 2x2 (4 panes)",
        "1t2b" to "One top + two bottom (3 panes)",
        "2x3" to "Grid 2x3 (6 panes)",
        "3x2" to "Grid 3x2 (6 panes)",
        "2x4" to "Grid 2x4 (8 panes)",
        "3x3" to "Grid 3x3 (9 panes)",
        "3x4" to "Grid 3x4 (12 panes)",
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
        // 2 rows x 3 columns
        "2x3" to (2 to listOf(
            Cell(0, 0), Cell(0, 1), Cell(0, 2),
            Cell(1, 0), Cell(1, 1), Cell(1, 2),
        )),
        // 3 rows x 2 columns
        "3x2" to (3 to listOf(
            Cell(0, 0), Cell(0, 1),
            Cell(1, 0), Cell(1, 1),
            Cell(2, 0), Cell(2, 1),
        )),
        // 2 rows x 4 columns
        "2x4" to (2 to listOf(
            Cell(0, 0), Cell(0, 1), Cell(0, 2), Cell(0, 3),
            Cell(1, 0), Cell(1, 1), Cell(1, 2), Cell(1, 3),
        )),
        "3x3" to (3 to listOf(
            Cell(0, 0), Cell(0, 1), Cell(0, 2),
            Cell(1, 0), Cell(1, 1), Cell(1, 2),
            Cell(2, 0), Cell(2, 1), Cell(2, 2),
        )),
        // 3 rows x 4 columns - the full 12-pane grid
        "3x4" to (3 to listOf(
            Cell(0, 0), Cell(0, 1), Cell(0, 2), Cell(0, 3),
            Cell(1, 0), Cell(1, 1), Cell(1, 2), Cell(1, 3),
            Cell(2, 0), Cell(2, 1), Cell(2, 2), Cell(2, 3),
        )),
    )

    fun isKnown(layoutId: String): Boolean = layoutId in SLOTS

    /** How many panes a layout can show at once, never more than [MAX_PANES]. */
    fun capacity(layoutId: String): Int {
        val slots = (SLOTS[layoutId] ?: SLOTS.getValue("1x1")).second
        return minOf(slots.size, MAX_PANES)
    }

    /** Number of columns in a layout. Names are "<rows>x<cols>". */
    fun columns(layoutId: String): Int = when (layoutId) {
        "1x1", "2x1" -> 1
        "1x2", "2x2", "1t2b", "3x2" -> 2
        "1x3", "2x3", "3x3" -> 3
        "2x4", "3x4" -> 4
        else -> 1
    }

    fun rows(layoutId: String): Int = SLOTS[layoutId]?.first ?: 1

    /**
     * Smallest layout whose capacity fits [paneCount], so opening N panes never
     * silently hides the ones past a too-small grid. Falls back to the full
     * 3x4 grid at the ceiling. Used by the grid path when the user's chosen
     * layout is too small for the panes that are actually open.
     */
    fun bestLayoutFor(paneCount: Int): String {
        val n = paneCount.coerceIn(1, MAX_PANES)
        return when {
            n <= 1 -> "1x1"
            n == 2 -> "1x2"
            n == 3 -> "1x3"
            n == 4 -> "2x2"
            n <= 6 -> "2x3"
            n <= 8 -> "2x4"
            n <= 9 -> "3x3"
            else -> "3x4"
        }
    }

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
