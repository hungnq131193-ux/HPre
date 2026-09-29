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

/** A release APK that may be downloaded in-app; only official repository assets are accepted. */
data class ReleaseApk(
    val downloadUrl: String,
    val sizeBytes: Long,
    /** Lowercase hex SHA-256 published by GitHub, or null when the release has no digest. */
    val sha256: String?
) {
    companion object {
        private const val DOWNLOAD_PREFIX = "https://github.com/hungnq131193-ux/HPre/releases/download/"
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        fun parse(downloadUrl: String?, sizeBytes: Long?, digest: String?): ReleaseApk? {
            if (downloadUrl == null || !downloadUrl.startsWith(DOWNLOAD_PREFIX)) return null
            val size = sizeBytes?.takeIf { it > 0 } ?: return null
            val sha256 = digest?.lowercase()?.removePrefix("sha256:")?.takeIf { SHA256_HEX.matches(it) }
            return ReleaseApk(downloadUrl, size, sha256)
        }
    }
}

sealed interface UpdateCheckResult {
    data class UpToDate(val installedVersion: SemanticVersion) : UpdateCheckResult

    data class UpdateAvailable(
        val installedVersion: SemanticVersion,
        val latestVersion: SemanticVersion,
        val releasePage: OfficialReleasePage,
        val apk: ReleaseApk? = null
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
