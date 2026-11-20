package com.example.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nexodrop_prefs")

enum class SaveLocationMode {
    APP_DOWNLOADS,
    CUSTOM_TREE,
    CATEGORY_SMART,
    PRIVATE_VAULT
}

data class UserSettings(
    val deviceId: String = "",
    val deviceName: String = Build.MODEL ?: "Android Device",
    val languageCode: String = "system",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val saveMode: SaveLocationMode = SaveLocationMode.APP_DOWNLOADS,
    val customSaveTreeUri: String = "",
    val customSaveLabel: String = "",
    val vaultPinHash: String = "",
    val vaultPinSalt: String = "",
    val biometricEnabled: Boolean = true,
    val stripExifOnSend: Boolean = false,
    val silentNotifications: Boolean = false,
    val notifyCompleteOnly: Boolean = false,
    val onboardingCompleted: Boolean = false
)

class AppPreferences(private val context: Context) {

    companion object {
        private const val LANG_MIRROR_PREFS = "nexodrop_lang_mirror"
        private const val KEY_LANG_MIRROR = "lang_code"

        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        private val KEY_LANGUAGE = stringPreferencesKey("language_code")
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_SAVE_MODE = stringPreferencesKey("save_mode")
        private val KEY_CUSTOM_TREE_URI = stringPreferencesKey("custom_save_tree_uri")
        private val KEY_CUSTOM_SAVE_LABEL = stringPreferencesKey("custom_save_label")
        private val KEY_VAULT_PIN_HASH = stringPreferencesKey("vault_pin_hash")
        private val KEY_VAULT_PIN_SALT = stringPreferencesKey("vault_pin_salt")
        private val KEY_BIOMETRIC = booleanPreferencesKey("biometric_enabled")
        private val KEY_STRIP_EXIF = booleanPreferencesKey("strip_exif_on_send")
        private val KEY_SILENT_NOTIF = booleanPreferencesKey("silent_notifications")
        private val KEY_COMPLETE_ONLY = booleanPreferencesKey("notify_complete_only")
        private val KEY_ONBOARDING = booleanPreferencesKey("onboarding_completed")

        fun getCachedLanguageSync(context: Context): String {
            return context.getSharedPreferences(LANG_MIRROR_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LANG_MIRROR, "system") ?: "system"
        }
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            deviceId = prefs[KEY_DEVICE_ID] ?: "",
            deviceName = prefs[KEY_DEVICE_NAME] ?: (Build.MODEL ?: "Android"),
            languageCode = prefs[KEY_LANGUAGE] ?: "system",
            themeMode = runCatching { ThemeMode.valueOf(prefs[KEY_THEME] ?: "SYSTEM") }
                .getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: false,
            saveMode = runCatching { SaveLocationMode.valueOf(prefs[KEY_SAVE_MODE] ?: "APP_DOWNLOADS") }
                .getOrDefault(SaveLocationMode.APP_DOWNLOADS),
            customSaveTreeUri = prefs[KEY_CUSTOM_TREE_URI] ?: "",
            customSaveLabel = prefs[KEY_CUSTOM_SAVE_LABEL] ?: "",
            vaultPinHash = prefs[KEY_VAULT_PIN_HASH] ?: "",
            vaultPinSalt = prefs[KEY_VAULT_PIN_SALT] ?: "",
            biometricEnabled = prefs[KEY_BIOMETRIC] ?: true,
            stripExifOnSend = prefs[KEY_STRIP_EXIF] ?: false,
            silentNotifications = prefs[KEY_SILENT_NOTIF] ?: false,
            notifyCompleteOnly = prefs[KEY_COMPLETE_ONLY] ?: false,
            onboardingCompleted = prefs[KEY_ONBOARDING] ?: false
        )
    }

    suspend fun ensureDeviceId(): String {
        var existing = ""
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_DEVICE_ID]
            if (current.isNullOrBlank()) {
                val created = UUID.randomUUID().toString().take(12)
                prefs[KEY_DEVICE_ID] = created
                existing = created
            } else {
                existing = current
            }
        }
        return existing
    }

    suspend fun setDeviceName(name: String) {
        context.dataStore.edit { it[KEY_DEVICE_NAME] = name.trim().ifEmpty { Build.MODEL ?: "Android" } }
    }

    suspend fun setLanguageCode(code: String) {
        context.getSharedPreferences(LANG_MIRROR_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG_MIRROR, code).apply()
        context.dataStore.edit { it[KEY_LANGUAGE] = code }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setSaveMode(mode: SaveLocationMode) {
        context.dataStore.edit { it[KEY_SAVE_MODE] = mode.name }
    }

    suspend fun setCustomSaveFolder(uri: String, label: String) {
        context.dataStore.edit {
            it[KEY_CUSTOM_TREE_URI] = uri
            it[KEY_CUSTOM_SAVE_LABEL] = label
            it[KEY_SAVE_MODE] = SaveLocationMode.CUSTOM_TREE.name
        }
    }

    suspend fun setVaultPin(hash: String, salt: String) {
        context.dataStore.edit {
            it[KEY_VAULT_PIN_HASH] = hash
            it[KEY_VAULT_PIN_SALT] = salt
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BIOMETRIC] = enabled }
    }

    suspend fun setStripExifOnSend(enabled: Boolean) {
        context.dataStore.edit { it[KEY_STRIP_EXIF] = enabled }
    }

    suspend fun setSilentNotifications(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SILENT_NOTIF] = enabled }
    }

    suspend fun setNotifyCompleteOnly(enabled: Boolean) {
        context.dataStore.edit { it[KEY_COMPLETE_ONLY] = enabled }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDING] = completed }
    }
}
