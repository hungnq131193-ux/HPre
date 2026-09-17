package com.hpre.app.ui.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object AdaptiveContentPolicy {
    const val MAX_CONTENT_WIDTH_DP = 1200

    fun shouldConstrain(isWatchScreen: Boolean): Boolean = !isWatchScreen
}

@Composable
fun AdaptiveContentHost(
    isWatchScreen: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = if (AdaptiveContentPolicy.shouldConstrain(isWatchScreen)) {
                Modifier
                    .widthIn(max = AdaptiveContentPolicy.MAX_CONTENT_WIDTH_DP.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            } else {
                Modifier.fillMaxSize()
            },
        ) {
            content()
        }
    }
}
