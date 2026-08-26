package com.hpre.app.extractor

import com.hpre.app.core.error.AppError
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Maps raw Java / NewPipeExtractor exceptions and HTTP response codes to safe domain AppError categories.
 * Sensitive URLs, tokens, cookies, and raw exception messages are never exposed to domain callers.
 */
object ExtractorErrorMapper {

    fun mapHttpFailure(
        statusCode: Int,
        operation: ExtractorOperationContext = ExtractorOperationContext.EXTRACTION_METADATA
    ): AppError {
        return when (statusCode) {
            403 -> AppError.LoginRequired
            404, 410 -> AppError.ContentUnavailable
            429 -> AppError.RateLimited
            in 500..599 -> AppError.NetworkError
            else -> AppError.NetworkError
        }
    }

    fun mapExtractorFailure(throwable: Throwable): AppError {
        // If wrapped in ExtractionException / ParsingException, inspect cause chain
        var root: Throwable? = throwable
        while (root != null) {
            if (root is ExtractorHttpException) {
                return mapHttpFailure(root.statusCode, root.operationContext)
            }
            if (root is ReCaptchaException) {
                return AppError.RateLimited
            }
            if (root is AgeRestrictedContentException) {
                return AppError.AgeRestricted
            }
            if (root is GeographicRestrictionException) {
                return AppError.GeoRestricted
            }
            if (root is PrivateContentException || root is PaidContentException ||
                root is YoutubeMusicPremiumContentException || root is SoundCloudGoPlusContentException) {
                return AppError.LoginRequired
            }
            if (root is AccountTerminatedException || root is ContentNotAvailableException) {
                return AppError.ContentUnavailable
            }
            if (root is ContentNotSupportedException) {
                return AppError.UnsupportedFormat
            }
            if (root is SocketTimeoutException || root is UnknownHostException ||
                root is SocketException || root is SSLException ||
                root is ProtocolException || root is InterruptedIOException) {
                return AppError.NetworkError
            }
            root = root.cause
        }

        return when (throwable) {
            is IOException -> AppError.NetworkError
            is ParsingException,
            is ExtractionException -> AppError.ExtractionFailed
            else -> AppError.Unknown
        }
    }
}

