package com.hpre.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_preferences")

interface PlaybackPreferences {
    val isBackgroundPlaybackEnabled: Flow<Boolean>
    val isPipEnabled: Flow<Boolean>
    val isHistoryEnabled: Flow<Boolean>
    suspend fun setBackgroundPlaybackEnabled(enabled: Boolean)
    suspend fun setPipEnabled(enabled: Boolean)
    suspend fun setHistoryEnabled(enabled: Boolean)
}

class DataStorePlaybackPreferences(
    private val dataStore: DataStore<Preferences>
) : PlaybackPreferences {

    companion object {
        val KEY_BACKGROUND_PLAYBACK = booleanPreferencesKey("background_playback_enabled")
        val KEY_PIP_ENABLED = booleanPreferencesKey("pip_enabled")
        val KEY_HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        const val DEFAULT_BACKGROUND_PLAYBACK = true
        const val DEFAULT_PIP_ENABLED = true
        const val DEFAULT_HISTORY_ENABLED = true
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
}
