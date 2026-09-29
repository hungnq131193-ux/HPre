package com.hpre.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val HPreBrandBrush: Brush = Brush.linearGradient(listOf(HPreBrandIndigo, HPreBrandViolet, HPreBrandFuchsia))

@Composable
fun HPreBrandMark(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(HPreBrandBrush, RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(com.hpre.app.R.drawable.ic_hpre_mark),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.72f)
        )
    }
}

@Composable
fun HPreGradientTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(HPreBrandBrush, RoundedCornerShape(HPreShapes.Thumbnail)),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White)
    }
}

@Composable
fun HPreWordmark(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge.copy(
            brush = HPreBrandBrush,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp
        ),
        modifier = modifier
    )
}

/** Faint brand wash behind top-level chrome; a static gradient, so it costs one draw call. */
@Composable
fun hpreChromeBrush(): Brush {
    val top = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val bottom = MaterialTheme.colorScheme.background
    return remember(top, bottom) { Brush.verticalGradient(listOf(top, bottom)) }
}
