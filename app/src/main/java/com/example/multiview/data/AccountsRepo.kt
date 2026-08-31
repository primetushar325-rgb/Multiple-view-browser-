package com.example.multiview.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * One saved Google account.
 *
 * The isolated WebView profile that holds this account's session is named
 * [slotId] (e.g. `mv-account-1`). The slot id - not a pane position - is the
 * stable key, so an account survives app restarts and is independent of which
 * panes happen to be open. [label] is display metadata only (an email when the
 * sign-in page exposed one, otherwise "Account n"); no credential is stored.
 */
data class GoogleAccount(val slotId: String, val label: String, val createdAt: Long)

/** JSON codec, kept pure so it is unit-testable without Android. */
object AccountsJson {
    private const val K_SLOT = "slot"
    private const val K_LABEL = "label"
    private const val K_TIME = "t"

    fun encode(list: List<GoogleAccount>): String {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().put(K_SLOT, a.slotId).put(K_LABEL, a.label).put(K_TIME, a.createdAt))
        }
        return arr.toString()
    }

    fun decode(json: String?): List<GoogleAccount> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<GoogleAccount>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val slot = o.optString(K_SLOT, "")
                if (slot.isEmpty()) continue
                out += GoogleAccount(slot, o.optString(K_LABEL, ""), o.optLong(K_TIME, 0L))
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }
}

private val Context.accountsDataStore by preferencesDataStore(name = "multiview_accounts")

/**
 * The saved Google-account pool. Persisted in DataStore exactly like
 * [HistoryRepo]/[PanesRepo], so it is safe to read from ANY Activity/Context -
 * which is what keeps Settings from ever reaching into MainActivity's live
 * objects (the source of the previous Settings crash).
 */
class AccountsRepo(private val context: Context) {

    private val key = stringPreferencesKey("accounts_json")
    private val store get() = context.accountsDataStore

    /** Saved accounts, oldest first: slot 1, slot 2, ... */
    val accounts: Flow<List<GoogleAccount>> = store.data.map { AccountsJson.decode(it[key]) }

    /** One-shot read for callers that need the list now (Home "Open"). */
    suspend fun current(): List<GoogleAccount> = accounts.first()

    /** Appends a new slot and returns it. [label] is display-only. */
    suspend fun add(label: String): GoogleAccount {
        val existing = runCatching { current() }.getOrDefault(emptyList())
        val created = GoogleAccount(
            slotId = slotIdFor(nextSlotNumber(existing)),
            label = label,
            createdAt = System.currentTimeMillis(),
        )
        store.edit { prefs ->
            val list = AccountsJson.decode(prefs[key]).toMutableList()
            list += created
            prefs[key] = AccountsJson.encode(list)
        }
        return created
    }

    suspend fun remove(slotId: String) {
        store.edit { prefs ->
            val list = AccountsJson.decode(prefs[key]).filterNot { it.slotId == slotId }
            prefs[key] = AccountsJson.encode(list)
        }
    }

    companion object {
        /** Profile-name prefix for an account slot's isolated WebView profile. */
        const val SLOT_PREFIX = "mv-account-"

        /** The Google sign-in page opened inside a slot's profile. */
        const val SIGN_IN_URL = "https://accounts.google.com"

        /** `mv-account-<n>`, n starting at 1. */
        fun slotIdFor(n: Int): String = "$SLOT_PREFIX$n"

        /**
         * Next unused slot number = highest existing + 1. Slots are never
         * reused, so a removed account's still-signed-in profile can never be
         * silently handed to a different account.
         */
        fun nextSlotNumber(existing: List<GoogleAccount>): Int =
            (existing.mapNotNull { it.slotId.removePrefix(SLOT_PREFIX).toIntOrNull() }.maxOrNull() ?: 0) + 1

        /**
         * Maps pane index -> account, one account per pane in order. Panes
         * beyond the saved-account count map to null (they open shared). Extra
         * accounts beyond [paneCount] are simply not used.
         */
        fun assignToPanes(accounts: List<GoogleAccount>, paneCount: Int): List<GoogleAccount?> =
            (0 until paneCount.coerceAtLeast(0)).map { i -> accounts.getOrNull(i) }
    }
}
