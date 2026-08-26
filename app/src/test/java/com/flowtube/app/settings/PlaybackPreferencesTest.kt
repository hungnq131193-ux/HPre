package com.flowtube.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PlaybackPreferencesTest {

    private lateinit var tempFile: File
    private lateinit var testScope: CoroutineScope
    private lateinit var preferences: PlaybackPreferences

    @Before
    fun setUp() {
        tempFile = File(System.getProperty("java.io.tmpdir"), "test_prefs_${System.currentTimeMillis()}.preferences_pb")
        testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tempFile }
        )
        preferences = DataStorePlaybackPreferences(dataStore)
    }

    @After
    fun tearDown() {
        testScope.cancel()
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    @Test
    fun default_background_playback_is_true() = runTest {
        val initial = preferences.isBackgroundPlaybackEnabled.first()
        assertTrue(initial)
    }

    @Test
    fun default_pip_enabled_is_true() = runTest {
        val initial = preferences.isPipEnabled.first()
        assertTrue(initial)
    }

    @Test
    fun updating_pip_emits_new_value() = runTest {
        preferences.setPipEnabled(false)
        val updated = preferences.isPipEnabled.first()
        assertFalse(updated)

        preferences.setPipEnabled(true)
        val updatedAgain = preferences.isPipEnabled.first()
        assertTrue(updatedAgain)
    }

    @Test
    fun updating_background_playback_emits_new_value() = runTest {
        preferences.setBackgroundPlaybackEnabled(false)
        val updated = preferences.isBackgroundPlaybackEnabled.first()
        assertFalse(updated)

        preferences.setBackgroundPlaybackEnabled(true)
        val updatedAgain = preferences.isBackgroundPlaybackEnabled.first()
        assertTrue(updatedAgain)
    }

    @Test
    fun datastore_emission_updates_playback_ui_coordinator_both_flags() = runTest {
        val coordinator = com.flowtube.app.player.PlaybackUiCoordinator()
        val appScope = CoroutineScope(Dispatchers.Unconfined + Job())

        // Simulate FlowTubeApplication preference collector binding
        appScope.launch {
            preferences.isBackgroundPlaybackEnabled.collect { bg ->
                coordinator.setBackgroundPlaybackEnabled(bg)
            }
        }
        appScope.launch {
            preferences.isPipEnabled.collect { pip ->
                coordinator.setPipEnabled(pip)
            }
        }

        // Initial default state
        assertTrue(coordinator.state.value.backgroundPlaybackEnabled)
        assertTrue(coordinator.state.value.pipEnabled)

        // Update background playback preference
        preferences.setBackgroundPlaybackEnabled(false)
        assertFalse(coordinator.state.value.backgroundPlaybackEnabled)

        // Update pip preference
        preferences.setPipEnabled(false)
        assertFalse(coordinator.state.value.pipEnabled)

        // Restore flags
        preferences.setBackgroundPlaybackEnabled(true)
        assertTrue(coordinator.state.value.backgroundPlaybackEnabled)

        preferences.setPipEnabled(true)
        assertTrue(coordinator.state.value.pipEnabled)

        appScope.cancel()
    }
}
