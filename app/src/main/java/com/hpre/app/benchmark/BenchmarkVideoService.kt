package com.hpre.app.benchmark

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.Channel
import com.hpre.app.model.ChannelDetails
import com.hpre.app.model.CommentPage
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.PlaylistDetails
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchPage
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoStream
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.VideoService

internal class BenchmarkVideoService : VideoService {
    override val serviceId: Int = 0
    override val serviceName: String = "Benchmark"
    override val supportsShorts: Boolean = false
    override val supportsComments: Boolean = false
    override val supportsSearchSuggestions: Boolean = true

    private val videos = (1..40).map { index ->
        val key = ContentKey(serviceId, "benchmark_video_$index")
        VideoSummary(
            key = key,
            title = "Benchmark video $index",
            canonicalUrl = "https://hpre.test/watch?v=${key.nativeId}",
            channelKey = channelKey,
            channelName = "Benchmark channel",
            channelAvatarUrl = null,
            thumbnailUrl = null,
            durationSeconds = 120L + index,
            viewCount = 1_000L + index,
            publishedTimestamp = 1_700_000_000L + index,
            isLive = false,
            isShort = false
        )
    }

    override suspend fun search(
        query: String,
        filter: SearchFilter,
        pageToken: PageToken?
    ): AppResult<SearchPage> = AppResult.Success(
        SearchPage(videos.map { SearchResultItem.VideoItem(it) })
    )

    override suspend fun suggestions(query: String): AppResult<List<String>> =
        AppResult.Success(listOf("benchmark", "benchmark video", "hpre"))

    override suspend fun video(key: ContentKey): AppResult<VideoDetails> = AppResult.Success(
        VideoDetails(
            key = key,
            title = "Benchmark video ${key.nativeId.removePrefix("benchmark_video_")}",
            canonicalUrl = "https://hpre.test/watch?v=${key.nativeId}",
            description = "Deterministic benchmark video.",
            channelKey = channelKey,
            channelName = "Benchmark channel",
            channelAvatarUrl = null,
            subscriberCountText = "1K subscribers",
            thumbnailUrl = null,
            durationSeconds = 180L,
            viewCount = 1_000L,
            likeCount = 100L,
            publishedTimestamp = 1_700_000_000L
        )
    )

    override suspend fun streamInfo(key: ContentKey): AppResult<StreamInfo> = AppResult.Success(
        StreamInfo(
            key = key,
            title = "Benchmark video ${key.nativeId}",
            videoStreams = listOf(
                VideoStream(
                    url = "https://hpre.test/video.mp4",
                    format = "mp4",
                    resolution = "720p",
                    width = 1280,
                    height = 720,
                    bitrate = null,
                    mimeType = "video/mp4"
                )
            )
        )
    )

    override suspend fun channel(key: ContentKey): AppResult<ChannelDetails> = AppResult.Success(
        ChannelDetails(channel = channel, videos = videos)
    )

    override suspend fun related(key: ContentKey): AppResult<List<VideoSummary>> =
        AppResult.Success(videos.take(12))

    override suspend fun playlist(key: ContentKey): AppResult<PlaylistDetails> = AppResult.Success(
        PlaylistDetails(
            key = key,
            title = "Benchmark playlist",
            canonicalUrl = "https://hpre.test/playlist?list=${key.nativeId}",
            channelKey = channelKey,
            channelName = channel.name,
            channelAvatarUrl = null,
            thumbnailUrl = null,
            description = "Deterministic benchmark playlist.",
            videoCount = videos.size.toLong(),
            videos = videos
        )
    )

    override suspend fun comments(key: ContentKey, pageToken: PageToken?): AppResult<CommentPage> =
        AppResult.Success(CommentPage(comments = emptyList()))

    override suspend fun trending(): AppResult<List<VideoSummary>> = AppResult.Success(videos)

    private companion object {
        val channelKey = ContentKey(0, "benchmark_channel")
        val channel = Channel(
            key = channelKey,
            name = "Benchmark channel",
            canonicalUrl = "https://hpre.test/channel/benchmark_channel",
            avatarUrl = null,
            bannerUrl = null,
            subscriberCountText = "1K subscribers",
            description = "Deterministic benchmark channel."
        )
    }
}
