package com.example.multiview.panes

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.example.multiview.browser.PaneHost
import com.example.multiview.browser.PerformanceEngine
import com.example.multiview.browser.PerformanceMode
import com.example.multiview.data.PaneIdentity
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
 *
 * Every pane is built by [createPane] and every pane carries a permanent
 * [PaneIdentity], so closing or reordering panes changes positions but never
 * makes one account's session belong to another pane.
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

    /**
     * How much background work to suppress. Defaults to FAST: unfocused panes
     * skip image loads and get throttled, the focused pane never does.
     */
    var performanceMode: PerformanceMode = PerformanceMode.FAST

    /**
     * Pane ceiling for THIS device, derived from physical RAM rather than a
     * fixed number: a 2 GB phone is capped at 4 panes, a flagship at 12.
     */
    private val capability = DeviceCapability.Probe(context)

    val paneCap: Int = capability.paneCap()

    /**
     * True when this device's RAM forced the ceiling below what the grid can
     * draw. Drives the one-time "your phone is limited to N screens" notice.
     */
    val isCappedByHardware: Boolean get() = paneCap < LayoutResolver.MAX_PANES

    /** Total physical RAM in MB, for display in Settings. */
    val totalRamMb: Long get() = capability.totalRamMb

    var onPanesChanged: (() -> Unit)? = null
    var onLimitReached: ((Int) -> Unit)? = null
    var onProfileUnsupported: (() -> Unit)? = null

    /** (pane, nowIsolated) */
    var onProfileChanged: ((PaneView, Boolean) -> Unit)? = null

    /** Fired when the user goes past the recommended number of isolated panes. */
    var onTooManyIsolated: (() -> Unit)? = null

    /**
     * Fired when free memory is low enough that the next pane may cost the user
     * an existing one. The caller decides how to present it.
     */
    var onMemoryPressure: (() -> Unit)? = null

    /**
     * Fired once for every newly created pane so callers can wire the parts
     * that live outside this class (for example opening a URL externally).
     */
    var onPaneCreated: ((PaneView) -> Unit)? = null

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

    /**
     * The ONLY place a PaneView is constructed.
     *
     * Keeping construction in one spot is what makes identity trustworthy:
     * there is exactly one path that decides a pane's profile, so a pane can
     * never end up bound to a profile chosen from its array position.
     */
    private fun createPane(index: Int, identity: PaneIdentity, mode: ProfileMode): PaneView {
        val supported = IsolatedProfileFactory.isSupported()
        val profileName = ProfilePlan.profileNameFor(identity, mode, supported)
        val pane = PaneView(context, index, identity, host, profileName)
        pane.isolatedInEffect = profileName != null
        pane.profileMode = if (profileName != null) ProfileMode.ISOLATED else ProfileMode.SHARED
        pane.onCloseClick = { closePane(it.index) }
        pane.onMaximizeClick = { maximize(it.index) }
        pane.onProfileClick = { toggleProfile(it) }
        wireDownloads(pane)
        onPaneCreated?.invoke(pane)
        return pane
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
    fun addPane(
        mode: ProfileMode? = null,
        url: String? = null,
        identity: PaneIdentity = PaneIdentity.newIdentity(),
    ): PaneView? {
        if (panes.size >= paneCap) {
            onLimitReached?.invoke(paneCap)
            return null
        }
        if (capability.underPressure()) onMemoryPressure?.invoke()
        val index = panes.size
        val wanted = mode ?: if (defaultIsolate) ProfileMode.ISOLATED else ProfileMode.SHARED
        val pane = createPane(index, identity, wanted)
        panes += pane
        focusedIndex = index
        refresh()
        pane.webView.loadUrl(url?.takeIf { it.isNotEmpty() } ?: DEFAULT_URL)
        onPanesChanged?.invoke()
        return pane
    }

    fun closePane(index: Int) {
        val pane = panes.getOrNull(index) ?: return
        pane.destroyCompletely()
        panes.removeAt(index)
        // Indices are positional, so renumber what is left and keep focus sane.
        // Identities are NOT renumbered - they are permanent.
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
     *
     * The pane keeps its ORIGINAL identity, so switching a pane back to
     * isolated lands on the same profile it used before - the user's login on
     * that pane is still there instead of being replaced by a blank profile.
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
        val email = pane.accountEmail
        pane.destroyCompletely()
        panes.removeAt(index)
        val replacement = addPaneAt(index, next, url, pane.identity)
        replacement?.let {
            it.accountEmail = email
            it.setDesktopMode(desktop)
        }
        refresh()
        replacement?.let { onProfileChanged?.invoke(it, it.isolatedInEffect) }
        reportIsolatedCount()
    }

    private fun addPaneAt(index: Int, mode: ProfileMode, url: String?, identity: PaneIdentity): PaneView {
        val pane = createPane(index, identity, mode)
        panes.add(index, pane)
        panes.forEachIndexed { i, p -> reindex(p, i) }
        focusedIndex = index
        pane.webView.loadUrl(url?.takeIf { it.isNotEmpty() } ?: DEFAULT_URL)
        onPanesChanged?.invoke()
        return pane
    }

    /**
     * Rebuilds ONE pane after its renderer died, keeping the same identity so
     * the profile (and therefore the login) is unchanged.
     *
     * Deliberately does not reload the page: the caller shows a recovery state
     * with a Retry button so the user decides, rather than the app silently
     * reloading a page that may have been mid-transaction.
     *
     * @return the replacement pane, or null when the index was out of range.
     */
    fun recreatePane(index: Int): PaneView? {
        val old = panes.getOrNull(index) ?: return null
        val identity = old.identity
        val mode = old.profileMode
        val url = old.currentUrl
        val title = old.currentTitle
        val desktop = old.desktopSite
        val email = old.accountEmail
        old.destroyCompletely()
        val replacement = createPane(index, identity, mode)
        replacement.accountEmail = email
        if (desktop) replacement.setDesktopMode(true)
        replacement.updateHeader(url, title)
        panes[index] = replacement
        refresh()
        return replacement
    }

    /** Closing a pane shifts the survivors down; keep their index truthful. */
    private fun reindex(pane: PaneView, newIndex: Int) {
        pane.index = newIndex
    }

    fun focus(index: Int) {
        if (panes.getOrNull(index) == null) return
        focusedIndex = index
        panes.forEachIndexed { i, p -> p.setFocused(i == index) }
        applyPerformancePolicy()
    }

    /**
     * Re-applies the effort budget to every pane.
     *
     * Called whenever focus or the layout changes, because both alter which
     * panes are worth spending CPU and bandwidth on. Every effect here is
     * reversible and none of it touches the network path: this reduces what the
     * app asks for, it does not claim to make the connection faster.
     */
    fun applyPerformancePolicy() {
        panes.forEachIndexed { i, pane ->
            val focused = i == focusedIndex
            // In a maximized layout only the maximized pane is visible.
            val visible = maximizedIndex < 0 || i == maximizedIndex
            pane.setImagesBlocked(PerformanceEngine.shouldBlockImages(performanceMode, focused))
            pane.setThrottled(PerformanceEngine.shouldThrottle(performanceMode, focused, visible))
        }
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
        panes = panes.map {
            PaneState(
                paneId = it.identity.paneId,
                profileId = it.identity.profileId,
                url = it.currentUrl,
                title = it.currentTitle,
                profileMode = it.profileMode,
                desktopMode = it.desktopSite,
                accountEmail = it.accountEmail,
                createdAt = it.createdAt,
            )
        },
        focusedIndex = focusedIndex,
    )

    fun restore(snapshot: PanesSnapshot) {
        panes.forEach { it.destroyCompletely() }
        panes.clear()
        snapshot.panes.forEach { state ->
            // Passing state.identity is the whole point of stable ids: a
            // restored pane reattaches to the SAME profile it had before the
            // restart, so its Google session is still signed in.
            val pane = addPane(state.profileMode, state.url.ifEmpty { null }, state.identity) ?: return
            pane.accountEmail = state.accountEmail
            if (state.desktopMode) pane.setDesktopMode(true)
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

    /** The profile name of one pane, for the "signed in as" summary. */
    fun accountEmails(): List<Pair<String, String>> =
        panes.mapNotNull { p -> p.accountEmail.takeIf { it.isNotBlank() }?.let { p.identity.profileId to it } }

    fun destroyAll() {
        panes.forEach { it.destroyCompletely() }
        panes.clear()
        container.removeAllViews()
    }

    companion object {
        /** A pane is never left empty; this is the neutral default page. */
        const val DEFAULT_URL = "https://www.google.com"

        /** Each isolated profile is its own browser instance; warn from the 5th. */
        const val ISOLATED_WARN_AT = 5
    }
}
