package com.hpre.app.update

import com.squareup.moshi.JsonReader
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.resume

class GitHubReleaseUpdateChecker(
    private val client: OkHttpClient,
    private val endpoint: HttpUrl = DEFAULT_ENDPOINT
) : AppUpdateChecker {
    override suspend fun check(installedVersion: String): UpdateCheckResult {
        val installed = SemanticVersion.parseInstalled(installedVersion)
            ?: return UpdateCheckResult.Unavailable(UpdateUnavailableReason.INVALID_RESPONSE)

        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(endpoint)
                .get()
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "HPre-Android-UpdateChecker")
                .build()

            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: IOException) {
                    continuation.resume(unavailable(UpdateUnavailableReason.NETWORK))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val result = try {
                        response.use {
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
                    continuation.resume(result)
                }
            })
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
        val apkAsset = release.assets.firstOrNull {
            it.name.startsWith("HPre-", ignoreCase = true) && it.name.endsWith(".apk", ignoreCase = true)
        } ?: return unavailable(UpdateUnavailableReason.INVALID_RESPONSE)

        return if (latest > installed) {
            val apk = ReleaseApk.parse(apkAsset.downloadUrl, apkAsset.size, apkAsset.digest)
            UpdateCheckResult.UpdateAvailable(installed, latest, page, apk)
        } else {
            UpdateCheckResult.UpToDate(installed)
        }
    }

    private fun parseRelease(reader: JsonReader): ParsedRelease? {
        var tagName: String? = null
        var htmlUrl: String? = null
        var draft: Boolean? = null
        var prerelease: Boolean? = null
        val assets = mutableListOf<ParsedAsset>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "tag_name" -> tagName = reader.nextNullableString()
                "html_url" -> htmlUrl = reader.nextNullableString()
                "draft" -> draft = reader.nextNullableBoolean()
                "prerelease" -> prerelease = reader.nextNullableBoolean()
                "assets" -> readAssets(reader, assets)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        return ParsedRelease(tagName, htmlUrl, draft, prerelease, assets)
    }

    private fun readAssets(reader: JsonReader, assets: MutableList<ParsedAsset>) {
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.nextNull<Unit>()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            var name: String? = null
            var downloadUrl: String? = null
            var size: Long? = null
            var digest: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "name" -> name = reader.nextNullableString()
                    "browser_download_url" -> downloadUrl = reader.nextNullableString()
                    "size" -> size = if (reader.peek() == JsonReader.Token.NUMBER) reader.nextLong() else {
                        reader.skipValue(); null
                    }
                    "digest" -> digest = reader.nextNullableString()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            name?.let { assets += ParsedAsset(it, downloadUrl, size, digest) }
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
        val assets: List<ParsedAsset>
    )

    private data class ParsedAsset(
        val name: String,
        val downloadUrl: String?,
        val size: Long?,
        val digest: String?
    )

    companion object {
        private val DEFAULT_ENDPOINT =
            "https://api.github.com/repos/hungnq131193-ux/HPre/releases/latest".toHttpUrl()
    }
}
