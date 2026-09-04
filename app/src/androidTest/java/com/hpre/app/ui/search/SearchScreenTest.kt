package com.hpre.app.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTextInput
import com.hpre.app.core.designsystem.HPreTheme
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.SearchPage
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.CatalogRepository
import com.hpre.app.testing.FakeVideoService
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class SearchScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun video_cards_render_vietnamese_metadata_and_truthful_live_badge() {
        val published = System.currentTimeMillis() - 90L * 86_400_000L
        val normal = summary("metadata").copy(
            viewCount = 1_500_000,
            publishedTimestamp = published
        )
        val live = summary("live").copy(
            durationSeconds = 61,
            isLive = true
        )

        composeTestRule.setContent {
            HPreTheme {
                Column {
                    com.hpre.app.ui.common.VideoCard(normal, onClick = {})
                    com.hpre.app.ui.common.VideoCard(live, onClick = {})
                }
            }
        }

        composeTestRule.onNodeWithText("1,5 Tr lượt xem", substring = true).assertIsDisplayed()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val age = com.hpre.app.ui.common.VideoFormat.age(normal.publishedTimestamp, System.currentTimeMillis())
        composeTestRule.onNodeWithText(age, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(com.hpre.app.R.string.video_live)).assertIsDisplayed()
        composeTestRule.onNodeWithText("1:01").assertDoesNotExist()
    }

    @Test
    fun production_compose_integration_searchResultsList_with_injected_lazyListState_and_swipe_cycle() {
        var loadMoreCallCount = 0
        var isLoadingNextPage by androidx.compose.runtime.mutableStateOf(false)
        var hasNextPage by androidx.compose.runtime.mutableStateOf(true)
        val requestKey = "search:same_key"
        var items by androidx.compose.runtime.mutableStateOf<List<SearchResultItem>>(
            (1..15).map { SearchResultItem.VideoItem(summary("item_$it")) }
        )
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState

        composeTestRule.setContent {
            HPreTheme {
                val state = androidx.compose.foundation.lazy.rememberLazyListState()
                listState = state
                SearchResultsList(
                    items = items,
                    requestKey = requestKey,
                    hasNextPage = hasNextPage,
                    isLoadingNextPage = isLoadingNextPage,
                    onLoadMore = {
                        loadMoreCallCount++
                        isLoadingNextPage = true
                    },
                    onVideoClick = {},
                    onChannelClick = {},
                    onPlaylistClick = {},
                    listState = state
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Establish near-end via controlled programmatic scroll first
        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                listState.scrollToItem(13)
            }
        }
        composeTestRule.waitForIdle()

        // Assert precondition: near-end position reached
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val total = listState.layoutInfo.totalItemsCount
        org.junit.Assert.assertTrue("Viewport must be near end", lastVisible >= total - 3)

        // Assert programmatic scroll produces ZERO triggers
        assertEquals("Programmatic scroll near end must produce zero callbacks", 0, loadMoreCallCount)

        // 2. Actual swipe user input produces EXACTLY ONE callback
        composeTestRule.onNodeWithTag("search_results_list")
            .performTouchInput {
                swipeUp(startY = 800f, endY = 200f, durationMillis = 200)
            }
        composeTestRule.waitForIdle()
        assertEquals("Actual swipe user input must produce exactly one callback", 1, loadMoreCallCount)

        // 3. Append / loading false with no swipe produces ZERO new triggers
        items = (1..30).map { SearchResultItem.VideoItem(summary("item_$it")) }
        isLoadingNextPage = false
        composeTestRule.waitForIdle()
        assertEquals("Append / loading false without swipe must produce zero triggers", 1, loadMoreCallCount)

        // 4. Programmatic scroll near new end (e.g. index 28) produces ZERO triggers
        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                listState.scrollToItem(28)
            }
        }
        composeTestRule.waitForIdle()
        assertEquals("Programmatic scroll to second page end must produce zero triggers", 1, loadMoreCallCount)

        // 5. Second actual fresh swipe near end produces EXACTLY ONE next callback
        composeTestRule.onNodeWithTag("search_results_list")
            .performTouchInput {
                swipeUp(startY = 800f, endY = 200f, durationMillis = 200)
            }
        composeTestRule.waitForIdle()
        assertEquals("Second actual fresh swipe near end must produce exactly one next callback", 2, loadMoreCallCount)
    }

    @Test
    fun recomposition_state_capture_regressions_hasNextPage_false_isLoadingNextPage_true_and_restoration() {
        var loadMoreCallCount = 0
        var isLoadingNextPage by androidx.compose.runtime.mutableStateOf(false)
        var hasNextPage by androidx.compose.runtime.mutableStateOf(true)
        val requestKey = "search:recomposition_test"
        var items by androidx.compose.runtime.mutableStateOf<List<SearchResultItem>>(
            (1..20).map { SearchResultItem.VideoItem(summary("state_item_$it")) }
        )
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState

        composeTestRule.setContent {
            HPreTheme {
                val state = androidx.compose.foundation.lazy.rememberLazyListState()
                listState = state
                SearchResultsList(
                    items = items,
                    requestKey = requestKey,
                    hasNextPage = hasNextPage,
                    isLoadingNextPage = isLoadingNextPage,
                    onLoadMore = {
                        loadMoreCallCount++
                    },
                    onVideoClick = {},
                    onChannelClick = {},
                    onPlaylistClick = {},
                    listState = state
                )
            }
        }
        composeTestRule.waitForIdle()

        // Establish near-end position via programmatic scroll
        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                listState.scrollToItem(18)
            }
        }
        composeTestRule.waitForIdle()

        // 1. After recomposition changes hasNextPage = false, real user swipe at a new near-end position NEVER calls load
        hasNextPage = false
        composeTestRule.waitForIdle()

        // Scroll to a different near-end position to avoid deduplication matching previous position
        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                listState.scrollToItem(17)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("search_results_list")
            .performTouchInput {
                swipeUp(startY = 800f, endY = 200f, durationMillis = 200)
            }
        composeTestRule.waitForIdle()
        assertEquals("When hasNextPage is false, user swipe at new near-end position must not call load", 0, loadMoreCallCount)

        // 2. After recomposition sets hasNextPage = true but isLoadingNextPage = true, swipe NEVER calls load
        hasNextPage = true
        isLoadingNextPage = true
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                listState.scrollToItem(18)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("search_results_list")
            .performTouchInput {
                swipeUp(startY = 800f, endY = 200f, durationMillis = 200)
            }
        composeTestRule.waitForIdle()
        assertEquals("When isLoadingNextPage is true, user swipe must not call load", 0, loadMoreCallCount)

        // 3. After eligible current state restored (hasNextPage = true, isLoadingNextPage = false), fresh swipe can call once
        isLoadingNextPage = false
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                listState.scrollToItem(19)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("search_results_list")
            .performTouchInput {
                swipeUp(startY = 800f, endY = 200f, durationMillis = 200)
            }
        composeTestRule.waitForIdle()
        assertEquals("When eligible state restored, fresh user swipe must call load once", 1, loadMoreCallCount)
    }

    private fun summary(id: String) = VideoSummary(
        key = ContentKey(0, id),
        title = "Video $id",
        canonicalUrl = "https://example.com/watch?v=$id",
        channelKey = ContentKey(0, "c_$id"),
        channelName = "Channel $id",
        channelAvatarUrl = null,
        thumbnailUrl = null,
        durationSeconds = 60,
        viewCount = 100,
        publishedTimestamp = 1000L
    )

    @Test
    fun typing_query_and_clicking_result_navigates_to_watch() {
        val fakeService = FakeVideoService(
            searchResponses = mapOf(
                "compose" to SearchPage(
                    items = listOf(SearchResultItem.VideoItem(summary("abc1234")))
                )
            )
        )
        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        )
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        var clickedKey: ContentKey? = null

        composeTestRule.setContent {
            HPreTheme {
                SearchScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onVideoClick = { key -> clickedKey = key }
                )
            }
        }

        // Initially search screen is shown
        composeTestRule.onNodeWithTag("search_screen").assertIsDisplayed()

        // Type query
        composeTestRule.onNodeWithTag("search_text_input").performTextInput("compose")

        // Wait for debounce/execution
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("video_card_abc1234"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Assert video item is displayed
        composeTestRule.onNodeWithTag("video_card_abc1234").assertIsDisplayed()
        composeTestRule.onNodeWithText("Video abc1234").assertIsDisplayed()

        // Click card
        composeTestRule.onNodeWithTag("video_card_abc1234").performClick()

        // Verify clicked key
        assertEquals(ContentKey(0, "abc1234"), clickedKey)
    }

    @Test
    fun clicking_channel_and_playlist_results_invokes_callbacks() {
        val fakeService = FakeVideoService(
            searchResponses = mapOf(
                "creators" to SearchPage(
                    items = listOf(
                        SearchResultItem.ChannelItem(
                            com.hpre.app.model.Channel(
                                key = ContentKey(0, "chan_42"),
                                name = "Channel Forty Two",
                                canonicalUrl = "https://example.com/channel/chan_42",
                                avatarUrl = null,
                                bannerUrl = null,
                                subscriberCountText = "100k subscribers",
                                description = "Test channel"
                            )
                        ),
                        SearchResultItem.PlaylistItem(
                            com.hpre.app.model.PlaylistSummary(
                                key = ContentKey(0, "play_99"),
                                title = "Awesome Playlist",
                                canonicalUrl = "https://example.com/playlist?list=play_99",
                                channelKey = ContentKey(0, "chan_42"),
                                channelName = "Channel Forty Two",
                                thumbnailUrl = null,
                                videoCount = 25
                            )
                        )
                    )
                )
            )
        )
        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        )
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        var clickedChannelKey: ContentKey? = null
        var clickedPlaylistKey: ContentKey? = null

        composeTestRule.setContent {
            HPreTheme {
                SearchScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onVideoClick = {},
                    onChannelClick = { clickedChannelKey = it },
                    onPlaylistClick = { clickedPlaylistKey = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("search_text_input").performTextInput("creators")

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("channel_card_chan_42"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Assert Channel result is displayed and click
        composeTestRule.onNodeWithTag("channel_card_chan_42").assertIsDisplayed()
        composeTestRule.onNodeWithTag("channel_card_chan_42").performClick()
        assertEquals(ContentKey(0, "chan_42"), clickedChannelKey)

        // Assert Playlist result is displayed and click
        composeTestRule.onNodeWithTag("playlist_card_play_99").assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_card_play_99").performClick()
        assertEquals(ContentKey(0, "play_99"), clickedPlaylistKey)
    }

    @Test
    fun short_search_results_with_token_do_not_automatically_trigger_next_page_load_without_scroll() {
        val fakeService = FakeVideoService()
        fakeService.searchHandler = { q, f, token ->
            if (token == null) {
                // Short page with 2 items and a next page token
                AppResult.Success(
                    SearchPage(
                        items = listOf(
                            SearchResultItem.VideoItem(summary("short_1")),
                            SearchResultItem.VideoItem(summary("short_2"))
                        ),
                        nextPageToken = PageToken.Id("next_token_123")
                    )
                )
            } else {
                AppResult.Success(
                    SearchPage(
                        items = listOf(SearchResultItem.VideoItem(summary("short_3"))),
                        nextPageToken = null
                    )
                )
            }
        }

        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        )
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        composeTestRule.setContent {
            HPreTheme {
                SearchScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onVideoClick = {}
                )
            }
        }

        viewModel.onQuerySubmitted("short_search")
        composeTestRule.waitForIdle()

        // Wait a short time to verify no automatic pagination loop occurs
        composeTestRule.waitUntil(3000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("video_card_short_1"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("video_card_short_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_card_short_3").assertDoesNotExist()

        // fakeService should have received exactly 1 search call (initial page), NOT 2 (next page was not requested)
        assertEquals(1, fakeService.searchCallCount)
    }

    @Test
    fun load_next_page_appends_results_once() {
        val page1Items = (1..15).map { SearchResultItem.VideoItem(summary("item_$it")) }
        val page2Items = (16..25).map { SearchResultItem.VideoItem(summary("item_$it")) }

        val fakeService = FakeVideoService()
        fakeService.searchHandler = { q, f, token ->
            if (token == null) {
                AppResult.Success(
                    SearchPage(
                        items = page1Items,
                        nextPageToken = PageToken.Id("token_p2")
                    )
                )
            } else {
                AppResult.Success(
                    SearchPage(
                        items = page2Items,
                        nextPageToken = null
                    )
                )
            }
        }

        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        )
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        composeTestRule.setContent {
            HPreTheme {
                SearchScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onVideoClick = {}
                )
            }
        }

        viewModel.onQuerySubmitted("scroll_test")
        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(3000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("video_card_item_1"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(1, fakeService.searchCallCount)

        // SearchResultsList user-input admission is covered independently. This
        // integration verifies that its callback loads and appends the next page.
        viewModel.loadNextPage()
        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(5000) { fakeService.searchCallCount == 2 }
        composeTestRule.onNodeWithTag("search_results_list")
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("video_card_item_16"))
        composeTestRule.onNodeWithTag("video_card_item_16").assertIsDisplayed()

        // Exactly 2 search calls made
        assertEquals(2, fakeService.searchCallCount)
    }

    @Test
    fun request_key_change_and_empty_state_resets_pagination() {
        var loadMoreCallCount = 0
        var requestKey by androidx.compose.runtime.mutableStateOf("search:queryA")
        var isLoadingNextPage by androidx.compose.runtime.mutableStateOf(false)
        var items by androidx.compose.runtime.mutableStateOf<List<SearchResultItem>>(
            (1..15).map { SearchResultItem.VideoItem(summary("req_item_$it")) }
        )
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState

        composeTestRule.setContent {
            HPreTheme {
                val state = androidx.compose.foundation.lazy.rememberLazyListState()
                listState = state
                SearchResultsList(
                    items = items,
                    requestKey = requestKey,
                    hasNextPage = true,
                    isLoadingNextPage = isLoadingNextPage,
                    onLoadMore = {
                        loadMoreCallCount++
                        isLoadingNextPage = true
                    },
                    onVideoClick = {},
                    onChannelClick = {},
                    onPlaylistClick = {},
                    listState = state
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Scroll queryA to near end programmatically first -> 0 triggers
        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                listState.scrollToItem(13)
            }
        }
        composeTestRule.waitForIdle()
        var lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        var total = listState.layoutInfo.totalItemsCount
        org.junit.Assert.assertTrue("Viewport must be near end", lastVisible >= total - 3)
        assertEquals(0, loadMoreCallCount)

        // Swipe queryA near end -> 1 trigger
        composeTestRule.onNodeWithTag("search_results_list")
            .performTouchInput {
                swipeUp(startY = 800f, endY = 100f, durationMillis = 200)
            }
        composeTestRule.waitForIdle()
        assertEquals(1, loadMoreCallCount)

        // Reset loading state for next query test step
        isLoadingNextPage = false
        composeTestRule.waitForIdle()

        // 2. Query changes to queryB with empty list initially
        items = emptyList<SearchResultItem>()
        requestKey = "search:queryB"
        composeTestRule.waitForIdle()

        // 3. queryB results arrive
        items = (1..15).map { SearchResultItem.VideoItem(summary("req_item_b_$it")) }
        composeTestRule.waitForIdle()

        // No automatic trigger for queryB without user swipe
        assertEquals(1, loadMoreCallCount)

        // Scroll queryB list to near end programmatically -> 0 new triggers
        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                listState.scrollToItem(13)
            }
        }
        composeTestRule.waitForIdle()

        // Assert precondition
        lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        total = listState.layoutInfo.totalItemsCount
        org.junit.Assert.assertTrue("Viewport must be near end", lastVisible >= total - 3)
        assertEquals(1, loadMoreCallCount)

        // 4. Fresh UserInput for queryB triggers load more (second trigger)
        composeTestRule.onNodeWithTag("search_results_list")
            .performTouchInput {
                swipeUp(startY = 800f, endY = 100f, durationMillis = 200)
            }
        composeTestRule.waitForIdle()
        assertEquals(2, loadMoreCallCount)
    }
}

