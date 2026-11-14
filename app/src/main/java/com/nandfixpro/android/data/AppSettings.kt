package com.nandfixpro.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "nandfix_settings")

data class AppSettings(
    val notificationsEnabled: Boolean = true,
    val usbTimeoutMs: Int = 10_000,
    val verboseLogs: Boolean = false,
    val autoHash: Boolean = true,
    val requireConfirmation: Boolean = true,
    val keepScreenOn: Boolean = true,
) {
    val usbTimeoutLabel: String get() = "${usbTimeoutMs / 1000} s"
}

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            notificationsEnabled = preferences[KEY_NOTIFICATIONS] ?: true,
            usbTimeoutMs = preferences[KEY_USB_TIMEOUT] ?: 10_000,
            verboseLogs = preferences[KEY_VERBOSE] ?: false,
            autoHash = preferences[KEY_AUTO_HASH] ?: true,
            requireConfirmation = preferences[KEY_CONFIRM] ?: true,
            keepScreenOn = preferences[KEY_KEEP_SCREEN_ON] ?: true,
        )
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) =
        context.settingsDataStore.edit { it[KEY_NOTIFICATIONS] = enabled }

    suspend fun setUsbTimeout(timeoutMs: Int) =
        context.settingsDataStore.edit { it[KEY_USB_TIMEOUT] = timeoutMs.coerceIn(1_000, 30_000) }

    suspend fun setVerboseLogs(enabled: Boolean) =
        context.settingsDataStore.edit { it[KEY_VERBOSE] = enabled }

    suspend fun setAutoHash(enabled: Boolean) =
        context.settingsDataStore.edit { it[KEY_AUTO_HASH] = enabled }

    suspend fun setRequireConfirmation(enabled: Boolean) =
        context.settingsDataStore.edit { it[KEY_CONFIRM] = enabled }

    suspend fun setKeepScreenOn(enabled: Boolean) =
        context.settingsDataStore.edit { it[KEY_KEEP_SCREEN_ON] = enabled }

    suspend fun resetPolicyDefaults() = context.settingsDataStore.edit { preferences ->
        preferences[KEY_NOTIFICATIONS] = true
        preferences[KEY_USB_TIMEOUT] = 10_000
        preferences[KEY_VERBOSE] = false
        preferences[KEY_AUTO_HASH] = true
        preferences[KEY_CONFIRM] = true
        preferences[KEY_KEEP_SCREEN_ON] = true
    }

    private companion object {
        val KEY_NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val KEY_USB_TIMEOUT = intPreferencesKey("usb_timeout_ms")
        val KEY_VERBOSE = booleanPreferencesKey("verbose_logs")
        val KEY_AUTO_HASH = booleanPreferencesKey("auto_hash")
        val KEY_CONFIRM = booleanPreferencesKey("require_confirmation")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    }
}
