package com.hpre.app.ui.home

import android.os.Looper
import android.os.MessageQueue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import com.hpre.app.ui.common.HPreChip
import com.hpre.app.ui.common.InlineErrorPane
import com.hpre.app.ui.common.AdaptiveVideoGridCells
import com.hpre.app.ui.common.videoGridItems

internal interface IdleQueueRegistry {
    fun addIdleHandler(handler: () -> Boolean): Any
    fun removeIdleHandler(token: Any)

    companion object {
        val Default: IdleQueueRegistry = object : IdleQueueRegistry {
            override fun addIdleHandler(handler: () -> Boolean): Any {
                val idleHandler = MessageQueue.IdleHandler { handler() }
                Looper.myQueue().addIdleHandler(idleHandler)
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

internal fun registerOneShotIdleCallback(
    registry: IdleQueueRegistry,
    callback: () -> Unit
): () -> Unit {
    var active = true
    val token = registry.addIdleHandler {
        if (active) {
            active = false
            callback()
        }
        false
    }
    return {
        active = false
        registry.removeIdleHandler(token)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    viewModel: HomeViewModel,
    onVideoClick: (ContentKey) -> Unit,
    modifier: Modifier = Modifier,
    onVideoSelected: ((VideoSummary) -> Unit)? = null,
    onChannelClick: ((ContentKey) -> Unit)? = null,
    onContentIdle: () -> Unit = {},
    idleQueueRegistry: IdleQueueRegistry = IdleQueueRegistry.Default
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chipsState by viewModel.chipsState.collectAsStateWithLifecycle()
    val currentOnContentIdle by rememberUpdatedState(onContentIdle)

    Column(modifier = modifier.fillMaxSize().testTag("home_screen")) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .testTag("home_filter_chips")
        ) {
            itemsIndexed(chipsState.chips) { index, chip ->
                HPreChip(
                    label = chip.label,
                    selected = index == chipsState.selectedIndex,
                    onClick = { viewModel.selectChip(index) },
                    modifier = Modifier.testTag("home_filter_chip_$index")
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
                DisposableEffect(Unit) {
                    val cancel = registerOneShotIdleCallback(idleQueueRegistry) {
                        currentOnContentIdle()
                    }
                    onDispose(cancel)
                }

                val gridState = rememberLazyGridState()
                val pullRefreshState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = state.content.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    state = pullRefreshState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyVerticalGrid(
                        columns = AdaptiveVideoGridCells,
                        state = gridState,
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize().testTag("home_video_list")
                    ) {
                        videoGridItems(
                            videos = state.content.videos,
                            onClick = { video ->
                                if (onVideoSelected != null) onVideoSelected(video) else onVideoClick(video.key)
                            },
                            onChannelClick = onChannelClick
                        )
                    }

                    // Switching chips keeps the previous list on screen; a thin bar plus a label
                    // naming the selected chip is the only signal that new content is on the way,
                    // instead of blanking the feed.
                    if (state.content.isLoadingSelection) {
                        Column(
                            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        ) {
                            DelayedLinearLoadingIndicator(
                                testTag = "home_selection_loading",
                                modifier = Modifier.fillMaxWidth()
                            )
                            val loadingLabel = chipsState.chips
                                .getOrNull(chipsState.selectedIndex)?.label
                            if (loadingLabel != null) {
                                Text(
                                    text = stringResource(R.string.home_loading_chip, loadingLabel),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .padding(top = 4.dp)
                                        .testTag("home_selection_loading_label")
                                )
                            }
                        }
                    }
                    state.content.refreshError?.let { error ->
                        InlineErrorPane(
                            error = error,
                            onRetry = { viewModel.refresh() },
                            testTag = "home_refresh_error",
                            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        )
                    }
                }
            }
            }
        }
    }
}
