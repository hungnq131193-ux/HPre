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
 * Locks the visual tokens approved in the UI/UX upgrade plan (section 2.3).
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
        assertEquals(Color(0xFF101114), scheme.background)
        assertEquals(Color(0xFF101114), scheme.surface)
        assertEquals(Color(0xFF17191D), scheme.surfaceContainerLow)
        assertEquals(Color(0xFF1E2025), scheme.surfaceContainer)
        assertEquals(Color(0xFF25282E), scheme.surfaceContainerHigh)
        assertEquals(Color(0xFF2D3038), scheme.surfaceContainerHighest)
        assertEquals(Color(0xFFF4F4F5), scheme.onSurface)
        assertEquals(Color(0xFFB4B6BF), scheme.onSurfaceVariant)
        assertEquals(Color(0xFFFFB4AB), scheme.primary)
        assertEquals(Color(0xFF690005), scheme.onPrimary)
        assertEquals(Color(0xFF8C1D18), scheme.primaryContainer)
        assertEquals(Color(0xFFFFDAD6), scheme.onPrimaryContainer)
    }

    @Test
    fun light_scheme_uses_approved_palette() {
        val scheme = LightColorScheme
        assertEquals(Color(0xFFFAFAFC), scheme.background)
        assertEquals(Color(0xFFFFFFFF), scheme.surface)
        assertEquals(Color(0xFFF5F5F8), scheme.surfaceContainerLow)
        assertEquals(Color(0xFFEEEEF3), scheme.surfaceContainer)
        assertEquals(Color(0xFFE8E8EE), scheme.surfaceContainerHigh)
        assertEquals(Color(0xFFE1E1E8), scheme.surfaceContainerHighest)
        assertEquals(Color(0xFF18191D), scheme.onSurface)
        assertEquals(Color(0xFF5F626D), scheme.onSurfaceVariant)
        assertEquals(Color(0xFFB32624), scheme.primary)
        assertEquals(Color(0xFFFFFFFF), scheme.onPrimary)
        assertEquals(Color(0xFFFFDAD6), scheme.primaryContainer)
        assertEquals(Color(0xFF410002), scheme.onPrimaryContainer)
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
        assertEquals(22.sp, Typography.headlineSmall.fontSize)
        assertEquals(28.sp, Typography.headlineSmall.lineHeight)
        assertEquals(FontWeight.SemiBold, Typography.headlineSmall.fontWeight)

        assertEquals(16.sp, Typography.titleMedium.fontSize)
        assertEquals(22.sp, Typography.titleMedium.lineHeight)
        assertEquals(FontWeight.Medium, Typography.titleMedium.fontWeight)

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
        assertEquals(12.dp, HPreShapes.Card)
        assertEquals(16.dp, HPreShapes.Group)
        assertEquals(24.dp, HPreShapes.Sheet)
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
