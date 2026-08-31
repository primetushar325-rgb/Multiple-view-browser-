package com.example.multiview.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** One visited page. */
data class HistoryEntry(val url: String, val title: String, val timestamp: Long)

/**
 * Persists visited pages the same way [PanesRepo] persists panes: a single
 * JSON blob in DataStore Preferences, newest first, capped so it cannot grow
 * without bound. No Room / no KSP, per the project's constraints.
 */
object HistoryJson {
    private const val K_URL = "url"
    private const val K_TITLE = "title"
    private const val K_TIME = "t"

    fun encode(list: List<HistoryEntry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().put(K_URL, e.url).put(K_TITLE, e.title).put(K_TIME, e.timestamp))
        }
        return arr.toString()
    }

    fun decode(json: String?): List<HistoryEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<HistoryEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString(K_URL, "")
                if (url.isEmpty()) continue
                out += HistoryEntry(url, o.optString(K_TITLE, ""), o.optLong(K_TIME, 0L))
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }
}

private val Context.historyDataStore by preferencesDataStore(name = "multiview_history")

class HistoryRepo(private val context: Context) {

    private val key = stringPreferencesKey("history_json")
    private val store get() = context.historyDataStore

    /** Newest first. */
    val entries: Flow<List<HistoryEntry>> = store.data.map { HistoryJson.decode(it[key]) }

    /** Record a visit, de-duplicating consecutive repeats, newest first. */
    suspend fun record(url: String, title: String) {
        if (url.isEmpty() || url == "about:blank") return
        store.edit { prefs ->
            val current = HistoryJson.decode(prefs[key]).toMutableList()
            if (current.firstOrNull()?.url == url) {
                // Same page re-finished: just refresh its timestamp/title.
                current[0] = HistoryEntry(url, title.ifEmpty { current[0].title }, System.currentTimeMillis())
            } else {
                current.add(0, HistoryEntry(url, title, System.currentTimeMillis()))
            }
            while (current.size > MAX_ENTRIES) current.removeAt(current.size - 1)
            prefs[key] = HistoryJson.encode(current)
        }
    }

    suspend fun clear() {
        store.edit { it.remove(key) }
    }

    companion object {
        const val MAX_ENTRIES = 100
    }
}
