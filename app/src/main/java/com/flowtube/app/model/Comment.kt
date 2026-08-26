package com.flowtube.app.model

data class Comment(
    val commentId: String,
    val authorName: String,
    val authorAvatarUrl: String?,
    val channelKey: ContentKey?,
    val commentText: String,
    val publishedTimestamp: Long?,
    val likeCount: Long?,
    val replyCount: Long? = null
)

data class CommentPage(
    val comments: List<Comment>,
    val nextPageToken: PageToken? = null
)
