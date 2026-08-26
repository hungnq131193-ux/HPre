package com.flowtube.app.navigation

import com.flowtube.app.model.ContentKey
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object RouteEncoder {
    private const val ALLOWED_UNRESERVED = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.~"

    /**
     * Encodes a route parameter. Encodes all characters except unreserved characters (RFC 3986)
     * using percent-encoding in UTF-8.
     */
    fun encode(value: String): String {
        val out = StringBuilder()
        val utf8Bytes = value.toByteArray(StandardCharsets.UTF_8)
        for (b in utf8Bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in ALLOWED_UNRESERVED) {
                out.append(c)
            } else {
                out.append('%')
                val hex1 = Character.forDigit((b.toInt() ushr 4) and 0xF, 16).uppercaseChar()
                val hex2 = Character.forDigit(b.toInt() and 0xF, 16).uppercaseChar()
                out.append(hex1)
                out.append(hex2)
            }
        }
        return out.toString()
    }

    /**
     * Strictly decodes a percent-encoded UTF-8 string.
     * Returns null if the string contains malformed percent sequences (e.g. "%", "%2", "%ZZ")
     * or malformed UTF-8 byte sequences.
     */
    fun decode(value: String): String? {
        val bytes = ByteArrayOutputStream()
        var i = 0
        val len = value.length
        while (i < len) {
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= len) {
                    return null // Incomplete percent sequence
                }
                val hex1 = Character.digit(value[i + 1], 16)
                val hex2 = Character.digit(value[i + 2], 16)
                if (hex1 == -1 || hex2 == -1) {
                    return null // Invalid hex digits
                }
                bytes.write((hex1 shl 4) or hex2)
                i += 3
            } else {
                val charBytes = c.toString().toByteArray(StandardCharsets.UTF_8)
                bytes.write(charBytes, 0, charBytes.size)
                i++
            }
        }

        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val decodedCharBuffer = decoder.decode(ByteBuffer.wrap(bytes.toByteArray()))
            decodedCharBuffer.toString()
        } catch (_: Exception) {
            null
        }
    }
}

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Search : Screen("search")
    data object Shorts : Screen("shorts")
    data object Subscriptions : Screen("subscriptions")
    data object Library : Screen("library")
    data object History : Screen("history")
    data object Playlists : Screen("playlists")
    data object PlaylistDetail : Screen("playlist_detail/{playlistId}") {
        fun createRoute(playlistId: Long): String = "playlist_detail/$playlistId"
    }
    data object Settings : Screen("settings")
    data object Channel : Screen("channel/{serviceId}/{nativeId}") {
        fun createRoute(key: ContentKey): String =
            "channel/${key.serviceId}/${RouteEncoder.encode(key.nativeId)}"

        fun parseNavArgument(serviceId: Int?, nativeId: String?): ContentKey? =
            if (serviceId == null || nativeId.isNullOrBlank()) null else ContentKey(serviceId, nativeId)

        fun parseRawPath(route: String): ContentKey? {
            if (!route.startsWith("channel/")) return null
            val parts = route.removePrefix("channel/").split("/", limit = 2)
            if (parts.size != 2) return null
            val serviceId = parts[0].toIntOrNull() ?: return null
            val nativeId = RouteEncoder.decode(parts[1]) ?: return null
            return if (nativeId.isBlank()) null else ContentKey(serviceId, nativeId)
        }
    }
    data object ChannelUnavailable : Screen("channel_unavailable/{serviceId}/{nativeId}") {
        fun createRoute(key: ContentKey): String {
            val encodedId = RouteEncoder.encode(key.nativeId)
            return "channel_unavailable/${key.serviceId}/$encodedId"
        }

        fun parseNavArgument(serviceId: Int?, rawNativeId: String?): ContentKey? {
            if (serviceId == null || rawNativeId == null || rawNativeId.isBlank()) return null
            return ContentKey(serviceId, rawNativeId)
        }
    }
    data object PlaylistUnavailable : Screen("playlist_unavailable/{serviceId}/{nativeId}") {
        fun createRoute(key: ContentKey): String {
            val encodedId = RouteEncoder.encode(key.nativeId)
            return "playlist_unavailable/${key.serviceId}/$encodedId"
        }

        fun parseNavArgument(serviceId: Int?, rawNativeId: String?): ContentKey? {
            if (serviceId == null || rawNativeId == null || rawNativeId.isBlank()) return null
            return ContentKey(serviceId, rawNativeId)
        }
    }
    data object Watch : Screen("watch/{serviceId}/{nativeId}") {
        fun createRoute(key: ContentKey): String {
            val encodedId = RouteEncoder.encode(key.nativeId)
            return "watch/${key.serviceId}/$encodedId"
        }

        /**
         * Parses a navigation argument string supplied by NavBackStackEntry.
         * Navigation runtime has already decoded the path parameter once.
         * We validate that it is nonblank and not empty, without double-decoding.
         */
        fun parseNavArgument(serviceId: Int?, navNativeId: String?): ContentKey? {
            if (serviceId == null || navNativeId == null || navNativeId.isBlank()) return null
            return ContentKey(serviceId, navNativeId)
        }

        /**
         * Parses a direct/raw route string by strictly decoding the percent-encoded path segment.
         */
        fun parseRawPath(rawRoute: String): ContentKey? {
            if (!rawRoute.startsWith("watch/")) return null
            val parts = rawRoute.removePrefix("watch/").split("/", limit = 2)
            if (parts.size != 2) return null
            val serviceId = parts[0].toIntOrNull() ?: return null
            val decodedNativeId = RouteEncoder.decode(parts[1]) ?: return null
            if (decodedNativeId.isBlank()) return null
            return ContentKey(serviceId, decodedNativeId)
        }
    }
}
