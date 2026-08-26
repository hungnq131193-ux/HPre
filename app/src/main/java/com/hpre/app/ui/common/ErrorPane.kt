package com.hpre.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.RetryPolicy

sealed interface AsyncState<out T> {
    data object Loading : AsyncState<Nothing>
    data class Content<out T>(val value: T) : AsyncState<T>
    data object Empty : AsyncState<Nothing>
    data class Error(val error: AppError) : AsyncState<Nothing>
}

@Composable
fun LoadingPane(
    modifier: Modifier = Modifier,
    testTag: String = "loading_pane"
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun EmptyPane(
    message: String = "No items available",
    modifier: Modifier = Modifier,
    testTag: String = "empty_pane"
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
fun ErrorPane(
    error: AppError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "error_pane"
) {
    val message = when (error) {
        AppError.NetworkError -> "Network connection error. Check your internet connection."
        AppError.RateLimited -> "Rate limit reached. Please wait a moment."
        AppError.ContentUnavailable -> "This content is unavailable."
        AppError.AgeRestricted -> "This content is age-restricted."
        AppError.GeoRestricted -> "This content is not available in your region."
        AppError.LoginRequired -> "This content requires login which is not supported."
        AppError.StreamExpired -> "Playback stream has expired."
        AppError.UnsupportedFormat -> "This format is not supported."
        AppError.ExtractionFailed -> "Failed to load content from provider."
        AppError.Unknown -> "An unexpected error occurred."
    }

    val isRetryable = RetryPolicy.isManualRetryable(error)

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            if (isRetryable) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier.testTag("error_retry_button")
                ) {
                    Text(text = "Retry")
                }
            }
        }
    }
}

@Composable
fun UnavailablePane(
    featureName: String,
    modifier: Modifier = Modifier,
    testTag: String = "unavailable_pane"
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = featureName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This feature is not supported or not available in the current provider.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
