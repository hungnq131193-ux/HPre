package com.hpre.app.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpre.app.core.designsystem.HPreTheme
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoCardTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun video(
        id: String = "vid1",
        channelKey: ContentKey? = ContentKey(0, "chan1"),
        channelName: String? = "Kênh Test",
        isLive: Boolean = false,
        title: String = "Video tiêu đề thử nghiệm"
    ) = VideoSummary(
        key = ContentKey(0, id),
        title = title,
        canonicalUrl = "https://hpre.test/watch?v=$id",
        channelKey = channelKey,
        channelName = channelName,
        channelAvatarUrl = null,
        thumbnailUrl = null,
        durationSeconds = 125,
        viewCount = 1234,
        publishedTimestamp = 1_600_000_000L,
        isLive = isLive
    )

    @Test
    fun card_regions_dispatch_to_video_or_channel_without_overlap() {
        var clickedVideo: ContentKey? = null
        var videoClicks = 0
        var clickedChannel: ContentKey? = null

        composeTestRule.setContent {
            HPreTheme {
                VideoCard(
                    video = video(),
                    onClick = { clickedVideo = it; videoClicks++ },
                    onChannelClick = { clickedChannel = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("video_card_vid1_thumbnail").performClick()
        assertEquals(ContentKey(0, "vid1"), clickedVideo)
        assertEquals(1, videoClicks)
        assertNull(clickedChannel)

        composeTestRule.onNodeWithTag("video_card_vid1_channel").performClick()
        assertEquals(ContentKey(0, "chan1"), clickedChannel)
        // Channel tap must not also open the video.
        assertEquals(1, videoClicks)
    }

    @Test
    fun channel_area_hidden_when_key_or_callback_missing() {
        composeTestRule.setContent {
            HPreTheme {
                VideoCard(
                    video = video(id = "nochan", channelKey = null),
                    onClick = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("video_card_nochan_channel").assertDoesNotExist()
    }

    @Test
    fun compact_layout_keeps_regions_and_live_badge() {
        var clickedChannel: ContentKey? = null
        composeTestRule.setContent {
            HPreTheme {
                VideoCard(
                    video = video(id = "live1", isLive = true),
                    onClick = {},
                    onChannelClick = { clickedChannel = it },
                    compact = true
                )
            }
        }
        composeTestRule.onNodeWithTag("video_card_live1_thumbnail").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_card_live1_channel").performClick()
        assertEquals(ContentKey(0, "chan1"), clickedChannel)
        composeTestRule.onNodeWithText(
            composeTestRule.activity.getString(com.hpre.app.R.string.video_live)
        ).assertIsDisplayed()
    }

    @Test
    fun long_title_and_missing_metadata_still_render() {
        composeTestRule.setContent {
            HPreTheme {
                VideoCard(
                    video = video(
                        id = "long",
                        channelName = null,
                        title = "a".repeat(200)
                    ),
                    onClick = {},
                    onChannelClick = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("video_card_long").assertIsDisplayed()
    }
}
