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
import com.hpre.app.update.AppUpdateManager
import com.hpre.app.update.OfficialReleasePage
import com.hpre.app.update.ReleaseApk
import com.hpre.app.update.UpdateUnavailableReason

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val installedVersion: String) : UpdateUiState
    data class UpdateAvailable(
        val installedVersion: String,
        val latestVersion: String,
        val releasePage: OfficialReleasePage,
        val openError: Boolean = false,
        /** Null when the release cannot be installed in-app; the release page stays available. */
        val apk: ReleaseApk? = null,
        val download: ApkDownloadState = ApkDownloadState.Idle
    ) : UpdateUiState
    data class Error(val reason: UpdateUnavailableReason) : UpdateUiState
}

sealed interface ApkDownloadState {
    data object Idle : ApkDownloadState
    data class Downloading(val progress: Float) : ApkDownloadState
    data object Failed : ApkDownloadState
    data object ReadyToInstall : ApkDownloadState
}

sealed interface VideoCacheClearUiState {
    data object Idle : VideoCacheClearUiState
    data object Clearing : VideoCacheClearUiState
    data object Success : VideoCacheClearUiState
    data object Error : VideoCacheClearUiState
}

sealed interface BackupUiState {
    data object Idle : BackupUiState
    data object Running : BackupUiState
    data object Exported : BackupUiState
    data class Imported(val summary: com.hpre.app.repository.BackupImportSummary) : BackupUiState
    data object Error : BackupUiState
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val appUpdateChecker: AppUpdateChecker,
    val installedVersion: String,
    private val mediaCacheManager: MediaCacheManager? = null,
    settingsSnapshot: AppSettingsSnapshot? = null,
    updateManager: AppUpdateManager? = null,
    private val backupManager: com.hpre.app.repository.BackupManager? = null
) : ViewModel() {

    private val updates = updateManager ?: AppUpdateManager(appUpdateChecker, installedVersion, viewModelScope)
    val updateState: StateFlow<UpdateUiState> = updates.state
    private val _videoCacheClearState = MutableStateFlow<VideoCacheClearUiState>(VideoCacheClearUiState.Idle)
    val videoCacheClearState: StateFlow<VideoCacheClearUiState> = _videoCacheClearState.asStateFlow()

    val settingsState: StateFlow<AppSettings> = settingsSnapshot?.settings
        ?: settingsRepository.settings.stateIn(
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

    private val _backupState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    fun exportBackup(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        val manager = backupManager ?: return
        if (_backupState.value == BackupUiState.Running) return
        _backupState.value = BackupUiState.Running
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val json = (manager.exportJson() as? com.hpre.app.core.error.AppResult.Success)?.value
            val ok = json != null && runCatching {
                resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } != null
            }.getOrDefault(false)
            _backupState.value = if (ok) BackupUiState.Exported else BackupUiState.Error
        }
    }

    fun importBackup(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        val manager = backupManager ?: return
        if (_backupState.value == BackupUiState.Running) return
        _backupState.value = BackupUiState.Running
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val json = runCatching {
                resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            val result = json?.let { manager.importJson(it) }
            _backupState.value = when (result) {
                is com.hpre.app.core.error.AppResult.Success -> BackupUiState.Imported(result.value)
                else -> BackupUiState.Error
            }
        }
    }

    fun consumeBackupResult() {
        if (_backupState.value != BackupUiState.Running) {
            _backupState.value = BackupUiState.Idle
        }
    }

    fun clearVideoCache() {
        if (_videoCacheClearState.value == VideoCacheClearUiState.Clearing) return
        _videoCacheClearState.value = VideoCacheClearUiState.Clearing
        viewModelScope.launch {
            _videoCacheClearState.value = if (mediaCacheManager?.clearCache() == true) {
                VideoCacheClearUiState.Success
            } else {
                VideoCacheClearUiState.Error
            }
        }
    }

    /** Clear a rendered clear-cache outcome; a running clear is left untouched. */
    fun consumeVideoCacheResult() {
        if (_videoCacheClearState.value != VideoCacheClearUiState.Clearing) {
            _videoCacheClearState.value = VideoCacheClearUiState.Idle
        }
    }

    fun checkForUpdates() = updates.check()

    fun downloadAndInstallUpdate() = updates.downloadAndInstall()

    fun releasePageToOpen(): OfficialReleasePage? =
        (updateState.value as? UpdateUiState.UpdateAvailable)?.releasePage

    fun reportReleasePageOpenFailure() = updates.reportReleasePageOpenFailure()

    companion object {
        fun provideFactory(
            settingsRepository: SettingsRepository,
            appUpdateChecker: AppUpdateChecker,
            installedVersion: String,
            mediaCacheManager: MediaCacheManager? = null,
            settingsSnapshot: AppSettingsSnapshot? = null,
            updateManager: AppUpdateManager? = null,
            backupManager: com.hpre.app.repository.BackupManager? = null
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(
                        settingsRepository,
                        appUpdateChecker,
                        installedVersion,
                        mediaCacheManager,
                        settingsSnapshot,
                        updateManager,
                        backupManager
                    ) as T
                }
            }
    }
}
