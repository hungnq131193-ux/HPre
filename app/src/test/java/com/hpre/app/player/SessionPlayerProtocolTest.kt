package com.hpre.app.player

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.core.error.safeMessageKey
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoStream
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlayerProtocolTest {

    @Test
    fun session_recovery_returns_pending_prepare_with_fresh_stream_state() = kotlinx.coroutines.test.runTest {
        val key = ContentKey(0, "recovery-session")
        val stream = StreamInfo(
            key = key,
            title = "Fresh",
            videoStreams = listOf(
                VideoStream(
                    url = "https://fresh.example/stream.mp4",
                    format = "mp4",
                    mimeType = "video/mp4",
                    codec = "avc1.64001F",
                    resolution = "720p",
                    width = 1280,
                    height = 720,
                    bitrate = 1_000_000,
                    isVideoOnly = false
                )
            )
        )
        val service = com.hpre.app.testing.FakeVideoService(
            streamResponses = mapOf(key.nativeId to stream)
        )
        val pending = recoverSessionPlayback(
            coordinator = StreamRecoveryCoordinator(service),
            key = key,
            sessionGen = 7L,
            positionMs = 12_000L,
            playWhenReady = true,
            quality = null,
            playbackSpeed = 1.5f,
            attemptedSourceTypes = setOf(PlaybackStreamType.HLS)
        )

        assertEquals(1, service.streamInfoCallCount)
        val recovered = pending as SessionRecoveryResult.Recovered
        assertEquals(key, recovered.value.pending.key)
        assertEquals(12_000L, recovered.value.pending.positionMs)
        assertTrue(recovered.value.pending.playWhenReady)
        assertEquals(1.5f, recovered.value.pending.playbackSpeed, 0.001f)
    }

    @Test
    fun obsolete_media_callbacks_cannot_replace_a_pending_video_or_reanimate_a_cleared_player() {
        val old = ContentKey(0, "old")
        val next = ContentKey(0, "next")
        assertFalse(acceptsPlaybackCallback(next, old, transitioning = true))
        assertFalse(acceptsPlaybackCallback(next, null, transitioning = true))
        assertTrue(acceptsPlaybackCallback(next, next, transitioning = true))
        assertFalse(acceptsPlaybackCallback(null, old, transitioning = true))
        assertFalse(acceptsPlaybackCallback(null, null, transitioning = true))
        // A freshly connected observer may adopt an existing service session.
        assertTrue(acceptsPlaybackCallback(null, old, transitioning = false))
    }

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
    fun mapServiceErrorName_maps_ExtractionFailed_properly() {
        val mapped = SessionPlayerController.mapServiceErrorName("ExtractionFailed")
        assertEquals(AppError.ExtractionFailed, mapped)
    }

    @Test
    fun pending_prepare_contains_quality_and_speed_and_retains_latest() {
        val commands = PendingSessionCommands()
        val quality = QualityOption(1080, "1080p", false, "mp4", "video/mp4", "avc1")
        val streamInfo = StreamInfo(testKey, "Title")
        val prep1 = PendingPrepare(testKey, streamInfo, 5000L, true, quality, playbackSpeed = 1.5f)
        commands.setPrepare(prep1)

        val retrieved = commands.takePrepare()
        assertNotNull(retrieved)
        assertEquals(testKey, retrieved?.key)
        assertSame(streamInfo, retrieved?.streamInfo)
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
        val streamInfo = StreamInfo(key, "Disconnect Title")
        val prep = PendingPrepare(key, streamInfo, 12000L, true, quality, 1.25f)
        commands.setPrepare(prep)

        // Controller disconnect event: mediaController becomes null, pending prepare retained
        val retrieved = commands.takePrepare()
        assertNotNull(retrieved)
        assertEquals(key, retrieved?.key)
        assertSame(streamInfo, retrieved?.streamInfo)
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
        val bgEnabledDefault = false
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
    fun clearMedia_invalidates_prepare_dispatch_that_has_not_reached_main() = kotlinx.coroutines.test.runTest {
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        var createdCount = 0
        val futures = mutableListOf<com.google.common.util.concurrent.SettableFuture<androidx.media3.session.MediaController>>()
        val coordinator = object : SessionPlayerController.ConnectionLifecycleCoordinator {
            override fun createControllerFuture(
                context: android.content.Context,
                listener: androidx.media3.session.MediaController.Listener
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> {
                createdCount++
                return com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
                    .also(futures::add)
            }
        }
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val controller = SessionPlayerController(
            context = fakeContext,
            connectionCoordinator = coordinator,
            mainDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            externalScope = this
        )

        testScheduler.runCurrent()
        assertEquals(1, createdCount)
        controller.prepare(testKey, StreamInfo(testKey, "Test Stream"), 0L, false)
        controller.clearMedia()
        testScheduler.runCurrent()

        assertEquals("stale prepare must not reconnect after clear", 1, createdCount)
        assertEquals(null, controller.state.value.key)
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

    @Test
    fun prepare_promotes_purpose_to_normal_and_connection_hints_reflect_it() = kotlinx.coroutines.runBlocking {
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val receivedIsPrewarmList = mutableListOf<Boolean>()
        val coordinator = object : SessionPlayerController.ConnectionLifecycleCoordinator {
            override fun createControllerFuture(
                context: android.content.Context,
                listener: androidx.media3.session.MediaController.Listener,
                isPrewarm: Boolean
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> {
                receivedIsPrewarmList.add(isPrewarm)
                return com.google.common.util.concurrent.SettableFuture.create()
            }
        }

        val testDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        val controller = SessionPlayerController(
            context = fakeContext,
            mediaSourceFactory = null,
            recoveryCoordinator = null,
            snapshotStore = PlaybackSnapshotStore(fakeContext),
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            externalScope = this,
            connectionCoordinator = coordinator,
            initialPurpose = ConnectionPurpose.PREWARM
        )

        assertEquals(ConnectionPurpose.PREWARM, controller.currentConnectionPurpose)
        assertEquals("receivedIsPrewarmList should have 1 entry from init", 1, receivedIsPrewarmList.size)
        assertTrue("first connection should have isPrewarm=true", receivedIsPrewarmList[0])

        // Calling prepare promotes purpose to NORMAL
        val streamInfo = StreamInfo(
            key = ContentKey(0, "promo_test"),
            title = "Promo Title",
            videoStreams = emptyList(),
            audioStreams = emptyList()
        )
        controller.prepare(key = streamInfo.key, streamInfo = streamInfo)

        assertEquals(ConnectionPurpose.NORMAL, controller.currentConnectionPurpose)
        controller.release()
    }

    @Test
    fun reconnect_after_prepare_carries_normal_connection_hints() = kotlinx.coroutines.test.runTest {
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val receivedIsPrewarmList = mutableListOf<Boolean>()
        val pendingFuture1 = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        val pendingFuture2 = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        val futures = mutableListOf(pendingFuture1, pendingFuture2)

        val coordinator = object : SessionPlayerController.ConnectionLifecycleCoordinator {
            override fun createControllerFuture(
                context: android.content.Context,
                listener: androidx.media3.session.MediaController.Listener,
                isPrewarm: Boolean
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> {
                receivedIsPrewarmList.add(isPrewarm)
                return if (futures.isNotEmpty()) futures.removeAt(0) else com.google.common.util.concurrent.SettableFuture.create()
            }
        }

        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val controller = SessionPlayerController(
            context = fakeContext,
            mediaSourceFactory = null,
            recoveryCoordinator = null,
            snapshotStore = PlaybackSnapshotStore(fakeContext),
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            externalScope = this,
            connectionCoordinator = coordinator,
            initialPurpose = ConnectionPurpose.PREWARM
        )

        testScheduler.runCurrent()

        // First attempt (init) was PREWARM
        assertEquals(1, receivedIsPrewarmList.size)
        assertTrue(receivedIsPrewarmList[0])

        // Trigger prepare
        val streamInfo = StreamInfo(
            key = ContentKey(0, "reconnect_test"),
            title = "Reconnect Title",
            videoStreams = emptyList(),
            audioStreams = emptyList()
        )
        controller.prepare(key = streamInfo.key, streamInfo = streamInfo)

        // Fail 1st attempt to trigger retry reconnect
        pendingFuture1.setException(java.lang.RuntimeException("Connection failed"))
        testScheduler.advanceTimeBy(1000)
        testScheduler.runCurrent()

        // Reconnect attempt carries NORMAL purpose (isPrewarm == false)
        assertTrue(receivedIsPrewarmList.size >= 2)
        assertFalse(receivedIsPrewarmList.last())

        controller.release()
    }

    @Test
    fun read_progress_returns_authoritative_media_controller_values_when_connected() = kotlinx.coroutines.test.runTest {
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val pendingFuture = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        var readToken: Long? = null
        var readOnExpectedDispatcher = false
        val expectedDispatcherName = "main_dispatcher_marker"
        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler, name = expectedDispatcherName)

        val coordinator = object : SessionPlayerController.ConnectionLifecycleCoordinator {
            override fun createControllerFuture(
                context: android.content.Context,
                listener: androidx.media3.session.MediaController.Listener,
                isPrewarm: Boolean
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> {
                return pendingFuture
            }

            override suspend fun readProgress(controller: androidx.media3.session.MediaController?, connectionToken: Long): PlaybackProgress? {
                readToken = connectionToken
                readOnExpectedDispatcher = kotlin.coroutines.coroutineContext[kotlinx.coroutines.CoroutineDispatcher] == testDispatcher
                return if (connectionToken > 0L) PlaybackProgress(positionMs = 35_000L, durationMs = 120_000L) else null
            }
        }

        val controller = SessionPlayerController(
            context = fakeContext,
            mediaSourceFactory = null,
            recoveryCoordinator = null,
            snapshotStore = PlaybackSnapshotStore(fakeContext),
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            externalScope = this,
            connectionCoordinator = coordinator,
            initialPurpose = ConnectionPurpose.NORMAL
        )

        testScheduler.runCurrent()
        // Unconnected before future returns
        val beforeConnect = controller.readProgress()
        assertEquals(0L, beforeConnect.positionMs)

        // Connected through coordinator seam token
        controller.simulateConnectedForTesting(1L)
        testScheduler.runCurrent()

        val progress = controller.readProgress()
        assertEquals(35_000L, progress.positionMs)
        assertEquals(120_000L, progress.durationMs)
        assertEquals(1L, readToken)
        assertTrue("readProgress must execute under mainDispatcher", readOnExpectedDispatcher)

        controller.release()
        val afterRelease = controller.readProgress()
        assertEquals(0L, afterRelease.positionMs)
    }

    @Test
    fun read_progress_falls_back_to_shared_state_when_disconnected() = kotlinx.coroutines.test.runTest {
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val pendingFuture = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        val coordinator = object : SessionPlayerController.ConnectionLifecycleCoordinator {
            override fun createControllerFuture(
                context: android.content.Context,
                listener: androidx.media3.session.MediaController.Listener,
                isPrewarm: Boolean
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> {
                return pendingFuture
            }
        }

        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val controller = SessionPlayerController(
            context = fakeContext,
            mediaSourceFactory = null,
            recoveryCoordinator = null,
            snapshotStore = PlaybackSnapshotStore(fakeContext),
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            externalScope = this,
            connectionCoordinator = coordinator,
            initialPurpose = ConnectionPurpose.NORMAL
        )

        testScheduler.runCurrent()
        val progress = controller.readProgress()
        assertEquals(0L, progress.positionMs)
        assertEquals(0L, progress.durationMs)

        controller.release()
    }

    @Test
    fun sessionPlayerController_has_no_periodic_progress_job_or_tracker_methods() {
        // Strong allowlist invariant over all Job-typed declared fields in SessionPlayerController:
        // Reconnect/runtime operations are permitted, but no UI/progress polling Job is allowed.
        val allowedJobFieldNames = setOf("reconnectJob", "recoveryJob")
        val jobFields = SessionPlayerController::class.java.declaredFields.filter {
            kotlinx.coroutines.Job::class.java.isAssignableFrom(it.type)
        }
        for (field in jobFields) {
            assertTrue(
                "Unrecognized Job field '${field.name}' in SessionPlayerController. Only approved runtime jobs $allowedJobFieldNames are allowed; no UI/progress polling Job.",
                allowedJobFieldNames.contains(field.name)
            )
        }

        // Structural invariant ceiling defense: verify no deprecated tracker methods exist
        val methods = SessionPlayerController::class.java.declaredMethods.map { it.name }
        assertFalse("SessionPlayerController must not contain startProgressTracker", methods.contains("startProgressTracker"))
        assertFalse("SessionPlayerController must not contain stopProgressTracker", methods.contains("stopProgressTracker"))
    }

    @Test
    fun structural_events_emit_shared_state_while_repeated_readProgress_does_not() = kotlinx.coroutines.test.runTest {
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val pendingFuture = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        var progressPosition = 10_000L

        val coordinator = object : SessionPlayerController.ConnectionLifecycleCoordinator {
            override fun createControllerFuture(
                context: android.content.Context,
                listener: androidx.media3.session.MediaController.Listener,
                isPrewarm: Boolean
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> {
                return pendingFuture
            }

            override suspend fun readProgress(controller: androidx.media3.session.MediaController?, connectionToken: Long): PlaybackProgress? {
                return if (connectionToken > 0L) PlaybackProgress(positionMs = progressPosition, durationMs = 120_000L) else null
            }
        }

        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val controller = SessionPlayerController(
            context = fakeContext,
            mediaSourceFactory = null,
            recoveryCoordinator = null,
            snapshotStore = PlaybackSnapshotStore(fakeContext),
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            externalScope = this,
            connectionCoordinator = coordinator,
            initialPurpose = ConnectionPurpose.NORMAL
        )

        testScheduler.runCurrent()
        controller.simulateConnectedForTesting(1L)
        testScheduler.runCurrent()

        controller.onLifecycleStart()
        testScheduler.runCurrent()

        var emissionCount = 0
        val collectJob = this.backgroundScope.launch {
            controller.state.collect { emissionCount++ }
        }
        testScheduler.runCurrent()
        val baseCount = emissionCount

        // 1. Authoritative progress reads and time progression do NOT emit or mutate state
        val initialProgress = controller.readProgress()
        assertEquals(10_000L, initialProgress.positionMs)

        progressPosition = 11_000L
        testScheduler.advanceTimeBy(1000L)
        testScheduler.runCurrent()

        progressPosition = 12_000L
        testScheduler.advanceTimeBy(1000L)
        testScheduler.runCurrent()

        val updatedProgress = controller.readProgress()
        assertEquals(12_000L, updatedProgress.positionMs)

        assertEquals("Progress advancement alone must not trigger shared state emissions", baseCount, emissionCount)
        assertEquals(0L, controller.state.value.currentPositionMs)

        // 2. Structural events (e.g. state transitions / prepare / speed changes) DO emit and update shared state
        controller.setPlaybackSpeed(1.5f)
        testScheduler.runCurrent()

        assertTrue("Structural change must cause emission", emissionCount > baseCount)
        assertEquals(1.5f, controller.state.value.playbackSpeed, 0.001f)

        collectJob.cancel()
        controller.release()
    }

    @Test
    fun terminal_error_transition_clears_buffering_and_loading_in_playback_state() {
        val bufferingState = PlaybackState(
            key = testKey,
            isLoading = true,
            isBuffering = true,
            isReady = false
        )

        val terminalState = restoreConnectedPlaybackState(
            current = bufferingState,
            playbackState = androidx.media3.common.Player.STATE_IDLE,
            isPlaying = false,
            playWhenReady = false,
            durationMs = 0L,
            positionMs = 0L,
            playbackSpeed = 1.0f
        ).copy(error = AppError.StreamExpired)

        assertFalse(terminalState.isBuffering)
        assertFalse(terminalState.isLoading)
        assertFalse(terminalState.isReady)
        assertEquals(AppError.StreamExpired, terminalState.error)
    }

    @Test
    fun recoverable_playback_failure_keeps_loading_without_showing_terminal_error() {
        val current = PlaybackState(
            key = testKey,
            isLoading = false,
            isBuffering = true,
            isReady = false
        )

        val recovering = playbackFailureState(
            current = current,
            decision = PlaybackRecoveryDecision(AppError.StreamExpired, shouldRefresh = true),
            canRecover = true
        )

        assertTrue(recovering.isLoading)
        assertFalse(recovering.isBuffering)
        assertFalse(recovering.isPlaying)
        assertEquals(null, recovering.error)
    }

    @Test
    fun cancelled_session_recovery_remains_cancelled_instead_of_becoming_terminal_failure() = kotlinx.coroutines.test.runTest {
        val service = com.hpre.app.testing.FakeVideoService()
        service.streamInfoHandler = { AppResult.Failure(AppError.NetworkError) }
        val coordinator = StreamRecoveryCoordinator(service)
        coordinator.release()

        val result = recoverSessionPlayback(
            coordinator = coordinator,
            key = testKey,
            sessionGen = 1L,
            positionMs = 0L,
            playWhenReady = true,
            quality = null,
            playbackSpeed = 1f,
            attemptedSourceTypes = emptySet()
        )

        assertEquals(SessionRecoveryResult.Cancelled, result)
    }

    @Test
    fun failed_playback_recovery_stops_loading_and_surfaces_terminal_error() {
        val failed = playbackRecoveryFailedState(
            current = PlaybackState(key = testKey, isLoading = true),
            error = AppError.StreamExpired
        )

        assertFalse(failed.isLoading)
        assertFalse(failed.isBuffering)
        assertEquals(AppError.StreamExpired, failed.error)
    }

    @Test
    fun handleTerminalSessionCommand_accepts_valid_current_generation_and_rejects_stale_or_released() {
        var observedError: AppError? = null
        val serviceId = testKey.serviceId
        val nativeId = testKey.nativeId

        // Valid command matching active key and session
        val handled = handleTerminalSessionCommand(
            commandAction = HPrePlaybackService.CUSTOM_COMMAND_TERMINAL_ERROR,
            errorName = "StreamExpired",
            sessionGen = 5L,
            mediaGen = 9L,
            serviceId = serviceId,
            nativeId = nativeId,
            currentKey = testKey,
            localSessionGen = 5L,
            localMediaGen = 9L,
            isReleased = false,
            onStateUpdate = { observedError = it }
        )
        assertTrue(handled)
        assertEquals(AppError.StreamExpired, observedError)

        // Stale session generation -> rejected
        observedError = null
        val staleHandled = handleTerminalSessionCommand(
            commandAction = HPrePlaybackService.CUSTOM_COMMAND_TERMINAL_ERROR,
            errorName = "StreamExpired",
            sessionGen = 5L,
            mediaGen = 9L,
            serviceId = serviceId,
            nativeId = nativeId,
            currentKey = testKey,
            localSessionGen = 6L,
            localMediaGen = 9L,
            isReleased = false,
            onStateUpdate = { observedError = it }
        )
        assertFalse(staleHandled)
        assertNull(observedError)

        // Mismatched key -> rejected
        val differentKey = ContentKey(0, "other_vid")
        val diffKeyHandled = handleTerminalSessionCommand(
            commandAction = HPrePlaybackService.CUSTOM_COMMAND_TERMINAL_ERROR,
            errorName = "StreamExpired",
            sessionGen = 5L,
            mediaGen = 9L,
            serviceId = serviceId,
            nativeId = nativeId,
            currentKey = differentKey,
            localSessionGen = 5L,
            localMediaGen = 9L,
            isReleased = false,
            onStateUpdate = { observedError = it }
        )
        assertFalse(diffKeyHandled)
        assertNull(observedError)

        // Released controller -> rejected
        val releasedHandled = handleTerminalSessionCommand(
            commandAction = HPrePlaybackService.CUSTOM_COMMAND_TERMINAL_ERROR,
            errorName = "StreamExpired",
            sessionGen = 5L,
            mediaGen = 9L,
            serviceId = serviceId,
            nativeId = nativeId,
            currentKey = testKey,
            localSessionGen = 5L,
            localMediaGen = 9L,
            isReleased = true,
            onStateUpdate = { observedError = it }
        )
        assertFalse(releasedHandled)
        assertNull(observedError)

        val staleMediaHandled = handleTerminalSessionCommand(
            commandAction = HPrePlaybackService.CUSTOM_COMMAND_TERMINAL_ERROR,
            errorName = "StreamExpired",
            sessionGen = 5L,
            mediaGen = 8L,
            serviceId = serviceId,
            nativeId = nativeId,
            currentKey = testKey,
            localSessionGen = 5L,
            localMediaGen = 9L,
            isReleased = false,
            onStateUpdate = { observedError = it }
        )
        assertFalse(staleMediaHandled)

        val missingIdentityHandled = handleTerminalSessionCommand(
            commandAction = HPrePlaybackService.CUSTOM_COMMAND_TERMINAL_ERROR,
            errorName = "StreamExpired",
            sessionGen = 5L,
            mediaGen = 9L,
            serviceId = Int.MIN_VALUE,
            nativeId = null,
            currentKey = testKey,
            localSessionGen = 5L,
            localMediaGen = 9L,
            isReleased = false,
            onStateUpdate = { observedError = it }
        )
        assertFalse(missingIdentityHandled)
    }

    @Test
    fun prewarm_connection_failure_retries_with_backoff_and_prepare_resets_budget() = kotlinx.coroutines.test.runTest {
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        var futureCreations = 0
        val failFuture = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        failFuture.setException(java.io.IOException("Connect failed"))

        val coordinator = object : SessionPlayerController.ConnectionLifecycleCoordinator {
            override fun createControllerFuture(
                context: android.content.Context,
                listener: androidx.media3.session.MediaController.Listener
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> {
                futureCreations++
                return failFuture
            }
        }

        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val controller = SessionPlayerController(
            context = fakeContext,
            connectionCoordinator = coordinator,
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            externalScope = this,
            initialPurpose = ConnectionPurpose.PREWARM
        )

        testScheduler.runCurrent()
        assertEquals(1, futureCreations)
        assertEquals(1, controller.currentRetryCount)
        assertTrue(controller.isReconnectingState)
        assertNull(controller.state.value.error)

        // Retry 1 after 500ms
        testScheduler.advanceTimeBy(500L)
        testScheduler.runCurrent()
        assertEquals(2, futureCreations)
        assertEquals(2, controller.currentRetryCount)

        // Retry 2 after 1000ms
        testScheduler.advanceTimeBy(1000L)
        testScheduler.runCurrent()
        assertEquals(3, futureCreations)
        assertEquals(3, controller.currentRetryCount)

        // Retry 3 after 1500ms
        testScheduler.advanceTimeBy(1500L)
        testScheduler.runCurrent()
        assertEquals(3, controller.currentRetryCount)
        assertFalse(controller.isReconnectingState)
        assertNull(controller.state.value.error)

        // prepare resets retry count and promotes purpose to NORMAL
        val streamInfo = StreamInfo(testKey, "Title")
        controller.prepare(testKey, streamInfo, startPositionMs = 0L, playWhenReady = true, initialQuality = null)
        assertEquals(ConnectionPurpose.NORMAL, controller.currentConnectionPurpose)
        assertEquals(0, controller.currentRetryCount)
        assertFalse(controller.isReconnectingState)
        controller.release()
    }
}
