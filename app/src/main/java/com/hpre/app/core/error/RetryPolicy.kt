package com.hpre.app.core.error

import kotlin.random.Random

fun interface JitterProvider {
    fun jitter(baseDelayMs: Long): Long
}

class RetryPolicy(
    private val jitterProvider: JitterProvider = JitterProvider { base ->
        val maxJitter = minOf(MAX_JITTER_MS, (base / 2))
        if (maxJitter <= 0L) 0L else Random.nextLong(0, maxJitter + 1)
    }
) {
    companion object {
        const val MAX_NETWORK_RETRIES = 2
        const val MAX_STREAM_REFRESH_ATTEMPTS = 1
        const val MAX_JITTER_MS = 250L

        private const val INITIAL_BACKOFF_MS = 500L
        private const val BACKOFF_MULTIPLIER = 2.0

        private val defaultInstance = RetryPolicy()

        fun shouldRetry(error: AppError, attempt: Int): Boolean {
            return defaultInstance.shouldRetry(error, attempt)
        }

        fun shouldRefreshExpiredStream(refreshAttempts: Int): Boolean {
            return defaultInstance.shouldRefreshExpiredStream(refreshAttempts)
        }

        fun getRetryDelayMs(error: AppError, attempt: Int): Long? {
            return defaultInstance.getRetryDelayMs(error, attempt)
        }

        fun isRetryable(error: AppError): Boolean {
            return defaultInstance.isRetryable(error)
        }

        fun isManualRetryable(error: AppError): Boolean {
            return defaultInstance.isManualRetryable(error)
        }
    }

    fun isRetryable(error: AppError): Boolean {
        return isManualRetryable(error)
    }

    fun isManualRetryable(error: AppError): Boolean {
        return error is AppError.NetworkError || error is AppError.ExtractionFailed
    }

    fun shouldRetry(error: AppError, attempt: Int): Boolean {
        if (attempt < 0 || attempt >= MAX_NETWORK_RETRIES) return false
        return error is AppError.NetworkError
    }

    fun shouldRefreshExpiredStream(refreshAttempts: Int): Boolean {
        return refreshAttempts == 0
    }

    fun getRetryDelayMs(error: AppError, attempt: Int): Long? {
        if (!shouldRetry(error, attempt)) return null
        val baseDelay = (INITIAL_BACKOFF_MS * Math.pow(BACKOFF_MULTIPLIER, attempt.toDouble())).toLong()
        val jitter = jitterProvider.jitter(baseDelay).coerceIn(0L, MAX_JITTER_MS)
        return baseDelay + jitter
    }
}
