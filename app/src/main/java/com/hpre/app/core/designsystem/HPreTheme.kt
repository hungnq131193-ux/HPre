package com.hpre.app.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val DarkColorScheme = darkColorScheme(
    primary = HPreDarkPrimary,
    onPrimary = HPreDarkOnPrimary,
    primaryContainer = HPreDarkPrimaryContainer,
    onPrimaryContainer = HPreDarkOnPrimaryContainer,
    secondary = HPreDarkSecondary,
    onSecondary = HPreDarkOnSecondary,
    secondaryContainer = HPreDarkSecondaryContainer,
    onSecondaryContainer = HPreDarkOnSecondaryContainer,
    tertiary = HPreDarkSecondary,
    onTertiary = HPreDarkOnSecondary,
    tertiaryContainer = HPreDarkSecondaryContainer,
    onTertiaryContainer = HPreDarkOnSecondaryContainer,
    background = HPreDarkBackground,
    onBackground = HPreDarkOnSurface,
    surface = HPreDarkBackground,
    onSurface = HPreDarkOnSurface,
    surfaceVariant = HPreDarkSurfaceVariant,
    onSurfaceVariant = HPreDarkOnSurfaceVariant,
    surfaceTint = HPreDarkPrimary,
    surfaceContainerLowest = HPreDarkSurfaceContainerLowest,
    surfaceContainerLow = HPreDarkSurfaceContainerLow,
    surfaceContainer = HPreDarkSurfaceContainer,
    surfaceContainerHigh = HPreDarkSurfaceContainerHigh,
    surfaceContainerHighest = HPreDarkSurfaceContainerHighest,
    surfaceDim = HPreDarkSurfaceDim,
    surfaceBright = HPreDarkSurfaceBright,
    inverseSurface = HPreDarkInverseSurface,
    inverseOnSurface = HPreDarkInverseOnSurface,
    inversePrimary = HPreLightPrimary,
    outline = HPreDarkOutline,
    outlineVariant = HPreDarkOutlineVariant,
    error = HPreDarkError,
    onError = HPreDarkOnError,
    errorContainer = HPreDarkErrorContainer,
    onErrorContainer = HPreDarkOnErrorContainer,
    scrim = Color.Black
)

internal val LightColorScheme = lightColorScheme(
    primary = HPreLightPrimary,
    onPrimary = HPreLightOnPrimary,
    primaryContainer = HPreLightPrimaryContainer,
    onPrimaryContainer = HPreLightOnPrimaryContainer,
    secondary = HPreLightSecondary,
    onSecondary = HPreLightOnSecondary,
    secondaryContainer = HPreLightSecondaryContainer,
    onSecondaryContainer = HPreLightOnSecondaryContainer,
    tertiary = HPreLightSecondary,
    onTertiary = HPreLightOnSecondary,
    tertiaryContainer = HPreLightSecondaryContainer,
    onTertiaryContainer = HPreLightOnSecondaryContainer,
    background = HPreLightBackground,
    onBackground = HPreLightOnSurface,
    surface = HPreLightSurface,
    onSurface = HPreLightOnSurface,
    surfaceVariant = HPreLightSurfaceVariant,
    onSurfaceVariant = HPreLightOnSurfaceVariant,
    surfaceTint = HPreLightPrimary,
    surfaceContainerLowest = HPreLightSurfaceContainerLowest,
    surfaceContainerLow = HPreLightSurfaceContainerLow,
    surfaceContainer = HPreLightSurfaceContainer,
    surfaceContainerHigh = HPreLightSurfaceContainerHigh,
    surfaceContainerHighest = HPreLightSurfaceContainerHighest,
    surfaceDim = HPreLightSurfaceDim,
    surfaceBright = HPreLightSurfaceBright,
    inverseSurface = HPreLightInverseSurface,
    inverseOnSurface = HPreLightInverseOnSurface,
    inversePrimary = HPreDarkPrimary,
    outline = HPreLightOutline,
    outlineVariant = HPreLightOutlineVariant,
    error = HPreLightError,
    onError = HPreLightOnError,
    errorContainer = HPreLightErrorContainer,
    onErrorContainer = HPreLightOnErrorContainer,
    scrim = Color.Black
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
