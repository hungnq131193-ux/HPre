package com.flowtube.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settingsState: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch {
            settingsRepository.setTheme(theme)
        }
    }

    fun setBackgroundPlayback(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBackgroundPlaybackEnabled(enabled)
        }
    }

    fun setPip(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPipEnabled(enabled)
        }
    }

    fun setHistory(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHistoryEnabled(enabled)
        }
    }

    fun setWifiQuality(quality: QualityPreferenceSetting) {
        viewModelScope.launch {
            settingsRepository.setWifiQuality(quality)
        }
    }

    fun setMobileQuality(quality: QualityPreferenceSetting) {
        viewModelScope.launch {
            settingsRepository.setMobileQuality(quality)
        }
    }

    fun setDefaultPlaybackSpeed(speed: Float) {
        viewModelScope.launch {
            settingsRepository.setDefaultPlaybackSpeed(speed)
        }
    }

    fun setAutoplay(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoplay(enabled)
        }
    }

    companion object {
        fun provideFactory(settingsRepository: SettingsRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(settingsRepository) as T
                }
            }
    }
}
