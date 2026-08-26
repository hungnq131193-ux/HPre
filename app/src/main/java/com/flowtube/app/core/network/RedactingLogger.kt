package com.flowtube.app.core.network

import android.net.Uri

enum class LogCategory {
    NETWORK,
    PLAYBACK,
    CATALOG,
    SYSTEM
}

enum class DiagnosticComponent {
    OKHTTP_DOWNLOADER,
    PLAYER_CONTROLLER,
    CATALOG_REPOSITORY,
    PLAYBACK_REPOSITORY,
    SYSTEM_DIAGNOSTICS
}

enum class DiagnosticOperation {
    EXTRACTION_METADATA,
    PLAYBACK_ERROR,
    STREAM_FETCH,
    CACHE_LOOKUP
}

enum class DiagnosticStatus {
    Http403,
    Http4xx,
    Http5xx,
    Network,
    Extraction,
    Unknown
}

interface SafeDiagnosticLogger {
    fun logDiagnostic(
        component: DiagnosticComponent,
        category: LogCategory,
        operation: DiagnosticOperation,
        status: DiagnosticStatus
    )
}

object RedactingLogger : SafeDiagnosticLogger {
    private const val REDACTED = "[REDACTED]"

    private val SENSITIVE_HEADER_KEYS = setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "token",
        "x-token",
        "api-key",
        "x-api-key",
        "auth-token"
    )

    private val SENSITIVE_PARAM_REGEX = Regex(
        "(?i)\\b(token|auth|authorization|cookie|session|key|secret|id|expire|signature|sig)=([^&\\s,;\"]+)"
    )

    private val AUTH_HEADER_REGEX = Regex(
        "(?i)\\b(Authorization|Cookie|X-Token|Token):\\s*([^,\\n\\r;\"]+)"
    )

    private val URL_QUERY_REPLACEMENT_REGEX = Regex(
        "((?:https?|ftp|rtmp)://[^?\\s/]+/?[^?\\s]*\\?)([^\\s\"',;]+)"
    )

    private fun interface LogSink {
        fun log(level: String, tag: String, message: String)
    }

    private var sink: LogSink? = null

    private fun redactUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val queryNames = uri.queryParameterNames
            if (queryNames.isNullOrEmpty()) {
                return url
            }
            val builder = uri.buildUpon().clearQuery()
            for (param in queryNames) {
                builder.appendQueryParameter(param, REDACTED)
            }
            builder.build().toString()
        } catch (_: Throwable) {
            // Fallback string-based regex redaction if Uri.parse fails
            url.replace(Regex("([?&][^=&#]+)=([^&#]*)")) { matchResult ->
                val key = matchResult.groupValues[1]
                "$key=$REDACTED"
            }
        }
    }

    private fun redactHeaders(headers: Map<String, List<String>>): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()
        for ((name, values) in headers) {
            val lower = name.lowercase()
            if (SENSITIVE_HEADER_KEYS.contains(lower) || lower.contains("token") || lower.contains("auth") || lower.contains("cookie")) {
                result[name] = listOf(REDACTED)
            } else {
                result[name] = values
            }
        }
        return result
    }

    private fun sanitizeMessage(message: String): String {
        var sanitized = message
        sanitized = sanitized.replace(AUTH_HEADER_REGEX) { match ->
            val key = match.groupValues[1]
            "$key: $REDACTED"
        }
        sanitized = sanitized.replace(URL_QUERY_REPLACEMENT_REGEX) { match ->
            val baseUrl = match.groupValues[1]
            val queryPart = match.groupValues[2]
            val redactedQuery = queryPart.replace(Regex("([^&=]+)=([^&]*)")) { qm ->
                val paramKey = qm.groupValues[1]
                "$paramKey=$REDACTED"
            }
            "$baseUrl$redactedQuery"
        }
        sanitized = sanitized.replace(SENSITIVE_PARAM_REGEX) { match ->
            val key = match.groupValues[1]
            "$key=$REDACTED"
        }
        return sanitized
    }

    override fun logDiagnostic(
        component: DiagnosticComponent,
        category: LogCategory,
        operation: DiagnosticOperation,
        status: DiagnosticStatus
    ) {
        val msg = "category=${category.name} operation=${operation.name} status=${status.name}"
        sink?.log("D", component.name, msg)
    }
}

