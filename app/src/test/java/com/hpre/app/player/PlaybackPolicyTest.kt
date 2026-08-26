package com.hpre.app.player

import com.hpre.app.model.ContentKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPolicyTest {
    @Test
    fun background_playback_continues_only_when_enabled() {
        assertTrue(PlaybackPolicy.shouldContinueInBackground(backgroundEnabled = true, enteringPip = false))
        assertTrue(PlaybackPolicy.shouldContinueInBackground(backgroundEnabled = false, enteringPip = true))
        assertFalse(PlaybackPolicy.shouldContinueInBackground(backgroundEnabled = false, enteringPip = false))
    }

    @Test
    fun pip_requires_watch_visible_enabled_playing_ready_video() {
        val eligible = PipEligibility(
            supported = true,
            enabled = true,
            watchVisible = true,
            alreadyInPip = false,
            hasVideo = true,
            isPlaying = true,
            isReady = true
        )
        assertTrue(PlaybackPolicy.canEnterPip(eligible))

        assertFalse(PlaybackPolicy.canEnterPip(eligible.copy(watchVisible = false)))
        assertFalse(PlaybackPolicy.canEnterPip(eligible.copy(enabled = false)))
        assertFalse(PlaybackPolicy.canEnterPip(eligible.copy(isPlaying = false)))
        assertFalse(PlaybackPolicy.canEnterPip(eligible.copy(isReady = false)))
        assertFalse(PlaybackPolicy.canEnterPip(eligible.copy(hasVideo = false)))
        assertFalse(PlaybackPolicy.canEnterPip(eligible.copy(alreadyInPip = true)))
    }

    @Test
    fun pip_failure_resets_entering_pip_state_and_pauses_when_background_disabled() {
        // When entering PiP fails, pipActiveOrEntering is reset to false.
        // If background playback is disabled (false), shouldContinueInBackground must return false.
        val backgroundEnabled = false
        var pipActiveOrEntering = true

        // Simulating PiP entry failure:
        pipActiveOrEntering = false

        val shouldContinue = PlaybackPolicy.shouldContinueInBackground(
            backgroundEnabled = backgroundEnabled,
            enteringPip = pipActiveOrEntering
        )
        assertFalse(shouldContinue)
    }

    @Test
    fun isChangingConfigurations_exempts_disabled_background_from_stopping() {
        // When background is disabled and not in pip, but activity is changing configurations (rotation):
        val shouldContinue = PlaybackPolicy.shouldContinueInBackground(
            backgroundEnabled = false,
            enteringPip = false,
            isChangingConfigurations = true
        )
        assertTrue(shouldContinue)

        // Real backgrounding without changing configs must not continue
        val realBackground = PlaybackPolicy.shouldContinueInBackground(
            backgroundEnabled = false,
            enteringPip = false,
            isChangingConfigurations = false
        )
        assertFalse(realBackground)
    }

    @Test
    fun isControllerAuthorized_only_accepts_same_package_or_trusted_same_uid() {
        val appPackage = "com.hpre.app"
        val appUid = 10123

        // Case 1: Same package name -> Authorized
        assertTrue(PlaybackPolicy.isControllerAuthorized(appPackage, appUid, controllerPackage = "com.hpre.app", controllerUid = 10123))

        // Case 2: Different package name, same trusted UID -> Authorized
        assertTrue(PlaybackPolicy.isControllerAuthorized(appPackage, appUid, controllerPackage = "com.hpre.app.media", controllerUid = 10123))

        // Case 3: Different package name, different UID -> Rejected
        assertFalse(PlaybackPolicy.isControllerAuthorized(appPackage, appUid, controllerPackage = "com.evil.app", controllerUid = 10999))

        // Case 4: Same package name spoofed with different UID -> Rejected
        assertFalse(PlaybackPolicy.isControllerAuthorized(appPackage, appUid, controllerPackage = "com.hpre.app", controllerUid = 10999))

        // Case 5: Empty/blank package name -> Rejected
        assertFalse(PlaybackPolicy.isControllerAuthorized(appPackage, appUid, controllerPackage = "", controllerUid = 10123))
    }

    @Test
    fun pip_eligibility_transitions_recomputed_on_audio_video_and_state_changes() {
        val baseState = PlaybackState(
            key = ContentKey(0, "test"),
            title = "Test",
            streamType = PlaybackStreamType.PROGRESSIVE,
            isPlaying = true,
            isReady = true
        )
        val baseUiState = PlaybackUiState(
            watchVisible = true,
            pipEnabled = true,
            isInPip = false
        )

        // Base state should be eligible
        assertTrue(PlaybackPolicy.canEnterPip(PlaybackPolicy.calculatePipEligibility(isPipSupported = true, uiState = baseUiState, playbackState = baseState)))

        // Audio only -> not eligible
        val audioState = baseState.copy(streamType = PlaybackStreamType.AUDIO_ONLY)
        assertFalse(PlaybackPolicy.canEnterPip(PlaybackPolicy.calculatePipEligibility(isPipSupported = true, uiState = baseUiState, playbackState = audioState)))

        // Not playing -> not eligible
        val pausedState = baseState.copy(isPlaying = false)
        assertFalse(PlaybackPolicy.canEnterPip(PlaybackPolicy.calculatePipEligibility(isPipSupported = true, uiState = baseUiState, playbackState = pausedState)))

        // Not ready -> not eligible
        val bufferingState = baseState.copy(isReady = false)
        assertFalse(PlaybackPolicy.canEnterPip(PlaybackPolicy.calculatePipEligibility(isPipSupported = true, uiState = baseUiState, playbackState = bufferingState)))

        // No key / streamType null -> not eligible
        val nullKeyState = baseState.copy(key = null)
        assertFalse(PlaybackPolicy.canEnterPip(PlaybackPolicy.calculatePipEligibility(isPipSupported = true, uiState = baseUiState, playbackState = nullKeyState)))

        // Watch not visible -> not eligible
        val hiddenUi = baseUiState.copy(watchVisible = false)
        assertFalse(PlaybackPolicy.canEnterPip(PlaybackPolicy.calculatePipEligibility(isPipSupported = true, uiState = hiddenUi, playbackState = baseState)))

        // Pip disabled in settings -> not eligible
        val disabledPipUi = baseUiState.copy(pipEnabled = false)
        assertFalse(PlaybackPolicy.canEnterPip(PlaybackPolicy.calculatePipEligibility(isPipSupported = true, uiState = disabledPipUi, playbackState = baseState)))
    }
}


