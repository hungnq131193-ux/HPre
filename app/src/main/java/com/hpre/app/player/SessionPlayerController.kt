package com.hpre.app.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ExecutionException

internal fun restoreConnectedPlaybackState(
    current: PlaybackState,
    playbackState: Int,
    isPlaying: Boolean,
    playWhenReady: Boolean,
    durationMs: Long,
    positionMs: Long,
    playbackSpeed: Float
): PlaybackState = current.copy(
    isPlaying = isPlaying,
    playWhenReady = playWhenReady,
    isReady = playbackState == Player.STATE_READY,
    isLoading = current.isLoading && playbackState == Player.STATE_IDLE,
    isBuffering = playbackState == Player.STATE_BUFFERING,
    isEnded = playbackState == Player.STATE_ENDED,
    durationMs = durationMs.coerceAtLeast(0L),
    currentPositionMs = positionMs.coerceAtLeast(0L),
    playbackSpeed = playbackSpeed.takeIf { it > 0f } ?: current.playbackSpeed
)

internal fun acceptsPlaybackCallback(expectedKey: ContentKey?, eventKey: ContentKey?, transitioning: Boolean): Boolean =
    if (expectedKey != null) expectedKey == eventKey else !transitioning

internal data class SessionRecovery(
    val streamInfo: StreamInfo,
    val pending: PendingPrepare
)

internal suspend fun recoverSessionPlayback(
    coordinator: StreamRecoveryCoordinator,
    key: ContentKey,
    sessionGen: Long,
    positionMs: Long,
    playWhenReady: Boolean,
    quality: QualityOption?,
    playbackSpeed: Float,
    attemptedSourceTypes: Set<PlaybackStreamType>
): SessionRecovery? {
    val preference = quality?.let(QualityPreference::SpecificOption) ?: QualityPreference.Auto
    return when (
        val recovered = coordinator.recoverExpiredStream(
            key = key,
            sessionGen = sessionGen,
            positionMs = positionMs,
            wasPlaying = playWhenReady,
            preference = preference,
            attemptedSourceTypes = attemptedSourceTypes
        )
    ) {
        is RecoveryResult.Recovered -> SessionRecovery(
            streamInfo = recovered.streamInfo,
            pending = PendingPrepare(
                key = recovered.key,
                positionMs = recovered.resumePositionMs,
                playWhenReady = recovered.resumeWhenReady,
                initialQuality = recovered.selectedQuality,
                playbackSpeed = playbackSpeed
            )
        )
        is RecoveryResult.Failed,
        RecoveryResult.Cancelled -> null
    }
}

@OptIn(UnstableApi::class)
class SessionPlayerController internal constructor(
    private val context: Context,
    val mediaSourceFactory: MediaSourceCreator? = null,
    val recoveryCoordinator: StreamRecoveryCoordinator? = null,
    val snapshotStore: PlaybackSnapshotStore = PlaybackSnapshotStore(context),
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val externalScope: CoroutineScope? = null,
    internal val connectionCoordinator: ConnectionLifecycleCoordinator? = null,
    initialPurpose: ConnectionPurpose = ConnectionPurpose.NORMAL
) : PlayerController, PlayerIntegrationProbe {

    internal constructor(
        context: Context,
        mediaSourceFactory: MediaSourceCreator? = null,
        recoveryCoordinator: StreamRecoveryCoordinator? = null,
        snapshotStore: PlaybackSnapshotStore = PlaybackSnapshotStore(context),
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        externalScope: CoroutineScope? = null,
        initialPurpose: ConnectionPurpose = ConnectionPurpose.NORMAL
    ) : this(
        context = context,
        mediaSourceFactory = mediaSourceFactory,
        recoveryCoordinator = recoveryCoordinator,
        snapshotStore = snapshotStore,
        mainDispatcher = mainDispatcher,
        ioDispatcher = ioDispatcher,
        externalScope = externalScope,
        connectionCoordinator = null,
        initialPurpose = initialPurpose
    )

    internal constructor(
        context: Context,
        connectionCoordinator: ConnectionLifecycleCoordinator,
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        externalScope: CoroutineScope? = null,
        initialPurpose: ConnectionPurpose = ConnectionPurpose.NORMAL
    ) : this(
        context = context,
        mediaSourceFactory = null,
        recoveryCoordinator = null,
        snapshotStore = PlaybackSnapshotStore(context),
        mainDispatcher = mainDispatcher,
        ioDispatcher = ioDispatcher,
        externalScope = externalScope,
        connectionCoordinator = connectionCoordinator,
        initialPurpose = initialPurpose
    )

    private val scope = externalScope ?: CoroutineScope(mainDispatcher + Job())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    @Volatile
    private var connectionPurpose: ConnectionPurpose = initialPurpose
    internal val currentConnectionPurpose: ConnectionPurpose get() = connectionPurpose

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var currentSurfaceView: PlayerView? = null
    private var reconnectJob: Job? = null
    private var isReleased: Boolean = false
    private var backgroundPlaybackEnabled: Boolean = true
    private var enteringPip: Boolean = false
    private var isLifecycleStarted: Boolean = true
    private var connectRetryCount: Int = 0
    private var isReconnecting: Boolean = false
    private var connectionAttemptGeneration: Long = 0L
    private var activeConnectionGeneration: Long = 0L

    internal val activeAttemptGen: Long get() = connectionAttemptGeneration
    internal val activeConnGen: Long get() = activeConnectionGeneration
    internal val isReconnectingState: Boolean get() = isReconnecting
    internal val currentRetryCount: Int get() = connectRetryCount

    private var currentStreamInfo: StreamInfo? = null
    private var currentKey: ContentKey? = null
    private var transitioning: Boolean = false
    private val pendingCommands = PendingSessionCommands()
    private var recoveryJob: Job? = null

    private fun acceptsCurrentPlaybackCallback(): Boolean = !isReleased && acceptsPlaybackCallback(
        currentKey, PlaybackMediaMetadata.from(mediaController?.currentMediaItem)?.key, transitioning
    )

    private var localMediaGen: Long = 0L
    private var localSessionGen: Long = 0L
    private val localPrepareRequestGeneration = AtomicLong(0L)
    private var localQualityRequestGen: Long = 0L
    private var localRenderedCount: Int = 0
    private var localAudioDecoderCount: Int = 0
    private var localVideoDecoderCount: Int = 0

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (isReleased) return
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            val metadata = PlaybackMediaMetadata.from(mediaItem)
            if (isReleased || !acceptsPlaybackCallback(currentKey, metadata?.key, transitioning)) return
            if (metadata?.key != null) transitioning = false
            val streamType = mediaItem?.mediaMetadata?.extras?.getString("hpre_stream_type")
                ?.let { runCatching { PlaybackStreamType.valueOf(it) }.getOrNull() }
            _state.update {
                it.copy(
                    key = metadata?.key,
                    title = metadata?.title,
                    streamType = streamType,
                    error = null
                )
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if (!acceptsCurrentPlaybackCallback()) return
            val decision = PlaybackRecoveryPolicy.decide(error)
            val snapshot = _state.value.let {
                it.key?.let { key ->
                    RetrySnapshot(
                        key = key,
                        sessionGen = localSessionGen,
                        positionMs = mediaController?.currentPosition?.coerceAtLeast(0L) ?: it.currentPositionMs,
                        userRequestedPlay = it.playWhenReady,
                        selectedQuality = it.selectedQuality
                    )
                }
            }
            _state.update {
                it.copy(
                    isLoading = false,
                    isBuffering = false,
                    isReady = false,
                    error = decision.error,
                    retrySnapshot = snapshot
                )
            }
            val coordinator = recoveryCoordinator ?: return
            val recoverySnapshot = snapshot ?: return
            if (!decision.shouldRefresh) return
            val expectedRequest = localPrepareRequestGeneration.get()
            val expectedKey = recoverySnapshot.key
            val expectedSession = recoverySnapshot.sessionGen
            val speed = _state.value.playbackSpeed
            val attempted = _state.value.streamType?.let(::setOf).orEmpty()
            recoveryJob?.cancel()
            recoveryJob = scope.launch(mainDispatcher) {
                try {
                    val recovered = recoverSessionPlayback(
                        coordinator = coordinator,
                        key = expectedKey,
                        sessionGen = expectedSession,
                        positionMs = recoverySnapshot.positionMs,
                        playWhenReady = recoverySnapshot.userRequestedPlay,
                        quality = recoverySnapshot.selectedQuality,
                        playbackSpeed = speed,
                        attemptedSourceTypes = attempted
                    ) ?: return@launch
                    if (isReleased || currentKey != expectedKey ||
                        localSessionGen != expectedSession ||
                        localPrepareRequestGeneration.get() != expectedRequest
                    ) return@launch
                    prepareWithSpeed(
                        key = recovered.pending.key,
                        streamInfo = recovered.streamInfo,
                        startPositionMs = recovered.pending.positionMs,
                        playWhenReady = recovered.pending.playWhenReady,
                        initialQuality = recovered.pending.initialQuality,
                        playbackSpeed = recovered.pending.playbackSpeed
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                }
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (!acceptsCurrentPlaybackCallback()) return
            _state.update { it.copy(playbackSpeed = playbackParameters.speed) }
        }

        override fun onRenderedFirstFrame() {
            if (!acceptsCurrentPlaybackCallback()) return
            _state.update { it.copy(hasRenderedFirstFrame = true) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!acceptsCurrentPlaybackCallback()) return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    _state.update { it.copy(isBuffering = true, isLoading = false, isReady = false) }
                }
                Player.STATE_READY -> {
                    _state.update {
                        it.copy(
                            isBuffering = false,
                            isLoading = false,
                            isReady = true,
                            isPlaying = mediaController?.isPlaying ?: false,
                            playWhenReady = mediaController?.playWhenReady ?: false,
                            durationMs = mediaController?.duration?.coerceAtLeast(0L) ?: 0L,
                            currentPositionMs = mediaController?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                            playbackSpeed = mediaController?.playbackParameters?.speed ?: it.playbackSpeed,
                            isEnded = false
                        )
                    }
                }
                Player.STATE_ENDED -> {
                    _state.update { it.copy(isEnded = true, isPlaying = false, isBuffering = false, isReady = false) }
                }
                Player.STATE_IDLE -> {}
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (!acceptsCurrentPlaybackCallback()) return
            val actualPos = mediaController?.currentPosition?.coerceAtLeast(0L) ?: newPosition.positionMs.coerceAtLeast(0L)
            _state.update { it.copy(currentPositionMs = actualPos) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!acceptsCurrentPlaybackCallback()) return
            _state.update {
                it.copy(
                    playWhenReady = playWhenReady,
                    isPlaying = mediaController?.isPlaying ?: false
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!acceptsCurrentPlaybackCallback()) return
            _state.update {
                it.copy(
                    isPlaying = isPlaying,
                    playWhenReady = mediaController?.playWhenReady ?: false
                )
            }
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            if (!acceptsCurrentPlaybackCallback()) return
            val groups = tracks.groups.filter {
                it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && it.isSelected
            }
            val selected = groups.asSequence()
                .flatMap { group ->
                    (0 until group.length).asSequence().map {
                        group.getTrackFormat(it) to group.isTrackSelected(it)
                    }
                }
                .firstOrNull { it.second }
                ?.first
            _state.update { state ->
                state.copy(
                    effectiveTrack = selected?.let { format ->
                        EffectiveTrack(
                            height = format.height.takeIf { value -> value > 0 },
                            bitrate = format.bitrate.takeIf { value -> value > 0 },
                            isAdaptive = (state.streamType == PlaybackStreamType.HLS ||
                                state.streamType == PlaybackStreamType.DASH) &&
                                groups.any { group -> group.length > 1 }
                        )
                    }
                )
            }
        }
    }

    init {
        connectController()
    }

    internal interface ConnectionLifecycleCoordinator {
        fun createControllerFuture(
            context: Context,
            listener: MediaController.Listener
        ): ListenableFuture<MediaController> {
            throw UnsupportedOperationException("Coordinator must implement createControllerFuture")
        }

        fun createControllerFuture(
            context: Context,
            listener: MediaController.Listener,
            isPrewarm: Boolean
        ): ListenableFuture<MediaController> {
            return createControllerFuture(context, listener)
        }

        suspend fun readProgress(controller: MediaController?, connectionToken: Long): PlaybackProgress? = null
        fun onFutureCompletedOrCancelled(future: ListenableFuture<MediaController>) {}
        fun onPrepareDelivered(pending: PendingPrepare) {}
        fun onPlayerViewDetached(playerView: PlayerView) {}
    }

    private fun connectController() {
        if (isReleased) return
        val attemptToken = ++connectionAttemptGeneration
        scope.launch(mainDispatcher) {
            if (isReleased || attemptToken != connectionAttemptGeneration) return@launch
            var capturedController: MediaController? = null
            val controllerListener = object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    val targetController = capturedController ?: controller
                    scope.launch(mainDispatcher) {
                        handleControllerDisconnected(attemptToken, targetController)
                    }
                }
            }
            val isPrewarm = connectionPurpose == ConnectionPurpose.PREWARM
            val future = if (connectionCoordinator != null) {
                connectionCoordinator.createControllerFuture(context.applicationContext, controllerListener, isPrewarm)
            } else {
                val sessionToken = SessionToken(
                    context.applicationContext,
                    ComponentName(context.applicationContext, HPrePlaybackService::class.java)
                )
                val connectionHints = createConnectionHints(isPrewarm)
                MediaController.Builder(context.applicationContext, sessionToken)
                    .setConnectionHints(connectionHints)
                    .setListener(controllerListener)
                    .buildAsync()
            }
            if (isReleased || attemptToken != connectionAttemptGeneration) {
                MediaController.releaseFuture(future)
                return@launch
            }
            controllerFuture = future
            future.addListener({
                connectionCoordinator?.onFutureCompletedOrCancelled(future)
                try {
                    val controller = future.get()
                    capturedController = controller
                    if (isReleased || attemptToken != connectionAttemptGeneration) {
                        controller.release()
                        return@addListener
                    }
                    connectRetryCount = 0
                    isReconnecting = false
                    reconnectJob?.cancel()
                    reconnectJob = null
                    mediaController = controller
                    activeConnectionGeneration = attemptToken
                    controller.addListener(playerListener)

                    currentSurfaceView?.let { pv ->
                        if (pv.player != controller) {
                            pv.player = controller
                        }
                    }

                    // Restore state from snapshot or controller
                    val duration = controller.duration.coerceAtLeast(0L)
                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    val metadata = PlaybackMediaMetadata.from(controller.currentMediaItem)
                    _state.update {
                        if (!acceptsPlaybackCallback(currentKey, metadata?.key, transitioning)) return@update it
                        restoreConnectedPlaybackState(
                            current = it.copy(
                            key = metadata?.key ?: it.key,
                                title = metadata?.title ?: it.title
                            ),
                            playbackState = controller.playbackState,
                            isPlaying = controller.isPlaying,
                            playWhenReady = controller.playWhenReady,
                            durationMs = duration,
                            positionMs = pos,
                            playbackSpeed = controller.playbackParameters.speed
                        )
                    }
                    applyVideoTrackPolicy(controller)
                    val policyArgs = Bundle().apply {
                        putBoolean(HPrePlaybackService.EXTRA_BACKGROUND_ENABLED, backgroundPlaybackEnabled)
                        putBoolean(HPrePlaybackService.EXTRA_PIP_ACTIVE_OR_ENTERING, enteringPip)
                    }
                    controller.sendCustomCommand(
                        SessionCommand(HPrePlaybackService.CUSTOM_COMMAND_SET_BACKGROUND_ENABLED, Bundle.EMPTY),
                        policyArgs
                    )
                    pendingCommands.takePrepare()?.let(::sendPrepare)
                } catch (_: java.util.concurrent.CancellationException) {
                    if (attemptToken == connectionAttemptGeneration) {
                        controllerFuture = null
                        handleConnectFailure()
                    }
                } catch (_: ExecutionException) {
                    if (attemptToken == connectionAttemptGeneration) {
                        controllerFuture = null
                        handleConnectFailure()
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    if (attemptToken == connectionAttemptGeneration) {
                        controllerFuture = null
                        handleConnectFailure()
                    }
                }
            }, { r -> scope.launch(mainDispatcher) { r.run() } })
        }
    }

    internal fun simulateDisconnectedForToken(attemptToken: Long, controller: MediaController? = null) {
        handleControllerDisconnected(attemptToken, controller ?: mediaController)
    }

    internal fun simulateConnectedForTesting(attemptToken: Long) {
        activeConnectionGeneration = attemptToken
    }

    private fun handleControllerDisconnected(attemptToken: Long, controller: MediaController?) {
        if (isReleased || isReconnecting) return
        val activeCtrl = mediaController
        if (activeConnectionGeneration == 0L || activeCtrl == null) {
            return
        }
        if (attemptToken != activeConnectionGeneration || controller != activeCtrl) {
            return
        }
        connectionAttemptGeneration++
        activeConnectionGeneration = 0L
        isReconnecting = false
        currentSurfaceView?.player = null
        mediaController?.removeListener(playerListener)
        mediaController = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null

        // Preserve current state for replay
        val currentK = _state.value.key
        val currentInfo = currentStreamInfo
        var hasWork = false
        if (currentK != null && currentInfo != null) {
            val pending = PendingPrepare(
                key = currentK,
                positionMs = _state.value.currentPositionMs,
                playWhenReady = _state.value.playWhenReady,
                initialQuality = _state.value.selectedQuality,
                playbackSpeed = _state.value.playbackSpeed
            )
            pendingCommands.setPrepare(pending)
            hasWork = true
        } else if (pendingCommands.takePrepare()?.also(pendingCommands::setPrepare) != null) {
            hasWork = true
        }

        // Bounded reconnect attempt only if we have pending or active playback to restore
        if (hasWork) {
            isReconnecting = true
            triggerBoundedReconnect()
        }
    }

    private fun triggerBoundedReconnect() {
        if (isReleased) return
        reconnectJob?.cancel()
        if (connectRetryCount < 3) {
            connectRetryCount++
            reconnectJob = scope.launch(mainDispatcher) {
                delay(500L * connectRetryCount)
                if (!isReleased && mediaController == null) {
                    connectController()
                }
            }
        } else {
            isReconnecting = false
            reconnectJob = null
        }
    }

    private fun handleConnectFailure() {
        if (isReleased) return
        _state.update { it.copy(isLoading = false, error = AppError.NetworkError) }
        isReconnecting = true
        reconnectJob?.cancel()
        if (connectRetryCount < 3) {
            connectRetryCount++
            reconnectJob = scope.launch(mainDispatcher) {
                delay(500L * connectRetryCount)
                if (!isReleased && mediaController == null) {
                    connectController()
                }
            }
        } else {
            isReconnecting = false
            reconnectJob = null
        }
    }

    private inline fun <reified T : Throwable> findCause(throwable: Throwable?): T? {
        var current = throwable
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    override fun attachSurface(playerView: PlayerView) {
        if (isReleased) return
        scope.launch(mainDispatcher) {
            if (isReleased) return@launch
            currentSurfaceView = playerView
            val controller = mediaController
            if (controller != null && playerView.player != controller) {
                playerView.player = controller
            }
        }
    }

    @Volatile
    private var currentSurfaceLease: SurfaceLease = SurfaceLease(SurfaceOwner.NONE, 0L)

    override fun attachSurface(playerView: PlayerView, lease: SurfaceLease): Boolean {
        if (isReleased || lease.generation < currentSurfaceLease.generation) return false
        currentSurfaceLease = lease
        scope.launch(mainDispatcher) {
            if (isReleased || currentSurfaceLease != lease) return@launch
            val previousView = currentSurfaceView
            currentSurfaceView = playerView
            val controller = mediaController
            if (controller != null && playerView.player != controller) playerView.player = controller
            if (previousView != null && previousView !== playerView && previousView.player == controller) {
                previousView.player = null
            }
        }

        return true
    }

    override fun detachSurface(playerView: PlayerView) {
        scope.launch(mainDispatcher) {
            if (isReleased) return@launch
            if (currentSurfaceView == playerView) {
                currentSurfaceView = null
                if (playerView.player == mediaController) {
                    playerView.player = null
                }
            } else if (playerView.player == mediaController) {
                playerView.player = null
            }
        }
    }

    override fun detachSurface(playerView: PlayerView, lease: SurfaceLease): Boolean {
        if (lease != currentSurfaceLease || currentSurfaceView != playerView) {
            // A disposed host may still retain a reference after a newer host won the lease. Clear
            // only that stale PlayerView; never touch the active owner or lease.
            scope.launch(mainDispatcher) {
                if (playerView !== currentSurfaceView && playerView.player == mediaController) {
                    playerView.player = null
                }
            }
            return false
        }
        scope.launch(mainDispatcher) {
            if (isReleased || lease != currentSurfaceLease || currentSurfaceView != playerView) return@launch
            currentSurfaceView = null
            if (playerView.player == mediaController) playerView.player = null
        }
        return true
    }

    override fun onLifecycleStart() {
        enteringPip = false
        isLifecycleStarted = true
        scope.launch(mainDispatcher) { applyVideoTrackPolicy() }
    }

    override fun onLifecycleStop() {
        onLifecycleStop(isChangingConfigurations = false, isInPip = false)
    }

    fun onLifecycleStop(isChangingConfigurations: Boolean, isInPip: Boolean = false) {
        enteringPip = isInPip
        val continueBg = PlaybackPolicy.shouldContinueInBackground(
            backgroundEnabled = backgroundPlaybackEnabled,
            enteringPip = enteringPip,
            isChangingConfigurations = isChangingConfigurations
        )
        if (!continueBg) {
            isLifecycleStarted = false
            pause()
            clearMedia()
        } else {
            if (!isChangingConfigurations) {
                isLifecycleStarted = false
            }
            scope.launch(mainDispatcher) {
                applyVideoTrackPolicy(isChangingConfigurations = isChangingConfigurations)
            }
        }
    }

    fun updateLifecyclePolicy(
        backgroundEnabled: Boolean,
        pipActiveOrEntering: Boolean,
        isChangingConfigurations: Boolean = false
    ) {
        backgroundPlaybackEnabled = backgroundEnabled
        enteringPip = pipActiveOrEntering
        scope.launch(mainDispatcher) {
            val controller = mediaController ?: return@launch
            applyVideoTrackPolicy(controller, isChangingConfigurations)
            val args = Bundle().apply {
                putBoolean(HPrePlaybackService.EXTRA_BACKGROUND_ENABLED, backgroundEnabled)
                putBoolean(HPrePlaybackService.EXTRA_PIP_ACTIVE_OR_ENTERING, pipActiveOrEntering)
            }
            controller.sendCustomCommand(
                SessionCommand(HPrePlaybackService.CUSTOM_COMMAND_SET_BACKGROUND_ENABLED, Bundle.EMPTY),
                args
            )
        }
    }

    private fun applyVideoTrackPolicy(
        controller: MediaController? = mediaController,
        isChangingConfigurations: Boolean = false
    ) {
        val activeController = controller ?: return
        if (!activeController.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) return
        val shouldEnableVideo = PlaybackPolicy.shouldEnableVideoTrack(
            lifecycleStarted = isLifecycleStarted,
            pipActiveOrEntering = enteringPip,
            isChangingConfigurations = isChangingConfigurations
        )
        activeController.trackSelectionParameters = activeController.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !shouldEnableVideo)
            .build()
    }

    override fun prepare(
        key: ContentKey,
        streamInfo: StreamInfo,
        startPositionMs: Long,
        playWhenReady: Boolean,
        initialQuality: QualityOption?
    ) {
        connectionPurpose = ConnectionPurpose.NORMAL
        prepareWithSpeed(key, streamInfo, startPositionMs, playWhenReady, initialQuality, _state.value.playbackSpeed)
    }

    fun prepareWithSpeed(
        key: ContentKey,
        streamInfo: StreamInfo,
        startPositionMs: Long,
        playWhenReady: Boolean,
        initialQuality: QualityOption?,
        playbackSpeed: Float
    ) {
        connectionPurpose = ConnectionPurpose.NORMAL
        if (isReleased) return
        recoveryJob?.cancel()
        currentKey = key
        currentStreamInfo = streamInfo
        val effectiveStartPositionMs = PlaybackPolicy.resolveStartPosition(
            isLive = streamInfo.isLive,
            requestedPositionMs = startPositionMs
        )

        val available = StreamSelector.getAvailableQualities(streamInfo)
        val initialSelection = if (initialQuality != null) {
            StreamSelector.selectStream(
                streamInfo,
                QualityPreference.SpecificOption(initialQuality)
            )
        } else {
            StartupStreamSelector.select(streamInfo)
        }
        val initialStreamType = (initialSelection as? AppResult.Success)?.value?.streamType
        val clampedSpeed = playbackSpeed.takeIf { it.isFinite() }?.coerceIn(0.25f, 3.0f) ?: 1.0f
        val existingPolicy = _state.value.qualityPolicy
        _state.update {
            it.copy(
                key = key,
                title = streamInfo.title,
                isLoading = true,
                isReady = false,
                hasRenderedFirstFrame = false,
                error = null,
                retrySnapshot = null,
                isEnded = false,
                availableQualities = available,
                selectedQuality = initialQuality?.takeIf { option -> available.contains(option) },
                qualityPolicy = initialQuality?.let(UserQualityPolicy::Fixed) ?: existingPolicy,
                streamType = initialStreamType,
                currentPositionMs = effectiveStartPositionMs,
                playWhenReady = playWhenReady,
                playbackSpeed = clampedSpeed
            )
        }

        val pending = PendingPrepare(key, effectiveStartPositionMs, playWhenReady, initialQuality, clampedSpeed)
        val prepareRequestGeneration = localPrepareRequestGeneration.incrementAndGet()
        connectionPurpose = ConnectionPurpose.NORMAL
        scope.launch(mainDispatcher) {
            if (isReleased || prepareRequestGeneration != localPrepareRequestGeneration.get()) return@launch
            if (mediaController == null) {
                pendingCommands.setPrepare(pending)
                if (controllerFuture == null && !isReconnecting) {
                    connectController()
                }
            } else {
                sendPrepare(pending)
            }
        }
    }

    private fun sendPrepare(pending: PendingPrepare) {
        val controller = mediaController ?: run {
            pendingCommands.setPrepare(pending)
            return
        }
        connectionCoordinator?.onPrepareDelivered(pending)
        val prepareGen = ++localMediaGen
        val handoffToken = currentStreamInfo?.takeIf { it.key == pending.key }?.let {
            PlaybackStreamHandoff.put(it, requestGen = prepareGen)
        }
        val args = Bundle().apply {
            putInt(HPrePlaybackService.EXTRA_SERVICE_ID, pending.key.serviceId)
            putString(HPrePlaybackService.EXTRA_NATIVE_ID, pending.key.nativeId)
            putLong(HPrePlaybackService.EXTRA_POSITION_MS, pending.positionMs)
            putBoolean(HPrePlaybackService.EXTRA_PLAY_WHEN_READY, pending.playWhenReady)
            putFloat(HPrePlaybackService.EXTRA_SPEED, pending.playbackSpeed)
            putLong(HPrePlaybackService.EXTRA_REQUEST_GENERATION, prepareGen)
            handoffToken?.let {
                putString(HPrePlaybackService.EXTRA_HANDOFF_TOKEN, it)
            }
            pending.initialQuality?.let { q ->
                putInt(HPrePlaybackService.EXTRA_QUALITY_HEIGHT, q.height)
                putString(HPrePlaybackService.EXTRA_QUALITY_LABEL, q.label)
                putBoolean(HPrePlaybackService.EXTRA_QUALITY_IS_PROGRESSIVE, q.isProgressive)
                putString(HPrePlaybackService.EXTRA_QUALITY_FORMAT, q.format)
                putString(HPrePlaybackService.EXTRA_QUALITY_MIME, q.mimeType)
                putString(HPrePlaybackService.EXTRA_QUALITY_CODEC, q.codec)
                putString(HPrePlaybackService.EXTRA_QUALITY_STREAM_TYPE, q.streamType.name)
            }
        }
        val future = controller.sendCustomCommand(
            SessionCommand(HPrePlaybackService.CUSTOM_COMMAND_PREPARE_STREAM, Bundle.EMPTY),
            args
        )
        observeCommandResult(future, onDisposedOrFailed = {
            handoffToken?.let { PlaybackStreamHandoff.remove(it) }
        })
    }

    private fun observeCommandResult(
        future: ListenableFuture<SessionResult>,
        onDisposedOrFailed: (() -> Unit)? = null
    ) {
        val requestGeneration = localPrepareRequestGeneration.get()
        future.addListener({
            val result = try {
                future.get()
            } catch (_: Throwable) {
                null
            }
            scope.launch(mainDispatcher) {
                if (isReleased || requestGeneration != localPrepareRequestGeneration.get()) {
                    onDisposedOrFailed?.invoke()
                    return@launch
                }
                if (result == null) {
                    onDisposedOrFailed?.invoke()
                    _state.update { it.copy(isLoading = false, error = AppError.NetworkError) }
                } else if (result.resultCode != SessionResult.RESULT_SUCCESS) {
                    onDisposedOrFailed?.invoke()
                    val errorName = result.extras.getString(HPrePlaybackService.EXTRA_PROBE_ERROR_CODE)
                    val mappedError = errorName?.let(::mapServiceErrorName) ?: AppError.Unknown
                    _state.update { it.copy(isLoading = false, error = mappedError) }
                } else {
                    val extras = result.extras
                    if (extras.containsKey(HPrePlaybackService.EXTRA_PROBE_SESSION_GEN)) {
                        val sessionGen = extras.getLong(HPrePlaybackService.EXTRA_PROBE_SESSION_GEN, localSessionGen)
                        val mediaGen = extras.getLong(HPrePlaybackService.EXTRA_PROBE_MEDIA_GEN, localMediaGen)
                        localSessionGen = sessionGen
                        localMediaGen = mediaGen
                    }
                }
            }
        }, { runnable -> scope.launch(mainDispatcher) { runnable.run() } })
    }

    override fun play() {
        if (isReleased) return
        scope.launch(mainDispatcher) {
            mediaController?.play()
        }
    }

    override fun pause() {
        if (isReleased) return
        scope.launch(mainDispatcher) {
            mediaController?.pause()
        }
    }

    override fun playPause() {
        if (isReleased) return
        scope.launch(mainDispatcher) {
            val controller = mediaController ?: return@launch
            if (controller.isPlaying) {
                controller.pause()
            } else {
                controller.play()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        if (isReleased) return
        scope.launch(mainDispatcher) {
            val controller = mediaController ?: return@launch
            val duration = controller.duration.coerceAtLeast(0L)
            val clamped = if (duration > 0L) {
                positionMs.coerceIn(0L, duration)
            } else {
                positionMs.coerceAtLeast(0L)
            }
            controller.seekTo(clamped)
            _state.update { it.copy(currentPositionMs = clamped) }
        }
    }

    override fun seekBy(deltaMs: Long) {
        if (isReleased) return
        scope.launch(mainDispatcher) {
            val controller = mediaController ?: return@launch
            val current = controller.currentPosition
            val duration = controller.duration.coerceAtLeast(0L)
            val target = if (duration > 0L) {
                (current + deltaMs).coerceIn(0L, duration)
            } else {
                (current + deltaMs).coerceAtLeast(0L)
            }
            controller.seekTo(target)
            _state.update { it.copy(currentPositionMs = target) }
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (isReleased) return
        if (!speed.isFinite()) return
        val clamped = speed.coerceIn(0.25f, 3.0f)
        scope.launch(mainDispatcher) {
            mediaController?.playbackParameters = PlaybackParameters(clamped)
            _state.update { it.copy(playbackSpeed = clamped) }
        }
    }

    override fun selectQuality(quality: QualityOption) {
        if (isReleased) return
        val key = currentKey ?: return
        val matched = _state.value.availableQualities.firstOrNull { it == quality } ?: return
        val resolvedPolicy = QualityPolicyResolver.forSelection(_state.value.streamType, matched)
        if (resolvedPolicy is UserQualityPolicy.Auto) {
            _state.update {
                it.copy(
                    selectedQuality = matched,
                    pendingQuality = null,
                    qualityPolicy = resolvedPolicy,
                    error = null
                )
            }
            setQualityPolicy(resolvedPolicy)
            return
        }
        val priorQuality = _state.value.selectedQuality

        val qualityGen = ++localQualityRequestGen
        _state.update { it.copy(isLoading = true, pendingQuality = matched) }
        scope.launch(mainDispatcher) {
            val controller = mediaController ?: run {
                if (qualityGen == localQualityRequestGen) {
                    _state.update { it.copy(isLoading = false, pendingQuality = null, error = AppError.NetworkError) }
                }
                return@launch
            }
            val expectedSession = if (localSessionGen > 0L) localSessionGen else -1L
            val args = Bundle().apply {
                putInt(HPrePlaybackService.EXTRA_SERVICE_ID, key.serviceId)
                putString(HPrePlaybackService.EXTRA_NATIVE_ID, key.nativeId)
                putLong(HPrePlaybackService.EXTRA_EXPECTED_SESSION_GENERATION, expectedSession)
                putInt(HPrePlaybackService.EXTRA_QUALITY_HEIGHT, matched.height)
                putString(HPrePlaybackService.EXTRA_QUALITY_LABEL, matched.label)
                putBoolean(HPrePlaybackService.EXTRA_QUALITY_IS_PROGRESSIVE, matched.isProgressive)
                putString(HPrePlaybackService.EXTRA_QUALITY_FORMAT, matched.format)
                putString(HPrePlaybackService.EXTRA_QUALITY_MIME, matched.mimeType)
                putString(HPrePlaybackService.EXTRA_QUALITY_CODEC, matched.codec)
                putString(HPrePlaybackService.EXTRA_QUALITY_STREAM_TYPE, matched.streamType.name)
            }
            val future = controller.sendCustomCommand(
                SessionCommand(HPrePlaybackService.CUSTOM_COMMAND_SELECT_QUALITY, Bundle.EMPTY),
                args
            )
            future.addListener({
                val result = try {
                    future.get()
                } catch (_: Throwable) {
                    null
                }
                scope.launch(mainDispatcher) {
                    if (isReleased || qualityGen != localQualityRequestGen) return@launch
                    if (result == null) {
                        _state.update { it.copy(isLoading = false, pendingQuality = null, selectedQuality = priorQuality, error = AppError.NetworkError) }
                    } else if (result.resultCode != SessionResult.RESULT_SUCCESS) {
                        val errorName = result.extras.getString(HPrePlaybackService.EXTRA_PROBE_ERROR_CODE)
                        val mappedError = errorName?.let(::mapServiceErrorName) ?: AppError.UnsupportedFormat
                        _state.update { it.copy(isLoading = false, pendingQuality = null, selectedQuality = priorQuality, error = mappedError) }
                    } else {
                        val extras = result.extras
                        val sessionGen = extras.getLong(HPrePlaybackService.EXTRA_PROBE_SESSION_GEN, localSessionGen)
                        val mediaGen = extras.getLong(HPrePlaybackService.EXTRA_PROBE_MEDIA_GEN, localMediaGen)
                        localSessionGen = sessionGen
                        localMediaGen = mediaGen
                        val streamType = extras.getString(HPrePlaybackService.EXTRA_PROBE_STREAM_TYPE)
                            ?.let { runCatching { PlaybackStreamType.valueOf(it) }.getOrNull() } ?: matched.streamType
                        _state.update {
                            it.copy(
                                isLoading = false,
                                pendingQuality = null,
                                selectedQuality = matched,
                                qualityPolicy = UserQualityPolicy.Fixed(matched),
                                streamType = streamType,
                                error = null
                            )
                        }
                    }
                }
            }, { runnable -> scope.launch(mainDispatcher) { runnable.run() } })
        }
    }

    override fun setQualityPolicy(policy: UserQualityPolicy) {
        if (isReleased) return
        val key = currentKey ?: return
        _state.update { it.copy(qualityPolicy = policy) }
        scope.launch(mainDispatcher) {
            val controller = mediaController ?: return@launch
            val args = Bundle().apply {
                putInt(HPrePlaybackService.EXTRA_SERVICE_ID, key.serviceId)
                putString(HPrePlaybackService.EXTRA_NATIVE_ID, key.nativeId)
                when (policy) {
                    is UserQualityPolicy.Auto -> {
                        putBoolean(HPrePlaybackService.EXTRA_POLICY_AUTO, true)
                        policy.maxHeight?.let { putInt(HPrePlaybackService.EXTRA_POLICY_MAX_HEIGHT, it) }
                        policy.maxBitrate?.let { putInt(HPrePlaybackService.EXTRA_POLICY_MAX_BITRATE, it) }
                    }
                    is UserQualityPolicy.Fixed -> {
                        putBoolean(HPrePlaybackService.EXTRA_POLICY_AUTO, false)
                        putInt(HPrePlaybackService.EXTRA_QUALITY_HEIGHT, policy.option.height)
                    }
                }
            }
            controller.sendCustomCommand(
                SessionCommand(HPrePlaybackService.CUSTOM_COMMAND_SET_QUALITY_POLICY, Bundle.EMPTY),
                args
            )
        }
    }

    override fun stopForTransition() {
        if (isReleased) return
        transitioning = true
        localPrepareRequestGeneration.incrementAndGet()
        pendingCommands.clearPrepare()
        currentKey = null
        currentStreamInfo = null
        val previous = _state.value
        _state.value = PlaybackState(
            playbackSpeed = previous.playbackSpeed,
            qualityPolicy = previous.qualityPolicy as? UserQualityPolicy.Auto ?: UserQualityPolicy.Auto()
        )
        // Commands use the same main-thread queue as prepare, so an already dispatched prepare is
        // invalidated by the service before the next video is delivered. Keep controller/surface.
        scope.launch(mainDispatcher) {
            mediaController?.sendCustomCommand(
                SessionCommand(HPrePlaybackService.CUSTOM_COMMAND_STOP_FOR_TRANSITION, Bundle.EMPTY),
                Bundle.EMPTY
            )
        }
    }

    override fun clearMedia() {
        transitioning = true
        recoveryJob?.cancel()
        recoveryJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting = false
        connectRetryCount = 0
        connectionAttemptGeneration++
        activeConnectionGeneration = 0L
        localPrepareRequestGeneration.incrementAndGet()

        snapshotStore.clear()
        pendingCommands.clearPrepare()
        currentKey = null
        currentStreamInfo = null
        _state.value = PlaybackState()

        val surfaceView = currentSurfaceView
        currentSurfaceView = null

        val controller = mediaController
        val future = controllerFuture
        mediaController = null
        controllerFuture = null

        scope.launch(mainDispatcher) {
            surfaceView?.let { pv ->
                pv.player = null
                connectionCoordinator?.onPlayerViewDetached(pv)
            }
            controller?.removeListener(playerListener)
            if (controller != null) {
                val commandFuture = controller.sendCustomCommand(
                    SessionCommand(HPrePlaybackService.CUSTOM_COMMAND_CLEAR_MEDIA, Bundle.EMPTY),
                    Bundle.EMPTY
                )
                commandFuture.addListener({
                    snapshotStore.clear()
                    future?.let { MediaController.releaseFuture(it) }
                    controller.release()
                }, { runnable -> scope.launch(mainDispatcher) { runnable.run() } })
            } else {
                future?.let { MediaController.releaseFuture(it) }
            }
        }
    }

    override fun release() {
        if (isReleased) return
        isReleased = true
        recoveryJob?.cancel()
        recoveryJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting = false
        connectRetryCount = 0
        connectionAttemptGeneration++
        activeConnectionGeneration = 0L
        localPrepareRequestGeneration.incrementAndGet()

        val surfaceView = currentSurfaceView
        currentSurfaceView = null

        val controller = mediaController
        val future = controllerFuture
        mediaController = null
        controllerFuture = null

        // Observer-only semantics: clean up local listeners and controller binding,
        // but do NOT cancel externalScope jobs or destroy background playback service
        scope.launch(mainDispatcher) {
            surfaceView?.let { pv ->
                pv.player = null
                connectionCoordinator?.onPlayerViewDetached(pv)
            }
            controller?.removeListener(playerListener)
            future?.let { MediaController.releaseFuture(it) }
            controller?.release()
        }
    }

    override suspend fun readProgress(): PlaybackProgress = withContext(mainDispatcher) {
        val coordinatorProgress = connectionCoordinator?.readProgress(mediaController, activeConnectionGeneration)
        if (coordinatorProgress != null) return@withContext coordinatorProgress

        val controller = mediaController
        if (controller != null) {
            PlaybackProgress(
                positionMs = controller.currentPosition.coerceAtLeast(0L),
                durationMs = controller.duration.coerceAtLeast(0L)
            )
        } else {
            _state.value.toProgress()
        }
    }

    override suspend fun getTestingSnapshot(): PlayerTestingSnapshot {
        val controller = withContext(mainDispatcher) { mediaController }
        var gen = localMediaGen
        var sessionGen = localSessionGen
        var rendered = localRenderedCount
        var audioDec = localAudioDecoderCount
        var videoDec = localVideoDecoderCount

        if (controller != null) {
            try {
                val future = withContext(mainDispatcher) {
                    controller.sendCustomCommand(
                        SessionCommand(HPrePlaybackService.CUSTOM_COMMAND_GET_PROBE_SNAPSHOT, Bundle.EMPTY),
                        Bundle.EMPTY
                    )
                }
                val extras = withContext(ioDispatcher) { future.get().extras }
                gen = extras.getLong(HPrePlaybackService.EXTRA_PROBE_MEDIA_GEN, gen)
                sessionGen = extras.getLong(HPrePlaybackService.EXTRA_PROBE_SESSION_GEN, sessionGen)
                localMediaGen = gen
                localSessionGen = sessionGen
                rendered = extras.getInt(HPrePlaybackService.EXTRA_PROBE_RENDERED_COUNT, rendered)
                audioDec = extras.getInt(HPrePlaybackService.EXTRA_PROBE_AUDIO_DECODER_COUNT, audioDec)
                videoDec = extras.getInt(HPrePlaybackService.EXTRA_PROBE_VIDEO_DECODER_COUNT, videoDec)
                val serviceId = extras.getInt(HPrePlaybackService.EXTRA_PROBE_SERVICE_ID, Int.MIN_VALUE)
                val nativeId = extras.getString(HPrePlaybackService.EXTRA_PROBE_NATIVE_ID)
                val streamType = extras.getString(HPrePlaybackService.EXTRA_PROBE_STREAM_TYPE)
                    ?.let { runCatching { PlaybackStreamType.valueOf(it) }.getOrNull() }
                val error = extras.getString(HPrePlaybackService.EXTRA_PROBE_ERROR_CODE)
                    ?.let(::mapServiceErrorName)
                if (serviceId != Int.MIN_VALUE && !nativeId.isNullOrBlank()) {
                    val key = ContentKey(serviceId, nativeId)
                    val title = extras.getString(HPrePlaybackService.EXTRA_PROBE_TITLE)?.takeIf(String::isNotBlank)
                    _state.update {
                        it.copy(
                            key = key,
                            title = title,
                            streamType = streamType,
                            error = error
                        )
                    }
                }
            } catch (_: ExecutionException) {
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        return withContext(mainDispatcher) {
            val activeController = mediaController
            val isSurfaceAttached = currentSurfaceView != null && activeController != null && currentSurfaceView?.player == activeController
            PlayerTestingSnapshot(
                mediaOperationGeneration = gen,
                playbackSessionGeneration = sessionGen,
                actualPositionMs = activeController?.currentPosition?.coerceAtLeast(0L) ?: _state.value.currentPositionMs,
                actualDurationMs = activeController?.duration?.coerceAtLeast(0L) ?: _state.value.durationMs,
                playbackState = activeController?.playbackState ?: Player.STATE_IDLE,
                isPlaying = activeController?.isPlaying ?: false,
                playWhenReady = activeController?.playWhenReady ?: false,
                selectedQuality = _state.value.selectedQuality,
                streamType = _state.value.streamType,
                error = _state.value.error,
                renderedFirstFrameCount = rendered,
                audioDecoderInitializedCount = audioDec,
                videoDecoderInitializedCount = videoDec,
                surfaceAttached = isSurfaceAttached
            )
        }
    }

    companion object {
        internal fun createConnectionHints(isPrewarm: Boolean): Bundle = Bundle().apply {
            putBoolean(HPrePlaybackService.KEY_INFRASTRUCTURE_PREWARM, isPrewarm)
        }

        internal fun mapServiceErrorName(value: String): AppError? = when (value) {
            AppError.NetworkError::class.java.simpleName -> AppError.NetworkError
            AppError.ContentUnavailable::class.java.simpleName -> AppError.ContentUnavailable
            AppError.AgeRestricted::class.java.simpleName -> AppError.AgeRestricted
            AppError.GeoRestricted::class.java.simpleName -> AppError.GeoRestricted
            AppError.LoginRequired::class.java.simpleName -> AppError.LoginRequired
            AppError.StreamExpired::class.java.simpleName -> AppError.StreamExpired
            AppError.UnsupportedFormat::class.java.simpleName -> AppError.UnsupportedFormat
            AppError.ExtractionFailed::class.java.simpleName -> AppError.ExtractionFailed
            AppError.RateLimited::class.java.simpleName -> AppError.RateLimited
            AppError.Unknown::class.java.simpleName -> AppError.Unknown
            else -> null
        }
    }
}
