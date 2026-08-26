package com.hpre.app.core.error

sealed interface AppError {
    data object NetworkError : AppError
    data object RateLimited : AppError
    data object ContentUnavailable : AppError
    data object AgeRestricted : AppError
    data object GeoRestricted : AppError
    data object LoginRequired : AppError
    data object StreamExpired : AppError
    data object UnsupportedFormat : AppError
    data object ExtractionFailed : AppError
    data object Unknown : AppError
}

fun AppError.safeMessageKey(): String {
    return when (this) {
        AppError.NetworkError -> "error_network"
        AppError.RateLimited -> "error_rate_limited"
        AppError.ContentUnavailable -> "error_content_unavailable"
        AppError.AgeRestricted -> "error_age_restricted"
        AppError.GeoRestricted -> "error_geo_restricted"
        AppError.LoginRequired -> "error_login_required"
        AppError.StreamExpired -> "error_stream_expired"
        AppError.UnsupportedFormat -> "error_unsupported_format"
        AppError.ExtractionFailed -> "error_extraction_failed"
        AppError.Unknown -> "error_unknown"
    }
}
