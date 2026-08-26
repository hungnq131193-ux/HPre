package com.flowtube.app.testing

import com.flowtube.app.core.error.AppResult
import com.flowtube.app.model.ChannelDetails
import com.flowtube.app.model.CommentPage
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.PageToken
import com.flowtube.app.model.PlaylistDetails
import com.flowtube.app.model.SearchFilter
import com.flowtube.app.model.SearchPage
import com.flowtube.app.model.StreamInfo
import com.flowtube.app.model.VideoDetails
import com.flowtube.app.model.VideoSummary
import com.flowtube.app.repository.VideoService

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
    var trendingResponse: AppResult<List<VideoSummary>> = AppResult.Success(emptyList()),
    var streamResponses: Map<String, StreamInfo> = emptyMap()
) : VideoService {

    var searchCallCount = 0
        private set
    var suggestionsCallCount = 0
        private set
    var trendingCallCount = 0
        private set
    var streamInfoCallCount = 0
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
        throw NotImplementedError("video() not mocked")
    }

    override suspend fun streamInfo(key: ContentKey): AppResult<StreamInfo> {
        streamInfoCallCount++
        streamInfoHandler?.let { return it(key) }
        val stream = streamResponses[key.nativeId]
        if (stream != null) {
            return AppResult.Success(stream)
        }
        throw NotImplementedError("streamInfo() not mocked")
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
