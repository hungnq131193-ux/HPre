package com.hpre.app.update

import com.hpre.app.settings.ApkDownloadState
import com.hpre.app.settings.UpdateUiState
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns update checking and in-app APK installation for the whole app, so a download started from
 * Settings or from the launch prompt keeps running while the user navigates elsewhere.
 */
class AppUpdateManager(
    private val checker: AppUpdateChecker,
    private val installedVersion: String,
    private val scope: CoroutineScope,
    private val installer: ApkUpdateInstaller? = null,
    private val launchCheckDelayMs: Long = LAUNCH_CHECK_DELAY_MS
) {
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _launchPromptVisible = MutableStateFlow(false)
    /** True once the launch check found an update the user has not yet answered. */
    val launchPromptVisible: StateFlow<Boolean> = _launchPromptVisible.asStateFlow()

    private val launchCheckStarted = AtomicBoolean(false)
    private var downloadedApk: File? = null

    fun check() {
        if (!beginCheck()) return
        scope.launch { finishCheck() }
    }

    /** Checks once per process, after startup work has settled, and raises the launch prompt. */
    fun checkOnLaunch() {
        if (!launchCheckStarted.compareAndSet(false, true)) return
        scope.launch {
            delay(launchCheckDelayMs)
            if (!beginCheck()) return@launch
            when (val result = finishCheck()) {
                is UpdateUiState.UpdateAvailable -> _launchPromptVisible.value = result.apk != null
                is UpdateUiState.UpToDate -> installer?.clearDownloads()
                else -> Unit
            }
        }
    }

    fun dismissLaunchPrompt() {
        _launchPromptVisible.value = false
    }

    fun downloadAndInstall() {
        val current = _state.value as? UpdateUiState.UpdateAvailable ?: return
        val apk = current.apk ?: return
        val installer = installer ?: return
        when (current.download) {
            is ApkDownloadState.Downloading -> return
            ApkDownloadState.ReadyToInstall -> downloadedApk?.takeIf { it.exists() }?.let { file ->
                if (!installer.install(file)) setDownload(ApkDownloadState.Failed)
                return
            }
            else -> Unit
        }
        setDownload(ApkDownloadState.Downloading(0f))
        scope.launch {
            val file = installer.download(apk) { progress ->
                setDownload(ApkDownloadState.Downloading(progress))
            }
            if (file == null) {
                setDownload(ApkDownloadState.Failed)
                return@launch
            }
            downloadedApk = file
            setDownload(ApkDownloadState.ReadyToInstall)
            if (!installer.install(file)) setDownload(ApkDownloadState.Failed)
        }
    }

    fun reportReleasePageOpenFailure() {
        _state.update { (it as? UpdateUiState.UpdateAvailable)?.copy(openError = true) ?: it }
    }

    private fun beginCheck(): Boolean {
        val current = _state.value
        if (current == UpdateUiState.Checking) return false
        if ((current as? UpdateUiState.UpdateAvailable)?.download is ApkDownloadState.Downloading) return false
        _state.value = UpdateUiState.Checking
        return true
    }

    private suspend fun finishCheck(): UpdateUiState {
        val next = when (val result = checker.check(installedVersion)) {
            is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(result.installedVersion.toString())
            is UpdateCheckResult.UpdateAvailable -> UpdateUiState.UpdateAvailable(
                installedVersion = result.installedVersion.toString(),
                latestVersion = result.latestVersion.toString(),
                releasePage = result.releasePage,
                apk = result.apk.takeIf { installer != null }
            )
            is UpdateCheckResult.Unavailable -> UpdateUiState.Error(result.reason)
        }
        _state.value = next
        return next
    }

    private fun setDownload(download: ApkDownloadState) {
        _state.update { (it as? UpdateUiState.UpdateAvailable)?.copy(download = download) ?: it }
    }

    private companion object {
        const val LAUNCH_CHECK_DELAY_MS = 4_000L
    }
}
