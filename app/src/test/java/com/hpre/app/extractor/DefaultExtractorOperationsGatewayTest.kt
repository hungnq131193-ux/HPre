package com.hpre.app.extractor

import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class DefaultExtractorOperationsGatewayTest {

    @Test
    fun videoBundle_calls_loader_once_and_returns_all_projections() {
        val key = ContentKey(0, "dQw4w9WgXcQ")
        val details = VideoDetails(
            key = key,
            title = "Bundle details",
            canonicalUrl = "https://youtube.com/watch?v=${key.nativeId}",
            description = null,
            channelKey = null,
            channelName = null,
            channelAvatarUrl = null,
            subscriberCountText = null,
            thumbnailUrl = null,
            durationSeconds = 120,
            viewCount = null,
            likeCount = null,
            publishedTimestamp = null
        )
        val streams = StreamInfo(key = key, title = "Bundle streams")
        val related = listOf(
            VideoSummary(
                key = ContentKey(0, "related"),
                title = "Related",
                canonicalUrl = "https://youtube.com/watch?v=related",
                channelKey = null,
                channelName = null,
                channelAvatarUrl = null,
                thumbnailUrl = null,
                durationSeconds = null,
                viewCount = null,
                publishedTimestamp = null
            )
        )
        val expected = ExtractedVideoBundle(details, streams, related)
        var loaderCalls = 0
        val operations = DefaultExtractorOperations(
            videoBundleLoader = { _, requestedKey, serviceId ->
                loaderCalls++
                assertEquals(key, requestedKey)
                assertEquals(0, serviceId)
                expected
            }
        )

        assertEquals(expected, operations.videoBundle(key))
        assertEquals(1, loaderCalls)
    }

    private class RecordingSearchCommentsGateway : SearchCommentsGateway {
        var searchGetInfoCount = 0
        var searchGetMoreItemsCount = 0
        var recordedSearchPage: Page? = null

        var commentsGetInfoCount = 0
        var commentsGetMoreItemsCount = 0
        var recordedCommentsPage: Page? = null

        override fun getSearchInfo(service: StreamingService, queryHandler: SearchQueryHandler): SearchInfo {
            searchGetInfoCount++
            throw UnsupportedOperationException("Not needed for continuation tests")
        }

        override fun getSearchMoreItems(
            service: StreamingService,
            queryHandler: SearchQueryHandler,
            page: Page
        ): ListExtractor.InfoItemsPage<org.schabi.newpipe.extractor.InfoItem> {
            searchGetMoreItemsCount++
            recordedSearchPage = page
            val item = StreamInfoItem(service.serviceId, "https://youtube.com/watch?v=dQw4w9WgXcQ", "Continuation Result", StreamType.VIDEO_STREAM)
            val nextPage = Page("https://youtube.com/continuation?token=search_distinct_page_3", null as String?)
            return ListExtractor.InfoItemsPage(listOf(item), nextPage, emptyList())
        }

        override fun getCommentsInfo(service: StreamingService, url: String): CommentsInfo {
            commentsGetInfoCount++
            throw UnsupportedOperationException("Not needed for continuation tests")
        }

        override fun getCommentsMoreItems(
            service: StreamingService,
            url: String,
            page: Page
        ): ListExtractor.InfoItemsPage<CommentsInfoItem> {
            commentsGetMoreItemsCount++
            recordedCommentsPage = page
            val commentItem = CommentsInfoItem(service.serviceId, "https://youtube.com/watch?v=dQw4w9WgXcQ", "c_cont_1").apply {
                commentId = "c_cont_1"
                uploaderName = "Commenter"
                uploaderUrl = "https://youtube.com/channel/UCuCKox3vgM_q8p1Ufx9kGqg"
                commentText = org.schabi.newpipe.extractor.stream.Description("Continuation Comment", org.schabi.newpipe.extractor.stream.Description.PLAIN_TEXT)
            }
            val nextPage = Page(null, "comments_distinct_page_3")
            return ListExtractor.InfoItemsPage(listOf(commentItem), nextPage, emptyList())
        }
    }

    @Test
    fun search_with_pageToken_Id_invokes_gateway_getMoreItems_with_reconstituted_page_and_getInfo_zero() {
        val recordingGateway = RecordingSearchCommentsGateway()
        val ops = DefaultExtractorOperations(
            gateway = recordingGateway
        )

        val pageTokenId = PageToken.Id("continuation_search_token_1")
        val searchPage = ops.search("kotlin", SearchFilter.ALL, pageTokenId)

        assertEquals("Continuation getMoreItems must be called exactly once", 1, recordingGateway.searchGetMoreItemsCount)
        assertEquals("Initial getInfo must not be called", 0, recordingGateway.searchGetInfoCount)

        val reconstituted = recordingGateway.recordedSearchPage
        assertNotNull("Reconstituted Page must not be null", reconstituted)
        assertEquals("continuation_search_token_1", reconstituted?.id)
        assertNotNull("Base url must be populated on reconstituted page", reconstituted?.url)

        assertEquals("Distinct next page token goes straight through", PageToken.Url("https://youtube.com/continuation?token=search_distinct_page_3"), searchPage.nextPageToken)
        assertEquals(1, searchPage.items.size)
        val firstItem = searchPage.items[0]
        assertTrue("Item should be VideoItem", firstItem is SearchResultItem.VideoItem)
        val videoSummary = (firstItem as SearchResultItem.VideoItem).summary
        assertEquals(ContentKey(ops.serviceId, "dQw4w9WgXcQ"), videoSummary.key)
        assertEquals("Continuation Result", videoSummary.title)
        assertEquals("https://youtube.com/watch?v=dQw4w9WgXcQ", videoSummary.canonicalUrl)

        assertEquals(PageToken.Id("continuation_search_token_1"), pageTokenId)
        assertEquals("continuation_search_token_1", pageTokenId.id)
    }

    @Test
    fun search_with_pageToken_Url_invokes_gateway_getMoreItems_with_reconstituted_page_and_getInfo_zero() {
        val recordingGateway = RecordingSearchCommentsGateway()
        val ops = DefaultExtractorOperations(
            gateway = recordingGateway
        )

        val pageTokenUrl = PageToken.Url("https://youtube.com/continuation?token=search_token_url_2")
        val searchPage = ops.search("kotlin", SearchFilter.ALL, pageTokenUrl)

        assertEquals(1, recordingGateway.searchGetMoreItemsCount)
        assertEquals(0, recordingGateway.searchGetInfoCount)

        val reconstituted = recordingGateway.recordedSearchPage
        assertNotNull(reconstituted)
        assertEquals("https://youtube.com/continuation?token=search_token_url_2", reconstituted?.url)
        assertNull(reconstituted?.id)

        assertEquals(PageToken.Url("https://youtube.com/continuation?token=search_distinct_page_3"), searchPage.nextPageToken)
        assertEquals(1, searchPage.items.size)
        val firstItem = searchPage.items[0]
        assertTrue("Item should be VideoItem", firstItem is SearchResultItem.VideoItem)
        val videoSummary = (firstItem as SearchResultItem.VideoItem).summary
        assertEquals(ContentKey(ops.serviceId, "dQw4w9WgXcQ"), videoSummary.key)
        assertEquals("Continuation Result", videoSummary.title)
        assertEquals("https://youtube.com/watch?v=dQw4w9WgXcQ", videoSummary.canonicalUrl)

        assertEquals(PageToken.Url("https://youtube.com/continuation?token=search_token_url_2"), pageTokenUrl)
        assertEquals("https://youtube.com/continuation?token=search_token_url_2", pageTokenUrl.url)
    }

    @Test
    fun comments_with_pageToken_Id_invokes_gateway_getMoreItems_with_reconstituted_page_and_getInfo_zero() {
        val recordingGateway = RecordingSearchCommentsGateway()
        val ops = DefaultExtractorOperations(
            gateway = recordingGateway
        )

        val commentsKey = ContentKey(ops.serviceId, "dQw4w9WgXcQ")
        val pageTokenId = PageToken.Id("continuation_comments_token_1")
        val commentPage = ops.comments(commentsKey, pageTokenId)

        assertEquals("Continuation comments getMoreItems must be called exactly once", 1, recordingGateway.commentsGetMoreItemsCount)
        assertEquals("Initial comments getInfo must not be called", 0, recordingGateway.commentsGetInfoCount)

        val reconstituted = recordingGateway.recordedCommentsPage
        assertNotNull(reconstituted)
        assertEquals("continuation_comments_token_1", reconstituted?.id)
        assertNotNull(reconstituted?.url)

        assertEquals(PageToken.Id("comments_distinct_page_3"), commentPage.nextPageToken)
        assertEquals(1, commentPage.comments.size)
        val firstComment = commentPage.comments[0]
        assertEquals("c_cont_1", firstComment.commentId)
        assertEquals("Commenter", firstComment.authorName)
        assertEquals("Continuation Comment", firstComment.commentText)
        assertEquals(ContentKey(ops.serviceId, "UCuCKox3vgM_q8p1Ufx9kGqg"), firstComment.channelKey)

        assertEquals(PageToken.Id("continuation_comments_token_1"), pageTokenId)
        assertEquals("continuation_comments_token_1", pageTokenId.id)
    }

    @Test
    fun comments_with_pageToken_Url_invokes_gateway_getMoreItems_with_reconstituted_page_and_getInfo_zero() {
        val recordingGateway = RecordingSearchCommentsGateway()
        val ops = DefaultExtractorOperations(
            gateway = recordingGateway
        )

        val commentsKey = ContentKey(ops.serviceId, "dQw4w9WgXcQ")
        val pageTokenUrl = PageToken.Url("https://youtube.com/comments_continuation?token=abc")
        val commentPage = ops.comments(commentsKey, pageTokenUrl)

        assertEquals(1, recordingGateway.commentsGetMoreItemsCount)
        assertEquals(0, recordingGateway.commentsGetInfoCount)

        val reconstituted = recordingGateway.recordedCommentsPage
        assertNotNull(reconstituted)
        assertEquals("https://youtube.com/comments_continuation?token=abc", reconstituted?.url)
        assertNull(reconstituted?.id)

        assertEquals(PageToken.Id("comments_distinct_page_3"), commentPage.nextPageToken)
        assertEquals(1, commentPage.comments.size)
        val firstComment = commentPage.comments[0]
        assertEquals("c_cont_1", firstComment.commentId)
        assertEquals("Commenter", firstComment.authorName)
        assertEquals("Continuation Comment", firstComment.commentText)
        assertEquals(ContentKey(ops.serviceId, "UCuCKox3vgM_q8p1Ufx9kGqg"), firstComment.channelKey)

        assertEquals(PageToken.Url("https://youtube.com/comments_continuation?token=abc"), pageTokenUrl)
        assertEquals("https://youtube.com/comments_continuation?token=abc", pageTokenUrl.url)
    }
}
