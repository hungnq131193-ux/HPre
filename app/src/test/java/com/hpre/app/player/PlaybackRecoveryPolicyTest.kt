package com.hpre.app.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.hpre.app.core.error.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    @Test
    fun temporary_http_delivery_failures_refresh_once() {
        assertEquals(AppError.StreamExpired, PlaybackRecoveryPolicy.decide(httpError(403)).error)
        assertTrue(PlaybackRecoveryPolicy.decide(httpError(403)).shouldRefresh)
        assertTrue(PlaybackRecoveryPolicy.decide(httpError(401)).shouldRefresh)
        assertTrue(PlaybackRecoveryPolicy.decide(httpError(404)).shouldRefresh)
        assertTrue(PlaybackRecoveryPolicy.decide(httpError(503)).shouldRefresh)
    }

    @Test
    fun connection_failures_refresh_but_decoder_and_unknown_failures_do_not() {
        assertTrue(
            PlaybackRecoveryPolicy.decide(
                PlaybackException(
                    "network",
                    null,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                )
            ).shouldRefresh
        )
        assertFalse(
            PlaybackRecoveryPolicy.decide(
                PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
            ).shouldRefresh
        )
        assertFalse(
            PlaybackRecoveryPolicy.decide(
                PlaybackException("unknown", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
            ).shouldRefresh
        )
    }

    @Test
    fun mapped_access_restrictions_do_not_refresh_without_temporary_media_failure() {
        listOf(
            AppError.LoginRequired,
            AppError.ContentUnavailable,
            AppError.AgeRestricted,
            AppError.GeoRestricted,
            AppError.RateLimited
        ).forEach { error ->
            assertFalse(PlaybackRecoveryPolicy.fromAppError(error).shouldRefresh)
        }
    }

    private fun httpError(status: Int): PlaybackException {
        val constructor = HttpDataSource.InvalidResponseCodeException::class.java.declaredConstructors.first {
            it.parameterTypes.size >= 4
        }
        constructor.isAccessible = true
        val params = Array<Any?>(constructor.parameterTypes.size) { index ->
            when (constructor.parameterTypes[index]) {
                Int::class.javaPrimitiveType -> if (index == 0) status else 0
                String::class.java -> "HTTP"
                java.io.IOException::class.java -> null
                Map::class.java -> emptyMap<String, List<String>>()
                ByteArray::class.java -> byteArrayOf()
                else -> null
            }
        }
        val cause = constructor.newInstance(*params) as HttpDataSource.InvalidResponseCodeException
        return PlaybackException("HTTP", cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
    }
}
