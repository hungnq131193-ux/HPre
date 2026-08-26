package com.hpre.app.testing

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ChannelDetails
import com.hpre.app.model.CommentPage
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.PlaylistDetails
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchPage
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.VideoService

class FakeVideoService(
    override val serviceId: Int = 0,
    override val serviceName: String = "FakeService",
    override val supportsShorts: Boolean = false,
    override val supportsComments: Boolean = false,
    override val supportsSearchSuggestions: Boolean = true,
    var searchHandler: (suspend (query: String, filter: SearchFilter, pageToken: PageToken?) -> AppResult<SearchPage>)? = null,
    var suggestionsHandler: (suspend (query: String) -> AppResult<List<String>>)? = null,
    var videoHandler: (suspend (key: ContentKey) -> AppResult<VideoDetails>)? = null,
    var streamInfoHandler: (suspend (key: ContentKey) -> AppResult<StreamInfo>)? = null,
    var channelHandler: (suspend (key: ContentKey) -> AppResult<ChannelDetails>)? = null,
    var relatedHandler: (suspend (key: ContentKey) -> AppResult<List<VideoSummary>>)? = null,
    var playlistHandler: (suspend (key: ContentKey) -> AppResult<PlaylistDetails>)? = null,
    var commentsHandler: (suspend (key: ContentKey, pageToken: PageToken?) -> AppResult<CommentPage>)? = null,
    var trendingHandler: (suspend () -> AppResult<List<VideoSummary>>)? = null,
    var searchResponses: Map<String, SearchPage> = emptyMap(),
    var suggestionsResponses: Map<String, List<String>> = emptyMap(),
    var trendingResponse: AppResult<List<VideoSummary>> = AppResult.Success(emptyList())
) : VideoService {

    var searchCallCount = 0
        private set
    var suggestionsCallCount = 0
        private set
    var trendingCallCount = 0
        private set

    override suspend fun search(query: String, filter: SearchFilter, pageToken: PageToken?): AppResult<SearchPage> {
        searchCallCount++
        searchHandler?.let { return it(query, filter, pageToken) }
        val page = searchResponses[query]
        return if (page != null) {
            AppResult.Success(page)
        } else {
            AppResult.Success(SearchPage(items = emptyList(), nextPageToken = null))
        }
    }

    override suspend fun suggestions(query: String): AppResult<List<String>> {
        suggestionsCallCount++
        suggestionsHandler?.let { return it(query) }
        val list = suggestionsResponses[query] ?: emptyList()
        return AppResult.Success(list)
    }

    override suspend fun video(key: ContentKey): AppResult<VideoDetails> {
        videoHandler?.let { return it(key) }
        return AppResult.Success(
            VideoDetails(
                key = key,
                title = "Mock Video ${key.nativeId}",
                canonicalUrl = "https://hpre.test/watch?v=${key.nativeId}",
                description = "Mock description",
                channelKey = ContentKey(key.serviceId, "mock_channel"),
                channelName = "Mock Channel",
                channelAvatarUrl = null,
                subscriberCountText = "10K",
                thumbnailUrl = null,
                durationSeconds = 120,
                viewCount = 100,
                likeCount = 10,
                publishedTimestamp = 1000L
            )
        )
    }

    override suspend fun streamInfo(key: ContentKey): AppResult<StreamInfo> {
        streamInfoHandler?.let { return it(key) }
        return AppResult.Success(
            StreamInfo(
                key = key,
                title = "Mock Stream ${key.nativeId}",
                hlsManifestUrl = "https://hpre.test/manifest.m3u8"
            )
        )
    }

    override suspend fun channel(key: ContentKey): AppResult<ChannelDetails> {
        channelHandler?.let { return it(key) }
        throw NotImplementedError("channel() not mocked")
    }

    override suspend fun related(key: ContentKey): AppResult<List<VideoSummary>> {
        relatedHandler?.let { return it(key) }
        throw NotImplementedError("related() not mocked")
    }

    override suspend fun playlist(key: ContentKey): AppResult<PlaylistDetails> {
        playlistHandler?.let { return it(key) }
        throw NotImplementedError("playlist() not mocked")
    }

    override suspend fun comments(key: ContentKey, pageToken: PageToken?): AppResult<CommentPage> {
        commentsHandler?.let { return it(key, pageToken) }
        throw NotImplementedError("comments() not mocked")
    }

    override suspend fun trending(): AppResult<List<VideoSummary>> {
        trendingCallCount++
        trendingHandler?.let { return it() }
        return trendingResponse
    }
}
