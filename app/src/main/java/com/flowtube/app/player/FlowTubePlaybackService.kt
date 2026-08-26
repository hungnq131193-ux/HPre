package com.flowtube.app.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.flowtube.app.FlowTubeApplication
import com.flowtube.app.MainActivity
import com.flowtube.app.core.error.AppError
import com.flowtube.app.core.error.AppResult
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.StreamInfo
import com.flowtube.app.model.VideoSummary
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class FlowTubePlaybackService : MediaSessionService() {

    companion object {
        const val CUSTOM_COMMAND_SELECT_QUALITY = "com.flowtube.app.CUSTOM_COMMAND_SELECT_QUALITY"
        const val CUSTOM_COMMAND_PREPARE_STREAM = "com.flowtube.app.CUSTOM_COMMAND_PREPARE_STREAM"
        const val CUSTOM_COMMAND_GET_PROBE_SNAPSHOT = "com.flowtube.app.CUSTOM_COMMAND_GET_PROBE_SNAPSHOT"
        const val CUSTOM_COMMAND_CLEAR_MEDIA = "com.flowtube.app.CUSTOM_COMMAND_CLEAR_MEDIA"
        const val CUSTOM_COMMAND_SET_BACKGROUND_ENABLED = "com.flowtube.app.CUSTOM_COMMAND_SET_BACKGROUND_ENABLED"

        const val EXTRA_SERVICE_ID = "extra_service_id"
        const val EXTRA_NATIVE_ID = "extra_native_id"
        const val EXTRA_POSITION_MS = "extra_position_ms"
        const val EXTRA_PLAY_WHEN_READY = "extra_play_when_ready"
        const val EXTRA_HANDOFF_TOKEN = "extra_handoff_token"
        const val EXTRA_QUALITY_HEIGHT = "extra_quality_height"
        const val EXTRA_QUALITY_LABEL = "extra_quality_label"
        const val EXTRA_QUALITY_IS_PROGRESSIVE = "extra_quality_is_progressive"
        const val EXTRA_QUALITY_FORMAT = "extra_quality_format"
        const val EXTRA_QUALITY_MIME = "extra_quality_mime"
        const val EXTRA_QUALITY_CODEC = "extra_quality_codec"
        const val EXTRA_QUALITY_STREAM_TYPE = "extra_quality_stream_type"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_EXPECTED_SESSION_GENERATION = "extra_expected_session_generation"
        const val EXTRA_BACKGROUND_ENABLED = "extra_background_enabled"
        const val EXTRA_PIP_ACTIVE_OR_ENTERING = "extra_pip_active_or_entering"
        const val EXTRA_REQUEST_GENERATION = "extra_request_generation"

        // Probe snapshot response extras
        const val EXTRA_PROBE_MEDIA_GEN = "extra_probe_media_gen"
        const val EXTRA_PROBE_SESSION_GEN = "extra_probe_session_gen"
        const val EXTRA_PROBE_RENDERED_COUNT = "extra_probe_rendered_count"
        const val EXTRA_PROBE_AUDIO_DECODER_COUNT = "extra_probe_audio_decoder_count"
        const val EXTRA_PROBE_VIDEO_DECODER_COUNT = "extra_probe_video_decoder_count"
        const val EXTRA_PROBE_ERROR_CODE = "extra_probe_error_code"
        const val EXTRA_PROBE_SERVICE_ID = "extra_probe_service_id"
        const val EXTRA_PROBE_NATIVE_ID = "extra_probe_native_id"
        const val EXTRA_PROBE_TITLE = "extra_probe_title"
        const val EXTRA_PROBE_STREAM_TYPE = "extra_probe_stream_type"
        const val EXTRA_PROBE_QUALITY_PRESENT = "extra_probe_quality_present"
    }

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var mediaSourceFactory: MediaSourceCreator? = null
    private var recoveryCoordinator: StreamRecoveryCoordinator? = null
    private var snapshotStore: PlaybackSnapshotStore? = null

    private var currentKey: ContentKey? = null
    private var currentStreamInfo: StreamInfo? = null
    private var currentSelectedQuality: QualityOption? = null
    private var availableQualities: List<QualityOption> = emptyList()
    private var currentStreamType: PlaybackStreamType? = null

    private var mediaOpJob: Job? = null
    private var recoveryJob: Job? = null
    private var mediaOperationGeneration: Long = 0L
    private var playbackSessionGeneration: Long = 0L
    private var prepareRequestGeneration: Long = 0L

    private var userRequestedPlay: Boolean = true
    private var isReleased: Boolean = false
    private var backgroundPlaybackEnabled: Boolean = false
    private var isPipActiveOrEntering: Boolean = false

    private var lastReportedAppError: AppError? = null
    private var activeQualityFuture: SettableFuture<SessionResult>? = null

    private val renderedFirstFrameCounters = mutableMapOf<Long, Int>()
    private val audioDecoderInitCounters = mutableMapOf<Long, Int>()
    private val videoDecoderInitCounters = mutableMapOf<Long, Int>()
    private var activeAnalyticsListener: AnalyticsListener? = null

    override fun onCreate() {
        super.onCreate()
        FlowTubeMediaNotification.ensureNotificationChannel(this)
        setMediaNotificationProvider(FlowTubeMediaNotification.createNotificationProvider(this))

        val app = application as? FlowTubeApplication
        val container = app?.container
        val okHttpClient = container?.okHttpClient
        mediaSourceFactory = container?.mediaSourceFactory ?: MediaSourceFactory(this)
        recoveryCoordinator = container?.let { StreamRecoveryCoordinator(it.videoService) }
        snapshotStore = PlaybackSnapshotStore(this)

        // Propagate PlaybackPreferences / DataStore policy synchronously/cached before restore
        val prefs = container?.playbackPreferences
        if (prefs != null) {
            serviceScope.launch {
                prefs.isBackgroundPlaybackEnabled.collect { bg ->
                    backgroundPlaybackEnabled = bg
                }
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(playerListener)
        registerAnalyticsListener(player, mediaOperationGeneration)
        exoPlayer = player

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(SessionCallback())
            .build()

        restorePersistedSession(prefs)
    }

    private fun restorePersistedSession(
        prefs: com.flowtube.app.settings.PlaybackPreferences?,
        totalTimeoutMs: Long = 15000L
    ) {
        val app = application as? FlowTubeApplication ?: return
        val restoreRequest = prepareRequestGeneration
        serviceScope.launch {
            try {
                kotlinx.coroutines.withTimeout(totalTimeoutMs) {
                    val authoritativeBackgroundAllowed: Boolean? = if (prefs != null) {
                        try {
                            withContext(Dispatchers.IO) {
                                prefs.isBackgroundPlaybackEnabled.first()
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            null
                        }
                    } else null

                    if (authoritativeBackgroundAllowed != null) {
                        backgroundPlaybackEnabled = authoritativeBackgroundAllowed
                    }

                    val snapshot = withContext(Dispatchers.IO) {
                        snapshotStore?.loadForServiceRestore()
                    }
                    if (snapshot == null) {
                        snapshotStore?.clear()
                        return@withTimeout
                    }

                    val effectivePlayWhenReady = if (authoritativeBackgroundAllowed != true) false else snapshot.playWhenReady
                    val streamResult = withContext(Dispatchers.IO) {
                        app.container.videoService.streamInfo(snapshot.key)
                    }

                    if (streamResult !is AppResult.Success) {
                        snapshotStore?.clear()
                        return@withTimeout
                    }

                    if (!isReleased && restoreRequest == prepareRequestGeneration) {
                        prepareInternal(
                            key = snapshot.key,
                            streamInfo = streamResult.value,
                            startPositionMs = snapshot.positionMs,
                            playWhenReady = effectivePlayWhenReady,
                            initialQuality = snapshot.selectedQuality,
                            playbackSpeed = snapshot.playbackSpeed
                        )
                    }
                }
            } catch (tce: kotlinx.coroutines.TimeoutCancellationException) {
                snapshotStore?.clear()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                snapshotStore?.clear()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private fun registerAnalyticsListener(player: ExoPlayer, token: Long) {
        activeAnalyticsListener?.let { player.removeAnalyticsListener(it) }
        val listener = object : AnalyticsListener {
            private val boundToken = token
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

    private val playerListener = object : Player.Listener {
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

            lastReportedAppError = appError
            val key = currentKey
            val currentSession = playbackSessionGeneration

            if (appError == AppError.StreamExpired && recoveryCoordinator != null && key != null) {
                val preference = currentSelectedQuality?.let { QualityPreference.SpecificOption(it) } ?: QualityPreference.Auto
                val currentPos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
                recoveryJob?.cancel()
                recoveryJob = serviceScope.launch(Dispatchers.Main) {
                    if (isReleased || currentKey != key || playbackSessionGeneration != currentSession) return@launch
                    val recoveryResult = recoveryCoordinator!!.recoverExpiredStream(
                        key = key,
                        sessionGen = currentSession,
                        positionMs = currentPos,
                        wasPlaying = userRequestedPlay,
                        preference = preference
                    )
                    if (isReleased || currentKey != key || playbackSessionGeneration != currentSession) return@launch
                    when (recoveryResult) {
                        is RecoveryResult.Recovered -> {
                            prepareInternal(
                                key = recoveryResult.key,
                                streamInfo = recoveryResult.streamInfo,
                                startPositionMs = recoveryResult.resumePositionMs,
                                playWhenReady = recoveryResult.resumeWhenReady,
                                initialQuality = recoveryResult.selectedQuality
                            )
                        }
                        is RecoveryResult.Failed -> {
                            lastReportedAppError = recoveryResult.error
                        }
                        RecoveryResult.Cancelled -> {
                            lastReportedAppError = AppError.StreamExpired
                        }
                    }
                }
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            persistCurrentSnapshot()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            persistCurrentSnapshot()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            persistCurrentSnapshot()
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

    private fun persistCurrentSnapshot() {
        if (isReleased) return
        val key = currentKey ?: return
        val player = exoPlayer ?: return
        val store = snapshotStore ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val pwr = player.playWhenReady
        val speed = player.playbackParameters.speed
        val snapshot = PlaybackSnapshot(
            key = key,
            positionMs = pos,
            playWhenReady = pwr,
            selectedQuality = currentSelectedQuality,
            playbackSpeed = speed
        )
        val token = store.enqueueSave()
        serviceScope.launch(Dispatchers.IO) {
            store.executeSave(snapshot, token)
        }
    }

    private fun cancelActiveQuality() {
        activeQualityFuture?.let { prev ->
            activeQualityFuture = null
            if (!prev.isDone) {
                prev.set(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
            }
        }
    }

    private fun prepareInternal(
        key: ContentKey,
        streamInfo: StreamInfo,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
        initialQuality: QualityOption? = null,
        playbackSpeed: Float = 1.0f
    ) {
        if (isReleased) return

        cancelActiveQuality()
        mediaOpJob?.cancel()
        recoveryJob?.cancel()
        val currentToken = ++mediaOperationGeneration
        val currentSession = ++playbackSessionGeneration

        currentKey = key
        currentStreamInfo = streamInfo
        userRequestedPlay = playWhenReady
        lastReportedAppError = null

        val available = StreamSelector.getAvailableQualities(streamInfo)
        availableQualities = available

        mediaOpJob = serviceScope.launch(Dispatchers.Main) {
            val pref = if (initialQuality != null) {
                val matched = available.firstOrNull { it == initialQuality }
                if (matched != null) QualityPreference.SpecificOption(matched) else QualityPreference.Auto
            } else {
                QualityPreference.Auto
            }

            val selectionAndSource = withContext(Dispatchers.IO) {
                val selectionResult = StreamSelector.selectStream(streamInfo, pref)
                when (selectionResult) {
                    is AppResult.Success -> {
                        val selected = selectionResult.value.copy(title = streamInfo.title)
                        val source = mediaSourceFactory?.createMediaSource(selected)
                        if (source != null) {
                            AppResult.Success(Pair(selected, source))
                        } else {
                            AppResult.Failure(AppError.UnsupportedFormat)
                        }
                    }
                    is AppResult.Failure -> AppResult.Failure(selectionResult.error)
                }
            }

            if (currentToken != mediaOperationGeneration || currentKey != key || isReleased) return@launch

            when (selectionAndSource) {
                is AppResult.Success -> {
                    val (selected, mediaSource) = selectionAndSource.value
                    currentStreamType = selected.streamType
                    currentSelectedQuality = when (selected.streamType) {
                        PlaybackStreamType.PROGRESSIVE -> {
                            selected.videoStream?.let { vs ->
                                available.firstOrNull {
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
                                available.firstOrNull {
                                    !it.isProgressive &&
                                            it.height == (vs.height ?: 0) &&
                                            it.format.equals(vs.format, ignoreCase = true) &&
                                            it.mimeType?.trim()?.lowercase() == vs.mimeType?.trim()?.lowercase() &&
                                            it.codec?.trim()?.lowercase() == vs.codec?.trim()?.lowercase()
                                }
                            }
                        }
                        PlaybackStreamType.HLS -> available.firstOrNull { it.streamType == PlaybackStreamType.HLS }
                        PlaybackStreamType.DASH -> available.firstOrNull { it.streamType == PlaybackStreamType.DASH }
                        PlaybackStreamType.AUDIO_ONLY -> null
                    }

                    exoPlayer?.let { player ->
                        registerAnalyticsListener(player, currentToken)
                        player.setMediaSource(mediaSource)
                        if (startPositionMs > 0L) {
                            player.seekTo(startPositionMs)
                        }
                        player.playWhenReady = playWhenReady
                        player.playbackParameters = androidx.media3.common.PlaybackParameters(
                            playbackSpeed.takeIf { it.isFinite() }?.coerceIn(0.25f, 3.0f) ?: 1.0f
                        )
                        player.prepare()
                    }
                    persistCurrentSnapshot()
                }
                is AppResult.Failure -> {
                    lastReportedAppError = selectionAndSource.error
                }
            }
        }
    }

    private fun selectQualityInternal(
        quality: QualityOption,
        completion: SettableFuture<SessionResult>? = null
    ) {
        // Complete any previously active quality future with invalid/cancelled state
        cancelActiveQuality()
        activeQualityFuture = completion

        if (isReleased) {
            completion?.set(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
            return
        }
        val targetKey = currentKey ?: run {
            completion?.set(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
            return
        }
        val streamInfo = currentStreamInfo ?: run {
            completion?.set(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
            return
        }

        mediaOpJob?.cancel()
        val currentToken = ++mediaOperationGeneration

        mediaOpJob = serviceScope.launch(Dispatchers.Main) {
            val player = exoPlayer ?: run {
                completion?.set(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                return@launch
            }
            val matched = availableQualities.firstOrNull { it == quality } ?: run {
                completion?.set(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                return@launch
            }
            val currentPos = player.currentPosition
            val wasPlaying = player.isPlaying || player.playWhenReady

            val pref = QualityPreference.SpecificOption(matched)
            val selectionAndSource = withContext(Dispatchers.IO) {
                val selectionResult = StreamSelector.selectStream(streamInfo, pref)
                when (selectionResult) {
                    is AppResult.Success -> {
                        val selected = selectionResult.value.copy(title = streamInfo.title)
                        val source = mediaSourceFactory?.createMediaSource(selected)
                        if (source != null) {
                            AppResult.Success(Pair(selected, source))
                        } else {
                            AppResult.Failure(AppError.UnsupportedFormat)
                        }
                    }
                    is AppResult.Failure -> AppResult.Failure(selectionResult.error)
                }
            }

            if (currentToken != mediaOperationGeneration || currentKey != targetKey || isReleased) {
                completion?.set(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                return@launch
            }

            when (selectionAndSource) {
                is AppResult.Success -> {
                    val (selected, mediaSource) = selectionAndSource.value
                    currentStreamType = selected.streamType
                    currentSelectedQuality = when (selected.streamType) {
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
                        PlaybackStreamType.HLS -> availableQualities.firstOrNull { it.streamType == PlaybackStreamType.HLS }
                        PlaybackStreamType.DASH -> availableQualities.firstOrNull { it.streamType == PlaybackStreamType.DASH }
                        PlaybackStreamType.AUDIO_ONLY -> null
                    }

                    registerAnalyticsListener(player, currentToken)
                    player.setMediaSource(mediaSource)
                    player.seekTo(currentPos)
                    player.playWhenReady = wasPlaying
                    player.prepare()
                    persistCurrentSnapshot()

                    val successBundle = Bundle().apply {
                        putLong(EXTRA_PROBE_SESSION_GEN, playbackSessionGeneration)
                        putLong(EXTRA_PROBE_MEDIA_GEN, mediaOperationGeneration)
                        putString(EXTRA_PROBE_STREAM_TYPE, currentStreamType?.name ?: "")
                        currentSelectedQuality?.let { q ->
                            putInt(EXTRA_QUALITY_HEIGHT, q.height)
                            putString(EXTRA_QUALITY_LABEL, q.label)
                            putBoolean(EXTRA_QUALITY_IS_PROGRESSIVE, q.isProgressive)
                            putString(EXTRA_QUALITY_FORMAT, q.format)
                            putString(EXTRA_QUALITY_MIME, q.mimeType)
                            putString(EXTRA_QUALITY_CODEC, q.codec)
                            putString(EXTRA_QUALITY_STREAM_TYPE, q.streamType.name)
                        }
                    }
                    completion?.set(SessionResult(SessionResult.RESULT_SUCCESS, successBundle))
                }
                is AppResult.Failure -> {
                    lastReportedAppError = selectionAndSource.error
                    val errBundle = Bundle().apply {
                        putString(EXTRA_PROBE_ERROR_CODE, selectionAndSource.error.javaClass.simpleName)
                    }
                    completion?.set(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE, errBundle))
                }
            }
        }
    }

    private fun clearMediaInternal() {
        val key = currentKey
        val streamInfo = currentStreamInfo
        val player = exoPlayer
        if (key != null && player != null) {
            val app = application as? FlowTubeApplication
            val historyRepo = app?.container?.historyRepository
            if (historyRepo != null) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val summary = VideoSummary(
                    key = key,
                    title = streamInfo?.title ?: "Video",
                    canonicalUrl = "https://flowtube.test/watch?v=${key.nativeId}",
                    channelKey = null,
                    channelName = null,
                    channelAvatarUrl = null,
                    thumbnailUrl = null,
                    durationSeconds = if (player.duration > 0) player.duration / 1000L else null,
                    viewCount = null,
                    publishedTimestamp = null
                )
                serviceScope.launch(Dispatchers.IO) {
                    historyRepo.recordHistory(summary, pos)
                }
            }
        }

        currentKey = null
        currentStreamInfo = null
        currentSelectedQuality = null
        availableQualities = emptyList()
        currentStreamType = null
        lastReportedAppError = null
        cancelActiveQuality()
        prepareRequestGeneration++
        playbackSessionGeneration++
        mediaOperationGeneration++
        mediaOpJob?.cancel()
        recoveryJob?.cancel()
        PlaybackStreamHandoff.clear()
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        snapshotStore?.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val isAuthorized = PlaybackPolicy.isControllerAuthorized(
                expectedPackageName = application.packageName,
                expectedUid = android.os.Process.myUid(),
                controllerPackage = controller.packageName,
                controllerUid = controller.uid
            )
            if (!isAuthorized) {
                return MediaSession.ConnectionResult.reject()
            }
            val availableCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(SessionCommand(CUSTOM_COMMAND_PREPARE_STREAM, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_SELECT_QUALITY, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_GET_PROBE_SNAPSHOT, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_CLEAR_MEDIA, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_SET_BACKGROUND_ENABLED, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(availableCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val isAuthorized = PlaybackPolicy.isControllerAuthorized(
                expectedPackageName = application.packageName,
                expectedUid = android.os.Process.myUid(),
                controllerPackage = controller.packageName,
                controllerUid = controller.uid
            )
            if (!isAuthorized) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            when (customCommand.customAction) {
                CUSTOM_COMMAND_PREPARE_STREAM -> {
                    val serviceId = args.getInt(EXTRA_SERVICE_ID, 0)
                    val nativeId = args.getString(EXTRA_NATIVE_ID, "")
                    val completion = SettableFuture.create<SessionResult>()
                    if (nativeId.isNotBlank()) {
                        val requestGeneration = ++prepareRequestGeneration
                        val key = ContentKey(serviceId, nativeId)
                        val startPos = args.getLong(EXTRA_POSITION_MS, 0L)
                        val playWhenReady = args.getBoolean(EXTRA_PLAY_WHEN_READY, true)
                        val speed = args.getFloat(EXTRA_SPEED, 1.0f)
                        val incomingReqGen = args.getLong(EXTRA_REQUEST_GENERATION, 0L)
                        val handoffStreamInfo = PlaybackStreamHandoff.takeConditional(
                            args.getString(EXTRA_HANDOFF_TOKEN),
                            expectedRequestGen = incomingReqGen
                        )

                        val initialQuality = if (args.containsKey(EXTRA_QUALITY_HEIGHT)) {
                            val height = args.getInt(EXTRA_QUALITY_HEIGHT, 0)
                            val label = args.getString(EXTRA_QUALITY_LABEL, "")
                            val isProg = args.getBoolean(EXTRA_QUALITY_IS_PROGRESSIVE, true)
                            val format = args.getString(EXTRA_QUALITY_FORMAT, "")
                            val mime = args.getString(EXTRA_QUALITY_MIME)
                            val codec = args.getString(EXTRA_QUALITY_CODEC)
                            val streamTypeStr = args.getString(EXTRA_QUALITY_STREAM_TYPE, "")
                            val st = try {
                                PlaybackStreamType.valueOf(streamTypeStr)
                            } catch (_: Throwable) {
                                if (isProg) PlaybackStreamType.PROGRESSIVE else PlaybackStreamType.MERGED_AV
                            }
                            QualityOption(
                                height = height,
                                label = label,
                                isProgressive = isProg,
                                format = format,
                                mimeType = mime,
                                codec = codec,
                                streamType = st
                            )
                        } else {
                            null
                        }

                        // If streamInfo is passed through memory or resolved
                        if (handoffStreamInfo != null && handoffStreamInfo.key == key) {
                            if (!isReleased && requestGeneration == prepareRequestGeneration) {
                                prepareInternal(
                                    key = key,
                                    streamInfo = handoffStreamInfo,
                                    startPositionMs = startPos,
                                    playWhenReady = playWhenReady,
                                    initialQuality = initialQuality,
                                    playbackSpeed = speed
                                )
                                val successResultBundle = Bundle().apply {
                                    putLong(EXTRA_PROBE_SESSION_GEN, playbackSessionGeneration)
                                    putLong(EXTRA_PROBE_MEDIA_GEN, mediaOperationGeneration)
                                }
                                completion.set(SessionResult(SessionResult.RESULT_SUCCESS, successResultBundle))
                            } else {
                                completion.set(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                            }
                        } else {
                            val app = application as? FlowTubeApplication
                            val videoService = app?.container?.videoService
                            if (videoService == null) {
                                lastReportedAppError = AppError.Unknown
                                val errBundle = Bundle().apply {
                                    putString(EXTRA_PROBE_ERROR_CODE, AppError.Unknown.javaClass.simpleName)
                                }
                                completion.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN, errBundle))
                                return completion
                            }
                            serviceScope.launch(Dispatchers.Main) {
                                try {
                                    val streamResult = withContext(Dispatchers.IO) {
                                        videoService.streamInfo(key)
                                    }
                                    if (isReleased || requestGeneration != prepareRequestGeneration) {
                                        completion.set(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                                    } else if (streamResult is AppResult.Success) {
                                        prepareInternal(
                                            key = key,
                                            streamInfo = streamResult.value,
                                            startPositionMs = startPos,
                                            playWhenReady = playWhenReady,
                                            initialQuality = initialQuality,
                                            playbackSpeed = speed
                                        )
                                        val successResultBundle = Bundle().apply {
                                            putLong(EXTRA_PROBE_SESSION_GEN, playbackSessionGeneration)
                                            putLong(EXTRA_PROBE_MEDIA_GEN, mediaOperationGeneration)
                                        }
                                        completion.set(SessionResult(SessionResult.RESULT_SUCCESS, successResultBundle))
                                    } else if (streamResult is AppResult.Failure) {
                                        lastReportedAppError = streamResult.error
                                        val errBundle = Bundle().apply {
                                            putString(EXTRA_PROBE_ERROR_CODE, streamResult.error.javaClass.simpleName)
                                        }
                                        completion.set(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE, errBundle))
                                    }
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (_: Exception) {
                                    if (!completion.isDone) {
                                        lastReportedAppError = AppError.Unknown
                                        val errBundle = Bundle().apply {
                                            putString(EXTRA_PROBE_ERROR_CODE, AppError.Unknown.javaClass.simpleName)
                                        }
                                        completion.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN, errBundle))
                                    }
                                }
                            }
                        }
                    } else {
                        val errBundle = Bundle().apply {
                            putString(EXTRA_PROBE_ERROR_CODE, AppError.ContentUnavailable.javaClass.simpleName)
                        }
                        completion.set(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE, errBundle))
                    }
                    return completion
                }
                CUSTOM_COMMAND_SELECT_QUALITY -> {
                    val height = args.getInt(EXTRA_QUALITY_HEIGHT, 0)
                    val label = args.getString(EXTRA_QUALITY_LABEL, "")
                    val isProg = args.getBoolean(EXTRA_QUALITY_IS_PROGRESSIVE, true)
                    val format = args.getString(EXTRA_QUALITY_FORMAT, "")
                    val mime = args.getString(EXTRA_QUALITY_MIME)
                    val codec = args.getString(EXTRA_QUALITY_CODEC)
                    val streamTypeStr = args.getString(EXTRA_QUALITY_STREAM_TYPE, "")
                    val st = try {
                        PlaybackStreamType.valueOf(streamTypeStr)
                    } catch (_: Exception) {
                        if (isProg) PlaybackStreamType.PROGRESSIVE else PlaybackStreamType.MERGED_AV
                    }
                    val opt = QualityOption(
                        height = height,
                        label = label,
                        isProgressive = isProg,
                        format = format,
                        mimeType = mime,
                        codec = codec,
                        streamType = st
                    )
                    val expectedServiceId = args.getInt(EXTRA_SERVICE_ID, Int.MIN_VALUE)
                    val expectedNativeId = args.getString(EXTRA_NATIVE_ID)
                    val expectedSession = args.getLong(EXTRA_EXPECTED_SESSION_GENERATION, -1L)
                    val matchesActiveSession = currentKey?.let {
                        it.serviceId == expectedServiceId && it.nativeId == expectedNativeId &&
                            (expectedSession == -1L || expectedSession == playbackSessionGeneration)
                    } == true
                    if (!matchesActiveSession || availableQualities.none { it == opt }) {
                        lastReportedAppError = AppError.UnsupportedFormat
                        val errBundle = Bundle().apply {
                            putString(EXTRA_PROBE_ERROR_CODE, AppError.UnsupportedFormat.javaClass.simpleName)
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE, errBundle))
                    }
                    val completion = SettableFuture.create<SessionResult>()
                    selectQualityInternal(opt, completion)
                    return completion
                }
                CUSTOM_COMMAND_SET_BACKGROUND_ENABLED -> {
                    val bgEnabled = args.getBoolean(EXTRA_BACKGROUND_ENABLED, false)
                    val pipActive = args.getBoolean(EXTRA_PIP_ACTIVE_OR_ENTERING, false)
                    backgroundPlaybackEnabled = bgEnabled
                    isPipActiveOrEntering = pipActive
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_COMMAND_CLEAR_MEDIA -> {
                    clearMediaInternal()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_COMMAND_GET_PROBE_SNAPSHOT -> {
                    val resultBundle = Bundle().apply {
                        putLong(EXTRA_PROBE_MEDIA_GEN, mediaOperationGeneration)
                        putLong(EXTRA_PROBE_SESSION_GEN, playbackSessionGeneration)
                        putInt(EXTRA_PROBE_RENDERED_COUNT, renderedFirstFrameCounters[mediaOperationGeneration] ?: 0)
                        putInt(EXTRA_PROBE_AUDIO_DECODER_COUNT, audioDecoderInitCounters[mediaOperationGeneration] ?: 0)
                        putInt(EXTRA_PROBE_VIDEO_DECODER_COUNT, videoDecoderInitCounters[mediaOperationGeneration] ?: 0)
                        currentKey?.let {
                            putInt(EXTRA_PROBE_SERVICE_ID, it.serviceId)
                            putString(EXTRA_PROBE_NATIVE_ID, it.nativeId)
                        }
                        putString(EXTRA_PROBE_TITLE, currentStreamInfo?.title ?: "")
                        putString(EXTRA_PROBE_STREAM_TYPE, currentStreamType?.name ?: "")
                        putString(EXTRA_PROBE_ERROR_CODE, lastReportedAppError?.javaClass?.simpleName ?: "")
                        currentSelectedQuality?.let { quality ->
                            putBoolean(EXTRA_PROBE_QUALITY_PRESENT, true)
                            putInt(EXTRA_QUALITY_HEIGHT, quality.height)
                            putString(EXTRA_QUALITY_LABEL, quality.label)
                            putBoolean(EXTRA_QUALITY_IS_PROGRESSIVE, quality.isProgressive)
                            putString(EXTRA_QUALITY_FORMAT, quality.format)
                            putString(EXTRA_QUALITY_MIME, quality.mimeType)
                            putString(EXTRA_QUALITY_CODEC, quality.codec)
                            putString(EXTRA_QUALITY_STREAM_TYPE, quality.streamType.name)
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, resultBundle))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    override fun onDestroy() {
        isReleased = true
        cancelActiveQuality()
        mediaOpJob?.cancel()
        recoveryJob?.cancel()

        // Synchronously-safe persist final snapshot before player release
        persistFinalSnapshotSync()

        serviceScope.cancel()

        activeAnalyticsListener?.let { exoPlayer?.removeAnalyticsListener(it) }
        activeAnalyticsListener = null

        mediaSession?.run {
            player.removeListener(playerListener)
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        super.onDestroy()
    }

    private fun persistFinalSnapshotSync() {
        val key = currentKey ?: return
        val player = exoPlayer ?: return
        val store = snapshotStore ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val pwr = player.playWhenReady
        val speed = player.playbackParameters.speed
        val snapshot = PlaybackSnapshot(
            key = key,
            positionMs = pos,
            playWhenReady = pwr,
            selectedQuality = currentSelectedQuality,
            playbackSpeed = speed
        )
        store.saveSync(snapshot)
    }
}
