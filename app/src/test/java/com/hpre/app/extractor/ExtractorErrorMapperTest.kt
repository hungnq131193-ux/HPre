package com.hpre.app.extractor

import com.hpre.app.core.error.AppError
import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ExtractorErrorMapperTest {

    @Test
    fun http_403_maps_to_login_required_with_extraction_metadata() {
        assertEquals(AppError.LoginRequired, ExtractorErrorMapper.mapHttpFailure(statusCode = 403, operation = ExtractorOperationContext.EXTRACTION_METADATA))
    }

    @Test
    fun http_429_maps_to_rate_limited() {
        assertEquals(AppError.RateLimited, ExtractorErrorMapper.mapHttpFailure(statusCode = 429))
    }

    @Test
    fun http_404_maps_to_content_unavailable() {
        assertEquals(AppError.ContentUnavailable, ExtractorErrorMapper.mapHttpFailure(statusCode = 404))
        assertEquals(AppError.ContentUnavailable, ExtractorErrorMapper.mapHttpFailure(statusCode = 410))
    }

    @Test
    fun http_5xx_and_others_map_to_network_error() {
        assertEquals(AppError.NetworkError, ExtractorErrorMapper.mapHttpFailure(statusCode = 500))
        assertEquals(AppError.NetworkError, ExtractorErrorMapper.mapHttpFailure(statusCode = 503))
        assertEquals(AppError.NetworkError, ExtractorErrorMapper.mapHttpFailure(statusCode = 504))
        assertEquals(AppError.NetworkError, ExtractorErrorMapper.mapHttpFailure(statusCode = 400))
    }

    @Test
    fun extractor_http_exception_maps_to_mapped_http_failure() {
        assertEquals(AppError.LoginRequired, ExtractorErrorMapper.mapExtractorFailure(ExtractorHttpException(403, ExtractorOperationContext.EXTRACTION_METADATA)))
        assertEquals(AppError.ContentUnavailable, ExtractorErrorMapper.mapExtractorFailure(ExtractorHttpException(404, ExtractorOperationContext.EXTRACTION_METADATA)))
        assertEquals(AppError.ContentUnavailable, ExtractorErrorMapper.mapExtractorFailure(ExtractorHttpException(410, ExtractorOperationContext.EXTRACTION_METADATA)))
        assertEquals(AppError.RateLimited, ExtractorErrorMapper.mapExtractorFailure(ExtractorHttpException(429, ExtractorOperationContext.EXTRACTION_METADATA)))
        assertEquals(AppError.NetworkError, ExtractorErrorMapper.mapExtractorFailure(ExtractorHttpException(500, ExtractorOperationContext.EXTRACTION_METADATA)))
        assertEquals(AppError.NetworkError, ExtractorErrorMapper.mapExtractorFailure(ExtractorHttpException(503, ExtractorOperationContext.EXTRACTION_METADATA)))
    }

    @Test
    fun network_exceptions_map_to_network_error() {
        assertEquals(AppError.NetworkError, ExtractorErrorMapper.mapExtractorFailure(SocketTimeoutException("Timeout")))
        assertEquals(AppError.NetworkError, ExtractorErrorMapper.mapExtractorFailure(UnknownHostException("DNS error")))
        assertEquals(AppError.NetworkError, ExtractorErrorMapper.mapExtractorFailure(IOException("Broken pipe")))
    }

    @Test
    fun restriction_exceptions_map_accurately() {
        assertEquals(AppError.AgeRestricted, ExtractorErrorMapper.mapExtractorFailure(AgeRestrictedContentException("Age restricted")))
        assertEquals(AppError.GeoRestricted, ExtractorErrorMapper.mapExtractorFailure(GeographicRestrictionException("Geo blocked")))
        assertEquals(AppError.LoginRequired, ExtractorErrorMapper.mapExtractorFailure(PrivateContentException("Private video")))
        assertEquals(AppError.LoginRequired, ExtractorErrorMapper.mapExtractorFailure(PaidContentException("Paid content")))
        assertEquals(AppError.LoginRequired, ExtractorErrorMapper.mapExtractorFailure(YoutubeMusicPremiumContentException()))
        assertEquals(AppError.LoginRequired, ExtractorErrorMapper.mapExtractorFailure(SoundCloudGoPlusContentException()))
        assertEquals(AppError.RateLimited, ExtractorErrorMapper.mapExtractorFailure(ExtractorHttpException(429, ExtractorOperationContext.EXTRACTION_METADATA)))
        assertEquals(AppError.RateLimited, ExtractorErrorMapper.mapExtractorFailure(ReCaptchaException("Captcha", "https://youtube.com")))
        assertEquals(AppError.ContentUnavailable, ExtractorErrorMapper.mapExtractorFailure(ContentNotAvailableException("Not available")))
        assertEquals(AppError.ContentUnavailable, ExtractorErrorMapper.mapExtractorFailure(AccountTerminatedException("Terminated")))
        assertEquals(AppError.UnsupportedFormat, ExtractorErrorMapper.mapExtractorFailure(ContentNotSupportedException("Unsupported")))
        assertEquals(AppError.ExtractionFailed, ExtractorErrorMapper.mapExtractorFailure(ParsingException("Failed to parse regex")))
        assertEquals(AppError.Unknown, ExtractorErrorMapper.mapExtractorFailure(IllegalStateException("Unknown state")))
    }
}
