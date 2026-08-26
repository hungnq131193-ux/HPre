package com.hpre.app.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = HPreRed,
    onPrimary = Color.White,
    primaryContainer = HPreRedDark,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF90CAF9),
    onSecondary = Color.Black,
    background = HPreDarkBackground,
    onBackground = HPreDarkOnBackground,
    surface = HPreDarkSurface,
    surfaceContainer = HPreDarkSurfaceContainer,
    surfaceContainerHighest = HPreDarkSurfaceVariant,
    onSurface = HPreDarkOnSurface,
    surfaceVariant = HPreDarkSurfaceVariant,
    onSurfaceVariant = HPreDarkOnSurfaceVariant,
    error = Color(0xFFCF6679),
    onError = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = HPreRed,
    onPrimary = Color.White,
    primaryContainer = HPreRedLight,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF1976D2),
    onSecondary = Color.White,
    background = HPreLightBackground,
    onBackground = HPreLightOnBackground,
    surface = HPreLightSurface,
    surfaceContainer = HPreLightSurface,
    surfaceContainerHighest = HPreLightSurfaceVariant,
    onSurface = HPreLightOnSurface,
    surfaceVariant = HPreLightSurfaceVariant,
    onSurfaceVariant = HPreLightOnSurfaceVariant,
    error = Color(0xFFB00020),
    onError = Color.White
)

@Composable
fun HPreTheme(
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
