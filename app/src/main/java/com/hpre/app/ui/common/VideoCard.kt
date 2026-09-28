package com.hpre.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.R
import com.hpre.app.core.designsystem.HPreLiveBadge
import com.hpre.app.core.designsystem.HPreOnLiveBadge
import com.hpre.app.core.designsystem.HPreShapes
import com.hpre.app.core.designsystem.HPreSpacing

@Composable
fun VideoCard(
    video: VideoSummary,
    onClick: (ContentKey) -> Unit,
    modifier: Modifier = Modifier,
    onChannelClick: ((ContentKey) -> Unit)? = null,
    compact: Boolean = false,
    horizontalPadding: Dp = HPreSpacing.Large
) {
    if (compact) {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            // Horizontal rows stop fitting on narrow screens or at large font
            // scales; fall back to the vertical card so the title stays readable.
            val density = LocalDensity.current
            val effectiveWidthDp = constraints.maxWidth / density.density / density.fontScale
            if (effectiveWidthDp < 280f) {
                LargeVideoCard(video, onClick, onChannelClick, Modifier, horizontalPadding)
            } else {
                CompactVideoCard(video, onClick, onChannelClick, Modifier, horizontalPadding)
            }
        }
    } else {
        LargeVideoCard(video, onClick, onChannelClick, modifier, horizontalPadding)
    }
}

@Composable
private fun LargeVideoCard(
    video: VideoSummary,
    onClick: (ContentKey) -> Unit,
    onChannelClick: ((ContentKey) -> Unit)?,
    modifier: Modifier,
    horizontalPadding: Dp
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(video.key) }
            .testTag("video_card_${video.key.nativeId}")
            .padding(top = HPreSpacing.Compact, bottom = HPreSpacing.Section - HPreSpacing.Compact)
    ) {
        VideoThumbnail(
            video = video,
            onClick = onClick,
            modifier = Modifier
                .padding(horizontal = horizontalPadding)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )

        Spacer(modifier = Modifier.height(HPreSpacing.Medium))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
        ) {
            val canOpenChannel = onChannelClick != null && video.channelKey != null
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .then(
                        if (canOpenChannel) {
                            Modifier.clickable { onChannelClick?.invoke(video.channelKey!!) }
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                ChannelAvatar(video = video, size = 36.dp)
            }

            Spacer(modifier = Modifier.width(HPreSpacing.Small))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                VideoMetadata(video = video, onChannelClick = onChannelClick)
            }
        }
    }
}

@Composable
private fun CompactVideoCard(
    video: VideoSummary,
    onClick: (ContentKey) -> Unit,
    onChannelClick: ((ContentKey) -> Unit)?,
    modifier: Modifier,
    horizontalPadding: Dp
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(video.key) }
            .testTag("video_card_${video.key.nativeId}")
            .padding(horizontal = horizontalPadding)
            .padding(bottom = HPreSpacing.Medium)
    ) {
        VideoThumbnail(
            video = video,
            onClick = onClick,
            shape = RoundedCornerShape(HPreShapes.Thumbnail),
            modifier = Modifier
                .width(144.dp)
                .height(81.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = HPreSpacing.Medium)
        ) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            VideoMetadata(video = video, onChannelClick = onChannelClick)
        }
    }
}

@Composable
private fun VideoThumbnail(
    video: VideoSummary,
    onClick: (ContentKey) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(HPreShapes.Card)
) {
    val fallbackPainter = rememberVectorPainter(Icons.Default.PlayArrow)
    val playLabel = stringResource(R.string.action_play)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { onClick(video.key) }
            .semantics { contentDescription = playLabel }
            .testTag("video_card_${video.key.nativeId}_thumbnail")
    ) {
        if (video.thumbnailUrl != null) {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("video_thumbnail"),
                contentScale = ContentScale.Crop,
                placeholder = fallbackPainter,
                error = fallbackPainter
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        val durationText = VideoFormat.duration(video.durationSeconds)
        when {
            video.isLive -> VideoBadge(
                text = stringResource(R.string.video_live),
                background = HPreLiveBadge,
                contentColor = HPreOnLiveBadge,
                showDot = true,
                modifier = Modifier.align(Alignment.BottomEnd).padding(HPreSpacing.Small)
            )
            durationText.isNotEmpty() -> VideoBadge(
                text = durationText,
                background = Color.Black.copy(alpha = 0.75f),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(HPreSpacing.Small)
            )
        }
    }
}

@Composable
private fun ChannelAvatar(video: VideoSummary, size: Dp) {
    if (video.channelAvatarUrl != null) {
        AsyncImage(
            model = video.channelAvatarUrl,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Icon(
            imageVector = Icons.Default.AccountCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size)
        )
    }
}

@Composable
private fun VideoMetadata(
    video: VideoSummary,
    onChannelClick: ((ContentKey) -> Unit)?
) {
    val canOpenChannel = onChannelClick != null && video.channelKey != null

    if (!video.channelName.isNullOrBlank()) {
        Text(
            text = video.channelName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .testTag("video_card_${video.key.nativeId}_channel")
                .then(
                    if (canOpenChannel) {
                        Modifier.clickable { onChannelClick?.invoke(video.channelKey!!) }
                    } else {
                        Modifier
                    }
                )
        )
    }

    val viewsText = VideoFormat.viewCount(video.viewCount)
    val now = remember(video.key, video.publishedTimestamp) { System.currentTimeMillis() }
    val ageText = VideoFormat.age(video.publishedTimestamp, now)
    val metaParts = listOfNotNull(
        viewsText.takeIf(String::isNotBlank),
        ageText.takeIf(String::isNotBlank)
    )

    if (metaParts.isNotEmpty()) {
        Text(
            text = metaParts.joinToString(" • "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun VideoBadge(
    text: String,
    background: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    showDot: Boolean = false
) {
    Row(
        modifier = modifier
            .background(background, RoundedCornerShape(HPreShapes.Badge))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(contentColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(HPreSpacing.Compact))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}

internal fun videoListItemKey(contentKey: ContentKey): String =
    "video:${contentKey.serviceId}:" + java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(contentKey.nativeId.toByteArray(Charsets.UTF_8))
