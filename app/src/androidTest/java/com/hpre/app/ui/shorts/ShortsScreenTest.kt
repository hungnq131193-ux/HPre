package com.hpre.app.ui.shorts

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipeUp
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpre.app.core.designsystem.HPreTheme
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoSummary
import com.hpre.app.player.PlaybackState
import com.hpre.app.player.PlayerController
import com.hpre.app.player.QualityOption
import com.hpre.app.repository.LocalPlaylist
import com.hpre.app.repository.LocalPlaylistWithEntries
import com.hpre.app.repository.PlaylistRepository
import com.hpre.app.repository.ShortsFeedSource
import com.hpre.app.testing.FakeVideoService
import com.hpre.app.ui.watch.ShareLauncher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShortsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun active_page_owns_one_surface_and_swipe_hands_off_to_next_key() {
        val first = video("first")
        val second = video("second")
        val player = RecordingPlayer()
        val model = ShortsViewModel(
            feedRepository = ShortsFeedSource { AppResult.Success(listOf(first, second)) },
            videoService = FakeVideoService(
                streamInfoHandler = { AppResult.Success(StreamInfo(it, it.nativeId)) }
            ),
            playerController = player,
            playlistRepository = FakePlaylists()
        )

        composeRule.setContent { HPreTheme { ShortsScreen(model) } }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag("shorts_pager")).fetchSemanticsNodes().isNotEmpty() &&
                player.preparedKeys.contains(first.key)
        }

        composeRule.onAllNodes(hasTestTag("shorts_active_player")).assertCountEquals(1)
        composeRule.onNodeWithTag("shorts_thumbnail_second").assertIsDisplayed()
        composeRule.onNodeWithTag("shorts_play_pause").assertIsDisplayed()
        composeRule.onNodeWithTag("shorts_share").assertIsDisplayed()
        composeRule.onNodeWithTag("shorts_save").assertIsDisplayed()

        composeRule.onNodeWithTag("shorts_pager").performTouchInput { swipeUp() }
        composeRule.waitUntil(5_000) { player.preparedKeys.lastOrNull() == second.key }

        composeRule.onAllNodes(hasTestTag("shorts_active_player")).assertCountEquals(1)
        assertEquals(listOf(first.key, second.key), player.preparedKeys.distinct())
    }

    @Test fun invalid_share_url_never_reaches_launcher() {
        val invalid = video("invalid").copy(canonicalUrl = "javascript:alert(1)")
        var shareCalls = 0
        val model = ShortsViewModel(
            ShortsFeedSource { AppResult.Success(listOf(invalid)) },
            FakeVideoService(streamInfoHandler = { AppResult.Success(StreamInfo(it, "invalid")) }),
            RecordingPlayer(),
            FakePlaylists()
        )
        composeRule.setContent {
            HPreTheme {
                ShortsScreen(model, shareLauncher = ShareLauncher { _, _ -> shareCalls++ })
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag("shorts_share")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("shorts_share").performClick()
        composeRule.runOnIdle { assertEquals(0, shareCalls) }
    }

    private class RecordingPlayer : PlayerController {
        override val state = MutableStateFlow(PlaybackState())
        val preparedKeys = mutableListOf<ContentKey>()
        override fun prepare(key: ContentKey, streamInfo: StreamInfo, startPositionMs: Long, playWhenReady: Boolean, initialQuality: QualityOption?) {
            preparedKeys += key
            state.value = state.value.copy(key = key, isPlaying = playWhenReady)
        }
        override fun attachSurface(playerView: PlayerView) = Unit
        override fun detachSurface(playerView: PlayerView) = Unit
        override fun onLifecycleStart() = Unit
        override fun onLifecycleStop() = Unit
        override fun play() { state.value = state.value.copy(isPlaying = true) }
        override fun pause() { state.value = state.value.copy(isPlaying = false) }
        override fun playPause() { state.value = state.value.copy(isPlaying = !state.value.isPlaying) }
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(deltaMs: Long) = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun selectQuality(quality: QualityOption) = Unit
        override fun release() = Unit
    }

    private class FakePlaylists : PlaylistRepository {
        override fun observePlaylists(): Flow<List<LocalPlaylist>> = flowOf(emptyList())
        override fun observePlaylistWithEntries(playlistId: Long): Flow<LocalPlaylistWithEntries?> = flowOf(null)
        override suspend fun getPlaylist(playlistId: Long) = AppResult.Success(null)
        override suspend fun createPlaylist(title: String, timestamp: Long) = AppResult.Success(1L)
        override suspend fun renamePlaylist(playlistId: Long, newTitle: String, timestamp: Long) = AppResult.Success(Unit)
        override suspend fun deletePlaylist(playlistId: Long) = AppResult.Success(Unit)
        override suspend fun addEntry(playlistId: Long, video: VideoSummary, addedTimestamp: Long) = AppResult.Success(Unit)
        override suspend fun removeEntry(playlistId: Long, videoKey: ContentKey, updatedTimestamp: Long) = AppResult.Success(Unit)
        override suspend fun reorderEntries(playlistId: Long, fromIndex: Int, toIndex: Int, updatedTimestamp: Long) = AppResult.Success(Unit)
    }

    private fun video(id: String) = VideoSummary(
        key = ContentKey(0, id), title = "Video $id", canonicalUrl = "https://example.test/$id",
        channelKey = null, channelName = "Channel $id", channelAvatarUrl = null,
        thumbnailUrl = "https://example.test/$id.jpg", durationSeconds = 30,
        viewCount = null, publishedTimestamp = null
    )
}
