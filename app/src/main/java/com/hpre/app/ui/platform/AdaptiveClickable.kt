package com.hpre.app.ui.platform

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
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
    var isFocused by remember { mutableStateOf(false) }
    val focusStroke = 2.dp
    val cornerRadius = 8.dp
    val focusColor = MaterialTheme.colorScheme.primary
    val focusModifier = if (isRemote) {
        Modifier
            .focusable(enabled = enabled)
            .onFocusChanged { isFocused = it.isFocused }
            .drawBehind {
                if (isFocused) {
                    val strokeWidth = focusStroke.toPx()
                    drawRoundRect(
                        color = focusColor,
                        topLeft = Offset(-strokeWidth / 2, -strokeWidth / 2),
                        size = Size(size.width + strokeWidth, size.height + strokeWidth),
                        cornerRadius = CornerRadius(cornerRadius.toPx()),
                        style = Stroke(width = strokeWidth),
                    )
                }
            }
    } else {
        Modifier
    }

    return this
        .clickable(enabled = enabled, onClickLabel = onClickLabel, onClick = onClick)
        .then(focusModifier)
}
