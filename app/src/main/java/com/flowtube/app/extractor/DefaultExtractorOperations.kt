package com.flowtube.app.extractor

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
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo as ExtractorStreamInfo

internal class DefaultExtractorOperations(
    private val streamingService: StreamingService = ServiceList.YouTube,
    private val gateway: SearchCommentsGateway = ProductionSearchCommentsGateway
) : ExtractorOperations {

    override val serviceId: Int
        get() = streamingService.serviceId

    override val serviceName: String
        get() = streamingService.serviceInfo.name

    override val supportsShorts: Boolean
        get() = false

    override val supportsComments: Boolean
        get() = true

    override val supportsSearchSuggestions: Boolean
        get() = streamingService.suggestionExtractor != null

    override fun search(query: String, filter: SearchFilter, pageToken: PageToken?): SearchPage {
        val queryHandlerFactory = streamingService.searchQHFactory
        val contentFilterList = when (filter) {
            SearchFilter.ALL -> emptyList<String>()
            SearchFilter.VIDEOS -> listOf("videos")
            SearchFilter.CHANNELS -> listOf("channels")
            SearchFilter.PLAYLISTS -> listOf("playlists")
        }
        val queryHandler = queryHandlerFactory.fromQuery(query, contentFilterList, "")

        return if (pageToken == null) {
            val searchInfo = gateway.getSearchInfo(streamingService, queryHandler)
            NewPipeMappers.mapSearchInfo(searchInfo, serviceId)
        } else {
            val page = NewPipeMappers.reconstituteNewPipePage(pageToken, queryHandler.url)
                ?: throw ExtractionException("Invalid search page token")
            val infoPage = gateway.getSearchMoreItems(streamingService, queryHandler, page)
            val items = infoPage.items.orEmpty().mapNotNull { NewPipeMappers.mapInfoItemToSearchResult(it, serviceId) }
            val nextToken = NewPipeMappers.mapPageToPageToken(infoPage.nextPage)
            SearchPage(items = items, nextPageToken = nextToken)
        }
    }

    override fun suggestions(query: String): List<String> {
        val suggestionExtractor = streamingService.suggestionExtractor
            ?: throw ExtractionException("Search suggestions not supported")
        return suggestionExtractor.suggestionList(query).orEmpty()
    }

    override fun video(key: ContentKey): VideoDetails {
        val linkHandler = streamingService.streamLHFactory.fromId(key.nativeId)
        val streamExtractor = streamingService.getStreamExtractor(linkHandler)
        streamExtractor.fetchPage()
        val streamInfo = ExtractorStreamInfo.getInfo(streamExtractor)
        val details = NewPipeMappers.mapVideoDetails(streamInfo, serviceId)
            ?: throw ExtractionException("Failed to map valid video details")
        if (details.key != key) {
            throw ExtractionException("Returned video key ${details.key} does not match requested key $key")
        }
        return details
    }

    override fun streamInfo(key: ContentKey): StreamInfo {
        val linkHandler = streamingService.streamLHFactory.fromId(key.nativeId)
        val streamExtractor = streamingService.getStreamExtractor(linkHandler)
        streamExtractor.fetchPage()
        val info = ExtractorStreamInfo.getInfo(streamExtractor)
        val details = NewPipeMappers.mapStreamInfo(info, serviceId)
            ?: throw ContentNotSupportedException("No usable playback streams or manifests found")
        if (details.key != key) {
            throw ExtractionException("Returned stream key ${details.key} does not match requested key $key")
        }
        return details
    }

    override fun channel(key: ContentKey): ChannelDetails {
        val channelLH = streamingService.channelLHFactory.fromId(key.nativeId)
        val channelInfo = ChannelInfo.getInfo(streamingService, channelLH.url)
        val tabLH = channelInfo.tabs.orEmpty().firstOrNull { it.url.contains("videos") }
            ?: channelInfo.tabs.orEmpty().firstOrNull()
        val tabInfo = if (tabLH != null) {
            try {
                ChannelTabInfo.getInfo(streamingService, tabLH)
            } catch (unsupported: ContentNotSupportedException) {
                null
            } catch (extraction: ExtractionException) {
                null
            }
        } else {
            null
        }
        val details = NewPipeMappers.mapChannelDetails(channelInfo, tabInfo, serviceId)
            ?: throw ExtractionException("Failed to map valid channel details")
        if (details.channel.key != key) {
            throw ExtractionException("Returned channel key ${details.channel.key} does not match requested key $key")
        }
        return details
    }

    override fun playlist(key: ContentKey): PlaylistDetails {
        val linkHandler = streamingService.playlistLHFactory.fromId(key.nativeId)
        val playlistInfo = PlaylistInfo.getInfo(streamingService, linkHandler.url)
        val details = NewPipeMappers.mapPlaylistDetails(playlistInfo, serviceId)
            ?: throw ExtractionException("Failed to map valid playlist details")
        if (details.key != key) {
            throw ExtractionException("Returned playlist key ${details.key} does not match requested key $key")
        }
        return details
    }

    override fun related(key: ContentKey): List<VideoSummary> {
        val linkHandler = streamingService.streamLHFactory.fromId(key.nativeId)
        val streamExtractor = streamingService.getStreamExtractor(linkHandler)
        streamExtractor.fetchPage()
        val info = ExtractorStreamInfo.getInfo(streamExtractor)
        return info.relatedItems.orEmpty()
            .filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
            .mapNotNull { NewPipeMappers.mapStreamInfoItemToSummary(it, serviceId) }
    }

    override fun comments(key: ContentKey, pageToken: PageToken?): CommentPage {
        val linkHandler = streamingService.commentsLHFactory.fromId(key.nativeId)
        return if (pageToken == null) {
            val commentsInfo = gateway.getCommentsInfo(streamingService, linkHandler.url)
            NewPipeMappers.mapCommentsInfo(commentsInfo, serviceId)
        } else {
            val page = NewPipeMappers.reconstituteNewPipePage(pageToken, linkHandler.url)
                ?: throw ExtractionException("Invalid comments page token")
            val infoPage = gateway.getCommentsMoreItems(streamingService, linkHandler.url, page)
            val items = infoPage.items.orEmpty().mapNotNull { item ->
                val commentId = item.commentId?.let { if (it.isBlank()) null else it } ?: item.url?.let { if (it.isBlank()) null else it } ?: return@mapNotNull null
                val channelId = NewPipeMappers.extractNativeChannelId(item.uploaderUrl)
                val channelKey = if (!channelId.isNullOrBlank()) ContentKey(serviceId, channelId) else null
                val likeCount = if (item.likeCount >= 0) item.likeCount.toLong() else null
                val replyCount = if (item.replyCount >= 0) item.replyCount.toLong() else null

                com.flowtube.app.model.Comment(
                    commentId = commentId,
                    authorName = item.uploaderName ?: "",
                    authorAvatarUrl = NewPipeMappers.selectPreferredImage(item.uploaderAvatars),
                    channelKey = channelKey,
                    commentText = item.commentText?.content ?: "",
                    publishedTimestamp = NewPipeMappers.mapDateWrapperToTimestamp(item.uploadDate),
                    likeCount = likeCount,
                    replyCount = replyCount
                )
            }
            val nextToken = NewPipeMappers.mapPageToPageToken(infoPage.nextPage)
            CommentPage(comments = items, nextPageToken = nextToken)
        }
    }

    override fun trending(): List<VideoSummary> {
        val kioskList = streamingService.kioskList
        val defaultKiosk = kioskList.defaultKioskExtractor
        defaultKiosk.fetchPage()
        val infoItems = defaultKiosk.initialPage.items
        return infoItems.filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
            .mapNotNull { NewPipeMappers.mapStreamInfoItemToSummary(it, serviceId) }
    }
}
