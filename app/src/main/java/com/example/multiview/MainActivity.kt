package com.example.multiview

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.multiview.appContainer
import com.example.multiview.browser.PaneHost
import com.example.multiview.data.PanesSnapshot
import com.example.multiview.data.ProfileMode
import com.example.multiview.databinding.ActivityMainBinding
import com.example.multiview.panes.LayoutResolver
import com.example.multiview.panes.PaneManager
import com.example.multiview.panes.PaneView
import com.example.multiview.panes.ProfilePlan
import com.example.multiview.ui.HomeSheet
import com.example.multiview.ui.FindSheet
import com.example.multiview.ui.LayoutPickerSheet
import com.example.multiview.ui.SettingsActivity
import com.example.multiview.ui.SitePickerSheet
import com.example.multiview.utils.HostMatcher
import com.example.multiview.utils.NetUtils
import com.example.multiview.utils.UrlUtils
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity(), PaneHost {

    private lateinit var b: ActivityMainBinding
    private lateinit var paneManager: PaneManager

    private val settings get() = appContainer.settings
    private val panesRepo get() = appContainer.panes
    private val blocklistRepo get() = appContainer.blocklist

    @Volatile private var adblockOn: Boolean = true
    @Volatile private var currentAdblockMode: com.example.multiview.utils.AdblockMode =
        com.example.multiview.utils.AdblockMode.NORMAL
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraFile: File? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var findSheet: FindSheet? = null
    private var notificationPermissionAsked = false

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingFileCallback ?: return@registerForActivityResult
        pendingFileCallback = null
        pendingCameraFile = null
        // parseResult yields null on cancel, which is exactly what the page needs.
        cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }

    private val runtimePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { !it }) toast(R.string.msg_perm_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        paneManager = PaneManager(this, b.paneGrid, this)
        wirePaneManager()
        setupToolbar()
        setupBackHandling()
        registerDownloadReceiver()
        applySettings()
        bootstrapPanes()
        handleIncomingIntent(intent)
    }

    // ------------------------------------------------------------------ setup

    private fun wirePaneManager() {
        b.tvPaneCount.text = "0"
        paneManager.onPanesChanged = {
            b.tvPaneCount.text = paneManager.panes.size.toString()
            b.toolbar.subtitle = getString(R.string.panes_count, paneManager.panes.size)
        }
        paneManager.onLimitReached = { cap ->
            Snackbar.make(b.root, getString(R.string.msg_pane_limit, cap), Snackbar.LENGTH_SHORT).show()
        }
        paneManager.onProfileUnsupported = {
            Snackbar.make(b.root, R.string.msg_profile_unsupported, Snackbar.LENGTH_LONG).show()
        }
        paneManager.onDownload = { url, disposition, mime, _ ->
            startDownload(url, disposition, mime)
        }
        paneManager.onProfileChanged = { pane, isolated ->
            if (isolated) {
                Snackbar.make(
                    b.root,
                    getString(R.string.msg_profile_on, pane.identity.profileId),
                    Snackbar.LENGTH_SHORT,
                ).show()
            } else {
                toast(R.string.msg_profile_off)
            }
            refreshBadges()
        }
        paneManager.onPaneCreated = { pane ->
            pane.onOpenExternal = { url -> openExternal(url) }
            pane.onIconClick = { p ->
                SitePickerSheet(this) { url -> loadInPane(p, url) }.show()
            }
        }
        paneManager.onTooManyIsolated = {
            Snackbar.make(b.root, R.string.msg_isolated_many, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun setupToolbar() {
        b.toolbar.inflateMenu(R.menu.menu_main)
        b.toolbar.setOnMenuItemClickListener { item -> onMenu(item.itemId) }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isFullscreenShowing() -> exitFullscreen()
                    findSheet?.isShowing == true -> findSheet?.dismiss()
                    paneManager.focusedPane()?.webView?.canGoBack() == true ->
                        paneManager.focusedPane()?.webView?.goBack()
                    paneManager.isMaximized() -> paneManager.restoreGrid()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    private fun bootstrapPanes() {
        lifecycleScope.launch {
            val snapshot = panesRepo.snapshot.firstOrNull()
            val restore = settings.restorePanes.firstOrNull() ?: true
            if (restore && snapshot != null && snapshot.panes.isNotEmpty()) {
                paneManager.restore(snapshot)
            } else {
                paneManager.addPane()
            }
            applyLayoutFromSettings()
            if (paneManager.isCappedByHardware) {
                Snackbar.make(b.root, getString(R.string.msg_low_ram, paneManager.paneCap),
                    Snackbar.LENGTH_LONG).show()
            }
            refreshBadges()
        }
    }

    private fun applyLayoutFromSettings() {
        lifecycleScope.launch {
            settings.defaultLayout.collect { id ->
                if (paneManager.layoutId != id && LayoutResolver.isKnown(id)) paneManager.setLayout(id)
            }
        }
    }

    private fun applySettings() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(settings.adblockEnabled, settings.textZoom, settings.forceDark,
                    settings.isolateDefault, settings.adblockMode) { ad, zoom, dark, isolate, mode ->
                    arrayOf<Any>(ad, zoom, dark, isolate, mode)
                }.collect { v ->
                    adblockOn = v[0] as Boolean
                    currentAdblockMode = com.example.multiview.utils.BlockingPolicy.fromName(v[4] as String)
                    paneManager.defaultIsolate = v[3] as Boolean
                    paneManager.applyTextZoom(v[1] as Int)
                    paneManager.panes.forEach { it.applyForceDark(v[2] as Boolean) }
                }
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val url = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        val pane = paneManager.focusedPane() ?: paneManager.addPane()
        pane?.webView?.loadUrl(url)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    // ------------------------------------------------------------------- menu

    private fun onMenu(id: Int): Boolean {
        val pane = paneManager.focusedPane()
        return when (id) {
            R.id.action_layout -> {
                LayoutPickerSheet(this, paneManager.layoutId) { chosen ->
                    paneManager.setLayout(chosen)
                    lifecycleScope.launch { settings.setDefaultLayout(chosen) }
                }.show()
                true
            }
            R.id.action_add -> { addPaneWithPicker(); true }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java)); true
            }
            R.id.action_find -> { if (pane != null) openFind(pane) else toast(R.string.msg_no_focused_pane); true }
            R.id.action_desktop -> {
                if (pane == null) { toast(R.string.msg_no_focused_pane); return true }
                pane.setDesktopMode(!pane.desktopSite)
                b.toolbar.menu.findItem(R.id.action_desktop)?.isChecked = pane.desktopSite
                true
            }
            R.id.action_share -> {
                if (pane != null && pane.currentUrl.isNotEmpty()) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, pane.currentUrl)
                    }
                    startActivity(Intent.createChooser(send, getString(R.string.action_share)))
                } else toast(R.string.msg_no_focused_pane)
                true
            }
            R.id.action_copy -> {
                if (pane != null && pane.currentUrl.isNotEmpty()) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("url", pane.currentUrl))
                    toast(R.string.msg_copied)
                } else toast(R.string.msg_no_focused_pane)
                true
            }
            R.id.action_external -> {
                if (pane != null && pane.currentUrl.isNotEmpty()) openExternal(pane.currentUrl)
                else toast(R.string.msg_no_focused_pane)
                true
            }
            R.id.action_clear -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.action_clear)
                    .setMessage(R.string.set_clear_cookies)
                    .setPositiveButton(R.string.set_clear_now) { _, _ ->
                        CookieManager.getInstance().removeAllCookies(null)
                        paneManager.panes.forEach {
                            it.webView.clearCache(true)
                            it.webView.clearHistory()
                        }
                        toast(R.string.msg_cleared)
                    }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show()
                true
            }
            else -> false
        }
    }

    private fun addPaneWithPicker() {
        HomeSheet(
            context = this,
            paneCap = paneManager.paneCap,
            defaultIsolate = paneManager.defaultIsolate,
        ) { url, count, mode -> openHomeScreens(url, count, mode) }.show()
    }

    /**
     * Creates the requested screens one by one, keeping the user informed with
     * a live "Opening screens… n/N" counter instead of a frozen dialog. When
     * the device cap is hit part-way, the remaining requests are skipped and
     * the existing limit notice explains why.
     */
    private fun openHomeScreens(url: String, count: Int, mode: ProfileMode) {
        val tv = android.widget.TextView(this).apply {
            setPadding(64, 56, 64, 24)
            text = getString(R.string.home_opening, 0, count)
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(tv)
            .setCancelable(false)
            .create()
        dialog.show()
        lifecycleScope.launch {
            repeat(count) { i ->
                tv.text = getString(R.string.home_opening, i + 1, count)
                paneManager.addPane(mode, url.ifBlank { null })
                kotlinx.coroutines.delay(120)
            }
            dialog.dismiss()
            refreshBadges()
        }
    }

    private fun openFind(pane: PaneView) {
        findSheet = FindSheet(this, pane).also { it.show() }
    }

    private fun registerDownloadReceiver() {
        val filter = android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        // ContextCompat picks the right flag per API level. NOT_EXPORTED is safe
        // here: system broadcasts are delivered regardless of the flag, and no
        // third-party app should be able to fake a download completion.
        ContextCompat.registerReceiver(
            this, downloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** P7: tell the user when a download lands, and offer to open it. */
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id < 0L) return
            val uri = runCatching {
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).getUriForDownloadedFile(id)
            }.getOrNull() ?: return
            Snackbar.make(b.root, R.string.msg_download_done, Snackbar.LENGTH_LONG)
                .setAction(R.string.msg_download_open) {
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, uri).addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                            )
                        )
                    }.onFailure { toast(R.string.msg_no_app) }
                }
                .show()
        }
    }

    /**
     * Single entry point for "put this URL in this pane": warns when offline
     * (P12) and fires the one-time shared-Gmail hint (P5 note) when the user
     * opens a second Google pane that shares one cookie store.
     */
    private fun loadInPane(pane: PaneView, url: String) {
        if (!NetUtils.isOnline(this)) toast(R.string.msg_offline)
        pane.webView.loadUrl(url)
        maybeShowGmailHint(pane, url)
    }

    private fun isGoogleHost(host: String): Boolean =
        host.endsWith("google.com") || host.endsWith("gmail.com")

    private fun maybeShowGmailHint(pane: PaneView, url: String) {
        if (!isGoogleHost(UrlUtils.hostOf(url))) return
        // An isolated pane really does get its own account, so no hint is needed.
        if (pane.isolatedInEffect) return
        val anotherGooglePane = paneManager.panes.any {
            it !== pane && isGoogleHost(UrlUtils.hostOf(it.currentUrl))
        }
        if (!anotherGooglePane) return
        lifecycleScope.launch {
            if (settings.gmailHintShown.firstOrNull() == true) return@launch
            settings.setGmailHintShown()
            Snackbar.make(b.root, R.string.msg_isolated_hint, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun refreshBadges() {
        var seen = 0
        paneManager.panes.forEach { p ->
            if (p.isolatedInEffect) { p.updateProfileBadge(p.profileMode, seen); seen++ }
            else p.updateProfileBadge(p.profileMode, seen)
        }
    }

    // -------------------------------------------------------------- PaneHost

    override fun onPageStarted(paneIndex: Int) {
        b.progress.visibility = View.VISIBLE
        // Native overlay instead of a black rectangle while the page starts.
        paneManager.panes.getOrNull(paneIndex)?.showLoading()
    }

    override fun onPageFinished(paneIndex: Int, url: String, title: String?) {
        paneManager.panes.getOrNull(paneIndex)?.apply {
            updateHeader(url, title)
            hideState()
        }
        b.progress.visibility = View.GONE
        persistPanes()
    }

    override fun onPageError(paneIndex: Int, messageRes: Int, url: String) {
        paneManager.panes.getOrNull(paneIndex)?.showError(messageRes, url)
    }

    override fun onProgress(paneIndex: Int, progress: Int) {
        if (paneIndex == paneManager.focusedIndex) {
            b.progress.progress = progress
            b.progress.visibility = if (progress >= 100) View.GONE else View.VISIBLE
        }
    }

    override fun onFavicon(paneIndex: Int, icon: Bitmap?) {
        paneManager.panes.getOrNull(paneIndex)?.setFavicon(icon)
    }

    override fun onFocusPane(paneIndex: Int) {
        paneManager.focus(paneIndex)
        b.toolbar.menu.findItem(R.id.action_desktop)?.isChecked =
            paneManager.focusedPane()?.desktopSite == true
    }

    override fun shouldBlockRequests(): Boolean = adblockOn

    override fun adblockMode(): com.example.multiview.utils.AdblockMode = currentAdblockMode

    override fun hostMatcher(): HostMatcher = blocklistRepo.matcher()

    override fun onBlockedRequest() {
        blocklistRepo.recordBlocked()
    }

    override fun onRequestExternal(url: String) = openExternal(url)

    override fun onRenderProcessGone(paneIndex: Int) {
        // Only THIS pane is rebuilt. Calling restore() here would have torn
        // down every other pane and its live page, which is exactly what a
        // renderer crash must not cause.
        val url = paneManager.panes.getOrNull(paneIndex)?.currentUrl.orEmpty()
        val rebuilt = paneManager.recreatePane(paneIndex) ?: return
        rebuilt.showCrashedState(url)
        refreshBadges()
    }

    override fun onPopupRequest(url: String) {
        // Google login popups land here. Give the user a real choice instead of
        // dropping the navigation, which is what breaks sign-in.
        popupDismiss()
        popupDialog = AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(url)
            .setPositiveButton(R.string.action_external) { _, _ -> openExternal(url) }
            .setNeutralButton(android.R.string.ok) { _, _ ->
                val pane = paneManager.focusedPane() ?: paneManager.addPane()
                pane?.webView?.loadUrl(url)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .setOnDismissListener { popupDialog = null }
            .show()
    }

    private fun popupDismiss() {
        popupDialog?.dismiss()
        popupDialog = null
    }

    private var popupDialog: AlertDialog? = null

    // ------------------------------------------------- fullscreen (P9)

    override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        fullscreenCallback = callback
        b.fullscreenOverlay.removeAllViews()
        b.fullscreenOverlay.addView(view)
        b.fullscreenOverlay.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    override fun onHideFullscreen() = exitFullscreen()

    private fun isFullscreenShowing(): Boolean = b.fullscreenOverlay.visibility == View.VISIBLE

    private fun exitFullscreen() {
        if (!isFullscreenShowing()) return
        b.fullscreenOverlay.removeAllViews()
        b.fullscreenOverlay.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    // ------------------------------------------------- file upload (P10)

    override fun onChooseFile(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = callback

        val chooser = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, params.createIntent())
            putExtra(Intent.EXTRA_TITLE, getString(R.string.app_name))
        }
        // Offer the camera alongside gallery/documents when a capture is possible.
        runCatching {
            val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            val photo = File(cacheDir, "mv-capture-${System.currentTimeMillis()}.jpg")
            pendingCameraFile = photo
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photo)
            capture.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf<android.os.Parcelable>(capture))
        }
        return runCatching { filePicker.launch(chooser); true }.getOrElse {
            pendingFileCallback = null
            callback.onReceiveValue(null)
            false
        }
    }

    override fun onWebPermissionRequest(request: PermissionRequest) {
        val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        val title = if (wantsCamera) R.string.perm_camera_title else R.string.perm_mic_title
        val body = if (wantsCamera) R.string.perm_camera_body else R.string.perm_mic_body

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(R.string.perm_allow) { _, _ ->
                val needed = mutableListOf<String>()
                if (wantsCamera && checkSelfPermission(android.Manifest.permission.CAMERA)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    needed += android.Manifest.permission.CAMERA
                }
                if (wantsMic && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    needed += android.Manifest.permission.RECORD_AUDIO
                }
                if (needed.isNotEmpty()) runtimePermissions.launch(needed.toTypedArray())
                request.grant(request.resources)
            }
            .setNegativeButton(R.string.perm_deny) { _, _ -> request.deny() }
            .setOnCancelListener { request.deny() }
            .show()
    }

    override fun onGeolocationRequest(origin: String, callback: GeolocationPermissions.Callback) {
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_loc_title)
            .setMessage(R.string.perm_loc_body)
            .setPositiveButton(R.string.perm_allow) { _, _ ->
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    runtimePermissions.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION))
                }
                callback.invoke(origin, true, false)
            }
            .setNegativeButton(R.string.perm_deny) { _, _ -> callback.invoke(origin, false, false) }
            .setOnCancelListener { callback.invoke(origin, false, false) }
            .show()
    }

    // ------------------------------------------------- downloads (P7)

    private fun startDownload(url: String, disposition: String?, mimeType: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionAsked) {
            notificationPermissionAsked = true
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runtimePermissions.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        val guessType = mimeType ?: URLUtil.guessFileName(url, disposition, null).let {
            android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(android.webkit.MimeTypeMap.getFileExtensionFromUrl(it))
        } ?: "application/octet-stream"
        val fileName = URLUtil.guessFileName(url, disposition, guessType)

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(guessType)
            setTitle(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
        }
        runCatching {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Snackbar.make(b.root, getString(R.string.msg_downloading, fileName), Snackbar.LENGTH_SHORT).show()
        }.onFailure { toast(R.string.msg_no_app) }
    }

    // ------------------------------------------------- external links (P11)

    private fun openExternal(url: String) {
        val intent = if (url.startsWith("intent://", ignoreCase = true)) {
            runCatching {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    setComponent(null)
                    selector = null
                }
            }.getOrNull() ?: return
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.msg_no_app)
        }
    }

    // ------------------------------------------------- lifecycle

    override fun onPause() {
        super.onPause()
        paneManager.pauseAll()
        runCatching { CookieManager.getInstance().flush() }
        persistPanes()
    }

    override fun onResume() {
        super.onResume()
        paneManager.resumeAll()
        syncAccountLabels()
    }

    /**
     * A logout performed in Settings clears the persisted email; reflect that
     * (and any other persisted header metadata) on the live panes.
     */
    private fun syncAccountLabels() {
        lifecycleScope.launch {
            val snap = panesRepo.snapshot.firstOrNull() ?: return@launch
            snap.panes.forEach { st ->
                paneManager.panes.firstOrNull { it.identity.paneId == st.paneId }?.let { p ->
                    if (p.accountEmail != st.accountEmail) p.accountEmail = st.accountEmail
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(downloadReceiver) }
        paneManager.destroyAll()
        super.onDestroy()
    }

    private fun persistPanes() {
        if (!::paneManager.isInitialized) return
        val snapshot = paneManager.snapshot()
        lifecycleScope.launch { panesRepo.save(snapshot) }
    }

    private fun toast(res: Int) {
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()
    }
}


