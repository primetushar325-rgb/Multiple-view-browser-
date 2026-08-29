package com.example.multiview.panes

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.multiview.R
import com.example.multiview.browser.PaneChromeClient
import com.example.multiview.browser.PaneHost
import com.example.multiview.browser.PaneWebViewClient
import com.example.multiview.browser.WebViewFactory
import com.example.multiview.data.ProfileMode
import com.example.multiview.utils.UrlUtils

/**
 * One pane: a 48dp header plus its WebView.
 *
 * The WebView is created once and lives as long as the pane. Re-parenting this
 * view during a layout change therefore never reloads the page, and closing the
 * pane is the only thing that calls WebView.destroy().
 */
class PaneView(
    context: Context,
    /**
     * Position in the pane list. A `var` on purpose: when an earlier pane is
     * closed the survivors shift down, and the WebView client lambdas read this
     * property (not a captured copy) so their callbacks always name the pane
     * that is really there.
     */
    var index: Int,
    private val host: PaneHost,
    profileName: String?,
) : LinearLayout(context) {

    private val badge: TextView
    private val icon: ImageView
    private val title: TextView
    private val profileButton: ImageView
    private val reloadButton: ImageView
    private val maximizeButton: ImageView
    private val closeButton: ImageView
    private val webHolder: FrameLayout

    val webView: android.webkit.WebView
    val originalUserAgent: String
    /** Renamed off `desktopMode` so it cannot collide with setDesktopMode(). */
    var desktopSite: Boolean = false
        private set
    var profileMode: ProfileMode = ProfileMode.SHARED
    var currentUrl: String = ""
    var currentTitle: String = ""
    private var faviconBitmap: Bitmap? = null
    var isolatedInEffect: Boolean = profileName != null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_pane, this, true)

        badge = findViewById(R.id.paneBadge)
        icon = findViewById(R.id.paneIcon)
        title = findViewById(R.id.paneTitle)
        profileButton = findViewById(R.id.paneProfile)
        reloadButton = findViewById(R.id.paneReload)
        maximizeButton = findViewById(R.id.paneMaximize)
        closeButton = findViewById(R.id.paneClose)
        webHolder = findViewById(R.id.paneWebHolder)

        webView = WebViewFactory.create(context, profileName)
        originalUserAgent = webView.settings.userAgentString ?: ""

        webView.webViewClient = PaneWebViewClient({ index }, host)
        webView.webChromeClient = PaneChromeClient({ index }, host)
        webHolder.addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Tapping anywhere in the pane focuses it.
        setOnClickListener { host.onFocusPane(index) }
        webHolder.setOnClickListener { host.onFocusPane(index) }

        reloadButton.setOnClickListener { webView.reload() }
        closeButton.setOnClickListener { host.onFocusPane(index); onCloseClick?.invoke(this) }
        maximizeButton.setOnClickListener { onMaximizeClick?.invoke(this) }
        profileButton.setOnClickListener { onProfileClick?.invoke(this) }
    }

    var onCloseClick: ((PaneView) -> Unit)? = null
    var onMaximizeClick: ((PaneView) -> Unit)? = null
    var onProfileClick: ((PaneView) -> Unit)? = null

    fun setFocused(focused: Boolean) {
        setBackgroundResource(if (focused) R.drawable.bg_pane_focused else R.drawable.bg_pane_normal)
    }

    fun updateHeader(url: String?, newTitle: String?) {
        if (!url.isNullOrEmpty()) currentUrl = url
        if (!newTitle.isNullOrBlank()) currentTitle = newTitle
        val host = UrlUtils.hostOf(currentUrl)
        title.text = when {
            host.isNotEmpty() -> host
            currentTitle.isNotEmpty() -> currentTitle
            else -> context.getString(R.string.pane_new_tab)
        }
    }

    fun setFavicon(bmp: Bitmap?) {
        if (bmp == null) return
        faviconBitmap = bmp
        icon.setImageBitmap(bmp)
    }

    fun resetIcon() {
        faviconBitmap = null
        icon.setImageResource(R.drawable.ic_globe)
    }

    /** Isolated panes get a coloured P1/P2/P3 badge so accounts stay tellable apart. */
    fun updateProfileBadge(mode: ProfileMode, isolatedCount: Int) {
        profileMode = mode
        if (mode == ProfileMode.ISOLATED && isolatedInEffect) {
            badge.visibility = View.VISIBLE
            badge.text = context.getString(R.string.badge_format, isolatedCount + 1)
            profileButton.setImageResource(R.drawable.ic_person_isolated)
        } else {
            badge.visibility = View.GONE
            profileButton.setImageResource(R.drawable.ic_person)
        }
    }

    fun setDesktopMode(enabled: Boolean) {
        if (desktopSite == enabled) return
        desktopSite = enabled
        WebViewFactory.setDesktopMode(webView, enabled, originalUserAgent)
        webView.reload()
    }

    fun applyTextZoom(zoom: Int) {
        webView.settings.textZoom = zoom
    }

    /** Algorithmic darkening is feature-gated; silently skipped when absent. */
    fun applyForceDark(enabled: Boolean) {
        runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(
                    androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, enabled)
            }
        }
    }

    fun pauseTimers() = webView.pauseTimers()
    fun resumeTimers() = webView.resumeTimers()

    /** Detaches from any parent so the pane can be moved into a new grid. */
    fun detachFromParent() {
        (parent as? android.view.ViewGroup)?.removeView(this)
    }

    /** The only place a WebView is torn down. */
    fun destroyCompletely() {
        detachFromParent()
        runCatching {
            webHolder.removeView(webView)
            webView.stopLoading()
            webView.webViewClient = android.webkit.WebViewClient()
            webView.webChromeClient = android.webkit.WebChromeClient()
            webView.destroy()
        }
    }
}
