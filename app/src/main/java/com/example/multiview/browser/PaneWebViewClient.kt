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
    fun shouldBlockRequests(): Boolean
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
        if (!host.hostMatcher().isBlockedUrl(url)) return null
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
        // Only the main frame gets the branded page; subresource failures are
        // ignored so a missing ad script never blanks a working page.
        if (request.isForMainFrame) {
            val ctx = view.context
            view.loadDataWithBaseURL(
                request.url?.toString(),
                ErrorPage.html(
                    url = request.url?.toString() ?: "",
                    title = ctx.getString(com.example.multiview.R.string.err_title),
                    retry = ctx.getString(com.example.multiview.R.string.err_retry),
                    offline = ctx.getString(com.example.multiview.R.string.msg_offline),
                    offlineNow = !com.example.multiview.utils.NetUtils.isOnline(ctx),
                ),
                "text/html",
                "utf-8",
                null,
            )
        }
    }

    override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
        // Never proceed past a certificate problem.
        handler.cancel()
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

/** Branded inline error page with a working Retry link. */
object ErrorPage {
    fun html(url: String, title: String, retry: String, offline: String, offlineNow: Boolean): String {
        fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        val safe = esc(url)
        val heading = esc(title)
        val retryLabel = esc(retry)
        val note = if (offlineNow) "<p>${esc(offline)}</p>" else ""

        return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>MultiView</title>
            <style>
              body{margin:0;background:#0b0d12;color:#e8ecf4;
                   font-family:system-ui,-apple-system,sans-serif;
                   display:flex;min-height:100vh;align-items:center;justify-content:center}
              .card{padding:28px;max-width:420px;text-align:center}
              h1{font-size:18px;margin:0 0 8px}
              p{font-size:13px;color:#9aa7bd;margin:0 0 4px;word-break:break-all}
              a{display:inline-block;margin-top:18px;padding:10px 22px;border-radius:22px;
                background:#4a6cf7;color:#fff;text-decoration:none;font-size:14px}
            </style></head>
            <body><div class="card">
              <h1>$heading</h1>
              <p>$safe</p>
              $note
              <a href="$safe">$retryLabel</a>
            </div></body></html>
        """.trimIndent()
    }
}
