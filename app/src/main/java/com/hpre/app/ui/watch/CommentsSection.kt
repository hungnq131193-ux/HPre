package com.hpre.app.ui.watch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hpre.app.R
import com.hpre.app.model.Comment
import com.hpre.app.model.CommentPage
import com.hpre.app.ui.common.AsyncState
import com.hpre.app.ui.common.EmptyPane
import com.hpre.app.ui.common.ErrorPane
import com.hpre.app.ui.common.LoadingPane
import com.hpre.app.ui.common.VideoFormat
import java.text.NumberFormat

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
                CommentRow(comment)
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

@Composable
private fun CommentRow(comment: Comment) {
    val age = remember(comment.publishedTimestamp) {
        VideoFormat.age(comment.publishedTimestamp, System.currentTimeMillis())
    }
    val initial = remember(comment.authorName) {
        comment.authorName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    }
    val likes = remember(comment.likeCount) {
        comment.likeCount?.takeIf { it >= 0L }?.let(NumberFormat.getIntegerInstance()::format)
    }
    val replyCount = comment.replyCount?.takeIf { it > 0L }
    val avatarDescription = stringResource(R.string.comments_author_avatar, comment.authorName)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("comment_${comment.commentId}")
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (!comment.authorAvatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = comment.authorAvatarUrl,
                    contentDescription = avatarDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .testTag("comment_avatar_${comment.commentId}")
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initial,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .semantics { contentDescription = avatarDescription }
                            .testTag("comment_avatar_${comment.commentId}")
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = comment.authorName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("comment_author_${comment.commentId}")
                    )
                    if (age.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = age,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("comment_age_${comment.commentId}")
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = comment.commentText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("comment_body_${comment.commentId}")
                )
                if (likes != null || replyCount != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (likes != null) {
                            Text(
                                text = stringResource(R.string.comments_like_count, likes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("comment_likes_${comment.commentId}")
                            )
                        }
                        if (replyCount != null) {
                            val quantity = replyCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            Text(
                                text = pluralStringResource(
                                    R.plurals.comments_reply_count,
                                    quantity,
                                    replyCount
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("comment_replies_${comment.commentId}")
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
