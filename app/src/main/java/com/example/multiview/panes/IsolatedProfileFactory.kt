package com.example.multiview.panes

import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.multiview.data.ProfileMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything about per-pane profiles lives here.
 *
 * The pure half ([ProfilePlan]) carries no androidx.webkit reference, so it is
 * safe to unit-test on the JVM. The Android half is guarded by
 * WebViewFeature.MULTI_PROFILE and wrapped in try/catch: if the installed
 * Android System WebView is too old, panes silently fall back to shared mode
 * instead of crashing.
 */
object ProfilePlan {

    private const val K_INDEX = "i"
    private const val K_MODE = "m"

    /**
     * Profile ids are derived from the pane index, never random, so pane 3 gets
     * the same cookie store after every restart and its Gmail login survives.
     */
    fun profileIdFor(paneIndex: Int): String = "mv-pane-$paneIndex"

    fun encode(modes: Map<Int, ProfileMode>): String {
        val arr = JSONArray()
        modes.toSortedMap().forEach { (index, mode) ->
            arr.put(JSONObject().put(K_INDEX, index).put(K_MODE, mode.name))
        }
        return arr.toString()
    }

    /** Never throws; unknown modes degrade to SHARED. */
    fun decode(json: String?): Map<Int, ProfileMode> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val arr = JSONArray(json)
            val out = LinkedHashMap<Int, ProfileMode>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val index = o.optInt(K_INDEX, -1)
                if (index < 0) continue
                out[index] = runCatching { ProfileMode.valueOf(o.optString(K_MODE, ProfileMode.SHARED.name)) }
                    .getOrDefault(ProfileMode.SHARED)
            }
            out
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    /**
     * The single decision point for "can this pane actually be isolated?".
     * Pure so the fallback path is testable without a device.
     */
    fun effectiveMode(requested: ProfileMode, profileStoreAvailable: Boolean): ProfileMode =
        if (requested == ProfileMode.ISOLATED && !profileStoreAvailable) ProfileMode.SHARED else requested

    /** Effective profile name, or null when the pane should stay on the shared store. */
    fun profileNameFor(paneIndex: Int, mode: ProfileMode, profileStoreAvailable: Boolean): String? =
        if (effectiveMode(mode, profileStoreAvailable) == ProfileMode.ISOLATED) profileIdFor(paneIndex) else null
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
    // The MULTI_PROFILE guard lives in isSupported() above; lint cannot follow
    // that call through a helper, so the check is asserted here instead.
    @android.annotation.SuppressLint("RequiresFeature")
    fun attach(webView: WebView, profileName: String): Boolean = runCatching {
        if (!isSupported()) return@runCatching false
        ProfileStore.getInstance().getOrCreateProfile(profileName)
        WebViewCompat.setProfile(webView, profileName)
        true
    }.getOrDefault(false)
}
