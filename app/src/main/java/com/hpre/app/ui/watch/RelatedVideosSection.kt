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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hpre.app.R
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.ui.common.EmptyPane
import com.hpre.app.ui.common.ErrorPane
import com.hpre.app.ui.common.LoadingPane
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
    onVideoSelected: ((VideoSummary) -> Unit)? = null
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
                        contentDescription = stringResource(R.string.action_retry)
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
                LoadingPane(testTag = "related_loading")
            }
        }
        state.error != null && state.value.isNullOrEmpty() -> {
            item(key = WATCH_KEY_RELATED_STATUS) {
                ErrorPane(state.error, onRetry, testTag = "related_error")
            }
        }
        state.value != null && state.value.isEmpty() -> {
            item(key = WATCH_KEY_RELATED_STATUS) {
                EmptyPane(
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
                        ErrorPane(state.error, onRetry, testTag = "related_error")
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
            items(
                items = videos,
                key = { video -> videoListItemKey(video.key) }
            ) { video ->
                VideoCard(
                    video = video,
                    onClick = { if (onVideoSelected != null) onVideoSelected(video) else onVideoClick(it) }
                )
            }
        }
        else -> {
            item(key = WATCH_KEY_RELATED_STATUS) {
                EmptyPane(
                    stringResource(R.string.related_videos_empty),
                    testTag = "related_empty"
                )
            }
        }
    }
}
