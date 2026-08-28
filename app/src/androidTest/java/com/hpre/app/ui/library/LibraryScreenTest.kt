package com.hpre.app.ui.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpre.app.core.designsystem.HPreTheme
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.HistoryRepository
import com.hpre.app.repository.LocalPlaylist
import com.hpre.app.repository.LocalPlaylistEntry
import com.hpre.app.repository.LocalPlaylistWithEntries
import com.hpre.app.repository.LocalSubscription
import com.hpre.app.repository.PlaylistRepository
import com.hpre.app.repository.SubscriptionRepository
import com.hpre.app.repository.WatchHistoryItem
import com.hpre.app.settings.AppSettings
import com.hpre.app.settings.AppTheme
import com.hpre.app.settings.QualityPreferenceSetting
import com.hpre.app.settings.SettingsRepository
import com.hpre.app.settings.SettingsScreen
import com.hpre.app.settings.SettingsViewModel
import com.hpre.app.update.AppUpdateChecker
import com.hpre.app.update.SemanticVersion
import com.hpre.app.update.UpdateCheckResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeHistoryRepo : HistoryRepository {
        val listFlow = MutableStateFlow<List<WatchHistoryItem>>(emptyList())
        override fun observeHistory(): Flow<List<WatchHistoryItem>> = listFlow
        override suspend fun getHistoryItem(key: ContentKey): AppResult<WatchHistoryItem?> =
            AppResult.Success(listFlow.value.firstOrNull { it.key == key })
        override suspend fun recordHistory(summary: VideoSummary, positionMs: Long, watchedTimestamp: Long): AppResult<Unit> =
            AppResult.Success(Unit)
        override suspend fun deleteHistoryItem(key: ContentKey): AppResult<Unit> {
            listFlow.value = listFlow.value.filterNot { it.key == key }
            return AppResult.Success(Unit)
        }
        override suspend fun clearHistory(): AppResult<Unit> {
            listFlow.value = emptyList()
            return AppResult.Success(Unit)
        }
    }

    private class FakeSubscriptionRepo : SubscriptionRepository {
        val subFlow = MutableStateFlow<List<LocalSubscription>>(emptyList())
        override fun observeSubscriptions(): Flow<List<LocalSubscription>> = subFlow
        override fun observeIsSubscribed(key: ContentKey): Flow<Boolean> =
            MutableStateFlow(subFlow.value.any { it.channelKey == key })
        override suspend fun isSubscribed(key: ContentKey): AppResult<Boolean> =
            AppResult.Success(subFlow.value.any { it.channelKey == key })
        override suspend fun subscribe(channel: com.hpre.app.model.Channel, subscribedTimestamp: Long): AppResult<Unit> {
            subFlow.value = subFlow.value + LocalSubscription(
                channelKey = channel.key,
                canonicalUrl = channel.canonicalUrl,
                name = channel.name,
                avatarUrl = channel.avatarUrl,
                subscribedTimestamp = subscribedTimestamp
            )
            return AppResult.Success(Unit)
        }
        override suspend fun unsubscribe(key: ContentKey): AppResult<Unit> {
            subFlow.value = subFlow.value.filterNot { it.channelKey == key }
            return AppResult.Success(Unit)
        }
        override suspend fun clearSubscriptions(): AppResult<Unit> {
            subFlow.value = emptyList()
            return AppResult.Success(Unit)
        }
    }

    private class FakePlaylistRepo : PlaylistRepository {
        val playlistsFlow = MutableStateFlow<List<LocalPlaylist>>(emptyList())
        val detailFlows = mutableMapOf<Long, MutableStateFlow<LocalPlaylistWithEntries?>>()
        private var nextId = 1L

        override fun observePlaylists(): Flow<List<LocalPlaylist>> = playlistsFlow
        override fun observePlaylistWithEntries(playlistId: Long): Flow<LocalPlaylistWithEntries?> {
            return detailFlows.getOrPut(playlistId) {
                val p = playlistsFlow.value.firstOrNull { it.playlistId == playlistId }
                MutableStateFlow(p?.let { LocalPlaylistWithEntries(it, emptyList()) })
            }
        }
        override suspend fun getPlaylist(playlistId: Long): AppResult<LocalPlaylist?> =
            AppResult.Success(playlistsFlow.value.firstOrNull { it.playlistId == playlistId })

        override suspend fun createPlaylist(title: String, timestamp: Long): AppResult<Long> {
            val id = nextId++
            val newP = LocalPlaylist(id, title, timestamp, timestamp, 0)
            playlistsFlow.value = playlistsFlow.value + newP
            detailFlows[id] = MutableStateFlow(LocalPlaylistWithEntries(newP, emptyList()))
            return AppResult.Success(id)
        }

        override suspend fun renamePlaylist(playlistId: Long, newTitle: String, timestamp: Long): AppResult<Unit> {
            playlistsFlow.value = playlistsFlow.value.map {
                if (it.playlistId == playlistId) it.copy(title = newTitle, updatedTimestamp = timestamp) else it
            }
            detailFlows[playlistId]?.let { flow ->
                flow.value?.let { current ->
                    flow.value = current.copy(playlist = current.playlist.copy(title = newTitle, updatedTimestamp = timestamp))
                }
            }
            return AppResult.Success(Unit)
        }

        override suspend fun deletePlaylist(playlistId: Long): AppResult<Unit> {
            playlistsFlow.value = playlistsFlow.value.filterNot { it.playlistId == playlistId }
            detailFlows.remove(playlistId)
            return AppResult.Success(Unit)
        }

        override suspend fun addEntry(playlistId: Long, video: VideoSummary, addedTimestamp: Long): AppResult<Unit> {
            detailFlows[playlistId]?.let { flow ->
                val current = flow.value ?: return@let
                val newEntry = LocalPlaylistEntry(
                    playlistId = playlistId,
                    videoKey = video.key,
                    canonicalUrl = video.canonicalUrl,
                    title = video.title,
                    channelKey = video.channelKey,
                    channelName = video.channelName,
                    thumbnailUrl = video.thumbnailUrl,
                    durationSeconds = video.durationSeconds,
                    addedTimestamp = addedTimestamp,
                    sortOrder = current.entries.size
                )
                val newEntries = current.entries + newEntry
                val updatedPlaylist = current.playlist.copy(entryCount = newEntries.size, updatedTimestamp = addedTimestamp)
                flow.value = LocalPlaylistWithEntries(updatedPlaylist, newEntries)
                playlistsFlow.value = playlistsFlow.value.map {
                    if (it.playlistId == playlistId) updatedPlaylist else it
                }
            }
            return AppResult.Success(Unit)
        }

        override suspend fun removeEntry(playlistId: Long, videoKey: ContentKey, updatedTimestamp: Long): AppResult<Unit> {
            detailFlows[playlistId]?.let { flow ->
                val current = flow.value ?: return@let
                val newEntries = current.entries.filterNot { it.videoKey == videoKey }.mapIndexed { index, e ->
                    e.copy(sortOrder = index)
                }
                val updatedPlaylist = current.playlist.copy(entryCount = newEntries.size, updatedTimestamp = updatedTimestamp)
                flow.value = LocalPlaylistWithEntries(updatedPlaylist, newEntries)
                playlistsFlow.value = playlistsFlow.value.map {
                    if (it.playlistId == playlistId) updatedPlaylist else it
                }
            }
            return AppResult.Success(Unit)
        }

        override suspend fun reorderEntries(playlistId: Long, fromIndex: Int, toIndex: Int, updatedTimestamp: Long): AppResult<Unit> {
            detailFlows[playlistId]?.let { flow ->
                val current = flow.value ?: return@let
                val mutable = current.entries.toMutableList()
                val item = mutable.removeAt(fromIndex)
                mutable.add(toIndex, item)
                val reindexed = mutable.mapIndexed { idx, e -> e.copy(sortOrder = idx) }
                val updatedPlaylist = current.playlist.copy(updatedTimestamp = updatedTimestamp)
                flow.value = LocalPlaylistWithEntries(updatedPlaylist, reindexed)
                playlistsFlow.value = playlistsFlow.value.map {
                    if (it.playlistId == playlistId) updatedPlaylist else it
                }
            }
            return AppResult.Success(Unit)
        }
    }

    private class FakeSettingsRepo : SettingsRepository {
        val settingsFlow = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = settingsFlow
        override val isBackgroundPlaybackEnabled: Flow<Boolean> = MutableStateFlow(true)
        override val isPipEnabled: Flow<Boolean> = MutableStateFlow(true)
        override val isHistoryEnabled: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setTheme(theme: AppTheme) {
            settingsFlow.value = settingsFlow.value.copy(theme = theme)
        }
        override suspend fun setLanguage(language: com.hpre.app.settings.AppLanguage) {
            settingsFlow.value = settingsFlow.value.copy(language = language)
        }
        override suspend fun setWifiQuality(quality: QualityPreferenceSetting) {
            settingsFlow.value = settingsFlow.value.copy(wifiQuality = quality)
        }
        override suspend fun setMobileQuality(quality: QualityPreferenceSetting) {
            settingsFlow.value = settingsFlow.value.copy(mobileQuality = quality)
        }
        override suspend fun setDefaultPlaybackSpeed(speed: Float) {
            settingsFlow.value = settingsFlow.value.copy(defaultPlaybackSpeed = speed)
        }
        override suspend fun setAutoplay(enabled: Boolean) {
            settingsFlow.value = settingsFlow.value.copy(autoplay = enabled)
        }
        override suspend fun setBackgroundPlaybackEnabled(enabled: Boolean) {
            settingsFlow.value = settingsFlow.value.copy(backgroundPlaybackEnabled = enabled)
        }
        override suspend fun setPipEnabled(enabled: Boolean) {
            settingsFlow.value = settingsFlow.value.copy(pipEnabled = enabled)
        }
        override suspend fun setHistoryEnabled(enabled: Boolean) {
            settingsFlow.value = settingsFlow.value.copy(historyEnabled = enabled)
        }
    }

    @Test
    fun library_screen_renders_sections_and_handles_playlist_creation() {
        val historyRepo = FakeHistoryRepo()
        val subRepo = FakeSubscriptionRepo()
        val playlistRepo = FakePlaylistRepo()
        val viewModel = LibraryViewModel(historyRepo, subRepo, playlistRepo)

        composeRule.setContent {
            HPreTheme {
                LibraryScreen(
                    viewModel = viewModel,
                    onNavigateToHistory = {},
                    onNavigateToSubscriptions = {},
                    onNavigateToPlaylists = {},
                    onPlaylistClick = {},
                    onVideoClick = {}
                )
            }
        }

        composeRule.onNodeWithTag("library_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("library_history_header", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("library_subscriptions_header", useUnmergedTree = true).fetchSemanticsNode()

        // Create a new playlist
        composeRule.onNodeWithTag("library_create_playlist_button").performClick()
        composeRule.onNodeWithTag("playlist_title_input").performTextInput("Cool Tracks")
        composeRule.onNodeWithTag("playlist_dialog_create_button").performClick()

        composeRule.waitForIdle()
        assertEquals(1, playlistRepo.playlistsFlow.value.size)
        assertEquals("Cool Tracks", playlistRepo.playlistsFlow.value.first().title)
    }

    @Test
    fun history_screen_clear_all_removes_history() {
        val historyRepo = FakeHistoryRepo()
        val subRepo = FakeSubscriptionRepo()
        val playlistRepo = FakePlaylistRepo()

        val item1 = WatchHistoryItem(
            key = ContentKey(1, "v1"),
            canonicalUrl = "https://example.com/v1",
            title = "Video One",
            channelKey = null,
            channelName = "Channel 1",
            thumbnailUrl = null,
            durationSeconds = 100L,
            playbackPositionMs = 30000L,
            watchedTimestamp = 1000L
        )
        historyRepo.listFlow.value = listOf(item1)
        val viewModel = LibraryViewModel(historyRepo, subRepo, playlistRepo)

        composeRule.setContent {
            HPreTheme {
                HistoryScreen(
                    viewModel = viewModel,
                    onVideoClick = {},
                    onNavigateBack = {}
                )
            }
        }

        composeRule.onNodeWithTag("history_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("history_item_v1").assertIsDisplayed()

        composeRule.onNodeWithTag("history_clear_all_button").performClick()
        composeRule.onNodeWithTag("history_clear_dialog_confirm").performClick()

        composeRule.waitForIdle()
        assertTrue(historyRepo.listFlow.value.isEmpty())
        composeRule.onNodeWithText("No watch history").assertIsDisplayed()
    }

    @Test
    fun subscriptions_screen_allows_local_unsubscribe() {
        val historyRepo = FakeHistoryRepo()
        val subRepo = FakeSubscriptionRepo()
        val playlistRepo = FakePlaylistRepo()

        subRepo.subFlow.value = listOf(
            LocalSubscription(ContentKey(1, "c1"), "https://example.com/c1", "Test Creator", null, 1000L)
        )
        val viewModel = LibraryViewModel(historyRepo, subRepo, playlistRepo)

        composeRule.setContent {
            HPreTheme {
                SubscriptionsScreen(
                    viewModel = viewModel,
                    onChannelClick = {}
                )
            }
        }

        composeRule.onNodeWithTag("subscriptions_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("subscription_row_c1").assertIsDisplayed()
        composeRule.onNodeWithText("Test Creator").assertIsDisplayed()

        composeRule.onNodeWithTag("unsubscribe_button_c1").performClick()
        composeRule.waitForIdle()
        assertTrue(subRepo.subFlow.value.isEmpty())
        composeRule.onNodeWithText("Chưa theo dõi kênh nào").assertIsDisplayed()
    }

    @Test
    fun settings_screen_toggles_and_choices_persist_state() {
        val settingsRepo = FakeSettingsRepo()
        var updateCheckCalls = 0
        val updateChecker = AppUpdateChecker {
            updateCheckCalls++
            UpdateCheckResult.UpToDate(SemanticVersion(1, 0, 0))
        }
        val viewModel = SettingsViewModel(settingsRepo, updateChecker, "1.0.0")

        composeRule.setContent {
            HPreTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onOpenReleasePage = {}
                )
            }
        }

        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("setting_check_update_item").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Phiên bản hiện tại: 1.0.0").assertIsDisplayed()
        assertEquals(0, updateCheckCalls)

        composeRule.onNodeWithTag("setting_check_update_item").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { updateCheckCalls == 1 }
        composeRule.onNodeWithText("Bạn đang dùng phiên bản mới nhất.").assertIsDisplayed()

        // Toggle background playback
        composeRule.onNodeWithTag("setting_background_playback_switch").performClick()
        composeRule.waitForIdle()
        assertFalse(settingsRepo.settingsFlow.value.backgroundPlaybackEnabled)

        // Toggle PiP
        composeRule.onNodeWithTag("setting_pip_switch").performClick()
        composeRule.waitForIdle()
        assertFalse(settingsRepo.settingsFlow.value.pipEnabled)

        // Toggle history
        composeRule.onNodeWithTag("setting_history_switch").performClick()
        composeRule.waitForIdle()
        assertFalse(settingsRepo.settingsFlow.value.historyEnabled)

        // Select Theme
        composeRule.onNodeWithTag("setting_theme_item").performClick()
        composeRule.onNodeWithTag("theme_option_DARK").performClick()
        composeRule.waitForIdle()
        assertEquals(AppTheme.DARK, settingsRepo.settingsFlow.value.theme)
    }
}
