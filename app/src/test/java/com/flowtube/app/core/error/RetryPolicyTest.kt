package com.flowtube.app.core.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun stream_expiry_only_refreshes_on_attempt_zero() {
        assertTrue(RetryPolicy.shouldRefreshExpiredStream(refreshAttempts = 0))
        assertFalse(RetryPolicy.shouldRefreshExpiredStream(refreshAttempts = 1))
        assertFalse(RetryPolicy.shouldRefreshExpiredStream(refreshAttempts = 2))
        assertFalse(RetryPolicy.shouldRefreshExpiredStream(refreshAttempts = -1))
        assertFalse(RetryPolicy.shouldRefreshExpiredStream(refreshAttempts = -10))
    }

    @Test
    fun network_error_retries_only_for_attempt_zero_and_one() {
        assertTrue(RetryPolicy.shouldRetry(AppError.NetworkError, attempt = 0))
        assertTrue(RetryPolicy.shouldRetry(AppError.NetworkError, attempt = 1))
        assertFalse(RetryPolicy.shouldRetry(AppError.NetworkError, attempt = 2))
        assertFalse(RetryPolicy.shouldRetry(AppError.NetworkError, attempt = 3))
        assertFalse(RetryPolicy.shouldRetry(AppError.NetworkError, attempt = -1))
    }

    @Test
    fun non_network_errors_do_not_retry() {
        val nonNetworkErrors = listOf(
            AppError.RateLimited,
            AppError.ContentUnavailable,
            AppError.AgeRestricted,
            AppError.GeoRestricted,
            AppError.LoginRequired,
            AppError.StreamExpired,
            AppError.UnsupportedFormat,
            AppError.ExtractionFailed,
            AppError.Unknown
        )

        for (error in nonNetworkErrors) {
            assertFalse(RetryPolicy.shouldRetry(error, attempt = 0))
            assertFalse(RetryPolicy.shouldRetry(error, attempt = 1))
            assertNull(RetryPolicy.getRetryDelayMs(error, attempt = 0))
            assertNull(RetryPolicy.getRetryDelayMs(error, attempt = 1))
        }
    }

    @Test
    fun retry_delay_is_null_for_invalid_and_exhausted_attempts() {
        assertNull(RetryPolicy.getRetryDelayMs(AppError.NetworkError, attempt = -1))
        assertNull(RetryPolicy.getRetryDelayMs(AppError.NetworkError, attempt = 2))
        assertNull(RetryPolicy.getRetryDelayMs(AppError.NetworkError, attempt = 5))
    }

    @Test
    fun retry_delay_uses_base_500_and_1000_with_deterministic_injected_jitter() {
        // Base delays: attempt 0 -> 500ms, attempt 1 -> 1000ms
        // Inject jitter function: (baseDelayMs) -> 50L (must be <= max jitter)
        val zeroJitterPolicy = RetryPolicy(jitterProvider = { 0L })
        assertEquals(500L, zeroJitterPolicy.getRetryDelayMs(AppError.NetworkError, attempt = 0))
        assertEquals(1000L, zeroJitterPolicy.getRetryDelayMs(AppError.NetworkError, attempt = 1))

        val customJitterPolicy = RetryPolicy(jitterProvider = { base -> (base / 10) })
        assertEquals(550L, customJitterPolicy.getRetryDelayMs(AppError.NetworkError, attempt = 0))
        assertEquals(1100L, customJitterPolicy.getRetryDelayMs(AppError.NetworkError, attempt = 1))
    }

    @Test
    fun default_retry_delay_is_bounded_between_base_and_base_plus_max_jitter() {
        // Default policy with random jitter (max jitter = 250ms or 25% base)
        for (i in 0 until 50) {
            val delay0 = RetryPolicy.getRetryDelayMs(AppError.NetworkError, attempt = 0)
            val delay1 = RetryPolicy.getRetryDelayMs(AppError.NetworkError, attempt = 1)

            assertTrue(delay0 != null && delay0 >= 500L && delay0 <= 500L + RetryPolicy.MAX_JITTER_MS)
            assertTrue(delay1 != null && delay1 >= 1000L && delay1 <= 1000L + RetryPolicy.MAX_JITTER_MS)
        }
    }

    @Test
    fun injected_jitter_negative_clamps_to_zero() {
        val negativeJitterPolicy = RetryPolicy(jitterProvider = { -100L })
        assertEquals(500L, negativeJitterPolicy.getRetryDelayMs(AppError.NetworkError, attempt = 0))
        assertEquals(1000L, negativeJitterPolicy.getRetryDelayMs(AppError.NetworkError, attempt = 1))
    }

    @Test
    fun injected_jitter_exactly_max_jitter_retained() {
        val exactMaxJitterPolicy = RetryPolicy(jitterProvider = { RetryPolicy.MAX_JITTER_MS })
        assertEquals(500L + RetryPolicy.MAX_JITTER_MS, exactMaxJitterPolicy.getRetryDelayMs(AppError.NetworkError, attempt = 0))
        assertEquals(1000L + RetryPolicy.MAX_JITTER_MS, exactMaxJitterPolicy.getRetryDelayMs(AppError.NetworkError, attempt = 1))
    }

    @Test
    fun injected_jitter_above_max_jitter_clamps_to_max_jitter() {
        val excessiveJitterPolicy = RetryPolicy(jitterProvider = { RetryPolicy.MAX_JITTER_MS + 500L })
        assertEquals(500L + RetryPolicy.MAX_JITTER_MS, excessiveJitterPolicy.getRetryDelayMs(AppError.NetworkError, attempt = 0))
        assertEquals(1000L + RetryPolicy.MAX_JITTER_MS, excessiveJitterPolicy.getRetryDelayMs(AppError.NetworkError, attempt = 1))
    }

    @Test
    fun isManualRetryable_returns_true_for_network_and_extraction_failed_only() {
        assertTrue(RetryPolicy.isManualRetryable(AppError.NetworkError))
        assertTrue(RetryPolicy.isManualRetryable(AppError.ExtractionFailed))
        assertFalse(RetryPolicy.isManualRetryable(AppError.RateLimited))
        assertFalse(RetryPolicy.isManualRetryable(AppError.ContentUnavailable))
        assertFalse(RetryPolicy.isManualRetryable(AppError.AgeRestricted))
        assertFalse(RetryPolicy.isManualRetryable(AppError.GeoRestricted))
        assertFalse(RetryPolicy.isManualRetryable(AppError.LoginRequired))
        assertFalse(RetryPolicy.isManualRetryable(AppError.StreamExpired))
        assertFalse(RetryPolicy.isManualRetryable(AppError.UnsupportedFormat))
        assertFalse(RetryPolicy.isManualRetryable(AppError.Unknown))
    }

    @Test
    fun automatic_vs_manual_retry_semantics() {
        // Automatic retry (shouldRetry) is ONLY for NetworkError
        assertTrue(RetryPolicy.shouldRetry(AppError.NetworkError, attempt = 0))
        assertFalse(RetryPolicy.shouldRetry(AppError.ExtractionFailed, attempt = 0))
        assertFalse(RetryPolicy.shouldRetry(AppError.RateLimited, attempt = 0))

        // Manual retry (isManualRetryable) allows NetworkError AND ExtractionFailed
        assertTrue(RetryPolicy.isManualRetryable(AppError.NetworkError))
        assertTrue(RetryPolicy.isManualRetryable(AppError.ExtractionFailed))
        assertFalse(RetryPolicy.isManualRetryable(AppError.RateLimited))
        assertFalse(RetryPolicy.isManualRetryable(AppError.LoginRequired))
    }
}
