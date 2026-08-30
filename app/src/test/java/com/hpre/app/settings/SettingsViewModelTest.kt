package com.hpre.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.hpre.app.update.AppUpdateChecker
import com.hpre.app.update.OfficialReleasePage
import com.hpre.app.update.SemanticVersion
import com.hpre.app.update.UpdateCheckResult
import com.hpre.app.update.UpdateUnavailableReason
import kotlinx.coroutines.CompletableDeferred

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeSettingsRepository : SettingsRepository {
        val settingsFlow = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = settingsFlow

        val bgFlow = MutableStateFlow(true)
        override val isBackgroundPlaybackEnabled: Flow<Boolean> = bgFlow

        val pipFlow = MutableStateFlow(true)
        override val isPipEnabled: Flow<Boolean> = pipFlow

        val histFlow = MutableStateFlow(true)
        override val isHistoryEnabled: Flow<Boolean> = histFlow

        override suspend fun setTheme(theme: AppTheme) {
            settingsFlow.value = settingsFlow.value.copy(theme = theme)
        }

        override suspend fun setLanguage(language: AppLanguage) {
            settingsFlow.value = settingsFlow.value.copy(language = language)
        }

        override suspend fun setWifiQuality(quality: QualityPreferenceSetting) {
            settingsFlow.value = settingsFlow.value.copy(wifiQuality = quality)
        }

        override suspend fun setMobileQuality(quality: QualityPreferenceSetting) {
            settingsFlow.value = settingsFlow.value.copy(mobileQuality = quality)
        }

        override suspend fun setDefaultPlaybackSpeed(speed: Float) {
            settingsFlow.value = settingsFlow.value.copy(defaultPlaybackSpeed = speed)
        }

        override suspend fun setAutoplay(enabled: Boolean) {
            settingsFlow.value = settingsFlow.value.copy(autoplay = enabled)
        }

        override suspend fun setBackgroundPlaybackEnabled(enabled: Boolean) {
            bgFlow.value = enabled
            settingsFlow.value = settingsFlow.value.copy(backgroundPlaybackEnabled = enabled)
        }

        override suspend fun setPipEnabled(enabled: Boolean) {
            pipFlow.value = enabled
            settingsFlow.value = settingsFlow.value.copy(pipEnabled = enabled)
        }

        override suspend fun setHistoryEnabled(enabled: Boolean) {
            histFlow.value = enabled
            settingsFlow.value = settingsFlow.value.copy(historyEnabled = enabled)
        }
    }

    private class FakeUpdateChecker(
        private val response: suspend () -> UpdateCheckResult
    ) : AppUpdateChecker {
        var callCount = 0

        override suspend fun check(installedVersion: String): UpdateCheckResult {
            callCount++
            return response()
        }
    }

    @Test
    fun clearVideoCache_delegatesToMediaCacheManager() = runTest {
        var cleared = false
        val fakeCacheManager = object : com.hpre.app.player.cache.MediaCacheManager {
            override val cache: androidx.media3.datasource.cache.Cache? = null
            override val isAvailable: Boolean = true
            override suspend fun clearCache(): Boolean {
                cleared = true
                return true
            }
        }
        val viewModel = SettingsViewModel(
            settingsRepository = FakeSettingsRepository(),
            appUpdateChecker = FakeUpdateChecker { UpdateCheckResult.UpToDate(SemanticVersion(1, 0, 13)) },
            installedVersion = "1.0.13",
            mediaCacheManager = fakeCacheManager
        )
        viewModel.clearVideoCache()
        testScheduler.advanceUntilIdle()

        assertTrue(cleared)
    }

    @Test
    fun viewModel_updates_settings_via_repository() = runTest {
        val fakeRepo = FakeSettingsRepository()
        val viewModel = SettingsViewModel(
            settingsRepository = fakeRepo,
            appUpdateChecker = FakeUpdateChecker {
                UpdateCheckResult.UpToDate(SemanticVersion(1, 0, 0))
            },
            installedVersion = "1.0.0"
        )

        viewModel.setTheme(AppTheme.DARK)
        testScheduler.advanceUntilIdle()
        assertEquals(AppTheme.DARK, fakeRepo.settingsFlow.value.theme)

        viewModel.setBackgroundPlayback(false)
        testScheduler.advanceUntilIdle()
        assertFalse(fakeRepo.settingsFlow.value.backgroundPlaybackEnabled)

        viewModel.setPip(false)
        testScheduler.advanceUntilIdle()
        assertFalse(fakeRepo.settingsFlow.value.pipEnabled)

        viewModel.setHistory(false)
        testScheduler.advanceUntilIdle()
        assertFalse(fakeRepo.settingsFlow.value.historyEnabled)

        viewModel.setWifiQuality(QualityPreferenceSetting.HIGH_1080P)
        testScheduler.advanceUntilIdle()
        assertEquals(QualityPreferenceSetting.HIGH_1080P, fakeRepo.settingsFlow.value.wifiQuality)

        viewModel.setMobileQuality(QualityPreferenceSetting.LOW_360P)
        testScheduler.advanceUntilIdle()
        assertEquals(QualityPreferenceSetting.LOW_360P, fakeRepo.settingsFlow.value.mobileQuality)

        viewModel.setDefaultPlaybackSpeed(1.25f)
        testScheduler.advanceUntilIdle()
        assertEquals(1.25f, fakeRepo.settingsFlow.value.defaultPlaybackSpeed, 0.001f)

        viewModel.setAutoplay(false)
        testScheduler.advanceUntilIdle()
        assertFalse(fakeRepo.settingsFlow.value.autoplay)
    }

    @Test
    fun update_check_is_idle_and_makes_no_request_on_construction() {
        val checker = FakeUpdateChecker {
            UpdateCheckResult.UpToDate(SemanticVersion(1, 0, 0))
        }

        val viewModel = SettingsViewModel(FakeSettingsRepository(), checker, "1.0.0")

        assertEquals(UpdateUiState.Idle, viewModel.updateState.value)
        assertEquals(0, checker.callCount)
    }

    @Test
    fun check_transitions_from_checking_to_up_to_date() = runTest {
        val deferred = CompletableDeferred<UpdateCheckResult>()
        val checker = FakeUpdateChecker { deferred.await() }
        val viewModel = SettingsViewModel(FakeSettingsRepository(), checker, "1.0.0")

        viewModel.checkForUpdates()
        runCurrent()
        assertEquals(UpdateUiState.Checking, viewModel.updateState.value)

        deferred.complete(UpdateCheckResult.UpToDate(SemanticVersion(1, 0, 0)))
        advanceUntilIdle()
        assertEquals(UpdateUiState.UpToDate("1.0.0"), viewModel.updateState.value)
    }

    @Test
    fun check_exposes_latest_version_and_release_page() = runTest {
        val page = requireNotNull(
            OfficialReleasePage.parse(
                "https://github.com/hungnq131193-ux/HPre/releases/tag/v1.0.1"
            )
        )
        val checker = FakeUpdateChecker {
            UpdateCheckResult.UpdateAvailable(
                SemanticVersion(1, 0, 0),
                SemanticVersion(1, 0, 1),
                page
            )
        }
        val viewModel = SettingsViewModel(FakeSettingsRepository(), checker, "1.0.0")

        viewModel.checkForUpdates()
        advanceUntilIdle()

        assertEquals(
            UpdateUiState.UpdateAvailable("1.0.0", "1.0.1", page),
            viewModel.updateState.value
        )
        assertEquals(page, viewModel.releasePageToOpen())
    }

    @Test
    fun repeated_taps_during_active_check_call_checker_once() = runTest {
        val deferred = CompletableDeferred<UpdateCheckResult>()
        val checker = FakeUpdateChecker { deferred.await() }
        val viewModel = SettingsViewModel(FakeSettingsRepository(), checker, "1.0.0")

        viewModel.checkForUpdates()
        runCurrent()
        viewModel.checkForUpdates()
        runCurrent()

        assertEquals(1, checker.callCount)
        deferred.complete(UpdateCheckResult.UpToDate(SemanticVersion(1, 0, 0)))
        advanceUntilIdle()
    }

    @Test
    fun unavailable_result_maps_to_retryable_error() = runTest {
        val checker = FakeUpdateChecker {
            UpdateCheckResult.Unavailable(UpdateUnavailableReason.RATE_LIMITED)
        }
        val viewModel = SettingsViewModel(FakeSettingsRepository(), checker, "1.0.0")

        viewModel.checkForUpdates()
        advanceUntilIdle()

        assertEquals(
            UpdateUiState.Error(UpdateUnavailableReason.RATE_LIMITED),
            viewModel.updateState.value
        )
    }

    @Test
    fun release_open_failure_preserves_available_release() = runTest {
        val page = requireNotNull(
            OfficialReleasePage.parse(
                "https://github.com/hungnq131193-ux/HPre/releases/tag/v1.0.1"
            )
        )
        val checker = FakeUpdateChecker {
            UpdateCheckResult.UpdateAvailable(
                SemanticVersion(1, 0, 0),
                SemanticVersion(1, 0, 1),
                page
            )
        }
        val viewModel = SettingsViewModel(FakeSettingsRepository(), checker, "1.0.0")
        viewModel.checkForUpdates()
        advanceUntilIdle()

        viewModel.reportReleasePageOpenFailure()

        assertEquals(
            UpdateUiState.UpdateAvailable("1.0.0", "1.0.1", page, openError = true),
            viewModel.updateState.value
        )
        assertEquals(page, viewModel.releasePageToOpen())
    }
}
