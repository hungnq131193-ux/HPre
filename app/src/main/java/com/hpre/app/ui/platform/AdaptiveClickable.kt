package com.hpre.app.ui.platform

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun Modifier.hpreAdaptiveClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val isRemote = LocalHPreInputMode.current == HPreInputMode.REMOTE
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusStroke = 2.dp
    val cornerRadius = 8.dp
    val focusColor = MaterialTheme.colorScheme.primary
    val focusModifier = if (isRemote) {
        Modifier
            .drawWithContent {
                drawContent()
                if (enabled && isFocused) {
                    val strokeWidth = focusStroke.toPx()
                    drawRoundRect(
                        color = focusColor,
                        topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                        size = Size((size.width - strokeWidth).coerceAtLeast(0f), (size.height - strokeWidth).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(cornerRadius.toPx()),
                        style = Stroke(width = strokeWidth),
                    )
                }
            }
    } else {
        Modifier
    }

    return this
        // clickable owns the single focus target and standard D-pad activation.
        .clickable(
            interactionSource = interactionSource,
            indication = androidx.compose.foundation.LocalIndication.current,
            enabled = enabled,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
        .then(focusModifier)
}
