package com.hpre.app.extractor

import com.hpre.app.model.AudioStream as DomainAudioStream
import com.hpre.app.model.Channel as DomainChannel
import com.hpre.app.model.ChannelDetails as DomainChannelDetails
import com.hpre.app.model.Comment as DomainComment
import com.hpre.app.model.CommentPage as DomainCommentPage
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PlaylistDetails as DomainPlaylistDetails
import com.hpre.app.model.PlaylistSummary as DomainPlaylistSummary
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchPage as DomainSearchPage
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.StreamInfo as DomainStreamInfo
import com.hpre.app.model.SubtitleStream as DomainSubtitleStream
import com.hpre.app.model.VideoDetails as DomainVideoDetails
import com.hpre.app.model.VideoStream as DomainVideoStream
import com.hpre.app.model.VideoSummary as DomainVideoSummary
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Pure mapping functions from NewPipeExtractor types to HPre domain models.
 * Completely keeps extractor objects isolated inside the extractor package.
 */
object NewPipeMappers {

    private val VIDEO_ID_REGEX = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val CHANNEL_ID_REGEX = Regex("""^UC[A-Za-z0-9_-]{22}$""")
    private val PLAYLIST_ID_REGEX = Regex("""^(PL|LL|UU|FL|RD|OLAK5uy_)[A-Za-z0-9_-]+$""")

    private val NUMERIC_LOOKING_IPV4 = Regex("""^\d+(\.\d+)+$""")

    fun mapPageToPageToken(page: org.schabi.newpipe.extractor.Page?): com.hpre.app.model.PageToken? {
        if (page == null) return null
        val url = page.url?.trim()
        if (!url.isNullOrBlank() && isValidHttpUrl(url)) {
            return com.hpre.app.model.PageToken.Url(url)
        }
        val id = page.id?.trim()
        if (!id.isNullOrBlank()) {
            return com.hpre.app.model.PageToken.Id(id)
        }
        return null
    }

    fun reconstituteNewPipePage(token: com.hpre.app.model.PageToken?, baseUrl: String?): org.schabi.newpipe.extractor.Page? {
        if (token == null) return null
        return when (token) {
            is com.hpre.app.model.PageToken.Url -> org.schabi.newpipe.extractor.Page(token.url)
            is com.hpre.app.model.PageToken.Id -> org.schabi.newpipe.extractor.Page(baseUrl, token.id)
        }
    }

    private fun isRestrictedIpv4(b0: Int, b1: Int, b2: Int, b3: Int): Boolean {
        // 0.0.0.0/8 (unspecified / broadcast)
        if (b0 == 0) return true
        // 10.0.0.0/8 (private)
        if (b0 == 10) return true
        // 127.0.0.0/8 (loopback)
        if (b0 == 127) return true
        // 169.254.0.0/16 (link-local)
        if (b0 == 169 && b1 == 254) return true
        // 172.16.0.0/12 (private: 172.16.x.x - 172.31.x.x)
        if (b0 == 172 && b1 in 16..31) return true
        // 192.168.0.0/16 (private)
        if (b0 == 192 && b1 == 168) return true
        return false
    }

    private fun parseIpv4DottedQuad(ipStr: String): IntArray? {
        val parts = ipStr.split(".")
        if (parts.size != 4) return null
        val bytes = IntArray(4)
        for (i in 0 until 4) {
            val intVal = parts[i].toIntOrNull() ?: return null
            if (intVal !in 0..255) return null
            bytes[i] = intVal
        }
        return bytes
    }

    fun isValidHttpUrl(url: String?, allowLocalhost: Boolean = false): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return false
        }
        return try {
            val uri = URI(trimmed)
            val host = uri.host ?: return false
            if (host.isBlank()) return false
            if (uri.userInfo != null) return false

            val hostLower = host.lowercase()

            if (allowLocalhost) {
                if (hostLower == "localhost" || hostLower.endsWith(".localhost") || hostLower == "127.0.0.1" || hostLower == "::1" || hostLower == "[::1]") {
                    return true
                }
            }

            // Reject localhost
            if (hostLower == "localhost" || hostLower.endsWith(".localhost")) {
                return false
            }

            // Reject numeric-looking IPv4 if malformed or in restricted ranges
            if (NUMERIC_LOOKING_IPV4.matches(hostLower)) {
                val bytes = parseIpv4DottedQuad(hostLower) ?: return false
                if (allowLocalhost && (bytes[0] == 127 || (bytes[0] == 192 && bytes[1] == 168) || (bytes[0] == 10) || (bytes[0] == 172 && bytes[1] in 16..31))) {
                    return true
                }
                if (isRestrictedIpv4(bytes[0], bytes[1], bytes[2], bytes[3])) {
                    return false
                }
            }

            // Reject IPv6 literals (loopback, unspecified, link-local, unique local, IPv4-mapped)
            if (hostLower.startsWith("[") && hostLower.endsWith("]")) {
                val ip6 = hostLower.removeSurrounding("[", "]")

                // Handle IPv4-mapped IPv6 representations:
                // 1. Dotted format: ::ffff:a.b.c.d or 0:0:0:0:0:ffff:a.b.c.d
                if (ip6.startsWith("::ffff:") || ip6.startsWith("0:0:0:0:0:ffff:")) {
                    val ipv4Part = ip6.substringAfterLast(":")
                    if (ipv4Part.contains(".")) {
                        val bytes = parseIpv4DottedQuad(ipv4Part) ?: return false
                        if (isRestrictedIpv4(bytes[0], bytes[1], bytes[2], bytes[3])) {
                            return false
                        }
                    }
                }

                // 2. Pure or hex-mapped parsing via java.net.InetAddress literal byte check (no DNS lookup on literals)
                val isStandardLiteral = try {
                    val inet = java.net.InetAddress.getByName(ip6)
                    val rawBytes = inet.address
                    if (rawBytes.size == 4) {
                        // Java parsed mapped IPv4 as Inet4Address
                        val b0 = rawBytes[0].toInt() and 0xFF
                        val b1 = rawBytes[1].toInt() and 0xFF
                        val b2 = rawBytes[2].toInt() and 0xFF
                        val b3 = rawBytes[3].toInt() and 0xFF
                        if (isRestrictedIpv4(b0, b1, b2, b3)) {
                            return false
                        }
                    } else if (rawBytes.size == 16) {
                        // Check if IPv4-compatible (12 bytes 0x00) or IPv4-mapped (10 bytes 0x00, 2 bytes 0xFF)
                        val isCompatible = rawBytes.slice(0..11).all { it == 0.toByte() }
                        val isMapped = rawBytes.slice(0..9).all { it == 0.toByte() } &&
                                rawBytes[10] == 0xFF.toByte() && rawBytes[11] == 0xFF.toByte()

                        if (isCompatible || isMapped) {
                            val b0 = rawBytes[12].toInt() and 0xFF
                            val b1 = rawBytes[13].toInt() and 0xFF
                            val b2 = rawBytes[14].toInt() and 0xFF
                            val b3 = rawBytes[15].toInt() and 0xFF
                            if (isRestrictedIpv4(b0, b1, b2, b3)) {
                                return false
                            }
                        }
                    }
                    if (inet.isLoopbackAddress || inet.isAnyLocalAddress || inet.isLinkLocalAddress || inet.isSiteLocalAddress) {
                        return false
                    }
                    true
                } catch (_: Throwable) {
                    // If parsing as IPv6 literal threw, it's malformed
                    false
                }

                if (!isStandardLiteral) {
                    return false
                }

                val isLoopback = ip6 == "::1" || (ip6.endsWith(":1") && ip6.replace("0", "").replace(":", "") == "1")
                val isUnspecified = ip6 == "::" || ip6.replace("0", "").replace(":", "").isEmpty()
                val isLinkLocal = ip6.startsWith("fe8") || ip6.startsWith("fe9") || ip6.startsWith("fea") || ip6.startsWith("feb")
                val isUniqueLocal = ip6.startsWith("fc") || ip6.startsWith("fd")
                if (isLoopback || isUnspecified || isLinkLocal || isUniqueLocal) {
                    return false
                }
            }

            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isCanonicalYouTubeHost(host: String): Boolean {
        val lower = host.lowercase()
        return lower == "youtube.com" || lower.endsWith(".youtube.com") ||
                lower == "youtube-nocookie.com" || lower.endsWith(".youtube-nocookie.com") ||
                lower == "youtu.be" || lower.endsWith(".youtu.be")
    }

    private fun decodeQueryValue(value: String): String {
        return try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: Throwable) {
            value
        }
    }

    fun extractNativeVideoId(urlOrId: String?): String? {
        if (urlOrId.isNullOrBlank()) return null
        val trimmed = urlOrId.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return if (VIDEO_ID_REGEX.matches(trimmed)) trimmed else null
        }
        val candidate = try {
            val uri = URI(trimmed)
            val host = uri.host ?: return null
            if (!isCanonicalYouTubeHost(host)) return null
            val hostLower = host.lowercase()
            val path = uri.path ?: ""
            val query = uri.query

            when {
                hostLower == "youtu.be" || hostLower.endsWith(".youtu.be") -> {
                    path.removePrefix("/").substringBefore("/").substringBefore("?").ifBlank { null }
                }
                hostLower == "youtube.com" || hostLower.endsWith(".youtube.com") ||
                hostLower == "youtube-nocookie.com" || hostLower.endsWith(".youtube-nocookie.com") -> {
                    when {
                        path.startsWith("/watch") && query != null -> {
                            var foundId: String? = null
                            for (param in query.split("&")) {
                                val parts = param.split("=", limit = 2)
                                if (parts.size == 2 && parts[0] == "v") {
                                    foundId = decodeQueryValue(parts[1]).substringBefore("&").ifBlank { null }
                                    break
                                }
                            }
                            foundId
                        }
                        path.startsWith("/shorts/") -> {
                            path.removePrefix("/shorts/").substringBefore("/").substringBefore("?").ifBlank { null }
                        }
                        path.startsWith("/embed/") -> {
                            path.removePrefix("/embed/").substringBefore("/").substringBefore("?").ifBlank { null }
                        }
                        path.startsWith("/v/") -> {
                            path.removePrefix("/v/").substringBefore("/").substringBefore("?").ifBlank { null }
                        }
                        else -> null
                    }
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }

        return candidate?.takeIf { VIDEO_ID_REGEX.matches(it) }
    }

    fun extractNativeChannelId(urlOrId: String?): String? {
        if (urlOrId.isNullOrBlank()) return null
        val trimmed = urlOrId.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return if (CHANNEL_ID_REGEX.matches(trimmed)) trimmed else null
        }
        val candidate = try {
            val uri = URI(trimmed)
            val host = uri.host ?: return null
            if (!isCanonicalYouTubeHost(host)) return null
            val path = uri.path ?: ""

            when {
                path.startsWith("/channel/") -> {
                    path.removePrefix("/channel/").substringBefore("/").substringBefore("?").ifBlank { null }
                }
                path.startsWith("/c/") -> {
                    path.removePrefix("/c/").substringBefore("/").substringBefore("?").ifBlank { null }
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }

        return candidate?.takeIf { CHANNEL_ID_REGEX.matches(it) }
    }

    fun extractNativePlaylistId(urlOrId: String?): String? {
        if (urlOrId.isNullOrBlank()) return null
        val trimmed = urlOrId.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return if (PLAYLIST_ID_REGEX.matches(trimmed)) trimmed else null
        }
        val candidate = try {
            val uri = URI(trimmed)
            val host = uri.host ?: return null
            if (!isCanonicalYouTubeHost(host)) return null
            val query = uri.query

            if (query != null) {
                var foundList: String? = null
                for (param in query.split("&")) {
                    val parts = param.split("=", limit = 2)
                    if (parts.size == 2 && parts[0] == "list") {
                        foundList = decodeQueryValue(parts[1]).substringBefore("&").ifBlank { null }
                        break
                    }
                }
                foundList
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }

        return candidate?.takeIf { PLAYLIST_ID_REGEX.matches(it) }
    }

    fun selectPreferredImage(images: List<Image>?): String? {
        if (images.isNullOrEmpty()) return null
        val validImages = images.filter { isValidHttpUrl(it.url) }
        if (validImages.isEmpty()) return null
        return validImages.maxByOrNull { (it.width.coerceAtLeast(0)) * (it.height.coerceAtLeast(0)) }?.url
            ?: validImages.firstOrNull()?.url
    }

    fun mapDateWrapperToTimestamp(dateWrapper: DateWrapper?): Long? {
        if (dateWrapper == null) return null
        return try {
            dateWrapper.instant?.toEpochMilli()
                ?: dateWrapper.offsetDateTime()?.toInstant()?.toEpochMilli()
        } catch (_: Throwable) {
            null
        }
    }

    fun mapStreamInfoItemToSummary(item: StreamInfoItem, fallbackServiceId: Int): DomainVideoSummary? {
        val serviceId = if (item.serviceId >= 0) item.serviceId else fallbackServiceId
        val nativeId = extractNativeVideoId(item.url) ?: return null
        val channelId = extractNativeChannelId(item.uploaderUrl)
        val channelKey = if (!channelId.isNullOrBlank()) ContentKey(serviceId, channelId) else null
        val duration = if (item.duration >= 0) item.duration else null
        val viewCount = if (item.viewCount >= 0) item.viewCount else null
        val isLive = item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM
        val isShort = item.isShortFormContent

        return DomainVideoSummary(
            key = ContentKey(serviceId, nativeId),
            title = item.name ?: "",
            canonicalUrl = item.url ?: "",
            channelKey = channelKey,
            channelName = item.uploaderName,
            channelAvatarUrl = selectPreferredImage(item.uploaderAvatars),
            thumbnailUrl = selectPreferredImage(item.thumbnails),
            durationSeconds = duration,
            viewCount = viewCount,
            publishedTimestamp = mapDateWrapperToTimestamp(item.uploadDate),
            isLive = isLive,
            isShort = isShort
        )
    }

    fun mapChannelInfoItemToChannel(item: ChannelInfoItem, fallbackServiceId: Int): DomainChannel? {
        val serviceId = if (item.serviceId >= 0) item.serviceId else fallbackServiceId
        val channelId = extractNativeChannelId(item.url) ?: return null
        val subCountText = if (item.subscriberCount >= 0) "${item.subscriberCount} subscribers" else null

        return DomainChannel(
            key = ContentKey(serviceId, channelId),
            name = item.name ?: "",
            canonicalUrl = item.url ?: "",
            avatarUrl = selectPreferredImage(item.thumbnails),
            bannerUrl = null,
            subscriberCountText = subCountText,
            description = item.description
        )
    }

    fun mapPlaylistInfoItemToSummary(item: PlaylistInfoItem, fallbackServiceId: Int): DomainPlaylistSummary? {
        val serviceId = if (item.serviceId >= 0) item.serviceId else fallbackServiceId
        val playlistId = extractNativePlaylistId(item.url) ?: return null
        val channelId = extractNativeChannelId(item.uploaderUrl)
        val channelKey = if (!channelId.isNullOrBlank()) ContentKey(serviceId, channelId) else null
        val streamCount = if (item.streamCount >= 0) item.streamCount else null

        return DomainPlaylistSummary(
            key = ContentKey(serviceId, playlistId),
            title = item.name ?: "",
            canonicalUrl = item.url ?: "",
            channelKey = channelKey,
            channelName = item.uploaderName,
            thumbnailUrl = selectPreferredImage(item.thumbnails),
            videoCount = streamCount
        )
    }

    fun mapInfoItemToSearchResult(item: InfoItem, fallbackServiceId: Int): SearchResultItem? {
        return when (item) {
            is StreamInfoItem -> mapStreamInfoItemToSummary(item, fallbackServiceId)?.let { SearchResultItem.VideoItem(it) }
            is ChannelInfoItem -> mapChannelInfoItemToChannel(item, fallbackServiceId)?.let { SearchResultItem.ChannelItem(it) }
            is PlaylistInfoItem -> mapPlaylistInfoItemToSummary(item, fallbackServiceId)?.let { SearchResultItem.PlaylistItem(it) }
            else -> null
        }
    }

    fun mapSearchInfo(searchInfo: SearchInfo, fallbackServiceId: Int): DomainSearchPage {
        val serviceId = if (searchInfo.serviceId >= 0) searchInfo.serviceId else fallbackServiceId
        val items = searchInfo.relatedItems.orEmpty().mapNotNull { mapInfoItemToSearchResult(it, serviceId) }
        val nextToken = mapPageToPageToken(searchInfo.nextPage)
        return DomainSearchPage(items = items, nextPageToken = nextToken)
    }

    fun mapVideoDetails(streamInfo: StreamInfo, fallbackServiceId: Int): DomainVideoDetails? {
        val serviceId = if (streamInfo.serviceId >= 0) streamInfo.serviceId else fallbackServiceId
        val nativeId = streamInfo.id?.let { if (VIDEO_ID_REGEX.matches(it)) it else null } ?: extractNativeVideoId(streamInfo.url) ?: return null
        val channelId = streamInfo.uploaderUrl?.let { extractNativeChannelId(it) }
        val channelKey = if (!channelId.isNullOrBlank()) ContentKey(serviceId, channelId) else null
        val subCount = if (streamInfo.uploaderSubscriberCount >= 0) "${streamInfo.uploaderSubscriberCount} subscribers" else null
        val duration = if (streamInfo.duration >= 0) streamInfo.duration else null
        val viewCount = if (streamInfo.viewCount >= 0) streamInfo.viewCount else null
        val likeCount = if (streamInfo.likeCount >= 0) streamInfo.likeCount else null
        val isLive = streamInfo.streamType == StreamType.LIVE_STREAM || streamInfo.streamType == StreamType.AUDIO_LIVE_STREAM
        val isShort = streamInfo.isShortFormContent

        return DomainVideoDetails(
            key = ContentKey(serviceId, nativeId),
            title = streamInfo.name ?: "",
            canonicalUrl = streamInfo.url ?: "",
            description = streamInfo.description?.content,
            channelKey = channelKey,
            channelName = streamInfo.uploaderName,
            channelAvatarUrl = selectPreferredImage(streamInfo.uploaderAvatars),
            subscriberCountText = subCount,
            thumbnailUrl = selectPreferredImage(streamInfo.thumbnails),
            durationSeconds = duration,
            viewCount = viewCount,
            likeCount = likeCount,
            publishedTimestamp = mapDateWrapperToTimestamp(streamInfo.uploadDate),
            isLive = isLive,
            isShort = isShort
        )
    }

    fun mapVideoStream(stream: VideoStream): DomainVideoStream? {
        val url = stream.content
        if (!isValidHttpUrl(url)) return null

        val width = if (stream.width > 0) stream.width else null
        val height = if (stream.height > 0) stream.height else null
        val bitrate = if (stream.bitrate > 0) stream.bitrate.toLong() else null

        val mime = stream.format?.mimeType ?: stream.format?.let { "video/${it.name.lowercase()}" }
        val codec = stream.codec

        return DomainVideoStream(
            url = url!!,
            format = stream.format?.name ?: "mp4",
            resolution = stream.getResolution() ?: "${height ?: 0}p",
            width = width,
            height = height,
            bitrate = bitrate,
            isVideoOnly = stream.isVideoOnly(),
            mimeType = mime,
            codec = codec
        )
    }

    fun mapAudioStream(stream: AudioStream): DomainAudioStream? {
        val url = stream.content
        if (!isValidHttpUrl(url)) return null

        val bitrate = if (stream.bitrate > 0) stream.bitrate.toLong() else null
        val avgBitrate = if (stream.averageBitrate > 0) stream.averageBitrate.toLong() else null

        val mime = stream.format?.mimeType ?: stream.format?.let { "audio/${it.name.lowercase()}" }
        val codec = stream.codec

        return DomainAudioStream(
            url = url!!,
            format = stream.format?.name ?: "m4a",
            bitrate = bitrate,
            averageBitrate = avgBitrate,
            language = stream.audioLocale?.language,
            mimeType = mime,
            codec = codec
        )
    }

    fun mapSubtitleStream(stream: SubtitlesStream): DomainSubtitleStream? {
        val url = stream.content
        if (!isValidHttpUrl(url)) return null

        val lang = stream.languageTag ?: stream.displayLanguageName ?: "en"
        val format = stream.extension ?: stream.format?.suffix ?: "vtt"
        val mime = stream.format?.mimeType

        return DomainSubtitleStream(
            url = url!!,
            language = lang,
            format = format,
            isAutoGenerated = stream.isAutoGenerated,
            mimeType = mime
        )
    }

    fun mapStreamInfo(streamInfo: StreamInfo, fallbackServiceId: Int): DomainStreamInfo? {
        val serviceId = if (streamInfo.serviceId >= 0) streamInfo.serviceId else fallbackServiceId
        val nativeId = streamInfo.id?.let { if (VIDEO_ID_REGEX.matches(it)) it else null } ?: extractNativeVideoId(streamInfo.url) ?: return null
        val title = if (!streamInfo.name.isNullOrBlank()) streamInfo.name else ""

        val videoStreams = mutableListOf<DomainVideoStream>()
        streamInfo.videoStreams?.forEach { s -> mapVideoStream(s)?.let { videoStreams.add(it) } }
        streamInfo.videoOnlyStreams?.forEach { s -> mapVideoStream(s)?.let { videoStreams.add(it) } }

        val audioStreams = streamInfo.audioStreams.orEmpty().mapNotNull { mapAudioStream(it) }
        val subtitles = streamInfo.subtitles.orEmpty().mapNotNull { mapSubtitleStream(it) }

        val isLive = streamInfo.streamType == StreamType.LIVE_STREAM || streamInfo.streamType == StreamType.AUDIO_LIVE_STREAM
        val hlsUrl = streamInfo.hlsUrl?.takeIf { isValidHttpUrl(it) }
        val dashUrl = streamInfo.dashMpdUrl?.takeIf { isValidHttpUrl(it) }

        if (videoStreams.isEmpty() && audioStreams.isEmpty() && hlsUrl == null && dashUrl == null) {
            return null
        }

        return DomainStreamInfo(
            key = ContentKey(serviceId, nativeId),
            title = title,
            videoStreams = videoStreams,
            audioStreams = audioStreams,
            subtitles = subtitles,
            hlsManifestUrl = hlsUrl,
            dashManifestUrl = dashUrl,
            isLive = isLive
        )
    }

    fun mapChannelDetails(
        channelInfo: ChannelInfo,
        tabInfo: ChannelTabInfo?,
        fallbackServiceId: Int
    ): DomainChannelDetails? {
        val serviceId = if (channelInfo.serviceId >= 0) channelInfo.serviceId else fallbackServiceId
        val channelId = channelInfo.id?.let { if (CHANNEL_ID_REGEX.matches(it)) it else null } ?: extractNativeChannelId(channelInfo.url) ?: return null
        val subCountText = if (channelInfo.subscriberCount >= 0) "${channelInfo.subscriberCount} subscribers" else null

        val channel = DomainChannel(
            key = ContentKey(serviceId, channelId),
            name = channelInfo.name ?: "",
            canonicalUrl = channelInfo.url ?: "",
            avatarUrl = selectPreferredImage(channelInfo.avatars),
            bannerUrl = selectPreferredImage(channelInfo.banners),
            subscriberCountText = subCountText,
            description = channelInfo.description
        )

        val related = tabInfo?.relatedItems.orEmpty()
        val videos = related
            .filterIsInstance<StreamInfoItem>()
            .filter { !it.isShortFormContent }
            .mapNotNull { mapStreamInfoItemToSummary(it, serviceId) }

        val shorts = related
            .filterIsInstance<StreamInfoItem>()
            .filter { it.isShortFormContent }
            .mapNotNull { mapStreamInfoItemToSummary(it, serviceId) }

        val nextToken = mapPageToPageToken(tabInfo?.nextPage)

        return DomainChannelDetails(
            channel = channel,
            videos = videos,
            shorts = shorts,
            nextPageToken = nextToken
        )
    }

    fun mapCommentsInfo(commentsInfo: CommentsInfo, fallbackServiceId: Int): DomainCommentPage {
        val serviceId = if (commentsInfo.serviceId >= 0) commentsInfo.serviceId else fallbackServiceId
        val comments = commentsInfo.relatedItems.orEmpty().mapNotNull { item ->
            val commentId = item.commentId?.let { if (it.isBlank()) null else it } ?: item.url?.let { if (it.isBlank()) null else it } ?: return@mapNotNull null
            val channelId = extractNativeChannelId(item.uploaderUrl)
            val channelKey = if (!channelId.isNullOrBlank()) ContentKey(serviceId, channelId) else null
            val likeCount = if (item.likeCount >= 0) item.likeCount.toLong() else null
            val replyCount = if (item.replyCount >= 0) item.replyCount.toLong() else null

            DomainComment(
                commentId = commentId,
                authorName = item.uploaderName ?: "",
                authorAvatarUrl = selectPreferredImage(item.uploaderAvatars),
                channelKey = channelKey,
                commentText = item.commentText?.content ?: "",
                publishedTimestamp = mapDateWrapperToTimestamp(item.uploadDate),
                likeCount = likeCount,
                replyCount = replyCount
            )
        }
        val nextToken = mapPageToPageToken(commentsInfo.nextPage)
        return DomainCommentPage(comments = comments, nextPageToken = nextToken)
    }

    fun mapPlaylistDetails(
        playlistInfo: PlaylistInfo,
        fallbackServiceId: Int
    ): DomainPlaylistDetails? {
        val serviceId = if (playlistInfo.serviceId >= 0) playlistInfo.serviceId else fallbackServiceId
        val playlistId = playlistInfo.id?.let { if (PLAYLIST_ID_REGEX.matches(it)) it else null }
            ?: extractNativePlaylistId(playlistInfo.url)
            ?: return null

        val channelId = extractNativeChannelId(playlistInfo.uploaderUrl)
        val channelKey = if (!channelId.isNullOrBlank()) ContentKey(serviceId, channelId) else null
        val streamCount = if (playlistInfo.streamCount >= 0) playlistInfo.streamCount else null

        val videos = playlistInfo.relatedItems.orEmpty()
            .mapNotNull { mapStreamInfoItemToSummary(it, serviceId) }

        val nextToken = mapPageToPageToken(playlistInfo.nextPage)

        return DomainPlaylistDetails(
            key = ContentKey(serviceId, playlistId),
            title = playlistInfo.name ?: "",
            canonicalUrl = playlistInfo.url ?: "",
            channelKey = channelKey,
            channelName = playlistInfo.uploaderName,
            channelAvatarUrl = selectPreferredImage(playlistInfo.uploaderAvatars),
            thumbnailUrl = selectPreferredImage(playlistInfo.thumbnails),
            description = playlistInfo.description?.content,
            videoCount = streamCount,
            videos = videos,
            nextPageToken = nextToken
        )
    }
}

