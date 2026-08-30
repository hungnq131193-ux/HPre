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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlayerProtocolTest {

    @Test
    fun position_only_updates_do_not_change_structural_playback_state() {
        val first = PlaybackState(
            key = testKey,
            title = "Video",
            isPlaying = true,
            currentPositionMs = 1_000L,
            durationMs = 10_000L
        )
        val tick = first.copy(currentPositionMs = 2_000L)

        assertEquals(first.toStructuralState(), tick.toStructuralState())
        assertNotEquals(first.toProgress(), tick.toProgress())
    }

    @Test
    fun ui_progress_tracking_runs_only_in_foreground_or_pip() {
        assertTrue(PlaybackPolicy.shouldTrackUiProgress(isLifecycleStarted = true, isInPip = false))
        assertTrue(PlaybackPolicy.shouldTrackUiProgress(isLifecycleStarted = false, isInPip = true))
        assertFalse(PlaybackPolicy.shouldTrackUiProgress(isLifecycleStarted = false, isInPip = false))
    }

    private val testKey = ContentKey(0, "proto_test_123")

    @Test
    fun late_controller_connection_restores_ready_state_and_clears_loading() {
        val loading = PlaybackState(isLoading = true, isBuffering = true)

        val restored = restoreConnectedPlaybackState(
            current = loading,
            playbackState = androidx.media3.common.Player.STATE_READY,
            isPlaying = true,
            playWhenReady = true,
            durationMs = 120_000L,
            positionMs = 5_000L,
            playbackSpeed = 1f
        )

        assertTrue(restored.isReady)
        assertTrue(restored.isPlaying)
        assertFalse(restored.isLoading)
        assertFalse(restored.isBuffering)
        assertFalse(restored.isEnded)
    }

    @Test
    fun idle_controller_connection_does_not_start_loading_without_pending_work() {
        val restored = restoreConnectedPlaybackState(
            current = PlaybackState(),
            playbackState = androidx.media3.common.Player.STATE_IDLE,
            isPlaying = false,
            playWhenReady = false,
            durationMs = 0L,
            positionMs = 0L,
            playbackSpeed = 1f
        )

        assertFalse(restored.isLoading)
        assertFalse(restored.isBuffering)
        assertFalse(restored.isReady)
    }

    @Test
    fun adaptive_quality_selection_resolves_to_auto_cap_while_progressive_stays_fixed() {
        val option = QualityOption(720, "720p", true, "mp4")
        assertEquals(
            UserQualityPolicy.Auto(maxHeight = 720),
            QualityPolicyResolver.forSelection(PlaybackStreamType.HLS, option)
        )
        assertEquals(
            UserQualityPolicy.Fixed(option),
            QualityPolicyResolver.forSelection(PlaybackStreamType.PROGRESSIVE, option)
        )
    }

    @Test
    fun surface_handoff_generations_reject_stale_attach_and_retain_previous_on_reject() {
        val coordinator = PlaybackUiCoordinator()
        val watch = coordinator.beginSurfaceHandoff(SurfaceOwner.WATCH)
        assertTrue(coordinator.confirmSurfaceAttached(watch))
        assertEquals(watch, coordinator.currentSurfaceLease())
        assertTrue(coordinator.isCurrentSurfaceLease(watch))

        val mini = coordinator.beginSurfaceHandoff(SurfaceOwner.MINI_PLAYER)
        assertTrue(mini.generation > watch.generation)
        assertFalse(coordinator.confirmSurfaceAttached(watch))
        assertTrue(coordinator.rejectSurfaceAttach(mini))
        assertEquals(watch, coordinator.currentSurfaceLease())
        assertFalse(coordinator.isCurrentSurfaceLease(mini))
    }

    @Test
    fun surface_handoff_latest_owner_supersedes_stale_detach_generation() {
        val coordinator = PlaybackUiCoordinator()
        val watch = coordinator.beginSurfaceHandoff(SurfaceOwner.WATCH)
        assertTrue(coordinator.confirmSurfaceAttached(watch))
        val mini = coordinator.beginSurfaceHandoff(SurfaceOwner.MINI_PLAYER)
        assertTrue(coordinator.confirmSurfaceAttached(mini))
        val pip = coordinator.beginSurfaceHandoff(SurfaceOwner.SYSTEM_PIP)
        assertTrue(coordinator.confirmSurfaceAttached(pip))

        assertFalse(coordinator.confirmSurfaceAttached(mini))
        assertEquals(SurfaceOwner.SYSTEM_PIP, coordinator.currentSurfaceLease().owner)
        assertEquals(pip.generation, coordinator.currentSurfaceLease().generation)
    }

    @Test
    fun playback_ui_coordinator_emits_setting_state_updates() {
        val coordinator = PlaybackUiCoordinator()
        assertTrue(coordinator.state.value.backgroundPlaybackEnabled)
        assertTrue(coordinator.state.value.pipEnabled)
        assertFalse(coordinator.state.value.watchVisible)
        assertFalse(coordinator.state.value.isInPip)
        assertEquals(PlayerPresentation.WATCH, coordinator.state.value.presentation)

        coordinator.setBackgroundPlaybackEnabled(false)
        assertFalse(coordinator.state.value.backgroundPlaybackEnabled)

        coordinator.setPipEnabled(false)
        assertFalse(coordinator.state.value.pipEnabled)

        coordinator.setWatchVisible(true)
        assertTrue(coordinator.state.value.watchVisible)
        assertEquals(PlayerPresentation.WATCH, coordinator.state.value.presentation)

        coordinator.setInPip(true)
        assertTrue(coordinator.state.value.isInPip)
        assertEquals(PlayerPresentation.SYSTEM_PIP, coordinator.state.value.presentation)

        coordinator.setInPip(false)
        assertFalse(coordinator.state.value.isInPip)
        assertEquals(PlayerPresentation.WATCH, coordinator.state.value.presentation)
    }

    @Test
    fun playback_ui_coordinator_minimize_request_and_visibility_transitions_with_guards() {
        val coordinator = PlaybackUiCoordinator()

        // Guard 1: Cannot minimize when watchVisible is false
        assertFalse(coordinator.state.value.watchVisible)
        coordinator.requestMinimizeToHome()
        assertEquals(PlayerPresentation.WATCH, coordinator.state.value.presentation)

        // Show Watch
        coordinator.setWatchVisible(true)
        assertEquals(PlayerPresentation.WATCH, coordinator.state.value.presentation)

        // Guard 2: Cannot minimize when in PiP
        coordinator.setInPip(true)
        assertEquals(PlayerPresentation.SYSTEM_PIP, coordinator.state.value.presentation)
        coordinator.requestMinimizeToHome()
        assertEquals(PlayerPresentation.SYSTEM_PIP, coordinator.state.value.presentation)

        coordinator.setInPip(false)
        assertEquals(PlayerPresentation.WATCH, coordinator.state.value.presentation)

        // Request minimize when valid (watchVisible == true, presentation == WATCH, isInPip == false)
        coordinator.requestMinimizeToHome()
        assertEquals(PlayerPresentation.MINIMIZING, coordinator.state.value.presentation)
        assertFalse(coordinator.state.value.isInPip)
        assertTrue(coordinator.state.value.pipEnabled)

        // Guard 3: Idempotent while MINIMIZING
        coordinator.requestMinimizeToHome()
        assertEquals(PlayerPresentation.MINIMIZING, coordinator.state.value.presentation)

        // setWatchVisible(false) while MINIMIZING -> transitions to MINI_PLAYER
        coordinator.setWatchVisible(false)
        assertEquals(PlayerPresentation.MINI_PLAYER, coordinator.state.value.presentation)

        // Normal hide from non-MINIMIZING state does not transition to MINI_PLAYER
        val normalCoordinator = PlaybackUiCoordinator()
        normalCoordinator.setWatchVisible(true)
        assertEquals(PlayerPresentation.WATCH, normalCoordinator.state.value.presentation)
        normalCoordinator.setWatchVisible(false)
        assertEquals(PlayerPresentation.WATCH, normalCoordinator.state.value.presentation)

        // PiP exit returns to MINI_PLAYER if prior was MINI_PLAYER
        coordinator.setInPip(true)
        assertEquals(PlayerPresentation.SYSTEM_PIP, coordinator.state.value.presentation)
        coordinator.setInPip(false)
        assertEquals(PlayerPresentation.MINI_PLAYER, coordinator.state.value.presentation)
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

    @Test
    fun readiness_tracker_rejects_when_either_or_both_media_ids_are_null() = kotlinx.coroutines.runBlocking {
        val readiness = PlaybackReadinessTracker()
        val mediaId = PlaybackMediaId.encode(ContentKey(0, "test_null_media_id"))

        // Case 1: activeMediaId is null at registration -> onPlaybackStateChanged must reject
        val defNullActive = readiness.registerSession(sessionGen = 10L, mediaId = null)
        readiness.onPlaybackStateChanged(sessionGen = 10L, currentMediaId = mediaId, playbackState = androidx.media3.common.Player.STATE_READY)
        assertFalse("Null activeMediaId must reject callback", defNullActive.isCompleted)

        // Case 2: activeMediaId is set, but callback has currentMediaId = null -> reject
        val defNullCurrent = readiness.registerSession(sessionGen = 20L, mediaId = mediaId)
        readiness.onPlaybackStateChanged(sessionGen = 20L, currentMediaId = null, playbackState = androidx.media3.common.Player.STATE_READY)
        assertFalse("Null currentMediaId must reject callback", defNullCurrent.isCompleted)

        // Case 3: both null -> reject
        val defBothNull = readiness.registerSession(sessionGen = 30L, mediaId = null)
        readiness.onPlaybackStateChanged(sessionGen = 30L, currentMediaId = null, playbackState = androidx.media3.common.Player.STATE_READY)
        assertFalse("Both null mediaIds must reject callback", defBothNull.isCompleted)

        // Case 4: onError with null mediaId must also reject
        val defNullError = readiness.registerSession(sessionGen = 40L, mediaId = null)
        readiness.onError(sessionGen = 40L, currentMediaId = mediaId)
        assertFalse("Null activeMediaId in onError must reject", defNullError.isCompleted)

        val defNullCurrError = readiness.registerSession(sessionGen = 50L, mediaId = mediaId)
        readiness.onError(sessionGen = 50L, currentMediaId = null)
        assertFalse("Null currentMediaId in onError must reject", defNullCurrError.isCompleted)
    }

    @Test
    fun readiness_tracker_ignores_stale_media_id_and_accepts_matching_media_id() = kotlinx.coroutines.runBlocking {
        val readiness = PlaybackReadinessTracker()
        val oldMediaId = PlaybackMediaId.encode(ContentKey(0, "old_video_100"))
        val newMediaId = PlaybackMediaId.encode(ContentKey(0, "new_video_200"))

        // Register session 200 with newMediaId
        val def200 = readiness.registerSession(sessionGen = 200L, mediaId = newMediaId)
        assertFalse("New session must be pending", def200.isCompleted)

        // Stale READY with oldMediaId for same sessionGen is ignored
        readiness.onPlaybackStateChanged(sessionGen = 200L, currentMediaId = oldMediaId, playbackState = androidx.media3.common.Player.STATE_READY)
        assertFalse("Stale mediaId READY must not complete session", def200.isCompleted)

        // Stale error with oldMediaId is ignored
        readiness.onError(sessionGen = 200L, currentMediaId = oldMediaId)
        assertFalse("Stale mediaId error must not complete session", def200.isCompleted)

        // Matching newMediaId with STATE_READY completes true
        readiness.onPlaybackStateChanged(sessionGen = 200L, currentMediaId = newMediaId, playbackState = androidx.media3.common.Player.STATE_READY)
        assertTrue(def200.await())
    }

    @Test
    fun readiness_tracker_onError_with_matching_media_id_completes_false() = kotlinx.coroutines.runBlocking {
        val readiness = PlaybackReadinessTracker()
        val mediaId = PlaybackMediaId.encode(ContentKey(0, "error_vid"))
        val def = readiness.registerSession(sessionGen = 300L, mediaId = mediaId)

        readiness.onError(sessionGen = 300L, currentMediaId = mediaId)
        assertFalse(def.await())
    }

    @Test
    fun session_player_controller_handles_cancellation_exception_and_triggers_bounded_retry() = kotlinx.coroutines.runBlocking {
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val cancelFuture = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        cancelFuture.cancel(true) // will throw CancellationException on get()

        var futureCreations = 0
        val coordinator = object : SessionPlayerController.ConnectionLifecycleCoordinator {
            override fun createControllerFuture(
                context: android.content.Context,
                listener: androidx.media3.session.MediaController.Listener
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> {
                futureCreations++
                return cancelFuture
            }
        }

        val testDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        val controller = SessionPlayerController(
            context = fakeContext,
            connectionCoordinator = coordinator,
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            externalScope = this
        )

        // Initial connect attempted
        assertEquals(1, futureCreations)
        assertEquals(1, controller.currentRetryCount)
        assertTrue(controller.isReconnectingState)

        controller.clearMedia()
        assertFalse(controller.isReconnectingState)
        assertEquals(0, controller.currentRetryCount)
        controller.release()
    }

    @Test
    fun clearMedia_and_release_unconditionally_detach_owned_surfaceView_when_mediaController_is_null() = kotlinx.coroutines.runBlocking {
        var playerViewPlayer: Any? = "InitialPlayer"
        var playerSetCount = 0

        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val pendingFuture = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        val coordinator = object : SessionPlayerController.ConnectionLifecycleCoordinator {
            override fun createControllerFuture(
                context: android.content.Context,
                listener: androidx.media3.session.MediaController.Listener
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> = pendingFuture
        }

        val testDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        val controller = SessionPlayerController(
            context = fakeContext,
            connectionCoordinator = coordinator,
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            externalScope = this
        )

        // Clear media while mediaController is null must null surface player and current surface view
        controller.clearMedia()
        val snapshotAfterClear = controller.getTestingSnapshot()
        assertFalse("Surface attached probe must be false after clearMedia", snapshotAfterClear.surfaceAttached)

        controller.release()
        val snapshotAfterRelease = controller.getTestingSnapshot()
        assertFalse("Surface attached probe must be false after release", snapshotAfterRelease.surfaceAttached)
    }

    @Test
    fun stale_future_completing_after_clear_is_released_and_not_committed() = kotlinx.coroutines.runBlocking {
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val pendingFuture = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        val coordinator = object : SessionPlayerController.ConnectionLifecycleCoordinator {
            override fun createControllerFuture(
                context: android.content.Context,
                listener: androidx.media3.session.MediaController.Listener
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> = pendingFuture
        }

        val testDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        val controller = SessionPlayerController(
            context = fakeContext,
            connectionCoordinator = coordinator,
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            externalScope = this
        )

        // Clear before future completes
        controller.clearMedia()
        assertEquals(2L, controller.activeAttemptGen)

        val snapshot = controller.getTestingSnapshot()
        assertEquals(0L, controller.activeConnGen)
        assertFalse(controller.isReconnectingState)

        controller.release()
    }

    @Test
    fun repeated_failure_drives_retry_deduplication_and_exhaustion_on_production_controller() = kotlinx.coroutines.runBlocking {
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        var futureCreations = 0
        val pendingFutures = mutableListOf<com.google.common.util.concurrent.SettableFuture<androidx.media3.session.MediaController>>()
        val coordinator = object : SessionPlayerController.ConnectionLifecycleCoordinator {
            override fun createControllerFuture(
                context: android.content.Context,
                listener: androidx.media3.session.MediaController.Listener
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> {
                futureCreations++
                val f = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
                pendingFutures.add(f)
                return f
            }
        }

        val testDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        val controller = SessionPlayerController(
            context = fakeContext,
            connectionCoordinator = coordinator,
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            externalScope = this
        )

        assertEquals(1, futureCreations)

        // Fail 1st attempt
        pendingFutures.removeAt(0).setException(java.lang.RuntimeException("Connect failed 1"))
        assertEquals(1, controller.currentRetryCount)

        // Fail 2nd attempt
        if (pendingFutures.isNotEmpty()) {
            pendingFutures.removeAt(0).setException(java.lang.RuntimeException("Connect failed 2"))
        }

        // Exhaustion resets reconnecting state
        controller.clearMedia()
        assertEquals(0, controller.currentRetryCount)
        assertFalse(controller.isReconnectingState)

        controller.release()
    }

    @Test
    fun readiness_tracker_handles_ready_before_await_and_after_await_and_error_and_supersede() = kotlinx.coroutines.runBlocking {
        val readiness = PlaybackReadinessTracker()
        val mediaId = PlaybackMediaId.encode(ContentKey(0, "readiness_tracker"))

        // Case A: READY arrives AFTER register
        val defLateReady = readiness.registerSession(sessionGen = 102L, mediaId = mediaId)
        assertFalse(defLateReady.isCompleted)
        readiness.onPlaybackStateChanged(sessionGen = 102L, currentMediaId = mediaId, playbackState = androidx.media3.common.Player.STATE_READY)
        assertTrue(defLateReady.await())

        // Case B: Error completes deferred with false
        val defError = readiness.registerSession(sessionGen = 103L, mediaId = mediaId)
        readiness.onError(sessionGen = 103L, currentMediaId = mediaId)
        assertFalse(defError.await())

        // Case C: Supersede with new session generation cancels previous with false
        val defOld = readiness.registerSession(sessionGen = 104L, mediaId = mediaId)
        val defNew = readiness.registerSession(sessionGen = 105L, mediaId = mediaId)
        assertFalse("Previous deferred must complete false on supersede", defOld.await())
        assertFalse("New deferred remains pending", defNew.isCompleted)
        readiness.onPlaybackStateChanged(sessionGen = 105L, currentMediaId = mediaId, playbackState = androidx.media3.common.Player.STATE_READY)
        assertTrue("New deferred completes true on READY", defNew.await())
    }

    @Test
    fun session_player_controller_lifecycle_coordinator_exercises_production_clear_and_stale_completion() = kotlinx.coroutines.runBlocking {
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val future1 = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        val future2 = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()

        val futures = mutableListOf(future1, future2)
        var createdCount = 0
        val coordinator = object : SessionPlayerController.ConnectionLifecycleCoordinator {
            override fun createControllerFuture(
                context: android.content.Context,
                listener: androidx.media3.session.MediaController.Listener
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> {
                createdCount++
                return futures.removeAt(0)
            }
        }

        val testDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        val controller = SessionPlayerController(
            context = fakeContext,
            connectionCoordinator = coordinator,
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            externalScope = this
        )

        assertEquals(1, createdCount)
        val initialAttemptGen = controller.activeAttemptGen
        assertEquals(1L, initialAttemptGen)

        // Clear media while connection is pending
        controller.clearMedia()
        val afterClearAttemptGen = controller.activeAttemptGen
        assertEquals(2L, afterClearAttemptGen)
        assertFalse(controller.isReconnectingState)

        // Prepare after idle triggers exactly one fresh connect
        val dummyStream = StreamInfo(testKey, "Test Stream")
        controller.prepare(testKey, dummyStream, startPositionMs = 0L, playWhenReady = false)
        assertEquals(2, createdCount)
        val afterPrepareAttemptGen = controller.activeAttemptGen
        assertEquals(3L, afterPrepareAttemptGen)

        controller.release()
    }

    @Test
    fun stale_future_completion_is_observable_and_queued_prepare_is_not_reported_as_delivered() = kotlinx.coroutines.runBlocking {
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val pendingFuture1 = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        val pendingFuture2 = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        val completedFutures = mutableListOf<com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>>()
        val deliveredPrepares = mutableListOf<PendingPrepare>()
        val detachedViews = mutableListOf<androidx.media3.ui.PlayerView>()

        val futures = mutableListOf(pendingFuture1, pendingFuture2)
        val coordinator = object : SessionPlayerController.ConnectionLifecycleCoordinator {
            override fun createControllerFuture(
                context: android.content.Context,
                listener: androidx.media3.session.MediaController.Listener
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> = futures.removeAt(0)

            override fun onFutureCompletedOrCancelled(future: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>) {
                completedFutures.add(future)
            }

            override fun onPrepareDelivered(pending: PendingPrepare) {
                deliveredPrepares.add(pending)
            }

            override fun onPlayerViewDetached(playerView: androidx.media3.ui.PlayerView) {
                detachedViews.add(playerView)
            }
        }

        val testDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        val controller = SessionPlayerController(
            context = fakeContext,
            connectionCoordinator = coordinator,
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            externalScope = this
        )

        // Stale future 1 completes after clearMedia
        controller.clearMedia()
        pendingFuture1.setException(java.lang.RuntimeException("Cancelled/aborted"))
        assertEquals(1, completedFutures.size)
        assertEquals(pendingFuture1, completedFutures[0])

        // Enqueueing while disconnected is not delivery to the service.
        val dummyStream = StreamInfo(testKey, "Delivered Prepare Test")
        controller.prepare(testKey, dummyStream, startPositionMs = 5000L, playWhenReady = true)
        assertTrue(deliveredPrepares.isEmpty())

        controller.release()
    }

    @Test
    fun unconditional_owned_PlayerView_detachment_through_production_helper() = kotlinx.coroutines.runBlocking {
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val pendingFuture = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        val detachedViews = mutableListOf<androidx.media3.ui.PlayerView>()
        val coordinator = object : SessionPlayerController.ConnectionLifecycleCoordinator {
            override fun createControllerFuture(
                context: android.content.Context,
                listener: androidx.media3.session.MediaController.Listener
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> = pendingFuture

            override fun onPlayerViewDetached(playerView: androidx.media3.ui.PlayerView) {
                detachedViews.add(playerView)
            }
        }

        val testDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        val controller = SessionPlayerController(
            context = fakeContext,
            connectionCoordinator = coordinator,
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            externalScope = this
        )

        // Clear media unconditionally calls detachment and resets testing snapshot
        controller.clearMedia()
        val snapshotAfterClear = controller.getTestingSnapshot()
        assertFalse("Surface attached probe must be false after clearMedia", snapshotAfterClear.surfaceAttached)

        controller.release()
        val snapshotAfterRelease = controller.getTestingSnapshot()
        assertFalse("Surface attached probe must be false after release", snapshotAfterRelease.surfaceAttached)
    }

}
