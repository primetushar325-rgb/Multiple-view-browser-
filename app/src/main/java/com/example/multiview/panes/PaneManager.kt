package com.example.multiview.panes

import android.app.ActivityManager
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.example.multiview.browser.PaneHost
import com.example.multiview.data.PaneState
import com.example.multiview.data.PanesSnapshot
import com.example.multiview.data.ProfileMode

/**
 * Owns the pane list and the grid that displays them.
 *
 * Layout changes never touch the WebViews: panes are detached from their old
 * parent and re-attached to the new one, so scroll position, history and any
 * in-progress load all survive. The same is true for maximizing, which simply
 * builds a one-cell grid out of the focused pane.
 */
class PaneManager(
    private val context: Context,
    private val container: FrameLayout,
    private val host: PaneHost,
) {
    val panes = mutableListOf<PaneView>()

    var layoutId: String = LayoutResolver.LAYOUTS[0].first
        private set

    var focusedIndex: Int = 0
        private set

    var maximizedIndex: Int = -1
        private set

    var defaultIsolate: Boolean = false

    /** Low-RAM devices get a smaller cap (P16). */
    val paneCap: Int = run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am?.isLowRamDevice == true) LOW_RAM_CAP else LayoutResolver.MAX_PANES
    }

    var onPanesChanged: (() -> Unit)? = null
    var onLimitReached: ((Int) -> Unit)? = null
    var onProfileUnsupported: (() -> Unit)? = null

    /** (pane, nowIsolated) */
    var onProfileChanged: ((PaneView, Boolean) -> Unit)? = null

    /** Fired when the user goes past the recommended number of isolated panes. */
    var onTooManyIsolated: (() -> Unit)? = null

    private fun reportIsolatedCount() {
        if (isolatedCount() >= ISOLATED_WARN_AT) onTooManyIsolated?.invoke()
    }

    /** (url, contentDisposition, mimeType, contentLength) */
    var onDownload: ((String, String?, String?, Long) -> Unit)? = null

    private fun wireDownloads(pane: PaneView) {
        pane.webView.setDownloadListener { url, _, disposition, mimeType, length ->
            onDownload?.invoke(url, disposition, mimeType, length)
        }
    }

    fun focusedPane(): PaneView? = panes.getOrNull(focusedIndex)

    fun setLayout(id: String) {
        if (!LayoutResolver.isKnown(id)) return
        layoutId = id
        maximizedIndex = -1
        refresh()
    }

    fun maximize(index: Int) {
        if (panes.getOrNull(index) == null) return
        maximizedIndex = if (maximizedIndex == index) -1 else index
        refresh()
    }

    fun restoreGrid() {
        maximizedIndex = -1
        refresh()
    }

    fun isMaximized(): Boolean = maximizedIndex >= 0

    /** @return the new pane, or null when the cap was hit. */
    fun addPane(mode: ProfileMode? = null, url: String? = null): PaneView? {
        if (panes.size >= paneCap) {
            onLimitReached?.invoke(paneCap)
            return null
        }
        val index = panes.size
        val wanted = mode ?: if (defaultIsolate) ProfileMode.ISOLATED else ProfileMode.SHARED
        val profileName = ProfilePlan.profileNameFor(index, wanted, IsolatedProfileFactory.isSupported())
        val pane = PaneView(context, index, host, profileName)
        pane.isolatedInEffect = profileName != null
        pane.profileMode = if (profileName != null) ProfileMode.ISOLATED else ProfileMode.SHARED
        pane.onCloseClick = { closePane(it.index) }
        pane.onMaximizeClick = { maximize(it.index) }
        pane.onProfileClick = { toggleProfile(it) }
        wireDownloads(pane)
        panes += pane
        focusedIndex = index
        refresh()
        if (!url.isNullOrEmpty()) pane.webView.loadUrl(url)
        onPanesChanged?.invoke()
        return pane
    }

    fun closePane(index: Int) {
        val pane = panes.getOrNull(index) ?: return
        pane.destroyCompletely()
        panes.removeAt(index)
        // Indices are positional, so renumber what is left and keep focus sane.
        panes.forEachIndexed { i, p -> reindex(p, i) }
        if (panes.isEmpty()) focusedIndex = 0 else focusedIndex = focusedIndex.coerceIn(0, panes.size - 1)
        if (maximizedIndex >= panes.size) maximizedIndex = -1
        refresh()
        onPanesChanged?.invoke()
    }

    /**
     * Flipping a pane between shared and isolated has to rebuild its WebView:
     * a WebView's profile is fixed at creation time. The page is reloaded, but
     * no other pane is disturbed.
     */
    fun toggleProfile(pane: PaneView) {
        val next = if (pane.profileMode == ProfileMode.ISOLATED) ProfileMode.SHARED else ProfileMode.ISOLATED
        if (next == ProfileMode.ISOLATED && !IsolatedProfileFactory.isSupported()) {
            onProfileUnsupported?.invoke()
            return
        }
        val index = pane.index
        val url = pane.currentUrl
        val desktop = pane.desktopSite
        pane.destroyCompletely()
        panes.removeAt(index)
        val replacement = addPaneAt(index, next, url)
        replacement?.setDesktopMode(desktop)
        refresh()
        replacement?.let { onProfileChanged?.invoke(it, it.isolatedInEffect) }
        reportIsolatedCount()
    }

    private fun addPaneAt(index: Int, mode: ProfileMode, url: String?): PaneView {
        val profileName = ProfilePlan.profileNameFor(index, mode, IsolatedProfileFactory.isSupported())
        val pane = PaneView(context, index, host, profileName)
        pane.isolatedInEffect = profileName != null
        pane.profileMode = if (profileName != null) ProfileMode.ISOLATED else ProfileMode.SHARED
        pane.onCloseClick = { closePane(it.index) }
        pane.onMaximizeClick = { maximize(it.index) }
        pane.onProfileClick = { toggleProfile(it) }
        wireDownloads(pane)
        panes.add(index, pane)
        panes.forEachIndexed { i, p -> reindex(p, i) }
        focusedIndex = index
        if (!url.isNullOrEmpty()) pane.webView.loadUrl(url)
        onPanesChanged?.invoke()
        return pane
    }

    /** Closing a pane shifts the survivors down; keep their index truthful. */
    private fun reindex(pane: PaneView, newIndex: Int) {
        pane.index = newIndex
    }

    fun focus(index: Int) {
        if (panes.getOrNull(index) == null) return
        focusedIndex = index
        panes.forEachIndexed { i, p -> p.setFocused(i == index) }
    }

    /** Rebuild the grid around the panes that already exist. */
    fun refresh() {
        container.removeAllViews()
        if (panes.isEmpty()) return

        val visible: List<PaneView> =
            if (maximizedIndex >= 0) listOfNotNull(panes.getOrNull(maximizedIndex)) else panes.toList()
        if (visible.isEmpty()) return

        val arrangement = LayoutResolver.resolve(
            paneCount = visible.size.coerceIn(1, LayoutResolver.MAX_PANES),
            layoutId = if (maximizedIndex >= 0) "1x1" else layoutId,
        )

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rows = LinkedHashMap<Int, MutableList<Pair<Cell, PaneView>>>()
        visible.forEachIndexed { i, pane ->
            val cell = arrangement.cells.getOrElse(i) { return@forEachIndexed }
            rows.getOrPut(cell.row) { mutableListOf() } += cell to pane
        }

        rows.toSortedMap().forEach { (_, entries) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            entries.sortedBy { it.first.col }.forEach { (cell, pane) ->
                pane.detachFromParent()
                row.addView(
                    pane,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, cell.colSpan.toFloat()),
                )
                val gap = context.resources.getDimensionPixelSize(com.example.multiview.R.dimen.pane_gap)
                (pane.layoutParams as LinearLayout.LayoutParams).setMargins(gap, gap, gap, gap)
            }
            root.addView(row)
        }

        container.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        focus(focusedIndex.coerceIn(0, panes.size - 1))
    }

    fun snapshot(): PanesSnapshot = PanesSnapshot(
        panes = panes.map { PaneState(it.currentUrl, it.currentTitle, it.profileMode) },
        focusedIndex = focusedIndex,
    )

    fun restore(snapshot: PanesSnapshot) {
        panes.forEach { it.destroyCompletely() }
        panes.clear()
        snapshot.panes.forEach { state ->
            val pane = addPane(state.profileMode, null) ?: return
            if (state.url.isNotEmpty()) pane.webView.loadUrl(state.url)
            pane.updateHeader(state.url, state.title)
        }
        focus(snapshot.focusedIndex.coerceIn(0, (panes.size - 1).coerceAtLeast(0)))
        refresh()
        onPanesChanged?.invoke()
    }

    fun pauseAll() = panes.forEach { it.pauseTimers() }
    fun resumeAll() = panes.forEach { it.resumeTimers() }

    fun applyTextZoom(zoom: Int) = panes.forEach { it.applyTextZoom(zoom) }

    fun isolatedCount(): Int = panes.count { it.isolatedInEffect }

    fun destroyAll() {
        panes.forEach { it.destroyCompletely() }
        panes.clear()
        container.removeAllViews()
    }

    companion object {
        const val LOW_RAM_CAP = 4

        /** Each isolated profile is its own browser instance; warn from the 5th. */
        const val ISOLATED_WARN_AT = 5
    }
}
