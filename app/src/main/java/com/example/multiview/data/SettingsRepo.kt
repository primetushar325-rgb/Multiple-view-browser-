package com.example.multiview.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "multiview_settings")

/** All user settings. Defaults match the spec: restore ON, adblock ON, isolate OFF. */
class SettingsRepo(private val context: Context) {

    private val store get() = context.settingsDataStore

    private val kDefaultLayout = stringKey("default_layout")
    private val kRestorePanes = booleanPreferencesKey("restore_panes")
    private val kAdblock = booleanPreferencesKey("adblock")
    private val kForceDark = booleanPreferencesKey("force_dark")
    private val kTextZoom = intPreferencesKey("text_zoom")
    private val kIsolateDefault = booleanPreferencesKey("isolate_default")
    private val kGmailHintShown = booleanPreferencesKey("gmail_hint_shown")

    val defaultLayout: Flow<String> = store.data.map { it[kDefaultLayout] ?: DEFAULT_LAYOUT }
    val restorePanes: Flow<Boolean> = store.data.map { it[kRestorePanes] ?: true }
    val adblockEnabled: Flow<Boolean> = store.data.map { it[kAdblock] ?: true }
    val forceDark: Flow<Boolean> = store.data.map { it[kForceDark] ?: false }
    val textZoom: Flow<Int> = store.data.map { (it[kTextZoom] ?: 100).coerceIn(MIN_ZOOM, MAX_ZOOM) }
    val isolateDefault: Flow<Boolean> = store.data.map { it[kIsolateDefault] ?: false }
    val gmailHintShown: Flow<Boolean> = store.data.map { it[kGmailHintShown] ?: false }

    suspend fun setDefaultLayout(id: String) = store.edit { it[kDefaultLayout] = id }
    suspend fun setRestorePanes(v: Boolean) = store.edit { it[kRestorePanes] = v }
    suspend fun setAdblock(v: Boolean) = store.edit { it[kAdblock] = v }
    suspend fun setForceDark(v: Boolean) = store.edit { it[kForceDark] = v }
    suspend fun setTextZoom(v: Int) = store.edit { it[kTextZoom] = v.coerceIn(MIN_ZOOM, MAX_ZOOM) }
    suspend fun setIsolateDefault(v: Boolean) = store.edit { it[kIsolateDefault] = v }
    suspend fun setGmailHintShown() = store.edit { it[kGmailHintShown] = true }

    private fun stringKey(name: String) = androidx.datastore.preferences.core.stringPreferencesKey(name)

    companion object {
        const val DEFAULT_LAYOUT = "1x1"
        const val MIN_ZOOM = 80
        const val MAX_ZOOM = 130
    }
}
