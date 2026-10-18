package com.hpre.app.extractor

import com.hpre.app.model.ContentKey
import com.hpre.app.model.SearchPage
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.VideoSummary
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import java.nio.charset.StandardCharsets
import java.util.Locale
import okio.Buffer
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Direct YouTube InnerTube `/youtubei/v1/search` parser that extracts `gridShelfViewModel`
 * (`shortsLockupViewModel`) and `reelShelfRenderer` (`reelItemRenderer`) Shorts shelves which
 * `NewPipeExtractor`'s `YoutubeSearchExtractor` ignores, while avoiding NewPipe's corrupted
 * `"EgIQAfABAQ%3D%3D"` video filter parameter that returns 0-view spam.
 */
internal object YoutubeShortsSearchHelper {
    private val VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
    private val SHORTS_TAG_REGEX = Regex("(?i)#?shorts?\\b")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val NON_ALNUM_REGEX = Regex("[^\\p{L}\\p{N}]+")
    private val TRAILING_PART_REGEX = Regex("(?i)\\b(p|part|phần|tập|ep)\\s*\\d+\\b|#\\d+")
    private val VIEW_NUMBER_REGEX = Regex(
        """(\d+(?:[.,]\d+)?)\s*(tr|triệu|n|nghìn|ngàn|t|tỷ|k|m|b)?""",
        RegexOption.IGNORE_CASE
    )

    private const val MIN_SHORTS_VIEW_COUNT = 1_500L
    private const val SEARCH_URL = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"

    fun searchShortsPage(rawQuery: String, serviceId: Int): SearchPage? {
        return try {
            val cleanedQuery = rawQuery
                .replace(SHORTS_TAG_REGEX, " ")
                .replace(WHITESPACE_REGEX, " ")
                .trim()
                .ifBlank { "xu hướng việt nam triệu view" }

            val clientVersion = runCatching { YoutubeParsingHelper.getClientVersion() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "2.20250219.01.00"

            val requestJson = buildSearchRequestJson(cleanedQuery, clientVersion)
            val headers = mapOf(
                "Origin" to listOf("https://www.youtube.com"),
                "Referer" to listOf("https://www.youtube.com"),
                "X-YouTube-Client-Name" to listOf("1"),
                "X-YouTube-Client-Version" to listOf(clientVersion)
            )

            val response = NewPipe.getDownloader().postWithContentTypeJson(
                SEARCH_URL,
                headers,
                requestJson.toByteArray(StandardCharsets.UTF_8),
                org.schabi.newpipe.extractor.localization.Localization("vi", "VN")
            )
            val responseBody = response.responseBody()
            if (responseBody.isNullOrBlank()) return null

            val root = JsonReader.of(Buffer().writeUtf8(responseBody)).use { reader ->
                reader.isLenient = true
                reader.readJsonValue()
            }

            val collected = mutableListOf<VideoSummary>()
            collectShortsFromJson(root, serviceId, collected)

            val seenIds = HashSet<String>()
            val seenTitles = HashSet<String>()
            val deduplicated = collected.filter { video ->
                if (!seenIds.add(video.key.nativeId)) return@filter false
                val titleKey = normalizeTitleFingerprint(video.title)
                if (titleKey.length >= 6 && !seenTitles.add(titleKey)) return@filter false
                true
            }

            if (deduplicated.isEmpty()) {
                null
            } else {
                SearchPage(
                    items = deduplicated.map { SearchResultItem.VideoItem(it) },
                    nextPageToken = null
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildSearchRequestJson(query: String, clientVersion: String): String {
        val buffer = Buffer()
        JsonWriter.of(buffer).use { writer ->
            writer.beginObject()
            writer.name("context").beginObject()
            writer.name("client").beginObject()
            writer.name("clientName").value("WEB")
            writer.name("clientVersion").value(clientVersion)
            writer.name("hl").value("vi")
            writer.name("gl").value("VN")
            writer.name("platform").value("DESKTOP")
            writer.name("utcOffsetMinutes").value(0)
            writer.endObject()
            writer.name("request").beginObject()
            writer.name("internalExperimentFlags").beginArray().endArray()
            writer.name("useSsl").value(true)
            writer.endObject()
            writer.name("user").beginObject()
            writer.name("lockedSafetyMode").value(false)
            writer.endObject()
            writer.endObject()
            writer.name("query").value(query)
            writer.endObject()
        }
        return buffer.readUtf8()
    }

    private fun collectShortsFromJson(
        node: Any?,
        serviceId: Int,
        out: MutableList<VideoSummary>
    ) {
        when (node) {
            is Map<*, *> -> {
                val lockup = node["shortsLockupViewModel"] as? Map<*, *>
                if (lockup != null) {
                    parseShortsLockupViewModel(lockup, serviceId)?.let(out::add)
                    return
                }
                val reel = node["reelItemRenderer"] as? Map<*, *>
                if (reel != null) {
                    parseReelItemRenderer(reel, serviceId)?.let(out::add)
                    return
                }
                val video = node["videoRenderer"] as? Map<*, *>
                if (video != null) {
                    parseShortVideoRenderer(video, serviceId)?.let(out::add)
                }
                for (value in node.values) {
                    collectShortsFromJson(value, serviceId, out)
                }
            }
            is List<*> -> {
                for (item in node) {
                    collectShortsFromJson(item, serviceId, out)
                }
            }
        }
    }

    private val CHANNEL_ID_REGEX = Regex("""UC[a-zA-Z0-9_-]{22}""")

    private fun extractChannelIdFromReelParams(params: String?): String? {
        if (params.isNullOrBlank()) return null
        CHANNEL_ID_REGEX.find(params)?.value?.let { return it }
        val clean = try {
            java.net.URLDecoder.decode(params, StandardCharsets.UTF_8.name())
        } catch (_: Throwable) {
            params
        }
        val padded = clean.padEnd((clean.length + 3) / 4 * 4, '=')
        val decodedBytes = runCatching { java.util.Base64.getUrlDecoder().decode(padded) }.getOrNull()
            ?: runCatching { java.util.Base64.getDecoder().decode(padded) }.getOrNull()
            ?: return null
        val text = String(decodedBytes, StandardCharsets.ISO_8859_1)
        return CHANNEL_ID_REGEX.find(text)?.value
    }

    private fun extractBrowseChannelId(textObj: Map<*, *>?): String? {
        val runs = textObj?.listAt("runs") ?: return null
        for (item in runs) {
            val run = item as? Map<*, *> ?: continue
            val browseId = run.mapAt("navigationEndpoint")
                ?.mapAt("browseEndpoint")
                ?.stringAt("browseId")
                ?.trim()
            if (browseId != null && CHANNEL_ID_REGEX.matches(browseId)) {
                return browseId
            }
        }
        return null
    }

    private fun parseShortsLockupViewModel(lockup: Map<*, *>, serviceId: Int): VideoSummary? {
        val reelWatchEndpoint = lockup.mapAt("onTap")
            ?.mapAt("innertubeCommand")
            ?.mapAt("reelWatchEndpoint")
        val rawId = reelWatchEndpoint?.stringAt("videoId")
            ?: lockup.mapAt("inlinePlayerData")
                ?.mapAt("onVisible")
                ?.mapAt("innertubeCommand")
                ?.mapAt("watchEndpoint")
                ?.stringAt("videoId")
            ?: lockup.stringAt("entityId")
                ?.removePrefix("shorts-shelf-item-")
                ?.takeIf { it.isNotBlank() }
            ?: return null

        if (!VIDEO_ID_REGEX.matches(rawId)) return null

        val overlay = lockup.mapAt("overlayMetadata")
        val title = overlay?.mapAt("primaryText")?.stringAt("content")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: lockup.stringAt("accessibilityText")?.substringBeforeLast(",")?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: return null

        val viewsText = overlay?.mapAt("secondaryText")?.stringAt("content")
        val viewCount = parseViewCount(viewsText)
        if (viewCount != null && viewCount < MIN_SHORTS_VIEW_COUNT) return null

        val channelId = extractChannelIdFromReelParams(reelWatchEndpoint?.stringAt("params"))
        val channelKey = channelId?.let { ContentKey(serviceId, it) }

        val sources = lockup.mapAt("thumbnailViewModel")
            ?.mapAt("thumbnailViewModel")
            ?.mapAt("image")
            ?.listAt("sources")
        var bestThumb: String? = null
        var bestArea = 0
        if (sources != null) {
            for (item in sources) {
                val src = item as? Map<*, *> ?: continue
                val url = src.stringAt("url")?.takeIf { it.startsWith("http") } ?: continue
                val area = src.intAt("width") * src.intAt("height")
                if (bestThumb == null || area >= bestArea) {
                    bestThumb = url
                    bestArea = area
                }
            }
        }

        return VideoSummary(
            key = ContentKey(serviceId, rawId),
            title = title,
            canonicalUrl = "https://www.youtube.com/shorts/$rawId",
            channelKey = channelKey,
            channelName = null,
            channelAvatarUrl = null,
            thumbnailUrl = bestThumb ?: "https://i.ytimg.com/vi/$rawId/oar2.jpg",
            durationSeconds = null,
            viewCount = viewCount,
            publishedTimestamp = null,
            isLive = false,
            isShort = true
        )
    }

    private fun parseReelItemRenderer(reel: Map<*, *>, serviceId: Int): VideoSummary? {
        val rawId = reel.stringAt("videoId")?.trim() ?: return null
        if (!VIDEO_ID_REGEX.matches(rawId)) return null
        val title = extractRunsOrSimpleText(reel.mapAt("headline"))?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val viewsText = extractRunsOrSimpleText(reel.mapAt("viewCountText"))
        val viewCount = parseViewCount(viewsText)
        if (viewCount != null && viewCount < MIN_SHORTS_VIEW_COUNT) return null

        val reelParams = reel.mapAt("navigationEndpoint")
            ?.mapAt("reelWatchEndpoint")
            ?.stringAt("params")
        val channelId = extractChannelIdFromReelParams(reelParams)
        val channelKey = channelId?.let { ContentKey(serviceId, it) }

        return VideoSummary(
            key = ContentKey(serviceId, rawId),
            title = title,
            canonicalUrl = "https://www.youtube.com/shorts/$rawId",
            channelKey = channelKey,
            channelName = null,
            channelAvatarUrl = null,
            thumbnailUrl = "https://i.ytimg.com/vi/$rawId/oar2.jpg",
            durationSeconds = null,
            viewCount = viewCount,
            publishedTimestamp = null,
            isLive = false,
            isShort = true
        )
    }

    private fun parseShortVideoRenderer(video: Map<*, *>, serviceId: Int): VideoSummary? {
        val rawId = video.stringAt("videoId")?.trim() ?: return null
        if (!VIDEO_ID_REGEX.matches(rawId)) return null

        val reelEndpoint = video.mapAt("navigationEndpoint")?.mapAt("reelWatchEndpoint")
        val hasReelEndpoint = reelEndpoint != null
        val lengthText = extractRunsOrSimpleText(video.mapAt("lengthText"))
        val durationSec = parseDurationSeconds(lengthText)
        if (!hasReelEndpoint && (durationSec == null || durationSec !in 1L..60L)) {
            return null
        }

        val title = extractRunsOrSimpleText(video.mapAt("title"))?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val viewsText = extractRunsOrSimpleText(video.mapAt("viewCountText"))
            ?: extractRunsOrSimpleText(video.mapAt("shortViewCountText"))
        val viewCount = parseViewCount(viewsText)
        if (viewCount == null || viewCount < MIN_SHORTS_VIEW_COUNT) return null

        val ownerText = video.mapAt("ownerText")
        val longBylineText = video.mapAt("longBylineText")
        val channelName = extractRunsOrSimpleText(ownerText)
            ?.takeIf { it.isNotBlank() }
            ?: extractRunsOrSimpleText(longBylineText)
                ?.takeIf { it.isNotBlank() }
        val channelId = extractBrowseChannelId(ownerText)
            ?: extractBrowseChannelId(longBylineText)
            ?: extractChannelIdFromReelParams(reelEndpoint?.stringAt("params"))
        val channelKey = channelId?.let { ContentKey(serviceId, it) }

        return VideoSummary(
            key = ContentKey(serviceId, rawId),
            title = title,
            canonicalUrl = "https://www.youtube.com/shorts/$rawId",
            channelKey = channelKey,
            channelName = channelName,
            channelAvatarUrl = null,
            thumbnailUrl = "https://i.ytimg.com/vi/$rawId/oar2.jpg",
            durationSeconds = durationSec,
            viewCount = viewCount,
            publishedTimestamp = null,
            isLive = false,
            isShort = true
        )
    }

    private fun extractRunsOrSimpleText(textObj: Map<*, *>?): String? {
        if (textObj == null) return null
        textObj.stringAt("simpleText")?.let { return it }
        textObj.stringAt("content")?.let { return it }
        val runs = textObj.listAt("runs") ?: return null
        val sb = StringBuilder()
        for (item in runs) {
            val run = item as? Map<*, *> ?: continue
            run.stringAt("text")?.let(sb::append)
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun Map<*, *>.mapAt(key: String): Map<*, *>? = this[key] as? Map<*, *>
    private fun Map<*, *>.listAt(key: String): List<*>? = this[key] as? List<*>
    private fun Map<*, *>.stringAt(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }
    private fun Map<*, *>.intAt(key: String): Int = (this[key] as? Number)?.toInt() ?: 0

    internal fun parseViewCount(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val normalized = text.replace('\u00A0', ' ').trim()
        if (normalized.contains("không có lượt xem", ignoreCase = true) ||
            normalized.contains("no views", ignoreCase = true)
        ) {
            return 0L
        }
        val match = VIEW_NUMBER_REGEX.find(normalized) ?: return null
        val numberPart = match.groupValues[1]
        val unitPart = match.groupValues.getOrNull(2)?.lowercase(Locale.ROOT).orEmpty()

        return if (unitPart.isNotEmpty()) {
            val value = numberPart.replace(',', '.').toDoubleOrNull() ?: return null
            val multiplier = when (unitPart) {
                "n", "nghìn", "ngàn", "k" -> 1_000.0
                "tr", "triệu", "m" -> 1_000_000.0
                "t", "tỷ", "b" -> 1_000_000_000.0
                else -> 1.0
            }
            (value * multiplier).toLong()
        } else {
            val digitsOnly = normalized.substringBefore("lượt").substringBefore("view")
                .filter { it.isDigit() }
            digitsOnly.toLongOrNull()
        }
    }

    private fun parseDurationSeconds(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val parts = text.trim().split(':').map { it.trim().toLongOrNull() ?: return null }
        return when (parts.size) {
            2 -> parts[0] * 60L + parts[1]
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            else -> null
        }
    }

    internal fun normalizeTitleFingerprint(title: String): String {
        val stripped = title
            .lowercase(Locale.ROOT)
            .replace(SHORTS_TAG_REGEX, " ")
            .replace(TRAILING_PART_REGEX, " ")
            .replace(NON_ALNUM_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        val tokens = stripped.split(' ').filter { it.isNotBlank() }
        return tokens.take(8).joinToString(" ")
    }
}
