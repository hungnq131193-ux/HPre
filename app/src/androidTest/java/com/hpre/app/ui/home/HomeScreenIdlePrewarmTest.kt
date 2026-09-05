package com.hpre.app.ui.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpre.app.core.designsystem.HPreTheme
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.RecommendationRequest
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenIdlePrewarmTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun prewarm_registers_only_for_content_and_cancels_when_screen_leaves_composition() {
        val result = CompletableDeferred<AppResult<List<VideoSummary>>>()
        val repository = com.hpre.app.repository.HomeRecommendationSource { _: RecommendationRequest ->
            result.await()
        }
        val viewModel = HomeViewModel(
            repository = repository,
            topicFeedSource = TopicFeedSource { _, _ -> AppResult.Success(emptyList()) }
        )
        val registry = FakeIdleQueueRegistry()
        val showHome = mutableStateOf(true)
        var prewarmCalls = 0

        composeRule.setContent {
            HPreTheme {
                if (showHome.value) {
                    HomeScreen(
                        viewModel = viewModel,
                        onVideoClick = {},
                        onContentIdle = { prewarmCalls++ },
                        idleQueueRegistry = registry
                    )
                }
            }
        }
        composeRule.runOnIdle {
            assertEquals(0, registry.addCalls)
            result.complete(AppResult.Success(listOf(summary())))
        }
        composeRule.waitUntil { registry.addCalls == 1 }
        composeRule.runOnIdle { showHome.value = false }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            registry.runHandler()
            assertEquals(0, prewarmCalls)
            assertEquals(1, registry.removeCalls)
        }
    }

    private class FakeIdleQueueRegistry : IdleQueueRegistry {
        private lateinit var handler: () -> Boolean
        var addCalls = 0
        var removeCalls = 0

        override fun addIdleHandler(handler: () -> Boolean): Any {
            addCalls++
            this.handler = handler
            return handler
        }

        override fun removeIdleHandler(token: Any) {
            removeCalls++
        }

        fun runHandler() {
            handler()
        }
    }

    private fun summary() = VideoSummary(
        key = ContentKey(0, "idle_test"),
        title = "Idle test",
        canonicalUrl = "https://example.com/watch?v=idle_test",
        channelKey = ContentKey(0, "channel"),
        channelName = "Channel",
        channelAvatarUrl = null,
        thumbnailUrl = null,
        durationSeconds = 60,
        viewCount = 1,
        publishedTimestamp = 1L
    )
}
