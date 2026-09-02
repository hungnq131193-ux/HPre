package com.hpre.app.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.hpre.app.core.error.AppError

data class PlaybackRecoveryDecision(
    val error: AppError,
    val shouldRefresh: Boolean
)

object PlaybackRecoveryPolicy {
    fun decide(error: PlaybackException): PlaybackRecoveryDecision {
        val http = findCause<HttpDataSource.InvalidResponseCodeException>(error)
        return when (http?.responseCode) {
            403 -> PlaybackRecoveryDecision(AppError.StreamExpired, shouldRefresh = true)
            401 -> PlaybackRecoveryDecision(AppError.LoginRequired, shouldRefresh = true)
            404 -> PlaybackRecoveryDecision(AppError.ContentUnavailable, shouldRefresh = true)
            in 500..599 -> PlaybackRecoveryDecision(AppError.NetworkError, shouldRefresh = true)
            else -> when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                    PlaybackRecoveryDecision(AppError.NetworkError, shouldRefresh = true)
                }
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> {
                    PlaybackRecoveryDecision(AppError.UnsupportedFormat, shouldRefresh = false)
                }
                else -> PlaybackRecoveryDecision(AppError.Unknown, shouldRefresh = false)
            }
        }
    }

    fun fromAppError(error: AppError): PlaybackRecoveryDecision =
        PlaybackRecoveryDecision(error, shouldRefresh = false)

    private inline fun <reified T : Throwable> findCause(throwable: Throwable?): T? {
        var current = throwable
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }
}
