package com.hpre.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hpre.app.model.ContentKey
import com.hpre.app.ui.common.EmptyPane
import com.hpre.app.ui.common.ErrorPane
import com.hpre.app.ui.common.LoadingPane
import com.hpre.app.ui.common.VideoCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onVideoClick: (ContentKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize().testTag("home_screen")) {
        when (val state = uiState) {
            is HomeUiState.Loading -> {
                LoadingPane(testTag = "home_loading")
            }
            is HomeUiState.Empty -> {
                EmptyPane(message = "No recommendations available", testTag = "home_empty")
            }
            is HomeUiState.Error -> {
                ErrorPane(
                    error = state.error,
                    onRetry = { viewModel.retry() },
                    testTag = "home_error"
                )
            }
            is HomeUiState.Content -> {
                val pullRefreshState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = false,
                    onRefresh = { viewModel.load(forceRefresh = true) },
                    state = pullRefreshState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                        modifier = Modifier.fillMaxSize().testTag("home_video_list")
                    ) {
                        items(
                            items = state.videos,
                            key = { it.key.toString() }
                        ) { video ->
                            VideoCard(
                                video = video,
                                onClick = onVideoClick
                            )
                        }
                    }
                }
            }
        }
    }
}
