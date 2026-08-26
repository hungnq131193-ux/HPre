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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

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

    @Test
    fun viewModel_updates_settings_via_repository() = runTest {
        val fakeRepo = FakeSettingsRepository()
        val viewModel = SettingsViewModel(fakeRepo)

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
}
