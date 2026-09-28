package com.hpre.app.core.designsystem

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpre.app.navigation.RootScaffold
import com.hpre.app.testing.FakeVideoService
import com.hpre.app.testing.TestHPreApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Theme/chrome contract for the UI/UX upgrade: labeled tabs with 48dp targets,
 * brand header only on Home, and real screen titles on the other top-level tabs.
 */
@RunWith(AndroidJUnit4::class)
class HPreThemeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun testContainer() = TestHPreApplication.TestAppContainer(
        fakeService = FakeVideoService(),
        playerControllerInstance = TestHPreApplication.RecordingPlayerController()
    )

    private fun homeLabel() = composeTestRule.activity
        .getString(com.hpre.app.R.string.nav_home)

    private fun subscriptionsLabel() = composeTestRule.activity
        .getString(com.hpre.app.R.string.nav_subscriptions)

    private fun libraryLabel() = composeTestRule.activity
        .getString(com.hpre.app.R.string.nav_library)

    @Test
    fun bottom_nav_items_have_labels_and_48dp_targets() {
        composeTestRule.setContent {
            HPreTheme {
                RootScaffold(container = testContainer())
            }
        }

        for ((tag, label) in listOf(
            "bottom_nav_home" to homeLabel(),
            "bottom_nav_subscriptions" to subscriptionsLabel(),
            "bottom_nav_library" to libraryLabel()
        )) {
            composeTestRule.onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .assert(hasAnyChild(hasText(label)))
        }
    }

    @Test
    fun top_bar_shows_brand_on_home_and_screen_titles_on_other_tabs() {
        composeTestRule.setContent {
            HPreTheme {
                RootScaffold(container = testContainer())
            }
        }

        // Home keeps the brand mark + wordmark.
        composeTestRule.onNodeWithTag("root_top_bar")
            .assert(hasAnyChild(hasText(composeTestRule.activity.getString(com.hpre.app.R.string.app_name))))

        // Other top-level tabs show their screen title, not the logo row.
        composeTestRule.onNodeWithTag("bottom_nav_subscriptions").performClick()
        composeTestRule.onNodeWithTag("root_top_bar")
            .assert(hasAnyChild(hasText(subscriptionsLabel())))

        composeTestRule.onNodeWithTag("bottom_nav_library").performClick()
        composeTestRule.onNodeWithTag("root_top_bar")
            .assert(hasAnyChild(hasText(libraryLabel())))
    }
}
