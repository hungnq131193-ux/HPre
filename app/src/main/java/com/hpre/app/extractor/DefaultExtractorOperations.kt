package com.hpre.app.extractor

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
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.kiosk.KioskList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo as ExtractorStreamInfo
import org.schabi.newpipe.extractor.stream.StreamExtractor

internal class DefaultExtractorOperations(
    private val streamingService: StreamingService = ServiceList.YouTube,
    private val gateway: SearchCommentsGateway = ProductionSearchCommentsGateway,
    private val videoBundleLoader: ((StreamingService, ContentKey, Int) -> ExtractedVideoBundle)? = null,
    private val streamExtractorFactory: ((StreamingService, ContentKey) -> StreamExtractor)? = null
) : ExtractorOperations, StagedVideoExtractorOperations {

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

    override fun videoBundle(key: ContentKey): ExtractedVideoBundle {
        return videoBundle(key) { }
    }

    override fun videoBundle(
        key: ContentKey,
        onStreamReady: (StreamInfo) -> Unit
    ): ExtractedVideoBundle {
        val loaded = videoBundleLoader?.invoke(streamingService, key, serviceId)
        if (loaded != null) {
            onStreamReady(loaded.streamInfo)
            return loaded
        }
        return loadVideoBundle(streamingService, key, serviceId, onStreamReady)
    }

    override fun refreshStreamInfo(key: ContentKey): StreamInfo {
        val linkHandler = streamingService.streamLHFactory.fromId(key.nativeId)
        val streamExtractor = streamingService.getStreamExtractor(linkHandler)
        streamExtractor.fetchPage()
        val streams = NewPipeMappers.mapStreamExtractor(streamExtractor, key, serviceId)
            ?: throw ContentNotSupportedException("No usable playback streams or manifests found")
        if (streams.key != key) {
            throw ExtractionException("Returned video key does not match requested key $key")
        }
        return streams
    }

    private fun loadVideoBundle(
        service: StreamingService,
        key: ContentKey,
        serviceId: Int,
        onStreamReady: (StreamInfo) -> Unit
    ): ExtractedVideoBundle {
        val linkHandler = service.streamLHFactory.fromId(key.nativeId)
        val streamExtractor = streamExtractorFactory?.invoke(service, key)
            ?: service.getStreamExtractor(linkHandler)
        streamExtractor.fetchPage()
        val extractedKey = ContentKey(streamExtractor.serviceId, streamExtractor.id)
        if (extractedKey != key) {
            throw ExtractionException("Returned video key does not match requested key $key")
        }
        val streams = NewPipeMappers.mapStreamExtractor(streamExtractor, key, serviceId)
            ?: throw ContentNotSupportedException("No usable playback streams or manifests found")
        if (streams.key != key) {
            throw ExtractionException("Returned video key does not match requested key $key")
        }
        onStreamReady(streams)

        val info = ExtractorStreamInfo.getInfo(streamExtractor)
        val details = NewPipeMappers.mapVideoDetails(info, serviceId)
            ?: throw ExtractionException("Failed to map valid video details")
        if (details.key != key) {
            throw ExtractionException("Returned video key does not match requested key $key")
        }
        val relatedItems = info.relatedItems.orEmpty()
            .filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
        return ExtractedVideoBundle(
            details = details,
            streamInfo = streams,
            related = emptyList(),
            deferredRelatedItems = relatedItems
        )
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

                com.hpre.app.model.Comment(
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
        // Kiosk extractors are built from the list's own locale, not the global default,
        // so the VN region has to be forced here for the trending feed to be Vietnamese.
        ExtractorLocalization.apply(KioskLocalizable(kioskList))
        val defaultKiosk = kioskList.defaultKioskExtractor
        defaultKiosk.fetchPage()
        val infoItems = defaultKiosk.initialPage.items
        return infoItems.filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
            .mapNotNull { NewPipeMappers.mapStreamInfoItemToSummary(it, serviceId) }
    }

    /** Adapts the provider's [KioskList] to the testable localization seam. */
    private class KioskLocalizable(
        private val kioskList: KioskList
    ) : ExtractorLocalization.Localizable {
        override fun forceLocalization(localization: Localization) {
            kioskList.forceLocalization(localization)
        }

        override fun forceContentCountry(contentCountry: ContentCountry) {
            kioskList.forceContentCountry(contentCountry)
        }
    }
}
