package com.hpre.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.hpre.app.player.cache.MediaCacheManager
import com.hpre.app.update.AppUpdateChecker
import com.hpre.app.update.OfficialReleasePage
import com.hpre.app.update.UpdateCheckResult
import com.hpre.app.update.UpdateUnavailableReason

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val installedVersion: String) : UpdateUiState
    data class UpdateAvailable(
        val installedVersion: String,
        val latestVersion: String,
        val releasePage: OfficialReleasePage,
        val openError: Boolean = false
    ) : UpdateUiState
    data class Error(val reason: UpdateUnavailableReason) : UpdateUiState
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val appUpdateChecker: AppUpdateChecker,
    val installedVersion: String,
    private val mediaCacheManager: MediaCacheManager? = null
) : ViewModel() {

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

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

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            settingsRepository.setLanguage(language)
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

    fun clearVideoCache() {
        viewModelScope.launch {
            mediaCacheManager?.clearCache()
        }
    }

    fun checkForUpdates() {
        if (_updateState.value == UpdateUiState.Checking) return
        _updateState.value = UpdateUiState.Checking
        viewModelScope.launch {
            _updateState.value = when (val result = appUpdateChecker.check(installedVersion)) {
                is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(result.installedVersion.toString())
                is UpdateCheckResult.UpdateAvailable -> UpdateUiState.UpdateAvailable(
                    installedVersion = result.installedVersion.toString(),
                    latestVersion = result.latestVersion.toString(),
                    releasePage = result.releasePage
                )
                is UpdateCheckResult.Unavailable -> UpdateUiState.Error(result.reason)
            }
        }
    }

    fun releasePageToOpen(): OfficialReleasePage? =
        (_updateState.value as? UpdateUiState.UpdateAvailable)?.releasePage

    fun reportReleasePageOpenFailure() {
        val current = _updateState.value as? UpdateUiState.UpdateAvailable ?: return
        _updateState.value = current.copy(openError = true)
    }

    companion object {
        fun provideFactory(
            settingsRepository: SettingsRepository,
            appUpdateChecker: AppUpdateChecker,
            installedVersion: String,
            mediaCacheManager: MediaCacheManager? = null
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(settingsRepository, appUpdateChecker, installedVersion, mediaCacheManager) as T
                }
            }
    }
}
