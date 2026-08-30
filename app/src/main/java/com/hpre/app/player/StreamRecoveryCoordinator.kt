package com.hpre.app.player

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.core.error.RetryPolicy
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.repository.VideoService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface RecoveryResult {
    data class Recovered(
        val key: ContentKey,
        val sessionGen: Long,
        val streamInfo: StreamInfo,
        val resumePositionMs: Long,
        val resumeWhenReady: Boolean,
        val selectedQuality: QualityOption?
    ) : RecoveryResult

    data class Failed(
        val error: AppError,
        val isAutoRetryable: Boolean
    ) : RecoveryResult

    data object Cancelled : RecoveryResult
}

class StreamRecoveryCoordinator(
    private val videoService: VideoService,
    private val retryPolicy: RetryPolicy = RetryPolicy()
) {
    private val mutex = Mutex()
    private var activeSession: Pair<ContentKey, Long>? = null
    private var refreshAttemptsForSession: Int = 0
    private var isReleased: Boolean = false
    private var activeRecoveryJob: Job? = null

    suspend fun recoverExpiredStream(
        key: ContentKey,
        sessionGen: Long = 0L,
        positionMs: Long,
        wasPlaying: Boolean,
        preference: QualityPreference
    ): RecoveryResult {
        val targetSession = Pair(key, sessionGen)
        val currentJob = kotlin.coroutines.coroutineContext[Job]

        mutex.withLock {
            if (isReleased) return RecoveryResult.Cancelled

            if (activeSession != targetSession) {
                activeRecoveryJob?.cancel(CancellationException("New recovery session started"))
                activeRecoveryJob = null
                activeSession = targetSession
                refreshAttemptsForSession = 0
            }

            if (!retryPolicy.shouldRefreshExpiredStream(refreshAttemptsForSession)) {
                return RecoveryResult.Failed(AppError.StreamExpired, isAutoRetryable = false)
            }

            refreshAttemptsForSession++
            activeRecoveryJob?.cancel(CancellationException("Replaced by new recovery request"))
            activeRecoveryJob = currentJob
        }

        val streamResult = try {
            videoService.refreshStreamInfo(key)
        } catch (ce: CancellationException) {
            mutex.withLock {
                if (activeRecoveryJob === currentJob) {
                    activeRecoveryJob = null
                }
            }
            throw ce
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Unknown)
        }

        mutex.withLock {
            if (activeRecoveryJob === currentJob) {
                activeRecoveryJob = null
            }
            if (isReleased || activeSession != targetSession) {
                return RecoveryResult.Cancelled
            }

            return when (streamResult) {
                is AppResult.Success -> {
                    val freshInfo = streamResult.value
                    val availableQualities = StreamSelector.getAvailableQualities(freshInfo)
                    val matchedQuality = when (preference) {
                        is QualityPreference.SpecificOption -> {
                            val requested = preference.option
                            availableQualities.firstOrNull {
                                it.height == requested.height &&
                                        it.isProgressive == requested.isProgressive &&
                                        it.format.equals(requested.format, ignoreCase = true)
                            } ?: availableQualities.firstOrNull { it.height == requested.height }
                            ?: availableQualities.firstOrNull()
                        }
                        is QualityPreference.ExactOrBelow -> {
                            availableQualities.filter { it.height <= preference.maxHeight }
                                .maxByOrNull { it.height } ?: availableQualities.firstOrNull()
                        }
                        QualityPreference.Auto -> {
                            availableQualities.firstOrNull()
                        }
                    }

                    RecoveryResult.Recovered(
                        key = key,
                        sessionGen = sessionGen,
                        streamInfo = freshInfo,
                        resumePositionMs = PlaybackPolicy.resolveStartPosition(
                            isLive = freshInfo.isLive,
                            requestedPositionMs = positionMs
                        ),
                        resumeWhenReady = wasPlaying,
                        selectedQuality = matchedQuality
                    )
                }
                is AppResult.Failure -> {
                    RecoveryResult.Failed(streamResult.error, isAutoRetryable = false)
                }
            }
        }
    }

    fun release() {
        isReleased = true
        activeRecoveryJob?.cancel(CancellationException("StreamRecoveryCoordinator released"))
        activeRecoveryJob = null
        activeSession = null
        refreshAttemptsForSession = 0
    }
}
