package com.example.multiview.panes

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.multiview.R
import com.example.multiview.browser.PaneChromeClient
import com.example.multiview.browser.PaneHost
import com.example.multiview.browser.PaneWebViewClient
import com.example.multiview.browser.WebViewFactory
import com.example.multiview.data.PaneIdentity
import com.example.multiview.data.ProfileMode
import com.example.multiview.utils.UrlUtils
import com.google.android.material.button.MaterialButton

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
     * Visual position in the pane list. A `var` on purpose: when an earlier
     * pane is closed the survivors shift down, and the WebView client lambdas
     * read this property (not a captured copy) so their callbacks always name
     * the pane that is really there.
     *
     * This is ONLY a position - it carries no account meaning.
     */
    var index: Int,
    /**
     * Permanent identity: the pane's stable id plus the WebView profile holding
     * its session. Closing or reordering other panes changes [index] but never
     * this, so account B can never silently become account A.
     */
    val identity: PaneIdentity,
    private val host: PaneHost,
    profileName: String?,
) : LinearLayout(context) {

    private val badge: TextView
    private val avatar: TextView
    private val accountLabel: TextView
    private val icon: ImageView
    private val title: TextView
    private val profileButton: ImageView
    private val reloadButton: ImageView
    private val maximizeButton: ImageView
    private val closeButton: ImageView
    private val stateBox: LinearLayout
    private val stateSpinner: ProgressBar
    private val stateText: TextView
    private val stateActions: LinearLayout
    private val retryButton: MaterialButton
    private val externalButton: MaterialButton
    private val webHolder: FrameLayout

    val webView: android.webkit.WebView
    val originalUserAgent: String

    /** Renamed off `desktopMode` so it cannot collide with setDesktopMode(). */
    var desktopSite: Boolean = false
        private set
    var profileMode: ProfileMode = ProfileMode.SHARED
    var currentUrl: String = ""
    var currentTitle: String = ""
    var isolatedInEffect: Boolean = profileName != null

    /** When this pane was first created; display metadata only. */
    val createdAt: Long = System.currentTimeMillis()

    /**
     * Email shown on the pane header and in Settings > Accounts.
     *
     * Only ever populated from information the page itself exposes, and stored
     * as display metadata. No credential, token or cookie is ever read out of
     * the profile.
     */
    var accountEmail: String = ""
        set(value) {
            field = value
            renderIdentity()
        }

    private var isolatedSlot: Int = -1
    private var headerReady: Boolean = false
    private var faviconBitmap: Bitmap? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_pane, this, true)
        // Premium card: rounded, elevated surface (the background drawables are
        // rounded, so the shadow follows the outline).
        elevation = resources.getDimension(R.dimen.pane_card_elevation)

        badge = findViewById(R.id.paneBadge)
        avatar = findViewById(R.id.paneAvatar)
        accountLabel = findViewById(R.id.paneAccount)
        icon = findViewById(R.id.paneIcon)
        title = findViewById(R.id.paneTitle)
        profileButton = findViewById(R.id.paneProfile)
        reloadButton = findViewById(R.id.paneReload)
        maximizeButton = findViewById(R.id.paneMaximize)
        closeButton = findViewById(R.id.paneClose)
        stateBox = findViewById(R.id.paneState)
        stateSpinner = findViewById(R.id.paneSpinner)
        stateText = findViewById(R.id.paneStateText)
        stateActions = findViewById(R.id.paneStateActions)
        retryButton = findViewById(R.id.paneRetry)
        externalButton = findViewById(R.id.paneOpenExternal)
        webHolder = findViewById(R.id.paneWebHolder)

        webView = WebViewFactory.create(context, profileName)
        originalUserAgent = webView.settings.userAgentString ?: ""

        webView.webViewClient = PaneWebViewClient({ index }, host)
        webView.webChromeClient = PaneChromeClient({ index }, host)
        // Index 0 keeps the state overlay drawn on top of the page.
        webHolder.addView(
            webView, 0,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        // Tapping anywhere in the pane focuses it.
        setOnClickListener { host.onFocusPane(index) }
        webHolder.setOnClickListener { host.onFocusPane(index) }

        reloadButton.setOnClickListener { webView.reload() }
        closeButton.setOnClickListener { host.onFocusPane(index); onCloseClick?.invoke(this) }
        maximizeButton.setOnClickListener { onMaximizeClick?.invoke(this) }
        profileButton.setOnClickListener { onProfileClick?.invoke(this) }
        accountLabel.setOnClickListener { onProfileClick?.invoke(this) }
        icon.setOnClickListener { onIconClick?.invoke(this) }
        headerReady = true
    }

    var onIconClick: ((PaneView) -> Unit)? = null
    var onCloseClick: ((PaneView) -> Unit)? = null
    var onMaximizeClick: ((PaneView) -> Unit)? = null
    var onProfileClick: ((PaneView) -> Unit)? = null
    var onOpenExternal: ((String) -> Unit)? = null

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
        detectAccountFromTitle()
    }

    /**
     * Best-effort display of which account a pane is showing, taken only from
     * the page's own title. Google renders "Inbox - user@example.com - Gmail",
     * so the address is already public to the user without scraping anything
     * sensitive. Anything that does not look like an address is ignored.
     */
    private fun detectAccountFromTitle() {
        val found = EMAIL_REGEX.find(currentTitle)?.value ?: return
        if (found.equals(accountEmail, ignoreCase = true)) return
        accountEmail = found
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
        isolatedSlot = if (mode == ProfileMode.ISOLATED && isolatedInEffect) isolatedCount + 1 else -1
        renderIdentity()
    }

    /** Redraws the Pn badge, the account line and the profile icon. */
    private fun renderIdentity() {
        // The accountEmail setter can fire before the header views exist.
        if (!headerReady) return
        if (isolatedSlot > 0) {
            badge.visibility = View.VISIBLE
            badge.text = context.getString(R.string.badge_format, isolatedSlot)
        } else {
            badge.visibility = View.GONE
        }
        val email = accountEmail.trim()
        if (email.isNotEmpty()) {
            avatar.visibility = View.VISIBLE
            avatar.text = email.first().uppercaseChar().toString()
            accountLabel.visibility = View.VISIBLE
            accountLabel.text = shortenEmail(email)
            accountLabel.contentDescription = email
        } else {
            avatar.visibility = View.GONE
            accountLabel.visibility = View.GONE
        }
        profileButton.setImageResource(
            if (isolatedSlot > 0) R.drawable.ic_person_isolated else R.drawable.ic_person
        )
    }

    /** account1@verylongdomain.com -> account1@verylon… so the header never overflows. */
    private fun shortenEmail(email: String): String =
        if (email.length <= MAX_EMAIL_CHARS) email
        else email.take(MAX_EMAIL_CHARS - 1) + context.getString(R.string.ellipsis)

    // ----------------------------------------------------- loading states

    /** Spinner + "Loading…"; replaces the black rectangle while a page starts. */
    fun showLoading() {
        if (!headerReady) return
        stateSpinner.visibility = View.VISIBLE
        stateActions.visibility = View.GONE
        stateText.text = context.getString(R.string.state_loading)
        stateBox.visibility = View.VISIBLE
    }

    /**
     * Failure state with a reason and two ways out. Shown for main-frame
     * errors, SSL failures and timeouts so a pane is never silently blank.
     */
    fun showError(messageRes: Int, url: String) {
        if (!headerReady) return
        stateSpinner.visibility = View.GONE
        stateActions.visibility = View.VISIBLE
        stateText.text = context.getString(messageRes)
        retryButton.setText(R.string.err_retry)
        retryButton.setOnClickListener {
            hideState()
            if (url.isNotEmpty()) webView.loadUrl(url) else webView.reload()
        }
        externalButton.visibility = View.VISIBLE
        externalButton.setOnClickListener { onOpenExternal?.invoke(url) }
        stateBox.visibility = View.VISIBLE
    }

    /** Renderer died: offer a reload of the same page in a fresh WebView. */
    fun showCrashedState(url: String) {
        if (!headerReady) return
        stateSpinner.visibility = View.GONE
        stateActions.visibility = View.VISIBLE
        stateText.text = context.getString(R.string.err_crashed)
        retryButton.setText(R.string.err_reload)
        retryButton.setOnClickListener {
            hideState()
            if (url.isNotEmpty()) webView.loadUrl(url)
        }
        externalButton.visibility = View.GONE
        stateBox.visibility = View.VISIBLE
    }

    fun hideState() {
        if (!headerReady) return
        stateBox.visibility = View.GONE
    }

    // ----------------------------------------------------- settings

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

    /**
     * Skips network image loads for this pane. Reversible: focusing the pane
     * sets this back to false and images appear on the next load.
     */
    fun setImagesBlocked(blocked: Boolean) {
        if (webView.settings.blockNetworkImage == blocked) return
        webView.settings.blockNetworkImage = blocked
    }

    /**
     * Per-instance throttle via WebView.onPause()/onResume().
     *
     * Best-effort by contract: it stops animations and geolocation, but it does
     * not stop JavaScript. The process-wide pauseTimers() is intentionally not
     * used for this - it would pause every pane, including the focused one.
     */
    fun setThrottled(throttled: Boolean) {
        if (throttled) webView.onPause() else webView.onResume()
    }

    /** Whole-app background/foreground: these ARE process-wide, and that is
     *  correct here because the app itself is leaving or returning. */
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

    companion object {
        private const val MAX_EMAIL_CHARS = 18
        private val EMAIL_REGEX =
            Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    }
}
