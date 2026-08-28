package com.hpre.app.update

@JvmInline
value class OfficialReleasePage private constructor(val url: String) {
    companion object {
        private const val PREFIX = "https://github.com/hungnq131193-ux/HPre/releases/tag/"

        fun parse(value: String): OfficialReleasePage? {
            if (!value.startsWith(PREFIX)) return null
            return value.removePrefix(PREFIX)
                .takeIf { SemanticVersion.parseTag(it) != null }
                ?.let { OfficialReleasePage(value) }
        }
    }
}

sealed interface UpdateCheckResult {
    data class UpToDate(val installedVersion: SemanticVersion) : UpdateCheckResult

    data class UpdateAvailable(
        val installedVersion: SemanticVersion,
        val latestVersion: SemanticVersion,
        val releasePage: OfficialReleasePage
    ) : UpdateCheckResult

    data class Unavailable(val reason: UpdateUnavailableReason) : UpdateCheckResult
}

enum class UpdateUnavailableReason {
    NETWORK,
    RATE_LIMITED,
    SERVER,
    INVALID_RESPONSE
}

fun interface AppUpdateChecker {
    suspend fun check(installedVersion: String): UpdateCheckResult
}
