package com.hpre.app.ui.watch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hpre.app.R
import com.hpre.app.model.CommentPage
import com.hpre.app.ui.common.AsyncState
import com.hpre.app.ui.common.EmptyPane
import com.hpre.app.ui.common.ErrorPane
import com.hpre.app.ui.common.LoadingPane

const val WATCH_KEY_COMMENTS_HEADER = "section:comments_header"
const val WATCH_KEY_COMMENTS_STATUS = "section:comments_status"
const val WATCH_KEY_COMMENTS_LOAD_MORE = "section:comments_load_more_sentinel"

fun LazyListScope.commentsItems(
    state: AsyncState<CommentPage>,
    onRetry: () -> Unit
) {
    item(key = WATCH_KEY_COMMENTS_HEADER) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("comments_section")
        ) {
            Text(
                text = stringResource(R.string.screen_comments),
                style = MaterialTheme.typography.titleSmall
            )
        }
    }

    when (state) {
        AsyncState.Loading -> {
            item(key = WATCH_KEY_COMMENTS_STATUS) {
                LoadingPane(testTag = "comments_loading")
            }
        }
        AsyncState.Empty -> {
            item(key = WATCH_KEY_COMMENTS_STATUS) {
                EmptyPane(
                    message = stringResource(R.string.comments_empty),
                    testTag = "comments_empty"
                )
            }
        }
        is AsyncState.Error -> {
            item(key = WATCH_KEY_COMMENTS_STATUS) {
                ErrorPane(
                    error = state.error,
                    onRetry = onRetry,
                    testTag = "comments_error"
                )
            }
        }
        is AsyncState.Content -> {
            items(
                items = state.value.comments,
                key = { comment -> "comment:${comment.commentId}" }
            ) { comment ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("comment_${comment.commentId}")
                ) {
                    Text(
                        text = comment.authorName,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = comment.commentText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (state.value.nextPageToken != null) {
                item(key = WATCH_KEY_COMMENTS_LOAD_MORE) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .testTag("comments_load_more_sentinel")
                    )
                }
            }
        }
    }
}
