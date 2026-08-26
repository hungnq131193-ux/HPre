package com.flowtube.app.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = FlowTubeRed,
    onPrimary = Color.White,
    primaryContainer = FlowTubeRedDark,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF90CAF9),
    onSecondary = Color.Black,
    background = FlowTubeDarkBackground,
    onBackground = FlowTubeDarkOnBackground,
    surface = FlowTubeDarkSurface,
    onSurface = FlowTubeDarkOnSurface,
    surfaceVariant = FlowTubeDarkSurfaceVariant,
    onSurfaceVariant = FlowTubeDarkOnSurfaceVariant,
    error = Color(0xFFCF6679),
    onError = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = FlowTubeRed,
    onPrimary = Color.White,
    primaryContainer = FlowTubeRedLight,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF1976D2),
    onSecondary = Color.White,
    background = FlowTubeLightBackground,
    onBackground = FlowTubeLightOnBackground,
    surface = FlowTubeLightSurface,
    onSurface = FlowTubeLightOnSurface,
    surfaceVariant = FlowTubeLightSurfaceVariant,
    onSurfaceVariant = FlowTubeLightOnSurfaceVariant,
    error = Color(0xFFB00020),
    onError = Color.White
)

@Composable
fun FlowTubeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
