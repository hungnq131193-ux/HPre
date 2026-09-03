package com.hpre.app.player

import android.app.PendingIntent
import android.app.ActivityManager
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.hpre.app.HPreApplication
import com.hpre.app.MainActivity
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.core.performance.VideoOpenEvent
import com.hpre.app.core.performance.VideoOpenMetrics
import com.hpre.app.core.performance.VideoOpenSession
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoSummary
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

internal fun shouldDisarmBufferingWatchdog(
    renderedFirstFrameCount: Int,
    streamType: PlaybackStreamType?,
    playbackState: Int
): Boolean {
    if (renderedFirstFrameCount > 0) return true
    if (streamType == PlaybackStreamType.AUDIO_ONLY && playbackState == Player.STATE_READY) return true
    if (playbackState == Player.STATE_ENDED) return true
    return false
}

internal fun decideSessionRestore(
    alreadyEvaluated: Boolean,
    isPrewarm: Boolean
): Boolean {
    if (alreadyEvaluated) return false
    return !isPrewarm
}

/**
 * Service session restore decision.
 *
 * Connection hints from the same UID are trusted because only app-internal authorized controllers
 * (verified via PlaybackPolicy.isControllerAuthorized matching packageName and Process.myUid()) are accepted.
 */
internal fun decideSessionRestore(
    alreadyEvaluated: Boolean,
    connectionHints: Bundle?
): Boolean {
    val isPrewarm = connectionHints?.getBoolean(HPrePlaybackService.KEY_INFRASTRUCTURE_PREWARM, false) == true
    return decideSessionRestore(alreadyEvaluated, isPrewarm)
}

@OptIn(UnstableApi::class)
class HPrePlaybackService : MediaSessionService() {

    companion object {
        const val KEY_INFRASTRUCTURE_PREWARM = "extra_infrastructure_prewarm"

        const val CUSTOM_COMMAND_SELECT_QUALITY = "com.hpre.app.CUSTOM_COMMAND_SELECT_QUALITY"
        const val CUSTOM_COMMAND_SET_QUALITY_POLICY = "com.hpre.app.CUSTOM_COMMAND_SET_QUALITY_POLICY"
        const val CUSTOM_COMMAND_PREPARE_STREAM = "com.hpre.app.CUSTOM_COMMAND_PREPARE_STREAM"
        const val CUSTOM_COMMAND_GET_PROBE_SNAPSHOT = "com.hpre.app.CUSTOM_COMMAND_GET_PROBE_SNAPSHOT"
        const val CUSTOM_COMMAND_CLEAR_MEDIA = "com.hpre.app.CUSTOM_COMMAND_CLEAR_MEDIA"
        const val CUSTOM_COMMAND_STOP_FOR_TRANSITION = "com.hpre.app.CUSTOM_COMMAND_STOP_FOR_TRANSITION"
        const val CUSTOM_COMMAND_SET_BACKGROUND_ENABLED = "com.hpre.app.CUSTOM_COMMAND_SET_BACKGROUND_ENABLED"
        const val CUSTOM_COMMAND_TERMINAL_ERROR = "com.hpre.app.CUSTOM_COMMAND_TERMINAL_ERROR"

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
        const val EXTRA_POLICY_AUTO = "extra_policy_auto"
        const val EXTRA_POLICY_MAX_HEIGHT = "extra_policy_max_height"
        const val EXTRA_POLICY_MAX_BITRATE = "extra_policy_max_bitrate"

        internal const val MIN_PLAYBACK_BUFFER_MS = 30_000
        internal const val MAX_PLAYBACK_BUFFER_MS = 90_000
        internal const val BUFFER_FOR_PLAYBACK_MS = 750
        internal const val BUFFER_AFTER_REBUFFER_MS = 8_000
        internal const val BUFFERING_WATCHDOG_TIMEOUT_MS = 15_000L
        private const val ANALYTICS_COUNTER_GENERATIONS = 8L

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

        /** Minimum gap between coalesced snapshot writes. */
        internal const val SNAPSHOT_THROTTLE_MS = 1_000L

    }

    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val historyScheduler by lazy {
        PlaybackHistoryScheduler(serviceScope, onWrite = ::recordCurrentHistory)
    }

    private var mediaSourceFactory: MediaSourceCreator? = null
    private var recoveryCoordinator: StreamRecoveryCoordinator? = null
    private var snapshotStore: PlaybackSnapshotStore? = null

    private var currentKey: ContentKey? = null
    private var currentStreamInfo: StreamInfo? = null
    private var currentSelectedQuality: QualityOption? = null
    private var currentQualityPolicy: UserQualityPolicy = UserQualityPolicy.Auto()
    private var currentEffectiveTrack: EffectiveTrack? = null
    private var availableQualities: List<QualityOption> = emptyList()
    private var currentStreamType: PlaybackStreamType? = null

    private var mediaOpJob: Job? = null
    private var recoveryJob: Job? = null
    private var prepareStreamJob: Job? = null
    private var prepareStreamCompletion: SettableFuture<SessionResult>? = null
    private var mediaOperationGeneration: Long = 0L
    private var playbackSessionGeneration: Long = 0L
    // Automatic source rebuilds belong to the same user playback request and share its retry budget.
    private var recoverySessionGeneration: Long = 0L
    private val attemptedSourceTypes = mutableSetOf<PlaybackStreamType>()
    private var prepareRequestGeneration: Long = 0L

    private var userRequestedPlay: Boolean = true
    private var isReleased: Boolean = false
    private var backgroundPlaybackEnabled: Boolean = false
    private var isPipActiveOrEntering: Boolean = false

    private var lastReportedAppError: AppError? = null
    private var activeQualityFuture: SettableFuture<SessionResult>? = null
    private var lastThrottledSnapshotMs: Long = 0L

    private val renderedFirstFrameCounters = mutableMapOf<Long, Int>()
    private val audioDecoderInitCounters = mutableMapOf<Long, Int>()
    private val videoDecoderInitCounters = mutableMapOf<Long, Int>()
    private var activeAnalyticsListener: AnalyticsListener? = null
    private var metricsReadySessionGeneration: Long = -1L
    private var metricsFirstFrameMediaGeneration: Long = -1L
    private var activeMetricsSession: VideoOpenSession? = null
    private var activeMetricsPlaybackGeneration: Long = -1L
    private var activeMetricsMediaGeneration: Long = -1L
    private var startupCeilingMediaGeneration: Long? = null

    private var bufferingWatchdog: BufferingWatchdog? = null

    override fun onCreate() {
        super.onCreate()
        HPreMediaNotification.ensureNotificationChannel(this)
        setMediaNotificationProvider(HPreMediaNotification.createNotificationProvider(this))

        val app = application as? HPreApplication
        val container = app?.container
        val okHttpClient = container?.okHttpClient
        mediaSourceFactory = container?.mediaSourceFactory ?: MediaSourceFactory(this)
        recoveryCoordinator = container?.let { StreamRecoveryCoordinator(it.videoService) }
        snapshotStore = (application as? HPreApplication)?.container?.playbackSnapshotStore
            ?: PlaybackSnapshotStore(this)

        bufferingWatchdog = BufferingWatchdog(
            scope = serviceScope,
            dispatcher = Dispatchers.Main,
            timeoutMs = BUFFERING_WATCHDOG_TIMEOUT_MS,
            onTimeout = { sessionGen, _ ->
                currentKey?.let { key ->
                    triggerBufferingRecovery(key, sessionGen)
                }
            }
        )

        // Propagate PlaybackPreferences / DataStore policy synchronously/cached before restore
        val prefs = container?.playbackPreferences
        if (prefs != null) {
            serviceScope.launch {
                prefs.isBackgroundPlaybackEnabled.collect { bg ->
                    backgroundPlaybackEnabled = bg
                }
            }
        }

        ensurePlayerAndSessionInitialized()
    }

    private var sessionRestoreEvaluated: Boolean = false

    internal fun onControllerConnected(connectionHints: Bundle?) {
        val shouldRestore = decideSessionRestore(sessionRestoreEvaluated, connectionHints)
        sessionRestoreEvaluated = true
        if (shouldRestore) {
            val app = application as? HPreApplication
            val prefs = app?.container?.playbackPreferences
            restorePersistedSession(prefs)
        }
    }

    private fun ensurePlayerAndSessionInitialized() {
        if (exoPlayer != null && mediaSession != null) return

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val selector = DefaultTrackSelector(this)
        trackSelector = selector
        val memory = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val loadControl = MemoryAwareLoadControl(
            PlaybackMemoryBudget.targetBytes(memory?.memoryClass ?: 128, memory?.isLowRamDevice == true)
        )
        val player = ExoPlayer.Builder(this)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
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
    }

    private fun restorePersistedSession(
        prefs: com.hpre.app.settings.PlaybackPreferences?,
        totalTimeoutMs: Long = 15000L
    ) {
        val app = application as? HPreApplication ?: return
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
                        currentQualityPolicy = snapshot.qualityPolicy
                        prepareInternal(
                            key = snapshot.key,
                            streamInfo = streamResult.value,
                            startPositionMs = snapshot.positionMs,
                            playWhenReady = effectivePlayWhenReady,
                            initialQuality = (snapshot.qualityPolicy as? UserQualityPolicy.Fixed)?.option,
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
        ensurePlayerAndSessionInitialized()
        return mediaSession
    }

    private fun registerAnalyticsListener(player: ExoPlayer, token: Long) {
        activeAnalyticsListener?.let { player.removeAnalyticsListener(it) }
        val oldestRetainedToken = (token - ANALYTICS_COUNTER_GENERATIONS + 1L).coerceAtLeast(0L)
        renderedFirstFrameCounters.keys.removeAll { it < oldestRetainedToken }
        audioDecoderInitCounters.keys.removeAll { it < oldestRetainedToken }
        videoDecoderInitCounters.keys.removeAll { it < oldestRetainedToken }
        val listener = object : AnalyticsListener {
            private val boundToken = token
            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                if (isReleased) return
                renderedFirstFrameCounters[boundToken] = (renderedFirstFrameCounters[boundToken] ?: 0) + 1
                if (boundToken == activeMetricsMediaGeneration &&
                    metricsFirstFrameMediaGeneration != boundToken
                ) {
                    metricsFirstFrameMediaGeneration = boundToken
                    activeMetricsSession?.let { session ->
                        VideoOpenMetrics.Default.finish(session, VideoOpenEvent.FIRST_FRAME)
                    }
                    activeMetricsSession = null
                }
                if (shouldReleaseStartupCeiling(
                        boundToken = boundToken,
                        activeToken = mediaOperationGeneration,
                        policy = currentQualityPolicy,
                        ceilingApplied = startupCeilingMediaGeneration == boundToken
                    )
                ) {
                    startupCeilingMediaGeneration = null
                    applyQualityPolicy(currentQualityPolicy)
                }
                checkBufferingWatchdog()
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
            val recoveryDecision = PlaybackRecoveryPolicy.decide(error)
            val appError = recoveryDecision.error

            lastReportedAppError = appError
            val key = currentKey
            val currentSession = playbackSessionGeneration
            activeMetricsSession?.let { session ->
                VideoOpenMetrics.Default.mark(
                    session,
                    VideoOpenEvent.PLAYBACK_ERROR,
                    "${appError.javaClass.simpleName}:${currentStreamType?.name ?: "UNKNOWN"}"
                )
            }

            if (appError == AppError.UnsupportedFormat && key != null) {
                val streamInfo = currentStreamInfo
                val failedType = currentStreamType
                // Reuse the list computed during prepareInternal for this same streamInfo. Rebuilding
                // it runs codec/container regexes over every stream, and this ran up to three times
                // per fallback.
                val available = if (streamInfo != null && availableQualities.isNotEmpty()) {
                    availableQualities
                } else {
                    streamInfo?.let(StreamSelector::getAvailableQualities).orEmpty()
                }
                val fallbackPreference = when (failedType) {
                    PlaybackStreamType.HLS -> if (streamInfo?.dashManifestUrl.isNullOrBlank()) {
                        QualityPreference.ExactOrBelow(
                            (currentQualityPolicy as? UserQualityPolicy.Auto)?.maxHeight ?: Int.MAX_VALUE
                        )
                    } else {
                        available
                            .firstOrNull { it.streamType == PlaybackStreamType.DASH }
                            ?.let(QualityPreference::SpecificOption)
                    }
                    PlaybackStreamType.DASH -> QualityPreference.ExactOrBelow(
                        (currentQualityPolicy as? UserQualityPolicy.Auto)?.maxHeight ?: Int.MAX_VALUE
                    )
                    else -> null
                }
                if (failedType != null) attemptedSourceTypes += failedType
                if (fallbackPreference != null && streamInfo != null) {
                    val fallbackResult = StreamSelector.selectStream(streamInfo, fallbackPreference)
                    val fallback = (fallbackResult as? AppResult.Success)?.value
                    if (fallback != null && fallback.streamType !in attemptedSourceTypes) {
                        attemptedSourceTypes += fallback.streamType
                        val position = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
                        prepareInternal(
                            key = key,
                            streamInfo = streamInfo,
                            startPositionMs = position,
                            playWhenReady = userRequestedPlay,
                            initialQuality = if (fallback.streamType == PlaybackStreamType.PROGRESSIVE ||
                                fallback.streamType == PlaybackStreamType.MERGED_AV
                            ) available.firstOrNull {
                                it.streamType == fallback.streamType && it.height == fallback.videoStream?.height
                            } else available.firstOrNull {
                                it.streamType == fallback.streamType
                            },
                            preserveSourceAttempts = true
                        )
                        return
                    }
                }
            }

            if (recoveryDecision.shouldRefresh && recoveryCoordinator != null && key != null) {
                val preference = currentSelectedQuality?.let { QualityPreference.SpecificOption(it) } ?: QualityPreference.Auto
                startBoundedRecovery(key, currentSession, preference)
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            persistCurrentSnapshot()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Buffering flips this repeatedly during normal playback. Each write is a DataStore edit
            // plus an atomic file rename, so unthrottled writes here produce steady IO churn while
            // watching. Position/speed are captured by the other callbacks and by onDestroy.
            if (playbackState == Player.STATE_READY &&
                activeMetricsPlaybackGeneration == playbackSessionGeneration &&
                metricsReadySessionGeneration != activeMetricsPlaybackGeneration
            ) {
                metricsReadySessionGeneration = activeMetricsPlaybackGeneration
                activeMetricsSession?.let { session ->
                    VideoOpenMetrics.Default.mark(session, VideoOpenEvent.PLAYER_READY)
                }
            }
            checkBufferingWatchdog()
            persistCurrentSnapshotThrottled()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            persistCurrentSnapshot()
            historyScheduler.update(isPlaying && currentStreamInfo?.isLive != true)
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            val selectedVideoGroups = tracks.groups.filter {
                it.type == C.TRACK_TYPE_VIDEO && it.isSelected
            }
            val selectedFormat = selectedVideoGroups.asSequence()
                .flatMap { group -> (0 until group.length).asSequence().map { group.getTrackFormat(it) to group.isTrackSelected(it) } }
                .firstOrNull { it.second }
                ?.first
            currentEffectiveTrack = selectedFormat?.let {
                EffectiveTrack(
                    height = it.height.takeIf { value -> value > 0 },
                    bitrate = it.bitrate.takeIf { value -> value > 0 },
                    isAdaptive = (currentStreamType == PlaybackStreamType.HLS || currentStreamType == PlaybackStreamType.DASH) &&
                        selectedVideoGroups.any { group -> group.length > 1 }
                )
            }
        }
    }

    private fun checkBufferingWatchdog() {
        val player = exoPlayer
        val playbackState = player?.playbackState ?: Player.STATE_IDLE
        val rendered = renderedFirstFrameCounters[mediaOperationGeneration] ?: 0
        bufferingWatchdog?.onPlaybackStateOrRenderChanged(
            playbackState = playbackState,
            renderedFirstFrameCount = rendered,
            streamType = currentStreamType
        )
    }

    private fun triggerBufferingRecovery(key: ContentKey, sessionGen: Long) {
        val preference = currentSelectedQuality?.let { QualityPreference.SpecificOption(it) } ?: QualityPreference.Auto
        startBoundedRecovery(key, sessionGen, preference)
    }

    private fun startBoundedRecovery(key: ContentKey, sessionGen: Long, preference: QualityPreference) {
        val coordinator = recoveryCoordinator ?: run {
            lastReportedAppError = AppError.StreamExpired
            handleTerminalError(AppError.StreamExpired, sessionGen)
            return
        }
        val currentPos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val currentSpeed = exoPlayer?.playbackParameters?.speed ?: 1.0f
        recoveryJob?.cancel()
        recoveryJob = serviceScope.launch(Dispatchers.Main) {
            if (isReleased || currentKey != key || playbackSessionGeneration != sessionGen) return@launch
            val recoveryResult = coordinator.recoverExpiredStream(
                key = key,
                sessionGen = recoverySessionGeneration,
                positionMs = currentPos,
                wasPlaying = userRequestedPlay,
                preference = preference,
                attemptedSourceTypes = attemptedSourceTypes.toSet()
            )
            if (isReleased || currentKey != key || playbackSessionGeneration != sessionGen) return@launch
            when (recoveryResult) {
                is RecoveryResult.Recovered -> {
                    val livePos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: currentPos
                    val livePlayWhenReady = exoPlayer?.playWhenReady ?: userRequestedPlay
                    val liveSpeed = exoPlayer?.playbackParameters?.speed ?: currentSpeed
                    prepareInternal(
                        key = recoveryResult.key,
                        streamInfo = recoveryResult.streamInfo,
                        startPositionMs = recoveryResult.resumePositionMs.takeIf { it > 0L } ?: livePos,
                        playWhenReady = livePlayWhenReady,
                        initialQuality = recoveryResult.selectedQuality,
                        playbackSpeed = liveSpeed,
                        preserveRecoverySession = true,
                        preserveSourceAttempts = true
                    )
                }
                is RecoveryResult.Failed -> {
                    lastReportedAppError = recoveryResult.error
                    handleTerminalError(recoveryResult.error, sessionGen)
                }
                RecoveryResult.Cancelled -> {
                    lastReportedAppError = AppError.StreamExpired
                    handleTerminalError(AppError.StreamExpired, sessionGen)
                }
            }
        }
    }

    private fun handleTerminalError(error: AppError, sessionGen: Long = playbackSessionGeneration) {
        bufferingWatchdog?.reset()
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        persistCurrentSnapshot()
        mediaSession?.let { session ->
            val eventArgs = Bundle().apply {
                putString(EXTRA_PROBE_ERROR_CODE, error.javaClass.simpleName)
                putLong(EXTRA_PROBE_SESSION_GEN, sessionGen)
                putLong(EXTRA_PROBE_MEDIA_GEN, mediaOperationGeneration)
                currentKey?.let {
                    putInt(EXTRA_PROBE_SERVICE_ID, it.serviceId)
                    putString(EXTRA_PROBE_NATIVE_ID, it.nativeId)
                }
            }
            session.broadcastCustomCommand(
                SessionCommand(CUSTOM_COMMAND_TERMINAL_ERROR, Bundle.EMPTY),
                eventArgs
            )
        }
    }

    private fun applyQualityPolicy(policy: UserQualityPolicy) {
        currentQualityPolicy = policy
        val player = exoPlayer ?: return
        val builder = player.trackSelectionParameters.buildUpon()
        builder.setForceLowestBitrate(false)
        when (policy) {
            is UserQualityPolicy.Auto -> {
                val maxHeight = policy.maxHeight ?: Int.MAX_VALUE
                builder.setMaxVideoSize(Int.MAX_VALUE, maxHeight)
                builder.setMaxVideoBitrate(policy.maxBitrate ?: Int.MAX_VALUE)
            }
            is UserQualityPolicy.Fixed -> {
                val height = policy.option.height
                if (height > 0) builder.setMaxVideoSize(Int.MAX_VALUE, height)
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun applyStartupQualityPolicy(token: Long) {
        val ceiling = startupAutoCeiling(currentQualityPolicy, currentStreamType)
        startupCeilingMediaGeneration = ceiling?.let { token }
        if (ceiling == null) {
            applyQualityPolicy(currentQualityPolicy)
            return
        }
        val player = exoPlayer ?: return
        val policy = currentQualityPolicy as UserQualityPolicy.Auto
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setForceLowestBitrate(false)
            .setMaxVideoSize(Int.MAX_VALUE, ceiling)
            .setMaxVideoBitrate(policy.maxBitrate ?: Int.MAX_VALUE)
            .build()
    }

    /**
     * Coalesces bursts of snapshot writes. Playback-state transitions (notably buffering) can fire
     * many times per second; without this each one enqueues a DataStore edit plus an atomic file
     * rename. Losing an intermediate write is harmless because every write carries the full snapshot
     * and [onDestroy] persists the final state synchronously.
     */
    private fun persistCurrentSnapshotThrottled() {
        if (isReleased) return
        if (currentKey == null) return
        val now = System.currentTimeMillis()
        if (now - lastThrottledSnapshotMs < SNAPSHOT_THROTTLE_MS) return
        lastThrottledSnapshotMs = now
        persistCurrentSnapshot()
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
            qualityPolicy = currentQualityPolicy,
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
                prev.set(SessionResult(SessionError.ERROR_INVALID_STATE))
            }
        }
    }

    private fun prepareInternal(
        key: ContentKey,
        streamInfo: StreamInfo,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
        initialQuality: QualityOption? = null,
        playbackSpeed: Float = 1.0f,
        preserveSourceAttempts: Boolean = false,
        preserveRecoverySession: Boolean = false
    ) {
        if (isReleased) return
        ensurePlayerAndSessionInitialized()

        val effectiveStartPositionMs = PlaybackPolicy.resolveStartPosition(
            isLive = streamInfo.isLive,
            requestedPositionMs = startPositionMs
        )

        cancelActiveQuality()
        mediaOpJob?.cancel()
        recoveryJob?.cancel()
        val currentToken = ++mediaOperationGeneration
        val currentSession = ++playbackSessionGeneration
        bufferingWatchdog?.onPrepare(currentSession, currentToken)
        if (!preserveRecoverySession && !preserveSourceAttempts) recoverySessionGeneration++
        activeMetricsSession = VideoOpenMetrics.Default.activeSession(key)
        activeMetricsPlaybackGeneration = currentSession
        activeMetricsMediaGeneration = currentToken
        if (!preserveSourceAttempts) attemptedSourceTypes.clear()

        currentKey = key
        currentStreamInfo = streamInfo
        userRequestedPlay = playWhenReady
        lastReportedAppError = null
        if (!preserveSourceAttempts && !preserveRecoverySession && initialQuality != null) {
            currentQualityPolicy = UserQualityPolicy.Fixed(initialQuality)
        }
        applyQualityPolicy(currentQualityPolicy)

        val available = StreamSelector.getAvailableQualities(streamInfo)
        availableQualities = available

        mediaOpJob = serviceScope.launch(Dispatchers.Main) {
            val explicitPreference = if (initialQuality != null) {
                QualityPreference.SpecificOption(initialQuality)
            } else {
                null
            }

            val selectionAndSource = withContext(Dispatchers.IO) {
                val selectionResult = if (explicitPreference != null) {
                    StreamSelector.selectStream(streamInfo, explicitPreference)
                } else {
                    StartupStreamSelector.select(streamInfo)
                }
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
                    attemptedSourceTypes += selected.streamType
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

                    applyStartupQualityPolicy(currentToken)
                    exoPlayer?.let { player ->
                        registerAnalyticsListener(player, currentToken)
                        player.setMediaSource(mediaSource)
                        if (effectiveStartPositionMs > 0L) {
                            player.seekTo(effectiveStartPositionMs)
                        }
                        player.playWhenReady = playWhenReady
                        player.playbackParameters = androidx.media3.common.PlaybackParameters(
                            playbackSpeed.takeIf { it.isFinite() }?.coerceIn(0.25f, 3.0f) ?: 1.0f
                        )
                        player.prepare()
                        checkBufferingWatchdog()
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
        applyQualityPolicy(currentQualityPolicy)
        activeQualityFuture = completion

        if (isReleased) {
            completion?.set(SessionResult(SessionError.ERROR_INVALID_STATE))
            return
        }
        val targetKey = currentKey ?: run {
            completion?.set(SessionResult(SessionError.ERROR_INVALID_STATE))
            return
        }
        val streamInfo = currentStreamInfo ?: run {
            completion?.set(SessionResult(SessionError.ERROR_INVALID_STATE))
            return
        }

        mediaOpJob?.cancel()
        val currentToken = ++mediaOperationGeneration

        mediaOpJob = serviceScope.launch(Dispatchers.Main) {
            val player = exoPlayer ?: run {
                completion?.set(SessionResult(SessionError.ERROR_INVALID_STATE))
                return@launch
            }
            val matched = availableQualities.firstOrNull { it == quality } ?: run {
                completion?.set(SessionResult(SessionError.ERROR_BAD_VALUE))
                return@launch
            }
            currentQualityPolicy = UserQualityPolicy.Fixed(matched)
            val currentPos = player.currentPosition
            val effectiveSwitchPositionMs = PlaybackPolicy.resolveStartPosition(
                isLive = streamInfo.isLive,
                requestedPositionMs = currentPos
            )
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
                completion?.set(SessionResult(SessionError.ERROR_INVALID_STATE))
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
                    applyQualityPolicy(currentQualityPolicy)

                    registerAnalyticsListener(player, currentToken)
                    player.setMediaSource(mediaSource)
                    if (effectiveSwitchPositionMs > 0L) {
                        player.seekTo(effectiveSwitchPositionMs)
                    }
                    player.playWhenReady = wasPlaying
                    player.prepare()
                    checkBufferingWatchdog()
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
                    completion?.set(SessionResult(SessionError.ERROR_BAD_VALUE, errBundle))
                }
            }
        }
    }

    private fun recordCurrentHistory() {
        val key = currentKey
        val streamInfo = currentStreamInfo
        val player = exoPlayer
        if (key != null && player != null) {
            val app = application as? HPreApplication
            val historyRepo = app?.container?.historyRepository
            if (historyRepo != null) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val summary = VideoSummary(
                    key = key,
                    title = streamInfo?.title ?: "Video",
                    canonicalUrl = "https://hpre.test/watch?v=${key.nativeId}",
                    channelKey = null,
                    channelName = null,
                    channelAvatarUrl = null,
                    thumbnailUrl = null,
                    durationSeconds = if (player.duration > 0) player.duration / 1000L else null,
                    viewCount = null,
                    publishedTimestamp = null,
                    isLive = streamInfo?.isLive == true
                )
                serviceScope.launch(Dispatchers.IO) {
                    historyRepo.recordHistory(summary, pos)
                }
            }
        }
    }

    private fun cancelPendingStreamResolve() {
        prepareStreamJob?.cancel()
        prepareStreamJob = null
        prepareStreamCompletion?.set(SessionResult(SessionError.ERROR_INVALID_STATE))
        prepareStreamCompletion = null
    }

    private fun clearMediaInternal(releaseResources: Boolean = true) {
        historyScheduler.stop()

        currentKey = null
        currentStreamInfo = null
        currentSelectedQuality = null
        availableQualities = emptyList()
        currentStreamType = null
        lastReportedAppError = null
        cancelActiveQuality()
        prepareRequestGeneration++
        cancelPendingStreamResolve()
        playbackSessionGeneration++
        mediaOperationGeneration++
        mediaOpJob?.cancel()
        recoveryJob?.cancel()
        bufferingWatchdog?.reset()
        if (releaseResources) PlaybackStreamHandoff.clear()
        snapshotStore?.clear()
        activeAnalyticsListener?.let { exoPlayer?.removeAnalyticsListener(it) }
        activeAnalyticsListener = null

        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        if (!releaseResources) {
            currentQualityPolicy = currentQualityPolicy as? UserQualityPolicy.Auto ?: UserQualityPolicy.Auto()
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        mediaSession?.run {
            player.removeListener(playerListener)
            player.release()
            release()
        }
        stopSelf()
        mediaSession = null
        exoPlayer = null
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
            onControllerConnected(controller.connectionHints)
            val availableCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(SessionCommand(CUSTOM_COMMAND_PREPARE_STREAM, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_SELECT_QUALITY, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_SET_QUALITY_POLICY, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_GET_PROBE_SNAPSHOT, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_CLEAR_MEDIA, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_STOP_FOR_TRANSITION, Bundle.EMPTY))
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
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            when (customCommand.customAction) {
                CUSTOM_COMMAND_STOP_FOR_TRANSITION -> {
                    clearMediaInternal(releaseResources = false)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_COMMAND_PREPARE_STREAM -> {
                    val serviceId = args.getInt(EXTRA_SERVICE_ID, 0)
                    val nativeId = args.getString(EXTRA_NATIVE_ID, "")
                    val completion = SettableFuture.create<SessionResult>()
                    if (nativeId.isNotBlank()) {
                        cancelPendingStreamResolve()
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
                                completion.set(SessionResult(SessionError.ERROR_INVALID_STATE))
                            }
                        } else {
                            val app = application as? HPreApplication
                            val videoService = app?.container?.videoService
                            if (videoService == null) {
                                lastReportedAppError = AppError.Unknown
                                val errBundle = Bundle().apply {
                                    putString(EXTRA_PROBE_ERROR_CODE, AppError.Unknown.javaClass.simpleName)
                                }
                                completion.set(SessionResult(SessionError.ERROR_UNKNOWN, errBundle))
                                return completion
                            }
                            prepareStreamCompletion = completion
                            val resolveJob = serviceScope.launch(
                                Dispatchers.Main,
                                start = kotlinx.coroutines.CoroutineStart.LAZY
                            ) {
                                try {
                                    val streamResult = withContext(Dispatchers.IO) {
                                        videoService.streamInfo(key)
                                    }
                                    if (isReleased || requestGeneration != prepareRequestGeneration) {
                                        completion.set(SessionResult(SessionError.ERROR_INVALID_STATE))
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
                                        completion.set(SessionResult(SessionError.ERROR_BAD_VALUE, errBundle))
                                    }
                                } catch (ce: CancellationException) {
                                    completion.set(SessionResult(SessionError.ERROR_INVALID_STATE))
                                    throw ce
                                } catch (_: Exception) {
                                    if (!completion.isDone) {
                                        lastReportedAppError = AppError.Unknown
                                        val errBundle = Bundle().apply {
                                            putString(EXTRA_PROBE_ERROR_CODE, AppError.Unknown.javaClass.simpleName)
                                        }
                                        completion.set(SessionResult(SessionError.ERROR_UNKNOWN, errBundle))
                                    }
                                } finally {
                                    if (prepareStreamCompletion === completion) {
                                        prepareStreamJob = null
                                        prepareStreamCompletion = null
                                    }
                                }
                            }
                            prepareStreamJob = resolveJob
                            resolveJob.start()
                        }
                    } else {
                        val errBundle = Bundle().apply {
                            putString(EXTRA_PROBE_ERROR_CODE, AppError.ContentUnavailable.javaClass.simpleName)
                        }
                        completion.set(SessionResult(SessionError.ERROR_BAD_VALUE, errBundle))
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
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE, errBundle))
                    }
                    val completion = SettableFuture.create<SessionResult>()
                    selectQualityInternal(opt, completion)
                    return completion
                }
                CUSTOM_COMMAND_SET_QUALITY_POLICY -> {
                    val expectedServiceId = args.getInt(EXTRA_SERVICE_ID, Int.MIN_VALUE)
                    val expectedNativeId = args.getString(EXTRA_NATIVE_ID)
                    if (currentKey?.serviceId != expectedServiceId || currentKey?.nativeId != expectedNativeId) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                    }
                    val policy = if (args.getBoolean(EXTRA_POLICY_AUTO, true)) {
                        UserQualityPolicy.Auto(
                            maxHeight = args.getInt(EXTRA_POLICY_MAX_HEIGHT, 0).takeIf { it > 0 },
                            maxBitrate = args.getInt(EXTRA_POLICY_MAX_BITRATE, 0).takeIf { it > 0 }
                        )
                    } else {
                        val matchHeight = args.getInt(EXTRA_QUALITY_HEIGHT, 0)
                        val option = availableQualities.firstOrNull { it.height == matchHeight }
                            ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        UserQualityPolicy.Fixed(option)
                    }
                    applyQualityPolicy(policy)
                    persistCurrentSnapshot()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
    }

    override fun onDestroy() {
        isReleased = true
        cancelPendingStreamResolve()
        historyScheduler.stop()
        cancelActiveQuality()
        mediaOpJob?.cancel()
        recoveryJob?.cancel()
        bufferingWatchdog?.reset()

        // Capture final state without blocking service teardown on DataStore I/O.
        persistFinalSnapshotSync()

        serviceScope.cancel()

        stopForeground(STOP_FOREGROUND_REMOVE)
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
            qualityPolicy = currentQualityPolicy,
            playbackSpeed = speed
        )
        store.saveSync(snapshot)
    }
}
