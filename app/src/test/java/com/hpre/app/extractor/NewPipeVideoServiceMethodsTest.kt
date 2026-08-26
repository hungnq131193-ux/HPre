package com.hpre.app.extractor

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.Channel
import com.hpre.app.model.ChannelDetails
import com.hpre.app.model.Comment
import com.hpre.app.model.CommentPage
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.PlaylistDetails
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchPage
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewPipeVideoServiceMethodsTest {

    @Test
    fun suggestions_when_unsupported_or_error_returns_failure() = runBlocking {
        ExtractorBootstrap.init(OkHttpDownloader())
        val errorOps = object : ExtractorOperations by DefaultExtractorOperations() {
            override fun suggestions(query: String): List<String> {
                throw ExtractorHttpException(500, ExtractorOperationContext.EXTRACTION_METADATA)
            }
        }

        val service = NewPipeVideoService(
            ioDispatcher = ExtractorDispatcher.IO,
            operations = errorOps
        )

        val result = service.suggestions("query")
        assertTrue(result is AppResult.Failure)
        assertEquals(AppError.NetworkError, (result as AppResult.Failure).error)
    }

    @Test
    fun related_returns_failure_on_http_error() = runBlocking {
        ExtractorBootstrap.init(OkHttpDownloader())
        val errorOps = object : ExtractorOperations by DefaultExtractorOperations() {
            override fun related(key: ContentKey): List<VideoSummary> {
                throw ExtractorHttpException(404, ExtractorOperationContext.EXTRACTION_METADATA)
            }
        }

        val service = NewPipeVideoService(
            ioDispatcher = ExtractorDispatcher.IO,
            operations = errorOps
        )

        val result = service.related(ContentKey(0, "dQw4w9WgXcQ"))
        assertTrue(result is AppResult.Failure)
        assertEquals(AppError.ContentUnavailable, (result as AppResult.Failure).error)
    }

    @Test
    fun trending_returns_failure_on_http_error() = runBlocking {
        ExtractorBootstrap.init(OkHttpDownloader())
        val errorOps = object : ExtractorOperations by DefaultExtractorOperations() {
            override fun trending(): List<VideoSummary> {
                throw ExtractorHttpException(503, ExtractorOperationContext.EXTRACTION_METADATA)
            }
        }

        val service = NewPipeVideoService(
            ioDispatcher = ExtractorDispatcher.IO,
            operations = errorOps
        )

        val result = service.trending()
        assertTrue(result is AppResult.Failure)
        assertEquals(AppError.NetworkError, (result as AppResult.Failure).error)
    }

    @Test
    fun playlist_success_and_error_contracts_are_honored() = runBlocking {
        ExtractorBootstrap.init(OkHttpDownloader())
        val testOps = object : ExtractorOperations by DefaultExtractorOperations() {
            override fun playlist(key: ContentKey): PlaylistDetails {
                if (key.nativeId == "PL_ERROR") {
                    throw ExtractorHttpException(404, ExtractorOperationContext.EXTRACTION_METADATA)
                }
                return PlaylistDetails(
                    key = key,
                    title = "Test Playlist",
                    canonicalUrl = "https://youtube.com/playlist?list=${key.nativeId}",
                    channelKey = ContentKey(key.serviceId, "UCuCKox3vgM_q8p1Ufx9kGqg"),
                    channelName = "Playlist Channel",
                    channelAvatarUrl = null,
                    thumbnailUrl = null,
                    description = "Playlist description",
                    videoCount = 1,
                    videos = listOf(
                        VideoSummary(
                            key = ContentKey(key.serviceId, "dQw4w9WgXcQ"),
                            title = "Rick Astley",
                            canonicalUrl = "https://youtube.com/watch?v=dQw4w9WgXcQ",
                            channelKey = null,
                            channelName = null,
                            channelAvatarUrl = null,
                            thumbnailUrl = null,
                            durationSeconds = 212,
                            viewCount = 1000000,
                            publishedTimestamp = 1256428800000L,
                            isLive = false,
                            isShort = false
                        )
                    ),
                    nextPageToken = PageToken.Id("next_pl_page")
                )
            }
        }

        val service = NewPipeVideoService(
            ioDispatcher = ExtractorDispatcher.IO,
            operations = testOps
        )

        // Success path
        val successRes = service.playlist(ContentKey(0, "PL_VALID_12345"))
        assertTrue(successRes is AppResult.Success)
        val details = (successRes as AppResult.Success).value
        assertEquals("Test Playlist", details.title)
        assertEquals(1, details.videos.size)
        assertEquals(PageToken.Id("next_pl_page"), details.nextPageToken)

        // Error path
        val errorRes = service.playlist(ContentKey(0, "PL_ERROR"))
        assertTrue(errorRes is AppResult.Failure)
        assertEquals(AppError.ContentUnavailable, (errorRes as AppResult.Failure).error)
    }

    @Test
    fun pagination_tokens_are_passed_and_mapped_without_mutating_page_one() = runBlocking {
        ExtractorBootstrap.init(OkHttpDownloader())
        var recordedSearchPageToken: PageToken? = null
        var recordedCommentPageToken: PageToken? = null

        val testOps = object : ExtractorOperations by DefaultExtractorOperations() {
            override fun search(query: String, filter: SearchFilter, pageToken: PageToken?): SearchPage {
                recordedSearchPageToken = pageToken
                return SearchPage(
                    items = listOf(
                        SearchResultItem.VideoItem(
                            VideoSummary(
                                key = ContentKey(0, "dQw4w9WgXcQ"),
                                title = "Result",
                                canonicalUrl = "https://example.com",
                                channelKey = null,
                                channelName = null,
                                channelAvatarUrl = null,
                                thumbnailUrl = null,
                                durationSeconds = null,
                                viewCount = null,
                                publishedTimestamp = null,
                                isLive = false,
                                isShort = false
                            )
                        )
                    ),
                    nextPageToken = if (pageToken == null) PageToken.Id("next_token_page_1") else PageToken.Id("next_token_page_2")
                )
            }

            override fun comments(key: ContentKey, pageToken: PageToken?): CommentPage {
                recordedCommentPageToken = pageToken
                return CommentPage(
                    comments = listOf(
                        Comment(
                            commentId = "c1",
                            authorName = "Author",
                            authorAvatarUrl = null,
                            channelKey = null,
                            commentText = "Text",
                            publishedTimestamp = null,
                            likeCount = null,
                            replyCount = null
                        )
                    ),
                    nextPageToken = if (pageToken == null) PageToken.Id("comment_token_page_1") else PageToken.Id("comment_token_page_2")
                )
            }
        }

        val service = NewPipeVideoService(
            ioDispatcher = ExtractorDispatcher.IO,
            operations = testOps
        )

        // 1. First search call
        val search1 = service.search("query", SearchFilter.ALL, null)
        assertTrue(search1 is AppResult.Success)
        assertEquals(null, recordedSearchPageToken)
        assertEquals(PageToken.Id("next_token_page_1"), (search1 as AppResult.Success).value.nextPageToken)

        // 2. Search with pageToken
        val search2 = service.search("query", SearchFilter.ALL, pageToken = PageToken.Id("token_page_2"))
        assertTrue(search2 is AppResult.Success)
        assertEquals(PageToken.Id("token_page_2"), recordedSearchPageToken)
        assertEquals(PageToken.Id("next_token_page_2"), (search2 as AppResult.Success).value.nextPageToken)

        // 3. Comments with pageToken
        val comment1 = service.comments(ContentKey(0, "dQw4w9WgXcQ"), null)
        assertTrue(comment1 is AppResult.Success)
        assertEquals(null, recordedCommentPageToken)
        assertEquals(PageToken.Id("comment_token_page_1"), (comment1 as AppResult.Success).value.nextPageToken)

        val comment2 = service.comments(ContentKey(0, "dQw4w9WgXcQ"), pageToken = PageToken.Id("token_comments_2"))
        assertTrue(comment2 is AppResult.Success)
        assertEquals(PageToken.Id("token_comments_2"), recordedCommentPageToken)
        assertEquals(PageToken.Id("comment_token_page_2"), (comment2 as AppResult.Success).value.nextPageToken)
    }
}

