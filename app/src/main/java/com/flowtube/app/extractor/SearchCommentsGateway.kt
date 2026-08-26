package com.flowtube.app.extractor

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo

internal interface SearchCommentsGateway {
    fun getSearchInfo(service: StreamingService, queryHandler: SearchQueryHandler): SearchInfo
    fun getSearchMoreItems(
        service: StreamingService,
        queryHandler: SearchQueryHandler,
        page: Page
    ): ListExtractor.InfoItemsPage<InfoItem>
    fun getCommentsInfo(service: StreamingService, url: String): CommentsInfo
    fun getCommentsMoreItems(
        service: StreamingService,
        url: String,
        page: Page
    ): ListExtractor.InfoItemsPage<CommentsInfoItem>
}

internal object ProductionSearchCommentsGateway : SearchCommentsGateway {
    override fun getSearchInfo(service: StreamingService, queryHandler: SearchQueryHandler): SearchInfo {
        return SearchInfo.getInfo(service, queryHandler)
    }

    override fun getSearchMoreItems(
        service: StreamingService,
        queryHandler: SearchQueryHandler,
        page: Page
    ): ListExtractor.InfoItemsPage<InfoItem> {
        return SearchInfo.getMoreItems(service, queryHandler, page)
    }

    override fun getCommentsInfo(service: StreamingService, url: String): CommentsInfo {
        return CommentsInfo.getInfo(service, url)
    }

    override fun getCommentsMoreItems(
        service: StreamingService,
        url: String,
        page: Page
    ): ListExtractor.InfoItemsPage<CommentsInfoItem> {
        return CommentsInfo.getMoreItems(service, url, page)
    }
}
