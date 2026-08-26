package com.hpre.app.core.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppResultTest {

    @Test
    fun success_holds_value() {
        val result: AppResult<String> = AppResult.Success("HPre")
        assertTrue(result is AppResult.Success)
        assertEquals("HPre", (result as AppResult.Success).value)
    }

    @Test
    fun failure_holds_error() {
        val result: AppResult<String> = AppResult.Failure(AppError.ContentUnavailable)
        assertTrue(result is AppResult.Failure)
        assertEquals(AppError.ContentUnavailable, (result as AppResult.Failure).error)
    }

    @Test
    fun app_error_has_exactly_the_ten_approved_subtypes() {
        val nestedClasses = AppError::class.java.declaredClasses.toSet()
        val expectedClasses = setOf(
            AppError.NetworkError::class.java,
            AppError.RateLimited::class.java,
            AppError.ContentUnavailable::class.java,
            AppError.AgeRestricted::class.java,
            AppError.GeoRestricted::class.java,
            AppError.LoginRequired::class.java,
            AppError.StreamExpired::class.java,
            AppError.UnsupportedFormat::class.java,
            AppError.ExtractionFailed::class.java,
            AppError.Unknown::class.java
        )

        assertEquals(10, nestedClasses.size)
        assertEquals(expectedClasses, nestedClasses)
        for (clazz in expectedClasses) {
            assertTrue(AppError::class.java.isAssignableFrom(clazz))
        }
    }

    @Test
    fun safe_message_key_mapping_returns_expected_resource_keys() {
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
}
