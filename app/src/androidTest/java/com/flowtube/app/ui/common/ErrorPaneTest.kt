package com.flowtube.app.ui.common

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowtube.app.core.designsystem.FlowTubeTheme
import com.flowtube.app.core.error.AppError
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ErrorPaneTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun retry_button_is_shown_for_network_and_extraction_failed_errors() {
        val errorState = mutableStateOf<AppError>(AppError.NetworkError)
        var retryClicked = 0

        composeTestRule.setContent {
            FlowTubeTheme {
                ErrorPane(
                    error = errorState.value,
                    onRetry = { retryClicked++ }
                )
            }
        }

        // NetworkError: retry button shown and clickable
        composeTestRule.onNodeWithTag("error_retry_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("error_retry_button").performClick()
        assertEquals(1, retryClicked)

        // ExtractionFailed: retry button shown and clickable
        errorState.value = AppError.ExtractionFailed
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("error_retry_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("error_retry_button").performClick()
        assertEquals(2, retryClicked)
    }

    @Test
    fun retry_button_is_not_shown_for_non_retryable_errors() {
        val errorState = mutableStateOf<AppError>(AppError.RateLimited)

        composeTestRule.setContent {
            FlowTubeTheme {
                ErrorPane(
                    error = errorState.value,
                    onRetry = {}
                )
            }
        }

        val nonRetryable = listOf(
            AppError.RateLimited,
            AppError.ContentUnavailable,
            AppError.AgeRestricted,
            AppError.GeoRestricted,
            AppError.LoginRequired,
            AppError.UnsupportedFormat,
            AppError.StreamExpired,
            AppError.Unknown
        )

        for (error in nonRetryable) {
            errorState.value = error
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("error_retry_button").assertDoesNotExist()
        }
    }
}
