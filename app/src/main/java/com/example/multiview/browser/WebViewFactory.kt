package com.example.multiview.browser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.example.multiview.panes.IsolatedProfileFactory

/**
 * Creates and configures every pane WebView identically.
 *
 * The user agent is left at the system default on purpose: Google flags custom
 * user agents during sign-in. The desktop-site toggle swaps in a desktop UA
 * only while the user has explicitly asked for it.
 */
object WebViewFactory {

    /** Used only when the user turns on Desktop site for a pane. */
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /**
     * @param profileName non-null to give this pane its own isolated cookie
     *   store. Must be applied before the first load, which is why it happens
     *   here rather than after the WebView reaches the view tree.
     */
    fun create(context: Context, profileName: String?): WebView {
        val webView = WebView(context)
        if (profileName != null) IsolatedProfileFactory.attach(webView, profileName)
        configure(webView)
        return webView
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true
        // No black rectangle while the renderer is still coming up.
        webView.setBackgroundColor(android.graphics.Color.WHITE)
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.databaseEnabled = true

        // Google/Gmail open login popups as new windows; without both of these
        // plus onCreateWindow the sign-in flow dies silently.
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true

        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false

        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        s.allowFileAccess = false
        s.allowContentAccess = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = true
        }

        s.textZoom = s.textZoom // no-op; real value applied by PaneManager

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
    }

    fun setDesktopMode(webView: WebView, enabled: Boolean, originalUserAgent: String) {
        webView.settings.userAgentString = if (enabled) DESKTOP_USER_AGENT else originalUserAgent
    }
}
