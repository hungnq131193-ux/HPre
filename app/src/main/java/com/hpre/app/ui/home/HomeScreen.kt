package com.hpre.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import android.os.Looper
import android.os.MessageQueue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.R
import com.hpre.app.ui.common.DelayedLinearLoadingIndicator
import com.hpre.app.ui.common.DelayedLoadingPane
import com.hpre.app.ui.common.EmptyPane
import com.hpre.app.ui.common.ErrorPane
import com.hpre.app.ui.common.VideoCard
import com.hpre.app.ui.common.VideoViewportPrefetchEffect
import com.hpre.app.ui.common.videoPrefetchItemKey

internal interface IdleQueueRegistry {
    fun addIdleHandler(handler: () -> Boolean): Any
    fun removeIdleHandler(token: Any)

    companion object {
        val Default: IdleQueueRegistry = object : IdleQueueRegistry {
            override fun addIdleHandler(handler: () -> Boolean): Any {
                val queue = Looper.myQueue()
                val idleHandler = MessageQueue.IdleHandler { handler() }
                queue.addIdleHandler(idleHandler)
                return idleHandler
            }

            override fun removeIdleHandler(token: Any) {
                if (token is MessageQueue.IdleHandler) {
                    Looper.myQueue().removeIdleHandler(token)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    viewModel: HomeViewModel,
    onVideoClick: (ContentKey) -> Unit,
    modifier: Modifier = Modifier,
    onContentIdle: () -> Unit = {},
    idleQueueRegistry: IdleQueueRegistry = IdleQueueRegistry.Default,
    prefetchVideos: suspend (List<ContentKey>) -> Unit = {},
    onVideoSelected: ((VideoSummary) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chipsState by viewModel.chipsState.collectAsStateWithLifecycle()
    val currentOnContentIdle by rememberUpdatedState(onContentIdle)

    Column(modifier = modifier.fillMaxSize().testTag("home_screen")) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag("home_filter_chips")
        ) {
            itemsIndexed(chipsState.chips) { index, chip ->
                FilterChip(
                    selected = index == chipsState.selectedIndex,
                    onClick = { viewModel.selectChip(index) },
                    label = { Text(chip.label) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("home_filter_chip_$index"),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                        selectedLabelColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (val state = uiState) {
            is HomeUiState.Loading -> {
                // Only reachable when there is genuinely nothing to show (first ever load), and even
                // then the spinner waits so a fast response never flashes one.
                DelayedLoadingPane(testTag = "home_loading")
            }
            is HomeUiState.Empty -> {
                EmptyPane(message = stringResource(R.string.home_empty), testTag = "home_empty")
            }
            is HomeUiState.Error -> {
                ErrorPane(
                    error = state.error,
                    onRetry = { viewModel.retry() },
                    testTag = "home_error"
                )
            }
            is HomeUiState.Content -> {
                val listState = rememberLazyListState()
                VideoViewportPrefetchEffect(
                    listState = listState,
                    orderedKeys = state.content.videos.map { it.key },
                    prefetch = {
                        // No-op prefetch to prevent network queue congestion and fast start video loading
                    }
                )
                DisposableEffect(Unit) {
                    var disposed = false
                    val token = idleQueueRegistry.addIdleHandler {
                        if (!disposed) {
                            currentOnContentIdle()
                        }
                        false
                    }
                    onDispose {
                        disposed = true
                        idleQueueRegistry.removeIdleHandler(token)
                    }
                }

                val pullRefreshState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = state.content.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    state = pullRefreshState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                        modifier = Modifier.fillMaxSize().testTag("home_video_list")
                    ) {
                        items(
                            items = state.content.videos,
                            key = { videoPrefetchItemKey(it.key) }
                        ) { video ->
                            VideoCard(
                                video = video,
                                onClick = { if (onVideoSelected != null) onVideoSelected(video) else onVideoClick(it) }
                            )
                        }
                    }

                    // Switching chips keeps the previous list on screen; this thin bar is the only
                    // signal that new content is on the way, instead of blanking the feed.
                    if (state.content.isLoadingSelection) {
                        DelayedLinearLoadingIndicator(
                            testTag = "home_selection_loading",
                            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        )
                    }
                }
            }
            }
        }
    }
}
