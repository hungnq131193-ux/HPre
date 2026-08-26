package com.flowtube.app.repository

import com.flowtube.app.core.error.AppResult
import com.flowtube.app.model.Channel
import com.flowtube.app.model.ChannelDetails
import com.flowtube.app.model.Comment
import com.flowtube.app.model.CommentPage
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.PageToken
import com.flowtube.app.model.PlaylistDetails
import com.flowtube.app.model.PlaylistSummary
import com.flowtube.app.model.SearchFilter
import com.flowtube.app.model.SearchPage
import com.flowtube.app.model.SearchResultItem
import com.flowtube.app.model.StreamInfo
import com.flowtube.app.model.VideoDetails
import com.flowtube.app.model.VideoSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoServiceContractTest {

    private val fakeService = object : VideoService {
        override val serviceId: Int = 0
        override val serviceName: String = "FakeService"
        override val supportsShorts: Boolean = false
        override val supportsComments: Boolean = true
        override val supportsSearchSuggestions: Boolean = true

        override suspend fun search(query: String, filter: SearchFilter, pageToken: PageToken?): AppResult<SearchPage> {
            return AppResult.Success(
                SearchPage(
                    items = listOf(
                        SearchResultItem.VideoItem(
                            VideoSummary(
                                key = ContentKey(0, "v1"),
                                title = "Video 1",
                                canonicalUrl = "https://example.com/v1",
                                channelKey = ContentKey(0, "c1"),
                                channelName = "Channel 1",
                                channelAvatarUrl = null,
                                thumbnailUrl = null,
                                durationSeconds = 120,
                                viewCount = 1000,
                                publishedTimestamp = 1600000000L
                            )
                        ),
                        SearchResultItem.ChannelItem(
                            Channel(
                                key = ContentKey(0, "c1"),
                                name = "Channel 1",
                                canonicalUrl = "https://example.com/c1",
                                avatarUrl = null,
                                bannerUrl = null,
                                subscriberCountText = "10K",
                                description = "Desc"
                            )
                        ),
                        SearchResultItem.PlaylistItem(
                            PlaylistSummary(
                                key = ContentKey(0, "p1"),
                                title = "Playlist 1",
                                canonicalUrl = "https://example.com/p1",
                                channelKey = ContentKey(0, "c1"),
                                channelName = "Channel 1",
                                thumbnailUrl = null,
                                videoCount = 5
                            )
                        )
                    ),
                    nextPageToken = PageToken.Id("page2")
                )
            )
        }

        override suspend fun suggestions(query: String): AppResult<List<String>> {
            return AppResult.Success(listOf("flowtube test", "flowtube tutorial"))
        }

        override suspend fun video(key: ContentKey): AppResult<VideoDetails> {
            return AppResult.Success(
                VideoDetails(
                    key = key,
                    title = "Test Video",
                    canonicalUrl = "https://example.com/v/${key.nativeId}",
                    description = "Test description",
                    channelKey = ContentKey(key.serviceId, "c1"),
                    channelName = "Channel 1",
                    channelAvatarUrl = null,
                    subscriberCountText = "10K",
                    thumbnailUrl = null,
                    durationSeconds = 300,
                    viewCount = 5000,
                    likeCount = 100,
                    publishedTimestamp = 1600000000L
                )
            )
        }

        override suspend fun streamInfo(key: ContentKey): AppResult<StreamInfo> {
            return AppResult.Success(
                StreamInfo(
                    key = key,
                    title = "Test Video"
                )
            )
        }

        override suspend fun channel(key: ContentKey): AppResult<ChannelDetails> {
            return AppResult.Success(
                ChannelDetails(
                    channel = Channel(
                        key = key,
                        name = "Channel",
                        canonicalUrl = "https://example.com/c/${key.nativeId}",
                        avatarUrl = null,
                        bannerUrl = null,
                        subscriberCountText = "10K",
                        description = "Channel Desc"
                    )
                )
            )
        }

        override suspend fun related(key: ContentKey): AppResult<List<VideoSummary>> {
            return AppResult.Success(emptyList())
        }

        override suspend fun playlist(key: ContentKey): AppResult<PlaylistDetails> {
            return AppResult.Success(
                PlaylistDetails(
                    key = key,
                    title = "Test Playlist",
                    canonicalUrl = "https://example.com/playlist?list=${key.nativeId}",
                    channelKey = ContentKey(key.serviceId, "c1"),
                    channelName = "Channel 1",
                    channelAvatarUrl = null,
                    thumbnailUrl = null,
                    description = "Playlist Desc",
                    videoCount = 1,
                    videos = listOf(
                        VideoSummary(
                            key = ContentKey(key.serviceId, "v1"),
                            title = "Video 1",
                            canonicalUrl = "https://example.com/v1",
                            channelKey = ContentKey(key.serviceId, "c1"),
                            channelName = "Channel 1",
                            channelAvatarUrl = null,
                            thumbnailUrl = null,
                            durationSeconds = 120,
                            viewCount = 1000,
                            publishedTimestamp = 1600000000L
                        )
                    ),
                    nextPageToken = null
                )
            )
        }

        override suspend fun comments(key: ContentKey, pageToken: PageToken?): AppResult<CommentPage> {
            return AppResult.Success(
                CommentPage(
                    comments = listOf(
                        Comment(
                            commentId = "cmt1",
                            authorName = "User 1",
                            authorAvatarUrl = null,
                            channelKey = null,
                            commentText = "Great video!",
                            publishedTimestamp = 1600001000L,
                            likeCount = 10
                        )
                    ),
                    nextPageToken = null
                )
            )
        }

        override suspend fun trending(): AppResult<List<VideoSummary>> {
            return AppResult.Success(emptyList())
        }
    }

    @Test
    fun contract_execution_and_type_integrity() = runBlocking {
        val searchRes = fakeService.search("test", SearchFilter.ALL, null)
        assertTrue(searchRes is AppResult.Success)
        val searchPage = (searchRes as AppResult.Success).value
        assertEquals(3, searchPage.items.size)
        assertEquals(PageToken.Id("page2"), searchPage.nextPageToken)

        val videoRes = fakeService.video(ContentKey(0, "vid123"))
        assertTrue(videoRes is AppResult.Success)
        assertEquals("vid123", (videoRes as AppResult.Success).value.key.nativeId)

        val playlistRes = fakeService.playlist(ContentKey(0, "PL_TEST"))
        assertTrue(playlistRes is AppResult.Success)
        assertEquals("PL_TEST", (playlistRes as AppResult.Success).value.key.nativeId)
        assertEquals(1, (playlistRes as AppResult.Success).value.videos.size)

        val commentsRes = fakeService.comments(ContentKey(0, "vid123"), null)
        assertTrue(commentsRes is AppResult.Success)
        assertEquals(1, (commentsRes as AppResult.Success).value.comments.size)
    }
}
