package com.example.multiview.browser

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.multiview.utils.HostMatcher
import com.example.multiview.utils.UrlUtils
import java.io.ByteArrayInputStream

/** Everything a pane needs to tell its host activity about. */
interface PaneHost {
    fun onPageStarted(paneIndex: Int)
    fun onPageFinished(paneIndex: Int, url: String, title: String?)
    fun onProgress(paneIndex: Int, progress: Int)
    fun onFavicon(paneIndex: Int, icon: Bitmap?)
    fun onRequestExternal(url: String)
    fun onBlockedRequest()
    fun onRenderProcessGone(paneIndex: Int)
    /** Main-frame load failed: (message resource, url) for the native overlay. */
    fun onPageError(paneIndex: Int, messageRes: Int, url: String)
    fun shouldBlockRequests(): Boolean
    fun adblockMode(): com.example.multiview.utils.AdblockMode
    fun hostMatcher(): HostMatcher
    fun onFocusPane(paneIndex: Int)

    // --- WebChromeClient side (popups, fullscreen, uploads, device access) ---
    fun onPopupRequest(url: String)
    fun onShowFullscreen(view: android.view.View, callback: WebChromeClient.CustomViewCallback)
    fun onHideFullscreen()
    fun onChooseFile(
        callback: android.webkit.ValueCallback<Array<android.net.Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean
    fun onWebPermissionRequest(request: android.webkit.PermissionRequest)
    fun onGeolocationRequest(origin: String, callback: android.webkit.GeolocationPermissions.Callback)
}

class PaneWebViewClient(
    private val paneIndex: () -> Int,
    private val host: PaneHost,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url?.toString() ?: return false
        if (UrlUtils.isExternalScheme(url)) {
            host.onRequestExternal(url)
            return true
        }
        if (request.isForMainFrame) host.onFocusPane(paneIndex())
        return false
    }

    /**
     * Ad-blocking lives here because this is the only callback that can answer
     * a subresource with an empty body. (shouldOverrideUrlLoading only sees
     * navigations, so blocking there would miss ad iframes and scripts.)
     */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (!host.shouldBlockRequests()) return null
        val url = request.url?.toString() ?: return null
        val scheme = request.url?.scheme.orEmpty().lowercase()
        val accept = request.requestHeaders?.get("Accept").orEmpty()
        // WebSocket / SSE / streaming transports are never intercepted.
        if (com.example.multiview.utils.BlockingPolicy.isProtected(scheme, accept)) return null
        if (!host.hostMatcher().isBlockedUrl(url)) return null
        if (!com.example.multiview.utils.BlockingPolicy.shouldBlock(
                host.adblockMode(), matched = true,
                isMainFrame = request.isForMainFrame, protected = false)) return null
        host.onBlockedRequest()
        return emptyResponse()
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        host.onPageStarted(paneIndex())
    }

    override fun onPageFinished(view: WebView, url: String) {
        host.onPageFinished(paneIndex(), url, view.title)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        // Only the main frame gets the failure overlay; subresource errors are
        // ignored so a missing ad script never blanks a working page.
        if (!request.isForMainFrame) return
        val url = request.url?.toString().orEmpty()
        host.onPageError(paneIndex(), messageFor(view, error.errorCode), url)
    }

    /** Maps a main-frame failure to the most honest one-line reason. */
    @Suppress("DEPRECATION") // errorCode is deprecated but the only signal on minSdk 24
    private fun messageFor(view: WebView, code: Int): Int {
        val online = com.example.multiview.utils.NetUtils.isOnline(view.context)
        return when (code) {
            WebViewClient.ERROR_HOST_LOOKUP ->
                if (online) com.example.multiview.R.string.err_dns
                else com.example.multiview.R.string.err_no_internet
            WebViewClient.ERROR_TIMEOUT -> com.example.multiview.R.string.err_timeout
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> com.example.multiview.R.string.err_ssl
            WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_IO ->
                if (online) com.example.multiview.R.string.err_generic
                else com.example.multiview.R.string.err_no_internet
            else -> com.example.multiview.R.string.err_generic
        }
    }

    override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
        // Never proceed past a certificate problem; tell the pane why.
        handler.cancel()
        host.onPageError(
            paneIndex(),
            com.example.multiview.R.string.err_ssl,
            error.url ?: view.url.orEmpty(),
        )
    }

    /**
     * A crashed renderer must take down only its own pane. Returning true tells
     * the system we handled it; the host rebuilds this pane in place.
     */
    override fun onRenderProcessGone(
        view: WebView,
        detail: android.webkit.RenderProcessGoneDetail,
    ): Boolean {
        host.onRenderProcessGone(paneIndex())
        return true
    }

    private fun emptyResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        ByteArrayInputStream(ByteArray(0)),
    )
}
