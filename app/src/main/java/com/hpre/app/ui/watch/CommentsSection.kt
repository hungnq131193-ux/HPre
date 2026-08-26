package com.hpre.app.ui.watch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hpre.app.model.CommentPage
import com.hpre.app.ui.common.AsyncState
import com.hpre.app.ui.common.EmptyPane
import com.hpre.app.ui.common.ErrorPane
import com.hpre.app.ui.common.LoadingPane

@Composable
fun CommentsSection(
    state: AsyncState<CommentPage>,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().testTag("comments_section")) {
        Text("Comments")
        when (state) {
            AsyncState.Loading -> LoadingPane(testTag = "comments_loading")
            AsyncState.Empty -> EmptyPane("Comments unavailable or empty", testTag = "comments_empty")
            is AsyncState.Error -> ErrorPane(state.error, onRetry, testTag = "comments_error")
            is AsyncState.Content -> {
                state.value.comments.forEach { comment ->
                    Column(Modifier.fillMaxWidth().testTag("comment_${comment.commentId}")) {
                        Text(comment.authorName, style = MaterialTheme.typography.labelLarge)
                        Text(comment.commentText, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                    }
                }
                if (state.value.nextPageToken != null) {
                    Button(onClick = onLoadMore, modifier = Modifier.testTag("comments_load_more")) {
                        Text("Load more")
                    }
                }
            }
        }
    }
}
