package com.hpre.app.player.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import java.net.URI
import java.net.URLEncoder

data class TransformedRequest(
    val uri: Uri,
    val uriString: String,
    val headers: Map<String, String>,
    val httpMethod: Int,
    val httpBody: ByteArray?,
    val isEligibleYouTube: Boolean
)

object YouTubeRequestPolicy {
    private val POST_BODY = byteArrayOf(0x78, 0x00)

    fun isEligibleYouTubeMediaUrl(urlStr: String): Boolean {
        return try {
            val uri = URI(urlStr)
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "https" && scheme != "http") return false
            val host = uri.host?.lowercase() ?: return false
            val path = uri.path ?: return false

            val isYtHost = host == "googlevideo.com" || host.endsWith(".googlevideo.com") ||
                    host == "youtube.com" || host.endsWith(".youtube.com")

            isYtHost && path.startsWith("/videoplayback")
        } catch (_: Throwable) {
            false
        }
    }

    fun transformDataSpec(
        dataSpec: DataSpec,
        profile: YouTubeRequestProfile,
        requestNumber: Int
    ): TransformedRequest {
        val originalUri = dataSpec.uri
        val urlStr = originalUri.toString()
        val eligible = isEligibleYouTubeMediaUrl(urlStr)
        if (!eligible) {
            return TransformedRequest(
                uri = originalUri,
                uriString = urlStr,
                headers = dataSpec.httpRequestHeaders,
                httpMethod = dataSpec.httpMethod,
                httpBody = dataSpec.httpBody,
                isEligibleYouTube = false
            )
        }

        var newUrl = urlStr

        // 1. rn parameter
        if (profile == YouTubeRequestProfile.DASH || profile == YouTubeRequestProfile.PROGRESSIVE) {
            if (!newUrl.contains("rn=") && !newUrl.contains("&rn=") && !newUrl.contains("?rn=")) {
                val separator = if (newUrl.contains("?")) "&" else "?"
                newUrl += "${separator}rn=$requestNumber"
            }
        }

        // 2. range parameter for DASH
        val headers = dataSpec.httpRequestHeaders.toMutableMap()
        if (profile == YouTubeRequestProfile.DASH) {
            val pos = dataSpec.position
            val len = dataSpec.length
            if (!(pos == 0L && len == C.LENGTH_UNSET.toLong())) {
                val rangeVal = if (len != C.LENGTH_UNSET.toLong()) {
                    val end = try {
                        Math.addExact(pos, len - 1)
                    } catch (_: ArithmeticException) {
                        Long.MAX_VALUE
                    }
                    "$pos-$end"
                } else {
                    "$pos-"
                }
                val separator = if (newUrl.contains("?")) "&" else "?"
                newUrl += "${separator}range=$rangeVal"
                headers.remove("Range")
            }
        }

        // 3. YouTube headers
        headers["Origin"] = "https://www.youtube.com"
        headers["Referer"] = "https://www.youtube.com/"
        headers["Sec-Fetch-Dest"] = "empty"
        headers["Sec-Fetch-Mode"] = "cors"
        headers["Sec-Fetch-Site"] = "cross-site"
        headers["TE"] = "trailers"

        val transformedUri = try {
            Uri.parse(newUrl)
        } catch (_: Throwable) {
            originalUri
        }

        return TransformedRequest(
            uri = transformedUri,
            uriString = newUrl,
            headers = headers,
            httpMethod = DataSpec.HTTP_METHOD_POST,
            httpBody = POST_BODY,
            isEligibleYouTube = true
        )
    }
}
