package com.flowtube.app.ui.shorts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flowtube.app.ui.common.EmptyPane
import com.flowtube.app.ui.common.ErrorPane
import com.flowtube.app.ui.common.LoadingPane
import com.flowtube.app.ui.common.UnavailablePane

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortsScreen(viewModel: ShortsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    when (val current = state) {
        ShortsUiState.Loading -> LoadingPane(modifier, "shorts_loading")
        ShortsUiState.Unavailable -> UnavailablePane("Shorts", modifier, "shorts_unavailable")
        ShortsUiState.Empty -> EmptyPane("No Shorts available", modifier, "shorts_empty")
        is ShortsUiState.Error -> ErrorPane(current.error, viewModel::retry, modifier, "shorts_error")
        is ShortsUiState.Content -> {
            val pagerState = rememberPagerState(pageCount = { current.videos.size })
            LaunchedEffect(pagerState.currentPage, current.videos) {
                current.videos.getOrNull(pagerState.currentPage)?.let(viewModel::activate)
            }
            VerticalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = modifier.fillMaxSize().testTag("shorts_pager")
            ) { page ->
                val video = current.videos[page]
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.BottomStart
                ) {
                    Text(video.title, Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
