package com.hpre.app.navigation

import com.hpre.app.core.performance.VideoOpenEvent
import com.hpre.app.core.performance.VideoOpenMetrics
import com.hpre.app.model.ContentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutesTest {
    @Test
    fun video_navigation_cancels_background_then_starts_tap_then_navigates() {
        val order = mutableListOf<String>()
        val metrics = VideoOpenMetrics(enabled = true, sink = { record ->
            if (record.event == VideoOpenEvent.VIDEO_OPEN_START) order += "tap"
        })

        beginVideoNavigation(
            key = ContentKey(0, "video"),
            beforeNavigate = { order += "cancel" },
            navigate = { order += "navigate" },
            metrics = metrics
        )

        assertEquals(listOf("cancel", "tap", "navigate"), order)
    }

    @Test
    fun channel_route_round_trips_stable_content_key() {
        val key = ContentKey(3, "creator/id")
        val route = Screen.Channel.createRoute(key)

        assertEquals("channel/3/creator%2Fid", route)
        assertEquals(key, Screen.Channel.parseRawPath(route))
    }

    @Test
    fun route_encoder_encodes_and_decodes_special_characters_and_unicode() {
        val testStrings = listOf(
            "simple123",
            "video/with/slash",
            "video?query=1&foo=bar",
            "video#hashtag",
            "video%20percent",
            "video with spaces",
            "video+plus",
            "video_tiếng_việt_日本語_🎉"
        )

        for (str in testStrings) {
            val encoded = RouteEncoder.encode(str)
            val decoded = RouteEncoder.decode(encoded)
            assertEquals("Decoded should match original for '$str'", str, decoded)
        }
    }

    @Test
    fun route_encoder_decode_is_strict_and_returns_null_on_malformed_input() {
        assertNull(RouteEncoder.decode("%"))
        assertNull(RouteEncoder.decode("%2"))
        assertNull(RouteEncoder.decode("%ZZ"))
        assertNull(RouteEncoder.decode("video%2Gtest"))
        assertNull(RouteEncoder.decode("video%E0%A4")) // Incomplete UTF-8 sequence
    }

    @Test
    fun watch_route_create_and_parse_arguments() {
        val testNativeIds = listOf(
            "simple123",
            "video/with/slash",
            "video?query=1&foo=bar",
            "video#hashtag",
            "video%20percent",
            "video with spaces",
            "video+plus",
            "video_tiếng_việt_日本語_🎉"
        )

        for (nativeId in testNativeIds) {
            val key = ContentKey(0, nativeId)
            // In Compose Navigation, NavHost passes the nav argument which was already URL-decoded once by Navigation runtime.
            // Screen.Watch.parseNavArgument validates that it is nonblank and valid.
            val navArg = nativeId // As delivered by NavBackStackEntry after Navigation's single decode
            val parsedKey = Screen.Watch.parseNavArgument(0, navArg)
            assertEquals(key, parsedKey)
        }
    }

    @Test
    fun watch_route_parse_raw_path_decodes_strictly() {
        val key = ContentKey(0, "video/slash#hash")
        val route = Screen.Watch.createRoute(key)
        val parsed = Screen.Watch.parseRawPath(route)
        assertEquals(key, parsed)

        assertNull(Screen.Watch.parseRawPath("watch/0/"))
        assertNull(Screen.Watch.parseRawPath("watch/0/%20"))
        assertNull(Screen.Watch.parseRawPath("watch/0/%ZZ"))
        assertNull(Screen.Watch.parseRawPath("watch/invalid/id123"))
        assertNull(Screen.Watch.parseRawPath("invalid_route"))
    }

    @Test
    fun watch_route_parse_nav_argument_validates_blank_and_null() {
        assertNull(Screen.Watch.parseNavArgument(null, "id123"))
        assertNull(Screen.Watch.parseNavArgument(0, null))
        assertNull(Screen.Watch.parseNavArgument(0, ""))
        assertNull(Screen.Watch.parseNavArgument(0, "   "))
    }

    @Test fun watch_route_carries_encoded_https_thumbnail() {
        val key = ContentKey(0, "video/id")
        val thumbnail = "https://i.test/thumb.jpg?x=1&y=2"
        val route = Screen.Watch.createRoute(key, thumbnail)

        assertEquals(
            "watch/0/video%2Fid?thumbnail=https%3A%2F%2Fi.test%2Fthumb.jpg%3Fx%3D1%26y%3D2",
            route
        )
        assertEquals(thumbnail, Screen.Watch.parseThumbnailArgument(thumbnail))
        assertEquals(key, Screen.Watch.parseRawPath(route))
    }

    @Test fun watch_thumbnail_rejects_non_http_schemes_and_blank_values() {
        assertNull(Screen.Watch.parseThumbnailArgument("file:///private/path"))
        assertNull(Screen.Watch.parseThumbnailArgument("javascript:alert(1)"))
        assertNull(Screen.Watch.parseThumbnailArgument(" "))
    }
}
