package com.hpre.app.update

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubReleaseUpdateCheckerTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(250, TimeUnit.MILLISECONDS)
            .readTimeout(250, TimeUnit.MILLISECONDS)
            .callTimeout(500, TimeUnit.MILLISECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun newer_stable_release_with_hpre_apk_returns_update_available() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson("v1.0.1")))

        val result = checker().check("1.0.0")

        assertEquals(
            UpdateCheckResult.UpdateAvailable(
                installedVersion = SemanticVersion(1, 0, 0),
                latestVersion = SemanticVersion(1, 0, 1),
                releasePage = officialPage("v1.0.1")
            ),
            result
        )
    }

    @Test
    fun newer_stable_release_without_v_tag_returns_update_available() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson("1.0.1")))

        val result = checker().check("1.0.0")

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        result as UpdateCheckResult.UpdateAvailable
        assertEquals(SemanticVersion(1, 0, 0), result.installedVersion)
        assertEquals(SemanticVersion(1, 0, 1), result.latestVersion)
        assertEquals(
            "https://github.com/hungnq131193-ux/HPre/releases/tag/1.0.1",
            result.releasePage.url
        )
    }

    @Test
    fun equal_or_older_release_returns_up_to_date() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson("v1.0.0")))
        assertEquals(
            UpdateCheckResult.UpToDate(SemanticVersion(1, 0, 0)),
            checker().check("1.0.0")
        )

        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson("v0.9.9")))
        assertEquals(
            UpdateCheckResult.UpToDate(SemanticVersion(1, 0, 0)),
            checker().check("1.0.0")
        )
    }

    @Test
    fun request_uses_expected_path_accept_and_safe_user_agent() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson("v1.0.0")))

        checker().check("1.0.0")

        val request = server.takeRequest()
        assertEquals("/repos/hungnq131193-ux/HPre/releases/latest", request.path)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
        assertEquals("HPre-Android-UpdateChecker", request.getHeader("User-Agent"))
        assertEquals(null, request.getHeader("Authorization"))
        assertEquals(null, request.getHeader("Cookie"))
    }

    @Test
    fun draft_prerelease_or_missing_hpre_apk_is_invalid_response() = runTest {
        val invalidBodies = listOf(
            releaseJson("v1.0.1", draft = true),
            releaseJson("v1.0.1", prerelease = true),
            releaseJson("v1.0.1", assetName = "app-release.apk"),
            releaseJson("v1.0.1", assetName = "HPre-v1.0.1-release.aab")
        )

        invalidBodies.forEach { body ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(body))
            assertUnavailable(UpdateUnavailableReason.INVALID_RESPONSE, checker().check("1.0.0"))
        }
    }

    @Test
    fun wrong_repository_or_http_release_url_is_invalid_response() = runTest {
        listOf(
            "https://github.com/other/HPre/releases/tag/v1.0.1",
            "http://github.com/hungnq131193-ux/HPre/releases/tag/v1.0.1"
        ).forEach { page ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson("v1.0.1", page)))
            assertUnavailable(UpdateUnavailableReason.INVALID_RESPONSE, checker().check("1.0.0"))
        }
    }

    @Test
    fun malformed_json_and_http_errors_map_to_safe_reasons() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{not-json"))
        assertUnavailable(UpdateUnavailableReason.INVALID_RESPONSE, checker().check("1.0.0"))

        server.enqueue(MockResponse().setResponseCode(403))
        assertUnavailable(UpdateUnavailableReason.RATE_LIMITED, checker().check("1.0.0"))

        server.enqueue(MockResponse().setResponseCode(404))
        assertUnavailable(UpdateUnavailableReason.INVALID_RESPONSE, checker().check("1.0.0"))

        server.enqueue(MockResponse().setResponseCode(503))
        assertUnavailable(UpdateUnavailableReason.SERVER, checker().check("1.0.0"))
    }

    @Test
    fun connection_failure_is_network() = runTest {
        val endpoint = server.url("/repos/hungnq131193-ux/HPre/releases/latest")
        server.shutdown()

        val result = GitHubReleaseUpdateChecker(client, endpoint).check("1.0.0")

        assertUnavailable(UpdateUnavailableReason.NETWORK, result)
        server = MockWebServer()
        server.start()
    }

    @Test
    fun invalid_installed_version_is_rejected_without_request() = runTest {
        val result = checker().check("1.0-beta")

        assertUnavailable(UpdateUnavailableReason.INVALID_RESPONSE, result)
        assertEquals(0, server.requestCount)
    }

    private fun checker(): GitHubReleaseUpdateChecker = GitHubReleaseUpdateChecker(
        client = client,
        endpoint = server.url("/repos/hungnq131193-ux/HPre/releases/latest")
    )

    private fun officialPage(tag: String): OfficialReleasePage = requireNotNull(
        OfficialReleasePage.parse("https://github.com/hungnq131193-ux/HPre/releases/tag/$tag")
    )

    private fun releaseJson(
        tag: String,
        page: String = "https://github.com/hungnq131193-ux/HPre/releases/tag/$tag",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assetName: String = "HPre-$tag-release.apk"
    ): String = """
        {
          "tag_name": "$tag",
          "html_url": "$page",
          "draft": $draft,
          "prerelease": $prerelease,
          "assets": [{"name": "$assetName"}],
          "ignored": {"nested": true}
        }
    """.trimIndent()

    private fun assertUnavailable(reason: UpdateUnavailableReason, result: UpdateCheckResult) {
        assertTrue(result is UpdateCheckResult.Unavailable)
        assertEquals(reason, (result as UpdateCheckResult.Unavailable).reason)
    }
}
