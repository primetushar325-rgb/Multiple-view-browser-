package com.example.multiview.ui

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.example.multiview.BuildConfig
import com.example.multiview.R
import com.example.multiview.appContainer
import com.example.multiview.databinding.ActivitySettingsBinding
import com.example.multiview.browser.WebViewFactory
import com.example.multiview.data.AccountsRepo
import com.example.multiview.panes.IsolatedProfileFactory
import com.example.multiview.panes.LayoutResolver
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/** Every user-facing setting. Writes straight through to DataStore. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    /**
     * Collecting settings from DataStore writes them back into the widgets,
     * which would fire their listeners and write the same value again. This
     * flag breaks that loop.
     */
    private var updatingUi = false
    private val repo get() = appContainer.settings
    private val blocklist get() = appContainer.blocklist
    private val panesRepo get() = appContainer.panes
    private val accountsRepo get() = appContainer.accounts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.settingsToolbar.setNavigationOnClickListener { finish() }

        b.rowDefaultLayout.setOnClickListener { pickDefaultLayout() }

        b.swRestorePanes.setOnCheckedChangeListener { _, v -> if (updatingUi) return@setOnCheckedChangeListener
            io { repo.setRestorePanes(v) } }
        b.swAdblock.setOnCheckedChangeListener { _, v -> if (updatingUi) return@setOnCheckedChangeListener
            io { repo.setAdblock(v) } }
        b.swAggressive.setOnCheckedChangeListener { _, v -> if (updatingUi) return@setOnCheckedChangeListener
            io { repo.setAdblockMode(if (v) "aggressive" else "normal") } }
        b.swForceDark.setOnCheckedChangeListener { _, v -> if (updatingUi) return@setOnCheckedChangeListener
            io { repo.setForceDark(v) } }
        b.swIsolateDefault.setOnCheckedChangeListener { _, v -> if (updatingUi) return@setOnCheckedChangeListener
            io { repo.setIsolateDefault(v) } }

        b.sliderTextZoom.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            if (updatingUi) return@OnChangeListener
            val zoom = value.toInt()
            b.tvTextZoom.text = getString(R.string.set_text_zoom, zoom)
            io { repo.setTextZoom(zoom) }
        })

        b.btnClearData.setOnClickListener { confirmClear() }

        renderAccounts()
        renderGoogleAccounts()
        b.btnAddAccount.setOnClickListener { addGoogleAccount() }

        b.tvAbout.text = getString(R.string.set_about_body, BuildConfig.VERSION_NAME)

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repo.defaultLayout.collect { b.tvDefaultLayout.text = LayoutResolver.labelOf(it) }
                }
                launch { repo.restorePanes.collect { b.swRestorePanes.setSilently(it) } }
                launch {
                    repo.adblockEnabled.collect {
                        b.swAdblock.setSilently(it)
                        b.tvBlockedCount.text =
                            getString(R.string.set_adblock_blocked, blocklist.blockedCount)
                    }
                }
                launch {
                    repo.adblockMode.collect { b.swAggressive.setSilently(it.equals("aggressive", true)) }
                }
                launch { repo.forceDark.collect { b.swForceDark.setSilently(it) } }
                launch { repo.isolateDefault.collect { b.swIsolateDefault.setSilently(it) } }
                launch {
                    repo.textZoom.collect {
                        b.tvTextZoom.text = getString(R.string.set_text_zoom, it)
                        b.sliderTextZoom.setSilently(it.toFloat())
                    }
                }
            }
        }
    }

    /**
     * Lists every screen that shows a signed-in account and offers a logout
     * per screen. Logout clears ONLY that pane's WebView profile (cookies and
     * web storage) via the profile API - the shared cookie store and every
     * other profile are untouched, so the other accounts stay signed in.
     */
    private fun renderAccounts() {
        lifecycleScope.launch {
            val snap = panesRepo.snapshot.firstOrNull() ?: return@launch
            val signedIn = snap.panes.filter { it.accountEmail.isNotBlank() }
            b.tvAccountsTitle.visibility = if (signedIn.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            b.tvAccountsBody.visibility = b.tvAccountsTitle.visibility
            b.accountsBox.removeAllViews()
            signedIn.forEach { st ->
                val row = layoutInflater.inflate(R.layout.item_account, b.accountsBox, false)
                row.findViewById<android.widget.TextView>(R.id.tvAccountEmail).text = st.accountEmail
                row.findViewById<android.widget.TextView>(R.id.tvAccountProfile).text =
                    getString(R.string.set_account_row, st.profileId)
                row.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnLogout)
                    .setOnClickListener {
                        io {
                            IsolatedProfileFactory.clearProfile(st.profileId)
                            panesRepo.save(
                                snap.copy(
                                    panes = snap.panes.map { p ->
                                        if (p.paneId == st.paneId) p.copy(accountEmail = "") else p
                                    },
                                ),
                            )
                            renderAccounts()
                        }
                    }
                b.accountsBox.addView(row)
            }
        }
    }

    /**
     * The persistent Google-account pool. Reads ONLY persisted state
     * (appContainer.accounts) - never MainActivity's live panes - so opening
     * Settings can no longer crash, whether 0 panes are open, many are open,
     * or it is a fresh install. Each row shows the label + slot id + Remove.
     */
    private fun renderGoogleAccounts() {
        b.tvPaneAccountsTitle.setText(R.string.set_google_accounts)
        b.btnAddAccount.isEnabled = IsolatedProfileFactory.isSupported()
        b.paneAccountsBox.removeAllViews()
        lifecycleScope.launch {
            val list = runCatching { accountsRepo.accounts.firstOrNull() }.getOrNull().orEmpty()
            b.paneAccountsBox.removeAllViews()
            if (list.isEmpty()) {
                b.paneAccountsBox.addView(android.widget.TextView(this@SettingsActivity).apply {
                    setText(R.string.set_google_accounts_empty)
                })
                return@launch
            }
            list.forEach { acc ->
                val row = layoutInflater.inflate(R.layout.item_account, b.paneAccountsBox, false)
                row.findViewById<android.widget.TextView>(R.id.tvAccountEmail).text = acc.label
                row.findViewById<android.widget.TextView>(R.id.tvAccountProfile).text = acc.slotId
                row.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnLogout).apply {
                    setText(R.string.set_remove)
                    setOnClickListener {
                        io {
                            IsolatedProfileFactory.clearProfile(acc.slotId)
                            accountsRepo.remove(acc.slotId)
                            renderGoogleAccounts()
                        }
                    }
                }
                b.paneAccountsBox.addView(row)
            }
        }
    }

    /**
     * "Add Account": mints the next slot id, opens Google sign-in inside that
     * slot's own isolated profile in a dialog, then saves the slot. The profile
     * persists, so the sign-in survives restarts and is reusable by panes.
     */
    private fun addGoogleAccount() {
        if (!IsolatedProfileFactory.isSupported()) {
            com.google.android.material.snackbar.Snackbar
                .make(b.root, R.string.msg_profile_unsupported, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .show()
            return
        }
        lifecycleScope.launch {
            val list = runCatching { accountsRepo.current() }.getOrDefault(emptyList())
            val slotId = AccountsRepo.slotIdFor(AccountsRepo.nextSlotNumber(list))
            val defaultLabel = getString(R.string.account_default_label, list.size + 1)
            showSignInDialog(slotId, defaultLabel)
        }
    }

    private fun showSignInDialog(slotId: String, defaultLabel: String) {
        val web = WebViewFactory.create(this, slotId)
        val height = (resources.displayMetrics.heightPixels * 0.7f).toInt()
        web.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, height,
        )
        web.loadUrl(AccountsRepo.SIGN_IN_URL)
        AlertDialog.Builder(this)
            .setTitle(R.string.set_add_account)
            .setView(web)
            .setPositiveButton(R.string.set_done) { _, _ ->
                val label = emailFromTitle(web.title) ?: defaultLabel
                io { accountsRepo.add(label); renderGoogleAccounts() }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .setOnDismissListener { runCatching { web.destroy() } }
            .show()
    }

    /** Best-effort display label taken from the sign-in page's own title. */
    private fun emailFromTitle(title: String?): String? =
        title?.let { ACCOUNT_EMAIL_REGEX.find(it)?.value }

    private fun pickDefaultLayout() {
        val ids = LayoutResolver.LAYOUTS.map { it.first }.toTypedArray()
        val labels = LayoutResolver.LAYOUTS.map { it.second }.toTypedArray()
        val current = ids.indexOf(b.tvDefaultLayout.text?.let { t ->
            LayoutResolver.LAYOUTS.firstOrNull { it.second == t }?.first
        } ?: LayoutResolver.LAYOUTS[0].first).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.set_default_layout)
            .setSingleChoiceItems(labels, current) { d, which ->
                io { repo.setDefaultLayout(ids[which]) }
                d.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmClear() {
        val items = arrayOf(
            getString(R.string.set_clear_cookies),
            getString(R.string.set_clear_cache),
            getString(R.string.set_clear_history),
        )
        val checked = booleanArrayOf(true, true, true)
        AlertDialog.Builder(this)
            .setTitle(R.string.set_clear_data)
            .setMultiChoiceItems(items, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.set_clear_now) { _, _ ->
                clearData(cookies = checked[0], cache = checked[1], history = checked[2])
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Clears the shared store and, when profiles exist, every isolated profile's
     * own cookie/web storage - otherwise an isolated pane would keep its login
     * after the user explicitly asked to clear it.
     */
    private fun clearData(cookies: Boolean, cache: Boolean, history: Boolean) {
        if (cookies) {
            CookieManager.getInstance().removeAllCookies(null)
            runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    val store = ProfileStore.getInstance()
                    store.getAllProfileNames().forEach { name ->
                        runCatching { store.getOrCreateProfile(name).cookieManager?.removeAllCookies(null) }
                        runCatching { store.getOrCreateProfile(name).webStorage?.deleteAllData() }
                    }
                }
            }
        }
        if (cache || history) {
            // A throwaway WebView can clear the shared cache/history without
            // disturbing the live panes.
            val tmp = WebView(this)
            if (cache) tmp.clearCache(true)
            if (history) tmp.clearHistory()
            tmp.destroy()
        }
        WebStorage.getInstance().deleteAllData()
        blocklist.resetCount()
        b.tvBlockedCount.text = getString(R.string.set_adblock_blocked, 0)
        com.google.android.material.snackbar.Snackbar
            .make(b.root, R.string.msg_cleared, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
            .show()
    }

    private fun com.google.android.material.materialswitch.MaterialSwitch.setSilently(value: Boolean) {
        if (isChecked == value) return
        updatingUi = true
        isChecked = value
        updatingUi = false
    }

    private fun Slider.setSilently(value: Float) {
        if (this.value == value) return
        updatingUi = true
        this.value = value
        updatingUi = false
    }

    private fun io(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }

    companion object {
        /** Pulls a display email out of a page title; display metadata only. */
        private val ACCOUNT_EMAIL_REGEX =
            Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    }
}

