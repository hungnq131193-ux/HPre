package com.hpre.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SettingsRepositoryTest {

    private lateinit var tempFile: File
    private lateinit var testScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        tempFile = File(System.getProperty("java.io.tmpdir"), "test_settings_${System.currentTimeMillis()}.preferences_pb")
        testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tempFile }
        )
        repository = DataStoreSettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        testScope.cancel()
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    @Test
    fun default_settings_match_specification() = runTest {
        val settings = repository.settings.first()
        assertEquals(AppTheme.DARK, settings.theme)
        assertTrue(settings.backgroundPlaybackEnabled)
        assertTrue(settings.pipEnabled)
        assertTrue(settings.historyEnabled)
        assertEquals(QualityPreferenceSetting.AUTO, settings.wifiQuality)
        assertEquals(QualityPreferenceSetting.AUTO, settings.mobileQuality)
        assertEquals(1.0f, settings.defaultPlaybackSpeed, 0.001f)
        assertTrue(settings.autoplay)
    }

    @Test
    fun invalid_stored_theme_falls_back_to_dark() = runTest {
        dataStore.edit { preferences ->
            preferences[DataStoreSettingsRepository.KEY_THEME] = "NOT_A_THEME"
        }

        assertEquals(AppTheme.DARK, repository.settings.first().theme)
    }

    @Test
    fun updating_theme_persists_and_emits() = runTest {
        repository.setTheme(AppTheme.DARK)
        assertEquals(AppTheme.DARK, repository.settings.first().theme)

        repository.setTheme(AppTheme.LIGHT)
        assertEquals(AppTheme.LIGHT, repository.settings.first().theme)

        repository.setTheme(AppTheme.SYSTEM)
        assertEquals(AppTheme.SYSTEM, repository.settings.first().theme)
    }

    @Test
    fun updating_playback_preferences_persists_and_emits() = runTest {
        repository.setBackgroundPlaybackEnabled(false)
        assertFalse(repository.settings.first().backgroundPlaybackEnabled)

        repository.setPipEnabled(false)
        assertFalse(repository.settings.first().pipEnabled)

        repository.setHistoryEnabled(false)
        assertFalse(repository.settings.first().historyEnabled)
    }

    @Test
    fun updating_qualities_and_speed_and_autoplay_persists() = runTest {
        repository.setWifiQuality(QualityPreferenceSetting.HIGH_1080P)
        assertEquals(QualityPreferenceSetting.HIGH_1080P, repository.settings.first().wifiQuality)

        repository.setMobileQuality(QualityPreferenceSetting.LOW_360P)
        assertEquals(QualityPreferenceSetting.LOW_360P, repository.settings.first().mobileQuality)

        repository.setDefaultPlaybackSpeed(1.5f)
        assertEquals(1.5f, repository.settings.first().defaultPlaybackSpeed, 0.001f)

        repository.setAutoplay(false)
        assertFalse(repository.settings.first().autoplay)
    }

    @Test
    fun repository_implements_playback_preferences_with_shared_single_source_of_truth() = runTest {
        val playbackPrefs = repository as PlaybackPreferences
        assertTrue(playbackPrefs.isBackgroundPlaybackEnabled.first())
        assertTrue(playbackPrefs.isPipEnabled.first())
        assertTrue(playbackPrefs.isHistoryEnabled.first())

        playbackPrefs.setBackgroundPlaybackEnabled(false)
        assertFalse(repository.settings.first().backgroundPlaybackEnabled)
        assertFalse(playbackPrefs.isBackgroundPlaybackEnabled.first())

        playbackPrefs.setPipEnabled(false)
        assertFalse(repository.settings.first().pipEnabled)
        assertFalse(playbackPrefs.isPipEnabled.first())

        playbackPrefs.setHistoryEnabled(false)
        assertFalse(repository.settings.first().historyEnabled)
        assertFalse(playbackPrefs.isHistoryEnabled.first())
    }
}
