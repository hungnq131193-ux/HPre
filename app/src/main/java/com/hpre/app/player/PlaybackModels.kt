package com.hpre.app.player

import com.hpre.app.model.AudioStream
import com.hpre.app.model.ContentKey
import com.hpre.app.model.SubtitleStream
import com.hpre.app.model.VideoStream

enum class PlaybackStreamType {
    PROGRESSIVE,
    MERGED_AV,
    AUDIO_ONLY,
    HLS,
    DASH
}

sealed interface QualityPreference {
    data object Auto : QualityPreference
    data class ExactOrBelow(val maxHeight: Int) : QualityPreference
    data class SpecificOption(val option: QualityOption) : QualityPreference
}

sealed interface UserQualityPolicy {
    data class Auto(
        val maxHeight: Int? = null,
        val maxBitrate: Int? = null
    ) : UserQualityPolicy

    data class Fixed(val option: QualityOption) : UserQualityPolicy
}

data class EffectiveTrack(
    val height: Int?,
    val bitrate: Int?,
    val isAdaptive: Boolean
)

object QualityPolicyResolver {
    fun forSelection(
        currentStreamType: PlaybackStreamType?,
        option: QualityOption
    ): UserQualityPolicy = if (
        (currentStreamType == PlaybackStreamType.HLS || currentStreamType == PlaybackStreamType.DASH) &&
        option.height > 0
    ) {
        UserQualityPolicy.Auto(maxHeight = option.height)
    } else {
        UserQualityPolicy.Fixed(option)
    }
}

data class QualityOption(
    val height: Int,
    val label: String,
    val isProgressive: Boolean,
    val format: String = "",
    val mimeType: String? = null,
    val codec: String? = null,
    val streamType: PlaybackStreamType = if (isProgressive) PlaybackStreamType.PROGRESSIVE else PlaybackStreamType.MERGED_AV
)

data class SelectedStreams(
    val key: ContentKey,
    val title: String = "",
    val streamType: PlaybackStreamType,
    val videoStream: VideoStream? = null,
    val audioStream: AudioStream? = null,
    val manifestUrl: String? = null,
    val subtitles: List<SubtitleStream> = emptyList()
)

data class RetrySnapshot(
    val key: ContentKey,
    val sessionGen: Long,
    val positionMs: Long,
    val userRequestedPlay: Boolean,
    val selectedQuality: QualityOption?
)

data class PlaybackState(
    val key: ContentKey? = null,
    val title: String? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isReady: Boolean = false,
    val isLoading: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val selectedQuality: QualityOption? = null,
    val qualityPolicy: UserQualityPolicy = UserQualityPolicy.Auto(),
    val effectiveTrack: EffectiveTrack? = null,
    val pendingQuality: QualityOption? = null,
    val availableQualities: List<QualityOption> = emptyList(),
    val streamType: PlaybackStreamType? = null,
    val isEnded: Boolean = false,
    val error: com.hpre.app.core.error.AppError? = null,
    val retrySnapshot: RetrySnapshot? = null
)

data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

fun PlaybackState.toStructuralState(): PlaybackState = copy(
    currentPositionMs = 0L,
    durationMs = 0L
)

fun PlaybackState.toProgress(): PlaybackProgress = PlaybackProgress(
    positionMs = currentPositionMs,
    durationMs = durationMs
)

/**
 * Controller-owned integration/testing snapshot returning actual player facts.
 * Runs on the main dispatcher and captures confirmed player state and media operation generation.
 */
data class PlayerTestingSnapshot(
    val mediaOperationGeneration: Long,
    val playbackSessionGeneration: Long = 0L,
    val actualPositionMs: Long,
    val actualDurationMs: Long,
    val playbackState: Int,
    val isPlaying: Boolean,
    val playWhenReady: Boolean,
    val selectedQuality: QualityOption?,
    val streamType: PlaybackStreamType?,
    val error: com.hpre.app.core.error.AppError?,
    val renderedFirstFrameCount: Int = 0,
    val audioDecoderInitializedCount: Int = 0,
    val videoDecoderInitializedCount: Int = 0,
    val surfaceAttached: Boolean = false
)
