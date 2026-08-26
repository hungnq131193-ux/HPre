package com.hpre.app.ui.watch

import java.net.URI

/**
 * Validates URLs used for canonical sharing.
 * Strictly requires http or https scheme, non-empty host, and no embedded userinfo.
 */
object ShareUrlValidator {

    fun isValid(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val trimmed = url.trim()
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            val userInfo = uri.userInfo

            (scheme == "http" || scheme == "https") &&
                    !host.isNullOrBlank() &&
                    userInfo == null
        } catch (_: Throwable) {
            false
        }
    }
}
