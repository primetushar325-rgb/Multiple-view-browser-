package com.example.multiview.browser

import android.graphics.Bitmap
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.net.Uri

/**
 * Chrome-level behaviour for one pane: progress, title, favicon, new windows,
 * fullscreen video, file upload and device-access prompts.
 */
class PaneChromeClient(
    private val paneIndex: () -> Int,
    private val host: PaneHost,
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        host.onProgress(paneIndex(), newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String) {
        host.onPageFinished(paneIndex(), view.url ?: "", title)
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap) {
        host.onFavicon(paneIndex(), icon)
    }

    /**
     * Google/Gmail sign-in opens its consent screens as a *new window*. If this
     * returns false the popup is dropped and login stalls with no error.
     *
     * A throwaway WebView is handed to the transport, its first navigation is
     * intercepted and routed to the host (which shows it in an overlay), and
     * the throwaway is destroyed immediately so nothing leaks.
     */
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean {
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val temp = WebView(view.context)
        temp.settings.javaScriptEnabled = true
        temp.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(
                v: WebView,
                request: android.webkit.WebResourceRequest,
            ): Boolean {
                val url = request.url?.toString()
                if (url != null) host.onPopupRequest(url)
                temp.destroy()
                return true
            }
        }
        transport.webView = temp
        resultMsg.sendToTarget()
        return true
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        host.onShowFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        host.onHideFullscreen()
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = host.onChooseFile(filePathCallback, fileChooserParams)

    override fun onPermissionRequest(request: PermissionRequest) {
        host.onWebPermissionRequest(request)
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        host.onGeolocationRequest(origin, callback)
    }

}
