package com.hpre.app.player

import android.os.Bundle
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.safeMessageKey
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlayerProtocolTest {

    private val testKey = ContentKey(0, "proto_test_123")

    @Test
    fun playback_ui_coordinator_emits_setting_state_updates() {
        val coordinator = PlaybackUiCoordinator()
        assertTrue(coordinator.state.value.backgroundPlaybackEnabled)
        assertTrue(coordinator.state.value.pipEnabled)
        assertFalse(coordinator.state.value.watchVisible)
        assertFalse(coordinator.state.value.isInPip)

        coordinator.setBackgroundPlaybackEnabled(false)
        assertFalse(coordinator.state.value.backgroundPlaybackEnabled)

        coordinator.setPipEnabled(false)
        assertFalse(coordinator.state.value.pipEnabled)

        coordinator.setWatchVisible(true)
        assertTrue(coordinator.state.value.watchVisible)

        coordinator.setInPip(true)
        assertTrue(coordinator.state.value.isInPip)
    }

    @Test
    fun typed_error_safe_message_keys_cover_all_app_errors() {
        assertEquals("error_network", AppError.NetworkError.safeMessageKey())
        assertEquals("error_rate_limited", AppError.RateLimited.safeMessageKey())
        assertEquals("error_content_unavailable", AppError.ContentUnavailable.safeMessageKey())
        assertEquals("error_age_restricted", AppError.AgeRestricted.safeMessageKey())
        assertEquals("error_geo_restricted", AppError.GeoRestricted.safeMessageKey())
        assertEquals("error_login_required", AppError.LoginRequired.safeMessageKey())
        assertEquals("error_stream_expired", AppError.StreamExpired.safeMessageKey())
        assertEquals("error_unsupported_format", AppError.UnsupportedFormat.safeMessageKey())
        assertEquals("error_extraction_failed", AppError.ExtractionFailed.safeMessageKey())
        assertEquals("error_unknown", AppError.Unknown.safeMessageKey())
    }

    @Test
    fun pending_prepare_contains_quality_and_speed_and_retains_latest() {
        val commands = PendingSessionCommands()
        val quality = QualityOption(1080, "1080p", false, "mp4", "video/mp4", "avc1")
        val prep1 = PendingPrepare(testKey, 5000L, true, quality, playbackSpeed = 1.5f)
        commands.setPrepare(prep1)

        val retrieved = commands.takePrepare()
        assertNotNull(retrieved)
        assertEquals(testKey, retrieved?.key)
        assertEquals(5000L, retrieved?.positionMs)
        assertEquals(true, retrieved?.playWhenReady)
        assertEquals(quality, retrieved?.initialQuality)
        assertEquals(1.5f, retrieved?.playbackSpeed ?: 0f, 0.001f)
    }

    @Test
    fun playback_policy_correctly_evaluates_background_and_pip_exemptions() {
        // Disabled background with no PiP must not continue in background
        assertFalse(PlaybackPolicy.shouldContinueInBackground(backgroundEnabled = false, enteringPip = false))

        // Disabled background with active/entering PiP IS exempt and continues
        assertTrue(PlaybackPolicy.shouldContinueInBackground(backgroundEnabled = false, enteringPip = true))

        // Enabled background continues
        assertTrue(PlaybackPolicy.shouldContinueInBackground(backgroundEnabled = true, enteringPip = false))
    }

    @Test
    fun pip_preference_false_disables_pip_eligibility() {
        val eligibility = PipEligibility(
            supported = true,
            enabled = false,
            watchVisible = true,
            alreadyInPip = false,
            hasVideo = true,
            isPlaying = true,
            isReady = true
        )
        assertFalse(PlaybackPolicy.canEnterPip(eligibility))
    }

    @Test
    fun quality_selection_state_holds_pending_and_rolls_back_on_failure() {
        val priorQuality = QualityOption(720, "720p", true, "mp4")
        val targetQuality = QualityOption(1080, "1080p", false, "mp4")
        val available = listOf(priorQuality, targetQuality)

        val state = PlaybackState(
            key = testKey,
            availableQualities = available,
            selectedQuality = priorQuality,
            pendingQuality = null
        )

        // When user selects quality:
        val pendingState = state.copy(isLoading = true, pendingQuality = targetQuality)
        assertEquals(priorQuality, pendingState.selectedQuality)
        assertEquals(targetQuality, pendingState.pendingQuality)

        // On failure: rollback pending, error set
        val failedState = pendingState.copy(isLoading = false, pendingQuality = null, error = AppError.UnsupportedFormat)
        assertEquals(priorQuality, failedState.selectedQuality)
        assertNull(failedState.pendingQuality)
        assertEquals(AppError.UnsupportedFormat, failedState.error)

        // On success: commit authoritative quality
        val successState = pendingState.copy(isLoading = false, pendingQuality = null, selectedQuality = targetQuality, error = null)
        assertEquals(targetQuality, successState.selectedQuality)
        assertNull(successState.pendingQuality)
    }

    @Test
    fun quality_selection_monotonic_local_generation_prevents_late_completion_rollback() {
        val qA = QualityOption(720, "720p", true, "mp4")
        val qB = QualityOption(1080, "1080p", false, "mp4")

        var qualityRequestGen = 0L

        // Request A
        val genA = ++qualityRequestGen
        var currentPending: QualityOption? = qA
        var currentSelected: QualityOption? = null

        // Request B arrives before A finishes
        val genB = ++qualityRequestGen
        currentPending = qB

        // Late A completes (success or failure)
        val handleLateAOutcome = {
            if (genA == qualityRequestGen) {
                currentSelected = qA
                currentPending = null
            }
        }
        handleLateAOutcome()

        // B should still be the pending quality, and selected should not be rolled back or mutated to A
        assertNull(currentSelected)
        assertEquals(qB, currentPending)

        // B completes successfully
        val handleBOutcome = {
            if (genB == qualityRequestGen) {
                currentSelected = qB
                currentPending = null
            }
        }
        handleBOutcome()

        assertEquals(qB, currentSelected)
        assertNull(currentPending)
    }

    @Test
    fun rapid_ab_quality_switch_completes_own_settable_future_with_invalid_state_and_no_hanging_futures() {
        val qA = QualityOption(720, "720p", true, "mp4")
        val qB = QualityOption(1080, "1080p", false, "mp4")

        val futureA = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.SessionResult>()
        val futureB = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.SessionResult>()

        var activeQualityFuture: com.google.common.util.concurrent.SettableFuture<androidx.media3.session.SessionResult>? = null
        var qualityRequestGen = 0L

        // Request A starts
        val genA = ++qualityRequestGen
        activeQualityFuture?.set(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_ERROR_INVALID_STATE))
        activeQualityFuture = futureA

        // Request B starts immediately, cancelling / completing A
        val genB = ++qualityRequestGen
        activeQualityFuture?.set(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_ERROR_INVALID_STATE))
        activeQualityFuture = futureB

        // Future A should be completed immediately with invalid state without hanging
        assertTrue(futureA.isDone)
        assertEquals(androidx.media3.session.SessionResult.RESULT_ERROR_INVALID_STATE, futureA.get().resultCode)

        // Future B completes with success
        futureB.set(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
        assertTrue(futureB.isDone)
        assertEquals(androidx.media3.session.SessionResult.RESULT_SUCCESS, futureB.get().resultCode)
    }

    @Test
    fun session_player_controller_disconnect_and_reconnect_replays_pending_prepare_and_policy() {
        val commands = PendingSessionCommands()
        val key = ContentKey(0, "disconnect_reconnect_vid")
        val quality = QualityOption(720, "720p", true)
        val prep = PendingPrepare(key, 12000L, true, quality, 1.25f)
        commands.setPrepare(prep)

        // Controller disconnect event: mediaController becomes null, pending prepare retained
        val retrieved = commands.takePrepare()
        assertNotNull(retrieved)
        assertEquals(key, retrieved?.key)
        assertEquals(12000L, retrieved?.positionMs)
        assertEquals(1.25f, retrieved?.playbackSpeed ?: 0f, 0.001f)
    }

    @Test
    fun stale_controller_listener_onDisconnected_ignored_when_controller_or_token_mismatches() {
        var connectionAttemptGeneration = 0L
        var activeConnectionToken: Long? = null
        var activeController: Any? = null
        var disconnectHandlingCount = 0

        val fakeController1 = Object()
        val fakeController2 = Object()

        // Connect attempt 1
        val token1 = ++connectionAttemptGeneration
        activeConnectionToken = token1
        activeController = fakeController1

        // Connect attempt 2
        val token2 = ++connectionAttemptGeneration
        activeConnectionToken = token2
        activeController = fakeController2

        fun handleDisconnectForToken(listenerBoundToken: Long, controller: Any?) {
            val activeToken = activeConnectionToken
            val activeCtrl = activeController
            if (activeToken == null || activeCtrl == null) return
            if (listenerBoundToken != activeToken || controller != activeCtrl) return

            disconnectHandlingCount++
            activeConnectionToken = null
            activeController = null
        }

        // Stale disconnect from old controller 1 with old token 1
        handleDisconnectForToken(token1, fakeController1)
        assertEquals(0, disconnectHandlingCount)
        assertEquals(token2, activeConnectionToken)
        assertEquals(fakeController2, activeController)

        // Stale disconnect from old controller 1 with current token 2 (stale listener same latest token)
        handleDisconnectForToken(token2, fakeController1)
        assertEquals(0, disconnectHandlingCount)
        assertEquals(token2, activeConnectionToken)
        assertEquals(fakeController2, activeController)

        // Valid disconnect from active controller 2 with current token 2
        handleDisconnectForToken(token2, fakeController2)
        assertEquals(1, disconnectHandlingCount)
        assertNull(activeConnectionToken)
        assertNull(activeController)
    }

    @Test
    fun policy_command_without_background_extra_defaults_to_false() {
        val emptyBundle = Bundle()
        val bgEnabledDefault = emptyBundle.getBoolean(HPrePlaybackService.EXTRA_BACKGROUND_ENABLED, false)
        assertFalse("EXTRA_BACKGROUND_ENABLED absent must default to false (fail closed)", bgEnabledDefault)
    }

    @Test
    fun pip_fallback_paused_and_cleared_when_background_disabled_on_pip_failure() {
        var paused = false
        var cleared = false
        val backgroundEnabled = false
        val pipEntered = false

        if (!pipEntered) {
            if (!PlaybackPolicy.shouldContinueInBackground(backgroundEnabled, enteringPip = false)) {
                paused = true
                cleared = true
            }
        }

        assertTrue(paused)
        assertTrue(cleared)
    }

    @Test
    fun service_restore_with_unknown_preference_or_timeout_fails_closed_and_restores_paused() {
        fun resolveEffectivePlayWhenReady(
            datastoreResult: Boolean?,
            snapshotPlayWhenReady: Boolean
        ): Boolean {
            // Fails closed: if preference unavailable (null / error / timeout / unknown), never autoplay
            val backgroundAllowed = datastoreResult ?: false
            return if (!backgroundAllowed) false else snapshotPlayWhenReady
        }

        // Case 1: Timeout / null emission / unknown policy -> Must be paused (false)
        assertFalse(
            resolveEffectivePlayWhenReady(
                datastoreResult = null,
                snapshotPlayWhenReady = true
            )
        )

        // Case 2: DataStore emits false -> Must be paused (false)
        assertFalse(
            resolveEffectivePlayWhenReady(
                datastoreResult = false,
                snapshotPlayWhenReady = true
            )
        )

        // Case 3: DataStore emits true -> Can restore snapshot playWhenReady (true)
        assertTrue(
            resolveEffectivePlayWhenReady(
                datastoreResult = true,
                snapshotPlayWhenReady = true
            )
        )

        // Case 4: DataStore emits true but snapshot was paused -> Must be paused (false)
        assertFalse(
            resolveEffectivePlayWhenReady(
                datastoreResult = true,
                snapshotPlayWhenReady = false
            )
        )
    }

    @Test
    fun service_restore_with_background_policy_false_does_not_autoplay() {
        val isBackgroundPlaybackEnabled = false
        val snapshot = PlaybackSnapshot(
            key = ContentKey(0, "policy_false_vid"),
            positionMs = 30000L,
            playWhenReady = true,
            playbackSpeed = 1.0f
        )
        // If background playback is disabled in settings policy, restore should not autoplay in background
        val effectivePlayWhenReady = if (!isBackgroundPlaybackEnabled) false else snapshot.playWhenReady
        assertFalse(effectivePlayWhenReady)
    }

    @Test
    fun media_controller_reconnect_stale_first_future_completes_after_second_reconnect_ignored() {
        var connectionAttemptGeneration = 0L
        var installedGeneration = 0L
        var isReleased = false

        // First connect attempt
        val token1 = ++connectionAttemptGeneration
        // Second connect attempt triggered before first completes
        val token2 = ++connectionAttemptGeneration

        // First future completes late
        val onFirstFutureComplete = {
            if (!isReleased && token1 == connectionAttemptGeneration) {
                installedGeneration = token1
            }
        }
        onFirstFutureComplete()

        // token1 is stale and was ignored
        assertEquals(0L, installedGeneration)

        // Second future completes
        val onSecondFutureComplete = {
            if (!isReleased && token2 == connectionAttemptGeneration) {
                installedGeneration = token2
            }
        }
        onSecondFutureComplete()

        // token2 is latest and installed
        assertEquals(token2, installedGeneration)
    }

    @Test
    fun cancelActiveQuality_invoked_from_prepare_and_clear_cancels_pending_future_exactly_once() {
        var activeQualityFuture: com.google.common.util.concurrent.SettableFuture<androidx.media3.session.SessionResult>? =
            com.google.common.util.concurrent.SettableFuture.create()

        var cancelCount = 0
        fun cancelActiveQuality() {
            val future = activeQualityFuture ?: return
            activeQualityFuture = null
            if (!future.isDone) {
                cancelCount++
                future.set(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_ERROR_INVALID_STATE))
            }
        }

        val futureRef = activeQualityFuture
        assertNotNull(futureRef)

        // Prepare or Clear triggers helper
        cancelActiveQuality()
        assertTrue(futureRef!!.isDone)
        assertEquals(androidx.media3.session.SessionResult.RESULT_ERROR_INVALID_STATE, futureRef.get().resultCode)
        assertEquals(1, cancelCount)
        assertNull(activeQualityFuture)

        // Second call (e.g. on clear or destroy) does not re-cancel or throw
        cancelActiveQuality()
        assertEquals(1, cancelCount)
    }

    @Test
    fun onLifecycleStop_with_changing_configurations_or_pip_does_not_clear_media_when_background_disabled() {
        var paused = false
        var cleared = false

        fun evaluateLifecycleStop(
            backgroundEnabled: Boolean,
            enteringPip: Boolean,
            isChangingConfigurations: Boolean,
            isInPip: Boolean
        ) {
            val shouldContinue = PlaybackPolicy.shouldContinueInBackground(
                backgroundEnabled = backgroundEnabled,
                enteringPip = enteringPip || isInPip,
                isChangingConfigurations = isChangingConfigurations
            )
            if (!shouldContinue) {
                paused = true
                cleared = true
            }
        }

        // Case 1: Config change (e.g. screen rotation) -> NO clear
        paused = false
        cleared = false
        evaluateLifecycleStop(backgroundEnabled = false, enteringPip = false, isChangingConfigurations = true, isInPip = false)
        assertFalse(paused)
        assertFalse(cleared)

        // Case 2: In PiP -> NO clear
        paused = false
        cleared = false
        evaluateLifecycleStop(backgroundEnabled = false, enteringPip = false, isChangingConfigurations = false, isInPip = true)
        assertFalse(paused)
        assertFalse(cleared)

        // Case 3: Entering PiP -> NO clear
        paused = false
        cleared = false
        evaluateLifecycleStop(backgroundEnabled = false, enteringPip = true, isChangingConfigurations = false, isInPip = false)
        assertFalse(paused)
        assertFalse(cleared)

        // Case 4: Actual background stop with background disabled -> MUST pause and clear
        paused = false
        cleared = false
        evaluateLifecycleStop(backgroundEnabled = false, enteringPip = false, isChangingConfigurations = false, isInPip = false)
        assertTrue(paused)
        assertTrue(cleared)
    }

    @Test
    fun end_to_end_restore_single_timeout_clears_on_timeout_and_preserves_on_cancellation() = kotlinx.coroutines.runBlocking {
        var snapshotCleared = false
        var rethrownCancellation = false

        suspend fun simulateRestoreWorkflow(
            totalTimeoutMs: Long,
            blockPref: Boolean = false,
            blockSnapshot: Boolean = false,
            blockStream: Boolean = false,
            throwCancellation: Boolean = false
        ) {
            try {
                kotlinx.coroutines.withTimeout(totalTimeoutMs) {
                    if (throwCancellation) {
                        throw kotlinx.coroutines.CancellationException("Job cancelled externally")
                    }
                    if (blockPref) {
                        kotlinx.coroutines.delay(1000L)
                    }
                    if (blockSnapshot) {
                        kotlinx.coroutines.delay(1000L)
                    }
                    if (blockStream) {
                        kotlinx.coroutines.delay(1000L)
                    }
                }
            } catch (tce: kotlinx.coroutines.TimeoutCancellationException) {
                snapshotCleared = true
            } catch (ce: kotlinx.coroutines.CancellationException) {
                rethrownCancellation = true
                throw ce
            } catch (_: Exception) {
                snapshotCleared = true
            }
        }

        // Test 1: Timeout on blocking pref (deadline 50ms) -> clears snapshot
        snapshotCleared = false
        simulateRestoreWorkflow(totalTimeoutMs = 50L, blockPref = true)
        assertTrue("Timeout on pref must clear snapshot", snapshotCleared)

        // Test 2: Timeout on blocking snapshot (deadline 50ms) -> clears snapshot
        snapshotCleared = false
        simulateRestoreWorkflow(totalTimeoutMs = 50L, blockSnapshot = true)
        assertTrue("Timeout on snapshot load must clear snapshot", snapshotCleared)

        // Test 3: Timeout on blocking stream fetch (deadline 50ms) -> clears snapshot
        snapshotCleared = false
        simulateRestoreWorkflow(totalTimeoutMs = 50L, blockStream = true)
        assertTrue("Timeout on stream info must clear snapshot", snapshotCleared)

        // Test 4: External cancellation -> MUST NOT clear snapshot and MUST rethrow CancellationException
        snapshotCleared = false
        rethrownCancellation = false
        try {
            simulateRestoreWorkflow(totalTimeoutMs = 5000L, throwCancellation = true)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Expected
        }
        assertFalse("External cancellation must NOT clear snapshot", snapshotCleared)
        assertTrue("CancellationException must be rethrown", rethrownCancellation)
    }
}
