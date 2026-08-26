package com.hpre.app.ui.watch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.hpre.app.R
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.ui.common.AsyncState
import com.hpre.app.ui.common.EmptyPane
import com.hpre.app.ui.common.ErrorPane
import com.hpre.app.ui.common.LoadingPane
import com.hpre.app.ui.common.VideoCard

@Composable
fun RelatedVideosSection(
    state: AsyncState<List<VideoSummary>>,
    onVideoClick: (ContentKey) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().testTag("related_videos_section")) {
        Text(stringResource(R.string.screen_related_videos))
        when (state) {
            AsyncState.Loading -> LoadingPane(testTag = "related_loading")
            AsyncState.Empty -> EmptyPane(
                stringResource(R.string.related_videos_empty),
                testTag = "related_empty"
            )
            is AsyncState.Error -> ErrorPane(state.error, onRetry, testTag = "related_error")
            is AsyncState.Content -> state.value.forEach { video ->
                VideoCard(video = video, onClick = onVideoClick)
            }
        }
    }
}
