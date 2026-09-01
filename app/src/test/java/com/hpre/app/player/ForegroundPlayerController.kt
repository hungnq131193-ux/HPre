package com.hpre.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class ForegroundPlayerController(
    private val mediaSourceFactory: MediaSourceCreator,
    private val playerFactory: () -> ExoPlayer,
    private val recoveryCoordinator: StreamRecoveryCoordinator? = null,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PlayerController, PlayerIntegrationProbe {

    constructor(
        context: Context,
        mediaSourceFactory: MediaSourceCreator,
        recoveryCoordinator: StreamRecoveryCoordinator? = null,
        playerFactory: (Context) -> ExoPlayer = { ctx ->
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            ExoPlayer.Builder(ctx)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .build()
        },
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        mediaSourceFactory = mediaSourceFactory,
        playerFactory = { playerFactory(context) },
        recoveryCoordinator = recoveryCoordinator,
        mainDispatcher = mainDispatcher,
        ioDispatcher = ioDispatcher
    )

    private val scope = CoroutineScope(mainDispatcher + Job())
    private var mediaOpJob: Job? = null
    private var recoveryJob: Job? = null
    private var mediaOperationGeneration: Long = 0L
    private var playbackSessionGeneration: Long = 0L

    private var exoPlayer: ExoPlayer? = null
    private var wasPlayingBeforeLifecycleStop: Boolean = false
    private var isLifecyclePaused: Boolean = false
    private var userRequestedPlay: Boolean = true

    private var currentSurfaceView: PlayerView? = null
    private var surfaceGeneration: Long = 0L

    private var activeAnalyticsListener: AnalyticsListener? = null
    private val renderedFirstFrameCounters = mutableMapOf<Long, Int>()
    private val audioDecoderInitCounters = mutableMapOf<Long, Int>()
    private val videoDecoderInitCounters = mutableMapOf<Long, Int>()

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var currentStreamInfo: StreamInfo? = null
    private var currentKey: ContentKey? = null
    private var isReleased: Boolean = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (isReleased) return
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
                            isPlaying = exoPlayer?.isPlaying ?: false,
                            playWhenReady = exoPlayer?.playWhenReady ?: false,
                            durationMs = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L,
                            currentPositionMs = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                            isEnded = false
                        )
                    }
                }
                Player.STATE_ENDED -> {
                    _state.update { it.copy(isEnded = true, isPlaying = false, isBuffering = false, isReady = false) }
                }
                Player.STATE_IDLE -> {
                    // Idle state
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (isReleased) return
            val actualPos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: newPosition.positionMs.coerceAtLeast(0L)
            _state.update { it.copy(currentPositionMs = actualPos) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (isReleased) return
            val player = exoPlayer
            _state.update {
                it.copy(
                    playWhenReady = playWhenReady,
                    isPlaying = player?.isPlaying ?: false
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isReleased) return
            _state.update {
                it.copy(
                    isPlaying = isPlaying,
                    playWhenReady = exoPlayer?.playWhenReady ?: false
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (isReleased) return
            val httpException = findCause<HttpDataSource.InvalidResponseCodeException>(error)
            val appError = when {
                httpException != null -> {
                    when (httpException.responseCode) {
                        403 -> AppError.StreamExpired
                        401 -> AppError.LoginRequired
                        404 -> AppError.ContentUnavailable
                        in 500..599 -> AppError.NetworkError
                        else -> AppError.Unknown
                    }
                }
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> AppError.NetworkError
                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> AppError.Unknown
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> AppError.UnsupportedFormat
                else -> AppError.Unknown
            }

            val diagnosticStatus = when {
                httpException?.responseCode == 403 -> com.hpre.app.core.network.DiagnosticStatus.Http403
                httpException?.responseCode in 400..499 -> com.hpre.app.core.network.DiagnosticStatus.Http4xx
                httpException?.responseCode in 500..599 -> com.hpre.app.core.network.DiagnosticStatus.Http5xx
                appError is AppError.NetworkError -> com.hpre.app.core.network.DiagnosticStatus.Network
                appError is AppError.StreamExpired -> com.hpre.app.core.network.DiagnosticStatus.Http403
                else -> com.hpre.app.core.network.DiagnosticStatus.Unknown
            }

            com.hpre.app.core.network.RedactingLogger.logDiagnostic(
                component = com.hpre.app.core.network.DiagnosticComponent.PLAYER_CONTROLLER,
                category = com.hpre.app.core.network.LogCategory.PLAYBACK,
                operation = com.hpre.app.core.network.DiagnosticOperation.PLAYBACK_ERROR,
                status = diagnosticStatus
            )

            val key = currentKey
            val currentSession = playbackSessionGeneration
            val snapshot = if (key != null) {
                // Use controller-owned published snapshot position on main rather than transient exoPlayer currentPosition
                val publishedPos = _state.value.currentPositionMs
                val position = if (publishedPos > 0L) {
                    publishedPos
                } else {
                    exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
                }
                RetrySnapshot(
                    key = key,
                    sessionGen = currentSession,
                    positionMs = position,
                    userRequestedPlay = userRequestedPlay,
                    selectedQuality = _state.value.selectedQuality
                )
            } else {
                null
            }

            if (appError == AppError.StreamExpired && recoveryCoordinator != null && key != null && snapshot != null) {
                // Trigger auto recovery
                val preference = snapshot.selectedQuality?.let { QualityPreference.SpecificOption(it) } ?: QualityPreference.Auto
                recoveryJob?.cancel()
                recoveryJob = scope.launch(mainDispatcher) {
                    if (isReleased || currentKey != key || playbackSessionGeneration != currentSession) return@launch
                    _state.update { it.copy(isLoading = true, isBuffering = false, isPlaying = false, error = null) }
                    val recoveryResult = recoveryCoordinator.recoverExpiredStream(
                        key = key,
                        sessionGen = currentSession,
                        positionMs = snapshot.positionMs,
                        wasPlaying = snapshot.userRequestedPlay,
                        preference = preference
                    )
                    if (isReleased || currentKey != key || playbackSessionGeneration != currentSession) return@launch
                    when (recoveryResult) {
                        is RecoveryResult.Recovered -> {
                            prepare(
                                key = recoveryResult.key,
                                streamInfo = recoveryResult.streamInfo,
                                startPositionMs = recoveryResult.resumePositionMs,
                                playWhenReady = recoveryResult.resumeWhenReady,
                                initialQuality = recoveryResult.selectedQuality
                            )
                        }
                        is RecoveryResult.Failed -> {
                            _state.update {
                                it.copy(
                                    isLoading = false,
                                    isBuffering = false,
                                    isPlaying = false,
                                    error = recoveryResult.error,
                                    retrySnapshot = snapshot
                                )
                            }
                        }
                        RecoveryResult.Cancelled -> {
                            _state.update {
                                it.copy(
                                    isLoading = false,
                                    isBuffering = false,
                                    isPlaying = false,
                                    error = AppError.StreamExpired,
                                    retrySnapshot = snapshot
                                )
                            }
                        }
                    }
                }
            } else {
                _state.update {
                    it.copy(
                        isLoading = false,
                        isBuffering = false,
                        isPlaying = false,
                        error = appError,
                        retrySnapshot = snapshot
                    )
                }
            }
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

    private fun registerAnalyticsListenerForGeneration(player: ExoPlayer, generationToken: Long) {
        activeAnalyticsListener?.let { old ->
            player.removeAnalyticsListener(old)
        }
        val listener = object : AnalyticsListener {
            private val boundToken: Long = generationToken

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                if (isReleased) return
                renderedFirstFrameCounters[boundToken] = (renderedFirstFrameCounters[boundToken] ?: 0) + 1
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                if (isReleased) return
                audioDecoderInitCounters[boundToken] = (audioDecoderInitCounters[boundToken] ?: 0) + 1
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                if (isReleased) return
                videoDecoderInitCounters[boundToken] = (videoDecoderInitCounters[boundToken] ?: 0) + 1
            }
        }
        activeAnalyticsListener = listener
        player.addAnalyticsListener(listener)
    }

    init {
        scope.launch(mainDispatcher) {
            initPlayer()
        }
    }

    private fun initPlayer() {
        if (!isReleased && exoPlayer == null) {
            val player = playerFactory()
            player.addListener(playerListener)
            registerAnalyticsListenerForGeneration(player, mediaOperationGeneration)
            exoPlayer = player
            currentSurfaceView?.let { playerView ->
                if (playerView.player != player) {
                    playerView.player = player
                }
            }
        }
    }

    override fun attachSurface(playerView: PlayerView) {
        if (isReleased) return
        scope.launch(mainDispatcher) {
            if (isReleased) return@launch
            val gen = ++surfaceGeneration
            currentSurfaceView = playerView
            initPlayer()
            // Clear previous surface view if different
            val player = exoPlayer
            if (player != null && playerView.player != player) {
                playerView.player = player
            }
        }
    }

    override fun detachSurface(playerView: PlayerView) {
        scope.launch(mainDispatcher) {
            if (isReleased) return@launch
            if (currentSurfaceView == playerView) {
                currentSurfaceView = null
                val gen = ++surfaceGeneration
                if (playerView.player == exoPlayer) {
                    playerView.player = null
                }
            } else {
                // Stale view being detached: clear its player directly on main if still holding player reference
                if (playerView.player == exoPlayer) {
                    playerView.player = null
                }
            }
        }
    }

    override fun onLifecycleStop() {
        if (isReleased) return
        scope.launch(mainDispatcher) {
            if (isReleased) return@launch
            val player = exoPlayer ?: return@launch
            wasPlayingBeforeLifecycleStop = player.isPlaying || player.playWhenReady
            isLifecyclePaused = true
            player.pause()
        }
    }

    override fun onLifecycleStart() {
        if (isReleased) return
        scope.launch(mainDispatcher) {
            if (isReleased) return@launch
            val player = exoPlayer ?: return@launch
            if (isLifecyclePaused && wasPlayingBeforeLifecycleStop) {
                player.play()
            }
            isLifecyclePaused = false
            wasPlayingBeforeLifecycleStop = false
        }
    }

    override fun prepare(
        key: ContentKey,
        streamInfo: StreamInfo,
        startPositionMs: Long,
        playWhenReady: Boolean,
        initialQuality: QualityOption?
    ) {
        if (isReleased) return

        mediaOpJob?.cancel()
        recoveryJob?.cancel()
        val currentToken = ++mediaOperationGeneration
        val currentSession = ++playbackSessionGeneration

        mediaOpJob = scope.launch(mainDispatcher) {
            if (isReleased || currentToken != mediaOperationGeneration) return@launch
            initPlayer()
            currentKey = key
            currentStreamInfo = streamInfo
            userRequestedPlay = playWhenReady

            val availableQualities = StreamSelector.getAvailableQualities(streamInfo)
            _state.update {
                it.copy(
                    key = key,
                    isLoading = true,
                    error = null,
                    retrySnapshot = null,
                    isEnded = false,
                    availableQualities = availableQualities,
                    durationMs = 0L,
                    currentPositionMs = startPositionMs.coerceAtLeast(0L),
                    playWhenReady = playWhenReady
                )
            }

            try {
                val pref = if (initialQuality != null) {
                    val matched = availableQualities.firstOrNull { it == initialQuality }
                    if (matched != null) QualityPreference.SpecificOption(matched) else QualityPreference.Auto
                } else {
                    QualityPreference.Auto
                }

                // Selection & media source creation performed on IO dispatcher
                val selectionAndSource = withContext(ioDispatcher) {
                    val selectionResult = StreamSelector.selectStream(streamInfo, pref)
                    when (selectionResult) {
                        is AppResult.Success -> {
                            val selected = selectionResult.value.copy(title = streamInfo.title)
                            val mediaSource = mediaSourceFactory.createMediaSource(selected)
                            AppResult.Success(Pair(selected, mediaSource))
                        }
                        is AppResult.Failure -> {
                            AppResult.Failure(selectionResult.error)
                        }
                    }
                }

                // After IO work returns, verify token and key before committing to Player on main
                if (currentToken != mediaOperationGeneration || currentKey != key || isReleased) return@launch

                when (selectionAndSource) {
                    is AppResult.Success -> {
                        val (selected, mediaSource) = selectionAndSource.value

                        val selectedQuality = when (selected.streamType) {
                            PlaybackStreamType.PROGRESSIVE -> {
                                selected.videoStream?.let { vs ->
                                    availableQualities.firstOrNull {
                                        it.isProgressive &&
                                                it.height == (vs.height ?: 0) &&
                                                it.format.equals(vs.format, ignoreCase = true) &&
                                                it.mimeType?.trim()?.lowercase() == vs.mimeType?.trim()?.lowercase() &&
                                                it.codec?.trim()?.lowercase() == vs.codec?.trim()?.lowercase()
                                    }
                                }
                            }
                            PlaybackStreamType.MERGED_AV -> {
                                selected.videoStream?.let { vs ->
                                    availableQualities.firstOrNull {
                                        !it.isProgressive &&
                                                it.height == (vs.height ?: 0) &&
                                                it.format.equals(vs.format, ignoreCase = true) &&
                                                it.mimeType?.trim()?.lowercase() == vs.mimeType?.trim()?.lowercase() &&
                                                it.codec?.trim()?.lowercase() == vs.codec?.trim()?.lowercase()
                                    }
                                }
                            }
                            PlaybackStreamType.HLS -> {
                                availableQualities.firstOrNull { it.streamType == PlaybackStreamType.HLS }
                            }
                            PlaybackStreamType.DASH -> {
                                availableQualities.firstOrNull { it.streamType == PlaybackStreamType.DASH }
                            }
                            PlaybackStreamType.AUDIO_ONLY -> null
                        }

                        _state.update {
                            it.copy(
                                streamType = selected.streamType,
                                selectedQuality = selectedQuality,
                                error = null,
                                retrySnapshot = null
                            )
                        }

                        exoPlayer?.let { player ->
                            registerAnalyticsListenerForGeneration(player, currentToken)
                            player.setMediaSource(mediaSource)
                            if (startPositionMs > 0L) {
                                player.seekTo(startPositionMs)
                            }
                            player.playWhenReady = playWhenReady
                            player.prepare()
                        }
                    }
                    is AppResult.Failure -> {
                        _state.update {
                            it.copy(
                                isLoading = false,
                                error = selectionAndSource.error
                            )
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                if (currentToken == mediaOperationGeneration && currentKey == key && !isReleased) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = AppError.UnsupportedFormat
                        )
                    }
                }
            }
        }
    }

    override fun play() {
        if (isReleased) return
        userRequestedPlay = true
        scope.launch(mainDispatcher) {
            if (isReleased) return@launch
            isLifecyclePaused = false
            wasPlayingBeforeLifecycleStop = false
            exoPlayer?.play()
        }
    }

    override fun pause() {
        if (isReleased) return
        userRequestedPlay = false
        scope.launch(mainDispatcher) {
            if (isReleased) return@launch
            wasPlayingBeforeLifecycleStop = false
            exoPlayer?.pause()
        }
    }

    override fun playPause() {
        if (isReleased) return
        scope.launch(mainDispatcher) {
            if (isReleased) return@launch
            val player = exoPlayer ?: return@launch
            if (player.isPlaying) {
                userRequestedPlay = false
                wasPlayingBeforeLifecycleStop = false
                player.pause()
            } else {
                userRequestedPlay = true
                isLifecyclePaused = false
                wasPlayingBeforeLifecycleStop = false
                player.play()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        if (isReleased) return
        scope.launch(mainDispatcher) {
            if (isReleased) return@launch
            val player = exoPlayer ?: return@launch
            val duration = player.duration.coerceAtLeast(0L)
            val clamped = if (duration > 0L) {
                positionMs.coerceIn(0L, duration)
            } else {
                positionMs.coerceAtLeast(0L)
            }
            player.seekTo(clamped)
        }
    }

    override fun seekBy(deltaMs: Long) {
        if (isReleased) return
        scope.launch(mainDispatcher) {
            if (isReleased) return@launch
            val player = exoPlayer ?: return@launch
            val current = player.currentPosition
            val duration = player.duration.coerceAtLeast(0L)
            val target = if (duration > 0L) {
                (current + deltaMs).coerceIn(0L, duration)
            } else {
                (current + deltaMs).coerceAtLeast(0L)
            }
            player.seekTo(target)
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (isReleased) return
        if (!speed.isFinite()) return
        val clamped = speed.coerceIn(0.25f, 3.0f)
        scope.launch(mainDispatcher) {
            if (isReleased) return@launch
            exoPlayer?.playbackParameters = PlaybackParameters(clamped)
            _state.update { it.copy(playbackSpeed = clamped) }
        }
    }

    override fun selectQuality(quality: QualityOption) {
        if (isReleased) return

        val targetKey = currentKey ?: return
        val streamInfo = currentStreamInfo ?: return

        mediaOpJob?.cancel()
        val currentToken = ++mediaOperationGeneration

        mediaOpJob = scope.launch(mainDispatcher) {
            if (isReleased || currentToken != mediaOperationGeneration) return@launch
            val player = exoPlayer ?: return@launch

            // Quality must be present exactly in current availableQualities
            val matchedQuality = _state.value.availableQualities.firstOrNull { it == quality } ?: return@launch

            val currentPos = player.currentPosition
            val wasPlaying = player.isPlaying || player.playWhenReady

            _state.update { it.copy(isLoading = true) }

            try {
                val pref = QualityPreference.SpecificOption(matchedQuality)

                // Selection & media source creation performed on IO dispatcher
                val selectionAndSource = withContext(ioDispatcher) {
                    val selectionResult = StreamSelector.selectStream(streamInfo, pref)
                    when (selectionResult) {
                        is AppResult.Success -> {
                            val selected = selectionResult.value.copy(title = streamInfo.title)
                            val mediaSource = mediaSourceFactory.createMediaSource(selected)
                            AppResult.Success(Pair(selected, mediaSource))
                        }
                        is AppResult.Failure -> {
                            AppResult.Failure(selectionResult.error)
                        }
                    }
                }

                // After IO work returns, verify token and key before committing to Player on main
                if (currentToken != mediaOperationGeneration || currentKey != targetKey || isReleased) return@launch

                when (selectionAndSource) {
                    is AppResult.Success -> {
                        val (selected, mediaSource) = selectionAndSource.value

                        val finalQuality = when (selected.streamType) {
                            PlaybackStreamType.PROGRESSIVE -> {
                                selected.videoStream?.let { vs ->
                                    _state.value.availableQualities.firstOrNull {
                                        it.isProgressive &&
                                                it.height == (vs.height ?: 0) &&
                                                it.format.equals(vs.format, ignoreCase = true) &&
                                                it.mimeType?.trim()?.lowercase() == vs.mimeType?.trim()?.lowercase() &&
                                                it.codec?.trim()?.lowercase() == vs.codec?.trim()?.lowercase()
                                    }
                                }
                            }
                            PlaybackStreamType.MERGED_AV -> {
                                selected.videoStream?.let { vs ->
                                    _state.value.availableQualities.firstOrNull {
                                        !it.isProgressive &&
                                                it.height == (vs.height ?: 0) &&
                                                it.format.equals(vs.format, ignoreCase = true) &&
                                                it.mimeType?.trim()?.lowercase() == vs.mimeType?.trim()?.lowercase() &&
                                                it.codec?.trim()?.lowercase() == vs.codec?.trim()?.lowercase()
                                    }
                                }
                            }
                            PlaybackStreamType.HLS -> {
                                _state.value.availableQualities.firstOrNull { it.streamType == PlaybackStreamType.HLS }
                            }
                            PlaybackStreamType.DASH -> {
                                _state.value.availableQualities.firstOrNull { it.streamType == PlaybackStreamType.DASH }
                            }
                            PlaybackStreamType.AUDIO_ONLY -> null
                        }

                        _state.update {
                            it.copy(
                                isLoading = false,
                                selectedQuality = finalQuality,
                                streamType = selected.streamType,
                                error = null,
                                retrySnapshot = null
                            )
                        }

                        registerAnalyticsListenerForGeneration(player, currentToken)
                        player.setMediaSource(mediaSource)
                        player.seekTo(currentPos)
                        player.playWhenReady = wasPlaying
                        player.prepare()
                    }
                    is AppResult.Failure -> {
                        _state.update {
                            it.copy(
                                isLoading = false,
                                error = selectionAndSource.error
                            )
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                if (currentToken == mediaOperationGeneration && currentKey == targetKey && !isReleased) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = AppError.UnsupportedFormat
                        )
                    }
                }
            }
        }
    }

    override fun release() {
        if (isReleased) return
        isReleased = true
        recoveryCoordinator?.release()
        mediaOpJob?.cancel()
        recoveryJob?.cancel()
        surfaceGeneration++
        scope.launch(mainDispatcher) {
            activeAnalyticsListener?.let { exoPlayer?.removeAnalyticsListener(it) }
            activeAnalyticsListener = null
            exoPlayer?.removeListener(playerListener)
            exoPlayer?.release()
            exoPlayer = null
            currentSurfaceView = null
            currentStreamInfo = null
            currentKey = null
            renderedFirstFrameCounters.clear()
            audioDecoderInitCounters.clear()
            videoDecoderInitCounters.clear()
            _state.update { PlaybackState() }
            scope.cancel()
        }
    }

    override suspend fun readProgress(): PlaybackProgress = withContext(mainDispatcher) {
        val player = exoPlayer
        if (player != null) {
            PlaybackProgress(
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.coerceAtLeast(0L)
            )
        } else {
            _state.value.toProgress()
        }
    }

    override suspend fun getTestingSnapshot(): PlayerTestingSnapshot {
        return withContext(mainDispatcher) {
            val player = exoPlayer
            val gen = mediaOperationGeneration
            val sessionGen = playbackSessionGeneration
            val isSurfaceAttached = currentSurfaceView != null && player != null && currentSurfaceView?.player == player
            PlayerTestingSnapshot(
                mediaOperationGeneration = gen,
                playbackSessionGeneration = sessionGen,
                actualPositionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                actualDurationMs = player?.duration?.coerceAtLeast(0L) ?: 0L,
                playbackState = player?.playbackState ?: Player.STATE_IDLE,
                isPlaying = player?.isPlaying ?: false,
                playWhenReady = player?.playWhenReady ?: false,
                selectedQuality = _state.value.selectedQuality,
                streamType = _state.value.streamType,
                error = _state.value.error,
                renderedFirstFrameCount = renderedFirstFrameCounters[gen] ?: 0,
                audioDecoderInitializedCount = audioDecoderInitCounters[gen] ?: 0,
                videoDecoderInitializedCount = videoDecoderInitCounters[gen] ?: 0,
                surfaceAttached = isSurfaceAttached
            )
        }
    }
}
