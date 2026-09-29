package com.hpre.app.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hpre.app.R
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.ui.common.DelayedLinearLoadingIndicator
import com.hpre.app.ui.common.InlineEmptyPane
import com.hpre.app.ui.common.InlineErrorPane
import com.hpre.app.ui.common.VideoCard
import com.hpre.app.ui.common.videoListItemKey

const val WATCH_KEY_RELATED_HEADER = "section:related_header"
const val WATCH_KEY_RELATED_PROGRESS = "section:related_progress"
const val WATCH_KEY_RELATED_STATUS = "section:related_status"
const val WATCH_KEY_RELATED_INLINE_ERROR = "section:related_inline_error"

fun LazyListScope.relatedVideoItems(
    state: RefreshableAsyncState<List<VideoSummary>>,
    onVideoClick: (ContentKey) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit = {},
    onVideoSelected: ((VideoSummary) -> Unit)? = null,
    onChannelClick: ((ContentKey) -> Unit)? = null,
    onEnqueueVideo: ((VideoSummary, Boolean) -> Unit)? = null
) {
    item(key = WATCH_KEY_RELATED_HEADER) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("related_videos_section")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.screen_related_videos),
                    style = MaterialTheme.typography.titleSmall
                )
                IconButton(
                    onClick = onRefresh,
                    enabled = !state.isRefreshing && !state.isInitialLoading,
                    modifier = Modifier.size(48.dp).testTag("related_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.action_refresh)
                    )
                }
            }
        }
    }

    if (state.isRefreshing) {
        item(key = WATCH_KEY_RELATED_PROGRESS) {
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .testTag("related_refresh_progress")
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    when {
        state.isInitialLoading && state.value == null -> {
            item(key = WATCH_KEY_RELATED_STATUS) {
                DelayedLinearLoadingIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "related_loading"
                )
            }
        }
        state.error != null && state.value.isNullOrEmpty() -> {
            item(key = WATCH_KEY_RELATED_STATUS) {
                InlineErrorPane(state.error, onRetry, testTag = "related_error")
            }
        }
        state.value != null && state.value.isEmpty() -> {
            item(key = WATCH_KEY_RELATED_STATUS) {
                InlineEmptyPane(
                    stringResource(R.string.related_videos_empty),
                    testTag = "related_empty"
                )
            }
        }
        state.value != null -> {
            val videos = state.value
            if (state.error != null) {
                item(key = WATCH_KEY_RELATED_INLINE_ERROR) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        InlineErrorPane(state.error, onRetry, testTag = "related_error")
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
            items(
                items = videos,
                key = { video -> videoListItemKey(video.key) },
                contentType = { "video" }
            ) { video ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                        VideoCard(
                            video = video,
                            onClick = { if (onVideoSelected != null) onVideoSelected(video) else onVideoClick(it) },
                            onChannelClick = onChannelClick,
                            horizontalPadding = 0.dp
                        )
                    }
                    if (onEnqueueVideo != null) {
                        var menuOpen by remember(video.key) { mutableStateOf(false) }
                        androidx.compose.foundation.layout.Box {
                            IconButton(
                                onClick = { menuOpen = true },
                                modifier = Modifier.testTag("related_enqueue_${'$'}{video.key.nativeId}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.queue_add),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.queue_play_next)) },
                                    onClick = {
                                        onEnqueueVideo(video, true)
                                        menuOpen = false
                                    },
                                    modifier = Modifier.testTag("enqueue_next_${'$'}{video.key.nativeId}")
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.queue_add)) },
                                    onClick = {
                                        onEnqueueVideo(video, false)
                                        menuOpen = false
                                    },
                                    modifier = Modifier.testTag("enqueue_last_${'$'}{video.key.nativeId}")
                                )
                            }
                        }
                    }
                }
            }
        }
        else -> {
            item(key = WATCH_KEY_RELATED_STATUS) {
                InlineEmptyPane(
                    stringResource(R.string.related_videos_empty),
                    testTag = "related_empty"
                )
            }
        }
    }
}
