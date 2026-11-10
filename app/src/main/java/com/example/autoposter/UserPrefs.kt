package com.example.autoposter

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

val Context.userPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Non-secret, per-install preferences (DataStore). Language is owned by AppCompat's per-app locale. */
class UserPrefs(private val store: DataStore<Preferences>) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val localKey = booleanPreferencesKey("local_mode")
    private val installKey = stringPreferencesKey("install_id")
    private val zoneKey = stringPreferencesKey("display_zone")

    val themeMode: Flow<ThemeMode> = store.data.map { p ->
        runCatching { ThemeMode.valueOf(p[themeKey] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
    }
    val localMode: Flow<Boolean> = store.data.map { it[localKey] ?: false }

    /** Time zone used by the calendar and the composer; "" = follow the device. */
    val displayZone: Flow<String> = store.data.map { it[zoneKey] ?: "" }
    suspend fun setDisplayZone(id: String) { store.edit { it[zoneKey] = id } }

    suspend fun setThemeMode(m: ThemeMode) { store.edit { it[themeKey] = m.name } }
    suspend fun setLocalMode(v: Boolean) { store.edit { it[localKey] = v } }

    /** Random id that identifies this installation (not the hardware) for device registration. */
    suspend fun installId(): String {
        store.data.first()[installKey]?.let { return it }
        val id = UUID.randomUUID().toString()
        store.edit { if (it[installKey] == null) it[installKey] = id }
        return store.data.first()[installKey] ?: id
    }
}
