package com.hpre.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.R
import com.hpre.app.core.designsystem.HPreShapes
import com.hpre.app.core.designsystem.HPreSpacing

@Composable
fun VideoCard(
    video: VideoSummary,
    onClick: (ContentKey) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(video.key) }
            .testTag("video_card_${video.key.nativeId}")
            .padding(bottom = HPreSpacing.Large)
    ) {
        // Thumbnail container with 16:9 ratio
        Box(
            modifier = Modifier
                .padding(horizontal = HPreSpacing.Medium)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(HPreShapes.Card))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            if (video.thumbnailUrl != null) {
                AsyncImage(
                    model = video.thumbnailUrl,
                    contentDescription = video.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("video_thumbnail"),
                    contentScale = ContentScale.Crop
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

            // Duration badge
            val durationText = VideoFormat.duration(video.durationSeconds)
            when {
                video.isLive -> VideoBadge(
                    text = stringResource(R.string.video_live),
                    background = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(HPreSpacing.Small)
                )
                durationText.isNotEmpty() -> VideoBadge(
                    text = durationText,
                    background = Color.Black.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(HPreSpacing.Small)
                )
            }
        }

        Spacer(modifier = Modifier.height(HPreSpacing.Small))

        // Info Row (Avatar + Title/Metadata)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HPreSpacing.Medium)
        ) {
            // Channel Avatar
            if (video.channelAvatarUrl != null) {
                AsyncImage(
                    model = video.channelAvatarUrl,
                    contentDescription = video.channelName,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.width(HPreSpacing.Medium))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val viewsText = VideoFormat.viewCount(video.viewCount)
                val now = remember(video.key, video.publishedTimestamp) { System.currentTimeMillis() }
                val ageText = VideoFormat.age(video.publishedTimestamp, now)
                val metaParts = listOfNotNull(
                    video.channelName?.takeIf(String::isNotBlank),
                    viewsText.takeIf(String::isNotBlank),
                    ageText.takeIf(String::isNotBlank)
                )

                if (metaParts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = metaParts.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoBadge(
    text: String,
    background: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(HPreShapes.Badge))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White
        )
    }
}

internal fun videoListItemKey(contentKey: ContentKey): String =
    "video:${contentKey.serviceId}:" + java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(contentKey.nativeId.toByteArray(Charsets.UTF_8))

