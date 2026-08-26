package com.flowtube.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowtube.app.core.designsystem.FlowTubeTheme
import com.flowtube.app.core.error.AppResult
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.SearchPage
import com.flowtube.app.model.SearchResultItem
import com.flowtube.app.model.VideoSummary
import com.flowtube.app.navigation.NavigationFlowTest
import com.flowtube.app.navigation.RootScaffold
import com.flowtube.app.testing.FakeVideoService
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EndToEndNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun search_result_opens_watch_and_back_shows_mini_player() {
        val video = VideoSummary(
            key = ContentKey(0, "fixture"),
            title = "Fixture video",
            canonicalUrl = "https://example.test/fixture",
            channelKey = ContentKey(0, "fixture-channel"),
            channelName = "Fixture creator",
            channelAvatarUrl = null,
            thumbnailUrl = null,
            durationSeconds = 60,
            viewCount = 1,
            publishedTimestamp = 1
        )
        val service = FakeVideoService(
            supportsSearchSuggestions = false,
            searchHandler = { _, _, _ -> AppResult.Success(SearchPage(listOf(SearchResultItem.VideoItem(video)))) }
        )

        composeRule.setContent {
            FlowTubeTheme { RootScaffold(NavigationFlowTest.TestContainer(service)) }
        }

        composeRule.onNodeWithTag("top_bar_search_button").performClick()
        composeRule.onNodeWithTag("search_text_input").performTextInput("fixture")
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasText("Fixture video"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Fixture video").performClick()
        composeRule.onNodeWithTag("watch_screen").assertIsDisplayed()

        composeRule.onNodeWithTag("watch_back_button").performClick()

        composeRule.onNodeWithTag("mini-player", useUnmergedTree = true).assertIsDisplayed()
    }
}
