package com.hpre.app.player.datasource

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import java.net.URI

data class TransformedRequest(
    val uri: Uri,
    val uriString: String,
    val headers: Map<String, String>,
    val httpMethod: Int,
    val httpBody: ByteArray?,
    val isEligibleYouTube: Boolean,
    val position: Long,
    val length: Long,
    val addedRn: Boolean
)

@OptIn(UnstableApi::class)
object YouTubeRequestPolicy {
    private val POST_BODY = byteArrayOf(0x78, 0x00)

    private fun hasQueryParameter(url: String, name: String): Boolean {
        val query = try {
            URI(url).rawQuery
        } catch (_: Throwable) {
            null
        } ?: return false

        return query.split("&").any { it.substringBefore("=") == name }
    }

    private fun replaceQueryParameter(url: String, name: String, value: String): String {
        val baseWithoutQuery = url.substringBefore("?")
        val rawQuery = url.substringAfter("?", "")
        if (rawQuery.isEmpty()) {
            return "$baseWithoutQuery?$name=$value"
        }
        val retained = rawQuery.split("&")
            .filter { it.isNotEmpty() && it.substringBefore("=") != name }
            .toMutableList()
        retained += "$name=$value"
        return "$baseWithoutQuery?${retained.joinToString("&")}"
    }

    fun isEligibleYouTubeMediaUrl(urlStr: String): Boolean {
        return try {
            val uri = URI(urlStr)
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "https" && scheme != "http") return false
            val host = uri.host?.lowercase() ?: return false
            val path = uri.path ?: return false

            val isYtHost = host == "googlevideo.com" || host.endsWith(".googlevideo.com") ||
                    host == "youtube.com" || host.endsWith(".youtube.com")

            isYtHost && (path == "/videoplayback" || path.startsWith("/videoplayback/"))
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
                isEligibleYouTube = false,
                position = dataSpec.position,
                length = dataSpec.length,
                addedRn = false
            )
        }

        var newUrl = urlStr
        var addedRn = false
        var position = dataSpec.position
        var length = dataSpec.length

        // 1. rn parameter
        if (profile == YouTubeRequestProfile.DASH || profile == YouTubeRequestProfile.PROGRESSIVE) {
            if (!hasQueryParameter(newUrl, "rn")) {
                newUrl = replaceQueryParameter(newUrl, "rn", requestNumber.toString())
                addedRn = true
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
                newUrl = replaceQueryParameter(newUrl, "range", rangeVal)
                headers.remove("Range")
                position = 0L
                length = C.LENGTH_UNSET.toLong()
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
            isEligibleYouTube = true,
            position = position,
            length = length,
            addedRn = addedRn
        )
    }
}
