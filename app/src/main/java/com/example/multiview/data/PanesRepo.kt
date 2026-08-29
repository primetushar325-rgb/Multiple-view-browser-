package com.example.multiview.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** Whether a pane shares one cookie store or owns an isolated one. */
enum class ProfileMode { SHARED, ISOLATED }

/** One pane as persisted: where it is, what it is called, and which profile it uses. */
data class PaneState(
    val url: String,
    val title: String = "",
    val profileMode: ProfileMode = ProfileMode.SHARED,
)

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
 * JSON codec for the pane list. Uses org.json (built into Android) so no
 * serialization plugin is needed. Pure: unit-testable on the JVM.
 */
object PaneStateJson {
    private const val K_URL = "url"
    private const val K_TITLE = "title"
    private const val K_MODE = "mode"
    private const val K_PANES = "panes"
    private const val K_FOCUSED = "focused"

    fun encode(snapshot: PanesSnapshot): String {
        val arr = JSONArray()
        snapshot.panes.forEach { p ->
            arr.put(
                JSONObject()
                    .put(K_URL, p.url)
                    .put(K_TITLE, p.title)
                    .put(K_MODE, p.profileMode.name)
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
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val mode = runCatching { ProfileMode.valueOf(o.optString(K_MODE, ProfileMode.SHARED.name)) }
                    .getOrDefault(ProfileMode.SHARED)
                panes += PaneState(
                    url = o.optString(K_URL, ""),
                    title = o.optString(K_TITLE, ""),
                    profileMode = mode,
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
