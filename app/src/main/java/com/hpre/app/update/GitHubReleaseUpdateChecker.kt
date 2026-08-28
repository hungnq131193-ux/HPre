package com.hpre.app.update

import com.squareup.moshi.JsonReader
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class GitHubReleaseUpdateChecker(
    private val client: OkHttpClient,
    private val endpoint: HttpUrl = DEFAULT_ENDPOINT
) : AppUpdateChecker {
    override suspend fun check(installedVersion: String): UpdateCheckResult {
        val installed = SemanticVersion.parseInstalled(installedVersion)
            ?: return UpdateCheckResult.Unavailable(UpdateUnavailableReason.INVALID_RESPONSE)

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint)
                .get()
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "HPre-Android-UpdateChecker")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 403 -> unavailable(UpdateUnavailableReason.RATE_LIMITED)
                        response.code in 500..599 -> unavailable(UpdateUnavailableReason.SERVER)
                        !response.isSuccessful -> unavailable(UpdateUnavailableReason.INVALID_RESPONSE)
                        else -> {
                            val body = response.body
                                ?: return@use unavailable(UpdateUnavailableReason.INVALID_RESPONSE)
                            val parsed = try {
                                body.use { parseRelease(JsonReader.of(it.source())) }
                            } catch (_: IOException) {
                                null
                            } ?: return@use unavailable(UpdateUnavailableReason.INVALID_RESPONSE)
                            mapRelease(installed, parsed)
                        }
                    }
                }
            } catch (_: IOException) {
                unavailable(UpdateUnavailableReason.NETWORK)
            } catch (_: RuntimeException) {
                unavailable(UpdateUnavailableReason.INVALID_RESPONSE)
            }
        }
    }

    private fun mapRelease(
        installed: SemanticVersion,
        release: ParsedRelease
    ): UpdateCheckResult {
        if (release.draft != false || release.prerelease != false) {
            return unavailable(UpdateUnavailableReason.INVALID_RESPONSE)
        }
        val latest = release.tagName?.let(SemanticVersion::parseTag)
            ?: return unavailable(UpdateUnavailableReason.INVALID_RESPONSE)
        val page = release.htmlUrl?.let(OfficialReleasePage::parse)
            ?: return unavailable(UpdateUnavailableReason.INVALID_RESPONSE)
        val hasApk = release.assetNames.any {
            it.startsWith("HPre-", ignoreCase = true) && it.endsWith(".apk", ignoreCase = true)
        }
        if (!hasApk) return unavailable(UpdateUnavailableReason.INVALID_RESPONSE)

        return if (latest > installed) {
            UpdateCheckResult.UpdateAvailable(installed, latest, page)
        } else {
            UpdateCheckResult.UpToDate(installed)
        }
    }

    private fun parseRelease(reader: JsonReader): ParsedRelease? {
        var tagName: String? = null
        var htmlUrl: String? = null
        var draft: Boolean? = null
        var prerelease: Boolean? = null
        val assetNames = mutableListOf<String>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "tag_name" -> tagName = reader.nextNullableString()
                "html_url" -> htmlUrl = reader.nextNullableString()
                "draft" -> draft = reader.nextNullableBoolean()
                "prerelease" -> prerelease = reader.nextNullableBoolean()
                "assets" -> readAssetNames(reader, assetNames)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        return ParsedRelease(tagName, htmlUrl, draft, prerelease, assetNames)
    }

    private fun readAssetNames(reader: JsonReader, names: MutableList<String>) {
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.nextNull<Unit>()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "name") {
                    reader.nextNullableString()?.let(names::add)
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        }
        reader.endArray()
    }

    private fun JsonReader.nextNullableString(): String? =
        if (peek() == JsonReader.Token.NULL) nextNull() else nextString()

    private fun JsonReader.nextNullableBoolean(): Boolean? =
        if (peek() == JsonReader.Token.NULL) nextNull() else nextBoolean()

    private fun unavailable(reason: UpdateUnavailableReason) =
        UpdateCheckResult.Unavailable(reason)

    private data class ParsedRelease(
        val tagName: String?,
        val htmlUrl: String?,
        val draft: Boolean?,
        val prerelease: Boolean?,
        val assetNames: List<String>
    )

    companion object {
        private val DEFAULT_ENDPOINT =
            "https://api.github.com/repos/hungnq131193-ux/HPre/releases/latest".toHttpUrl()
    }
}
