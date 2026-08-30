package com.example.multiview.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Whether a pane shares one cookie store or owns an isolated one. */
enum class ProfileMode { SHARED, ISOLATED }

/**
 * Permanent identity of one pane.
 *
 * Visual position and identity are deliberately separate concepts. A pane's
 * [paneId] never changes, and neither does the [profileId] of the WebView
 * profile holding its session. Closing an earlier pane shifts the survivors'
 * positions, but it can never make account B turn into account A.
 *
 * @param paneId    stable id for the pane itself (survives reorder and restart)
 * @param profileId name of the WebView profile bound to this pane. Only used
 *   by ISOLATED panes; shared panes use the default cookie store.
 */
data class PaneIdentity(val paneId: String, val profileId: String) {

    companion object {
        private const val PREFIX = "profile_"

        /** A brand-new pane gets fresh, collision-free ids. */
        fun newIdentity(): PaneIdentity {
            val uuid = UUID.randomUUID().toString()
            return PaneIdentity(
                paneId = uuid,
                profileId = PREFIX + uuid.replace("-", "").take(12),
            )
        }

        /**
         * Migration for state written before stable ids existed.
         *
         * The old scheme named profiles `mv-pane-<index>`, and on an upgraded
         * install those profiles already hold real Google sessions. Reusing the
         * same name here is what keeps those logins alive; minting a fresh UUID
         * would silently sign every existing user out. Deterministic rather
         * than random so repeated decodes of un-migrated state agree.
         */
        fun fromLegacyPosition(position: Int): PaneIdentity = PaneIdentity(
            paneId = "legacy-pane-$position",
            profileId = "mv-pane-$position",
        )
    }
}

/**
 * One pane as persisted.
 *
 * Only safe metadata lives here: ids, URL, a display title, and an email the
 * page itself chose to expose. Credentials, OTPs, tokens and cookies are never
 * written to DataStore - authentication stays inside the WebView profile.
 */
data class PaneState(
    val paneId: String,
    val profileId: String,
    val url: String,
    val title: String = "",
    val profileMode: ProfileMode = ProfileMode.SHARED,
    val desktopMode: Boolean = false,
    val accountEmail: String = "",
    val createdAt: Long = 0L,
) {
    val identity: PaneIdentity get() = PaneIdentity(paneId, profileId)

    companion object {
        fun newPane(url: String = "", title: String = ""): PaneState {
            val id = PaneIdentity.newIdentity()
            return PaneState(
                paneId = id.paneId,
                profileId = id.profileId,
                url = url,
                title = title,
                createdAt = System.currentTimeMillis(),
            )
        }
    }
}

/** Everything needed to rebuild the grid after a restart. */
data class PanesSnapshot(
    val panes: List<PaneState>,
    val focusedIndex: Int,
) {
    companion object {
        val EMPTY = PanesSnapshot(emptyList(), 0)
    }
}

/**
 * JSON codec for the pane list, using org.json (built into Android) so no
 * serialization plugin is needed. Pure, so it is unit-testable on the JVM.
 *
 * Reading old payloads stays supported: entries without stable ids are
 * migrated from their array position.
 */
object PaneStateJson {
    private const val K_URL = "url"
    private const val K_TITLE = "title"
    private const val K_MODE = "mode"
    private const val K_PANES = "panes"
    private const val K_FOCUSED = "focused"
    private const val K_ID = "id"
    private const val K_PROFILE = "pid"
    private const val K_DESKTOP = "desktop"
    private const val K_EMAIL = "email"
    private const val K_CREATED = "created"

    fun encode(snapshot: PanesSnapshot): String {
        val arr = JSONArray()
        snapshot.panes.forEach { p ->
            arr.put(
                JSONObject()
                    .put(K_ID, p.paneId)
                    .put(K_PROFILE, p.profileId)
                    .put(K_URL, p.url)
                    .put(K_TITLE, p.title)
                    .put(K_MODE, p.profileMode.name)
                    .put(K_DESKTOP, p.desktopMode)
                    .put(K_EMAIL, p.accountEmail)
                    .put(K_CREATED, p.createdAt)
            )
        }
        return JSONObject()
            .put(K_PANES, arr)
            .put(K_FOCUSED, snapshot.focusedIndex.coerceAtLeast(0))
            .toString()
    }

    /** Never throws: corrupt or missing data yields [PanesSnapshot.EMPTY]. */
    fun decode(json: String?): PanesSnapshot {
        if (json.isNullOrBlank()) return PanesSnapshot.EMPTY
        return try {
            val root = JSONObject(json)
            val arr = root.optJSONArray(K_PANES) ?: return PanesSnapshot.EMPTY
            val panes = ArrayList<PaneState>(arr.length())
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val mode = runCatching {
                    ProfileMode.valueOf(o.optString(K_MODE, ProfileMode.SHARED.name))
                }.getOrDefault(ProfileMode.SHARED)

                val storedId = o.optString(K_ID, "")
                // Duplicate ids would bind two panes to one profile, so a
                // collision is treated as un-migrated state instead.
                val identity = if (storedId.isNotBlank() && storedId !in seen) {
                    PaneIdentity(
                        paneId = storedId,
                        profileId = o.optString(K_PROFILE, "").ifBlank { "mv-pane-$i" },
                    )
                } else {
                    PaneIdentity.fromLegacyPosition(i)
                }
                seen += identity.paneId

                panes += PaneState(
                    paneId = identity.paneId,
                    profileId = identity.profileId,
                    url = o.optString(K_URL, ""),
                    title = o.optString(K_TITLE, ""),
                    profileMode = mode,
                    desktopMode = o.optBoolean(K_DESKTOP, false),
                    accountEmail = o.optString(K_EMAIL, ""),
                    createdAt = o.optLong(K_CREATED, 0L),
                )
            }
            val focused = root.optInt(K_FOCUSED, 0)
                .coerceIn(0, (panes.size - 1).coerceAtLeast(0))
            PanesSnapshot(panes, focused)
        } catch (t: Throwable) {
            PanesSnapshot.EMPTY
        }
    }
}

private val Context.panesDataStore by preferencesDataStore(name = "multiview_panes")

/** Persists the pane grid as a single JSON blob in DataStore Preferences. */
class PanesRepo(private val context: Context) {

    private val key = stringPreferencesKey("panes_json")
    private val store get() = context.panesDataStore

    val snapshot: Flow<PanesSnapshot> = store.data.map { prefs -> PaneStateJson.decode(prefs[key]) }

    suspend fun save(snapshot: PanesSnapshot) {
        store.edit { it[key] = PaneStateJson.encode(snapshot) }
    }

    suspend fun clear() {
        store.edit { it.remove(key) }
    }
}
