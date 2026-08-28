package com.hpre.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface SettingsRepository : PlaybackPreferences {
    val settings: Flow<AppSettings>
    suspend fun setTheme(theme: AppTheme)
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setWifiQuality(quality: QualityPreferenceSetting)
    suspend fun setMobileQuality(quality: QualityPreferenceSetting)
    suspend fun setDefaultPlaybackSpeed(speed: Float)
    suspend fun setAutoplay(enabled: Boolean)
}

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    companion object {
        val KEY_THEME = stringPreferencesKey("app_theme")
        val KEY_LANGUAGE = stringPreferencesKey("app_language")
        val KEY_BACKGROUND_PLAYBACK = DataStorePlaybackPreferences.KEY_BACKGROUND_PLAYBACK
        val KEY_PIP_ENABLED = DataStorePlaybackPreferences.KEY_PIP_ENABLED
        val KEY_HISTORY_ENABLED = DataStorePlaybackPreferences.KEY_HISTORY_ENABLED
        val KEY_WIFI_QUALITY = stringPreferencesKey("wifi_quality")
        val KEY_MOBILE_QUALITY = stringPreferencesKey("mobile_quality")
        val KEY_DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val KEY_AUTOPLAY = booleanPreferencesKey("autoplay")

        const val DEFAULT_BACKGROUND_PLAYBACK = DataStorePlaybackPreferences.DEFAULT_BACKGROUND_PLAYBACK
        const val DEFAULT_PIP_ENABLED = DataStorePlaybackPreferences.DEFAULT_PIP_ENABLED
        const val DEFAULT_HISTORY_ENABLED = DataStorePlaybackPreferences.DEFAULT_HISTORY_ENABLED
        const val DEFAULT_SPEED = 1.0f
        const val DEFAULT_AUTOPLAY = true
        val DEFAULT_THEME = AppTheme.DARK
        val DEFAULT_LANGUAGE = AppLanguage.VIETNAMESE
    }

    override val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        val themeStr = preferences[KEY_THEME]
        val theme = themeStr?.let {
            try {
                AppTheme.valueOf(it)
            } catch (_: IllegalArgumentException) {
                DEFAULT_THEME
            }
        } ?: DEFAULT_THEME

        val languageStr = preferences[KEY_LANGUAGE]
        val language = languageStr?.let {
            try {
                AppLanguage.valueOf(it)
            } catch (_: IllegalArgumentException) {
                DEFAULT_LANGUAGE
            }
        } ?: DEFAULT_LANGUAGE

        val wifiQualityStr = preferences[KEY_WIFI_QUALITY]
        val wifiQuality = wifiQualityStr?.let {
            try {
                QualityPreferenceSetting.valueOf(it)
            } catch (_: IllegalArgumentException) {
                QualityPreferenceSetting.AUTO
            }
        } ?: QualityPreferenceSetting.AUTO

        val mobileQualityStr = preferences[KEY_MOBILE_QUALITY]
        val mobileQuality = mobileQualityStr?.let {
            try {
                QualityPreferenceSetting.valueOf(it)
            } catch (_: IllegalArgumentException) {
                QualityPreferenceSetting.AUTO
            }
        } ?: QualityPreferenceSetting.AUTO

        AppSettings(
            theme = theme,
            language = language,
            backgroundPlaybackEnabled = preferences[KEY_BACKGROUND_PLAYBACK] ?: DEFAULT_BACKGROUND_PLAYBACK,
            pipEnabled = preferences[KEY_PIP_ENABLED] ?: DEFAULT_PIP_ENABLED,
            historyEnabled = preferences[KEY_HISTORY_ENABLED] ?: DEFAULT_HISTORY_ENABLED,
            wifiQuality = wifiQuality,
            mobileQuality = mobileQuality,
            defaultPlaybackSpeed = preferences[KEY_DEFAULT_SPEED] ?: DEFAULT_SPEED,
            autoplay = preferences[KEY_AUTOPLAY] ?: DEFAULT_AUTOPLAY
        )
    }

    override val isBackgroundPlaybackEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_BACKGROUND_PLAYBACK] ?: DEFAULT_BACKGROUND_PLAYBACK
    }

    override val isPipEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_PIP_ENABLED] ?: DEFAULT_PIP_ENABLED
    }

    override val isHistoryEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_HISTORY_ENABLED] ?: DEFAULT_HISTORY_ENABLED
    }

    override suspend fun setTheme(theme: AppTheme) {
        dataStore.edit { preferences ->
            preferences[KEY_THEME] = theme.name
        }
    }

    override suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { preferences ->
            preferences[KEY_LANGUAGE] = language.name
        }
    }

    override suspend fun setBackgroundPlaybackEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_BACKGROUND_PLAYBACK] = enabled
        }
    }

    override suspend fun setPipEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_PIP_ENABLED] = enabled
        }
    }

    override suspend fun setHistoryEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_HISTORY_ENABLED] = enabled
        }
    }

    override suspend fun setWifiQuality(quality: QualityPreferenceSetting) {
        dataStore.edit { preferences ->
            preferences[KEY_WIFI_QUALITY] = quality.name
        }
    }

    override suspend fun setMobileQuality(quality: QualityPreferenceSetting) {
        dataStore.edit { preferences ->
            preferences[KEY_MOBILE_QUALITY] = quality.name
        }
    }

    override suspend fun setDefaultPlaybackSpeed(speed: Float) {
        dataStore.edit { preferences ->
            preferences[KEY_DEFAULT_SPEED] = speed
        }
    }

    override suspend fun setAutoplay(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_AUTOPLAY] = enabled
        }
    }
}
