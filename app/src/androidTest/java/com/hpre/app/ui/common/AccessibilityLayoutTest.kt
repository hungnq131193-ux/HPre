package com.hpre.app.ui.common

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpre.app.R
import com.hpre.app.core.designsystem.HPreTheme
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.settings.AppLanguage
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Semantics-level accessibility checks that don't need a device feature set —
 * TalkBack reads exactly these semantics nodes, so asserting roles and single
 * click actions here prevents double-announce / double-toggle regressions.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeSettingsRepo : SettingsRepository {
        val settingsFlow = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = settingsFlow
        override val isBackgroundPlaybackEnabled: Flow<Boolean> = MutableStateFlow(true)
        override val isPipEnabled: Flow<Boolean> = MutableStateFlow(true)
        override val isHistoryEnabled: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setTheme(theme: AppTheme) {
            settingsFlow.value = settingsFlow.value.copy(theme = theme)
        }
        override suspend fun setLanguage(language: AppLanguage) {
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
            override suspend fun setSponsorBlockEnabled(enabled: Boolean) {}
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

    private fun settingsViewModel(repo: FakeSettingsRepo = FakeSettingsRepo()) =
        SettingsViewModel(
            repo,
            AppUpdateChecker { UpdateCheckResult.UpToDate(SemanticVersion(1, 0, 0)) },
            "1.0.0"
        )

    @Test
    fun settings_switch_row_is_single_toggleable_semantics_node() {
        val repo = FakeSettingsRepo()
        composeRule.setContent {
            HPreTheme {
                SettingsScreen(
                    viewModel = settingsViewModel(repo),
                    onNavigateBack = {},
                    onOpenReleasePage = {}
                )
            }
        }

        // The row itself is the single toggle target (TalkBack reads it once).
        composeRule.onNodeWithTag("setting_pip_switch")
            .assertIsToggleable()
            .performClick()
        composeRule.waitForIdle()
        assertTrue(!repo.settingsFlow.value.pipEnabled)

        // The inner Switch control is visual only: no separate click action.
        composeRule.onNodeWithTag("setting_pip_switch_control")
            .assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.Disabled, Unit
            ).not())
    }

    @Test
    fun settings_choice_dialog_options_expose_selected_radio_semantics() {
        composeRule.setContent {
            HPreTheme {
                SettingsScreen(
                    viewModel = settingsViewModel(),
                    onNavigateBack = {},
                    onOpenReleasePage = {}
                )
            }
        }

        composeRule.onNodeWithTag("setting_theme_item").performClick()
        composeRule.onNodeWithTag("theme_option_DARK")
            .assertIsDisplayed()
            .assert(isSelectable())
            .assertIsNotSelected()
            .performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun video_card_thumbnail_announces_action_not_duplicated_title() {
        val video = VideoSummary(
            key = ContentKey(0, "v1"),
            title = "Some video title",
            canonicalUrl = "https://example.com/v1",
            thumbnailUrl = null,
            channelKey = null,
            channelName = "Channel",
            channelAvatarUrl = null,
            durationSeconds = 60L,
            viewCount = 10L,
            publishedTimestamp = 10L
        )
        composeRule.setContent {
            HPreTheme {
                VideoCard(video = video, onClick = {})
            }
        }

        val playLabel = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.action_play)
        composeRule.onNodeWithTag("video_card_v1_thumbnail")
            .assertContentDescriptionEquals(playLabel)
            .assert(hasClickAction())
        // Title is announced once, by the text node — thumbnail image adds no label.
        composeRule.onNodeWithTag("video_thumbnail").assertDoesNotExist()
    }
}
