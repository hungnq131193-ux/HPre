package com.hpre.app.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the HPre visual tokens (midnight / sunset palette).
 * Color roles must match the spec exactly and text roles must keep WCAG 4.5:1
 * contrast against their background role.
 */
class HPreThemeTokensTest {

    private fun luminance(color: Color): Double {
        fun channel(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun dark_scheme_uses_approved_palette() {
        val scheme = DarkColorScheme
        assertEquals(Color(0xFF0D0E17), scheme.background)
        assertEquals(Color(0xFF0D0E17), scheme.surface)
        assertEquals(Color(0xFF14151F), scheme.surfaceContainerLow)
        assertEquals(Color(0xFF1A1C28), scheme.surfaceContainer)
        assertEquals(Color(0xFF222433), scheme.surfaceContainerHigh)
        assertEquals(Color(0xFF2A2D3E), scheme.surfaceContainerHighest)
        assertEquals(Color(0xFFF3F3F8), scheme.onSurface)
        assertEquals(Color(0xFFAEB1C6), scheme.onSurfaceVariant)
        assertEquals(Color(0xFFFF8A80), scheme.primary)
        assertEquals(Color(0xFF3F0008), scheme.onPrimary)
        assertEquals(Color(0xFF5E1726), scheme.primaryContainer)
        assertEquals(Color(0xFFFFDADE), scheme.onPrimaryContainer)
    }

    @Test
    fun light_scheme_uses_approved_palette() {
        val scheme = LightColorScheme
        assertEquals(Color(0xFFFAF8FB), scheme.background)
        assertEquals(Color(0xFFFFFFFF), scheme.surface)
        assertEquals(Color(0xFFF6F3F8), scheme.surfaceContainerLow)
        assertEquals(Color(0xFFF0ECF3), scheme.surfaceContainer)
        assertEquals(Color(0xFFEAE5EE), scheme.surfaceContainerHigh)
        assertEquals(Color(0xFFE3DEE9), scheme.surfaceContainerHighest)
        assertEquals(Color(0xFF1B1A22), scheme.onSurface)
        assertEquals(Color(0xFF585566), scheme.onSurfaceVariant)
        assertEquals(Color(0xFFC62838), scheme.primary)
        assertEquals(Color(0xFFFFFFFF), scheme.onPrimary)
        assertEquals(Color(0xFFFFE0E2), scheme.primaryContainer)
        assertEquals(Color(0xFF40000C), scheme.onPrimaryContainer)
    }

    @Test
    fun text_roles_keep_45_to_1_contrast_in_both_schemes() {
        for (scheme in listOf(DarkColorScheme, LightColorScheme)) {
            val pairs = listOf(
                "onSurface/surface" to (scheme.onSurface to scheme.surface),
                "onSurfaceVariant/surface" to (scheme.onSurfaceVariant to scheme.surface),
                "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
                "onPrimaryContainer/primaryContainer" to
                    (scheme.onPrimaryContainer to scheme.primaryContainer),
                "onSecondaryContainer/secondaryContainer" to
                    (scheme.onSecondaryContainer to scheme.secondaryContainer),
                "onTertiaryContainer/tertiaryContainer" to
                    (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                "onSurface/surfaceContainerHigh" to
                    (scheme.onSurface to scheme.surfaceContainerHigh),
                "onSurfaceVariant/surfaceContainerHigh" to
                    (scheme.onSurfaceVariant to scheme.surfaceContainerHigh),
                "primary/surfaceContainerLow" to (scheme.primary to scheme.surfaceContainerLow),
                "onError/error" to (scheme.onError to scheme.error),
                "onErrorContainer/errorContainer" to
                    (scheme.onErrorContainer to scheme.errorContainer)
            )
            for ((name, pair) in pairs) {
                val ratio = contrast(pair.first, pair.second)
                assertTrue("$name contrast $ratio < 4.5", ratio >= 4.5)
            }
        }
    }

    @Test
    fun typography_uses_approved_scale() {
        assertEquals(24.sp, Typography.headlineSmall.fontSize)
        assertEquals(30.sp, Typography.headlineSmall.lineHeight)
        assertEquals(FontWeight.Bold, Typography.headlineSmall.fontWeight)

        assertEquals(16.sp, Typography.titleMedium.fontSize)
        assertEquals(22.sp, Typography.titleMedium.lineHeight)
        assertEquals(FontWeight.SemiBold, Typography.titleMedium.fontWeight)

        assertEquals(14.sp, Typography.bodyMedium.fontSize)
        assertEquals(20.sp, Typography.bodyMedium.lineHeight)
        assertEquals(FontWeight.Normal, Typography.bodyMedium.fontWeight)

        assertEquals(12.sp, Typography.bodySmall.fontSize)
        assertEquals(18.sp, Typography.bodySmall.lineHeight)
        // Badges/metadata must stay readable: no 10sp for regularly-read info.
        assertTrue(Typography.labelSmall.fontSize >= 11.sp)
        assertEquals(TextUnitType.Sp, Typography.labelSmall.fontSize.type)
    }

    @Test
    fun shape_and_spacing_tokens_cover_component_roles() {
        assertEquals(10.dp, HPreShapes.Thumbnail)
        assertEquals(16.dp, HPreShapes.Card)
        assertEquals(20.dp, HPreShapes.Group)
        assertEquals(28.dp, HPreShapes.Sheet)
        assertEquals(48.dp, MinimumTouchTarget)
        assertEquals(16.dp, HPreSpacing.Large)
        assertEquals(24.dp, HPreSpacing.Section)
    }

    @Test
    fun live_badge_uses_dedicated_status_token_with_contrast() {
        // LIVE keeps a dedicated status token (not colorScheme.error) so error
        // stays semantic; badge text must read on the badge background.
        assertTrue(
            "LIVE badge contrast ${contrast(HPreOnLiveBadge, HPreLiveBadge)} < 4.5",
            contrast(HPreOnLiveBadge, HPreLiveBadge) >= 4.5
        )
        assertTrue(HPreLiveBadge != Color.Unspecified)
        assertTrue(HPreOnLiveBadge != Color.Unspecified)
    }
}
