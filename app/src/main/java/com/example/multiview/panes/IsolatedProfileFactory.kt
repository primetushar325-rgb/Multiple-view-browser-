package com.example.multiview.panes

import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.multiview.data.PaneIdentity
import com.example.multiview.data.ProfileMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything about per-pane profiles lives in this one file.
 *
 * The pure half ([ProfilePlan]) carries no androidx.webkit reference, so it is
 * safe to unit-test on the JVM. The Android half is guarded by
 * WebViewFeature.MULTI_PROFILE and wrapped in try/catch: if the installed
 * Android System WebView is too old, panes silently fall back to shared mode
 * instead of crashing.
 *
 * Verified against androidx.webkit 1.12.1 with javap - the real API is
 * `ProfileStore.getInstance()` (no context), `getOrCreateProfile(name)` and
 * `WebViewCompat.setProfile(WebView, name)`. There is no `Profile.getWebView`.
 */
object ProfilePlan {

    private const val K_ID = "id"
    private const val K_MODE = "m"

    fun encode(modes: Map<String, ProfileMode>): String {
        val arr = JSONArray()
        modes.toSortedMap().forEach { (paneId, mode) ->
            arr.put(JSONObject().put(K_ID, paneId).put(K_MODE, mode.name))
        }
        return arr.toString()
    }

    /** Never throws; unknown modes degrade to SHARED. */
    fun decode(json: String?): Map<String, ProfileMode> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val arr = JSONArray(json)
            val out = LinkedHashMap<String, ProfileMode>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString(K_ID, "")
                if (id.isBlank()) continue
                out[id] = runCatching {
                    ProfileMode.valueOf(o.optString(K_MODE, ProfileMode.SHARED.name))
                }.getOrDefault(ProfileMode.SHARED)
            }
            out
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    /**
     * The single decision point for "can this pane actually be isolated?".
     * Pure, so the fallback path is testable without a device.
     */
    fun effectiveMode(requested: ProfileMode, profileStoreAvailable: Boolean): ProfileMode =
        if (requested == ProfileMode.ISOLATED && !profileStoreAvailable) ProfileMode.SHARED else requested

    /**
     * Effective WebView profile name for a pane, or null when the pane should
     * stay on the shared cookie store.
     *
     * Keyed by the pane's permanent [identity], never by its visual position,
     * so a pane that moves slot keeps the same session.
     */
    fun profileNameFor(
        identity: PaneIdentity,
        mode: ProfileMode,
        profileStoreAvailable: Boolean,
    ): String? =
        if (effectiveMode(mode, profileStoreAvailable) == ProfileMode.ISOLATED) identity.profileId else null
}

/** Android half: talks to androidx.webkit and never lets a failure escape. */
object IsolatedProfileFactory {

    /** True only when the installed WebView really supports multi-profile. */
    fun isSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }.getOrDefault(false)

    /**
     * Creates (or reuses) the named profile and binds [webView] to it.
     *
     * Must be called before the WebView loads anything, which is why
     * WebViewFactory does it immediately after construction.
     *
     * @return false when unsupported or when anything went wrong - callers then
     *   keep the pane on the shared cookie store.
     */
    // The MULTI_PROFILE guard lives in isSupported(); lint cannot follow that
    // call through a helper, so the check is asserted here instead.
    @android.annotation.SuppressLint("RequiresFeature")
    fun attach(webView: WebView, profileName: String): Boolean = runCatching {
        if (!isSupported()) return@runCatching false
        ProfileStore.getInstance().getOrCreateProfile(profileName)
        WebViewCompat.setProfile(webView, profileName)
        true
    }.getOrDefault(false)

    /**
     * Clears ONE profile's cookies and web storage.
     *
     * This is what makes per-pane logout safe: the global CookieManager is
     * never touched, so logging pane 2 out leaves panes 1, 3 and 4 signed in.
     */
    @android.annotation.SuppressLint("RequiresFeature")
    fun clearProfile(profileName: String): Boolean = runCatching {
        if (!isSupported()) return@runCatching false
        val profile = ProfileStore.getInstance().getOrCreateProfile(profileName)
        profile.cookieManager?.removeAllCookies(null)
        profile.webStorage?.deleteAllData()
        true
    }.getOrDefault(false)
}
