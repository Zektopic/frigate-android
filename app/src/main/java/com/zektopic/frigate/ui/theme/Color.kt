package com.zektopic.frigate.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The Frigate web UI's design tokens, one field per CSS custom property in
 * `web/themes/theme-default.css`. Values are the same HSL triples the stylesheet
 * declares, so a screen built from these matches the desktop UI's colours exactly
 * rather than approximately.
 */
@Immutable
data class FrigateColors(
    val isDark: Boolean,
    val background: Color,
    val backgroundAlt: Color,
    val foreground: Color,
    val card: Color,
    val popover: Color,
    val popoverForeground: Color,
    val primary: Color,
    val primaryVariant: Color,
    val primaryForeground: Color,
    val secondary: Color,
    val secondaryForeground: Color,
    val secondaryHighlight: Color,
    val neutral: Color,
    val muted: Color,
    val mutedForeground: Color,
    val accent: Color,
    val accentForeground: Color,
    val destructive: Color,
    val destructiveForeground: Color,
    val warning: Color,
    val warningForeground: Color,
    val border: Color,
    val input: Color,
    // The remaining tokens are the same in both modes.
    val selected: Color = hsl(228f, 89f, 63f),
    val selectedForeground: Color = Color.White,
    val severityAlert: Color = hsl(0f, 70f, 35.3f),            // red-800
    val severityAlertDimmed: Color = hsl(0f, 84.2f, 60.2f),    // red-500
    val severityDetection: Color = hsl(20.5f, 90.2f, 48.2f),   // orange-600
    val severityDetectionDimmed: Color = hsl(27f, 96f, 61f),   // orange-400
    val severityMotion: Color = hsl(47.9f, 95.8f, 53.1f),      // yellow-400
    val severityMotionDimmed: Color = hsl(52.8f, 98.3f, 76.9f), // yellow-200
    val danger: Color = Color(0xFFEF4444),
    val success: Color = Color(0xFF22C55E),
    val unsaved: Color = Color(0xFFF59E0B)
)

private fun hsl(hue: Float, saturation: Float, lightness: Float): Color =
    Color.hsl(hue, saturation / 100f, lightness / 100f)

val DarkFrigateColors = FrigateColors(
    isDark = true,
    background = hsl(0f, 0f, 0f),
    backgroundAlt = hsl(0f, 0f, 9f),
    foreground = hsl(0f, 0f, 100f),
    card = hsl(0f, 0f, 12f),
    popover = hsl(0f, 0f, 15f),
    popoverForeground = hsl(0f, 0f, 98f),
    primary = hsl(0f, 0f, 91f),
    primaryVariant = hsl(0f, 0f, 64f),
    primaryForeground = hsl(0f, 0f, 9f),
    secondary = hsl(0f, 0f, 15f),
    secondaryForeground = hsl(0f, 0f, 42f),
    secondaryHighlight = hsl(0f, 0f, 25f),
    neutral = hsl(0f, 0f, 55.1f),
    muted = hsl(0f, 0f, 12f),
    mutedForeground = hsl(0f, 0f, 42f),
    accent = hsl(0f, 0f, 19f),
    accentForeground = hsl(210f, 40f, 95f),
    destructive = hsl(0f, 62.8f, 30.6f),
    destructiveForeground = hsl(0f, 100f, 83f),
    warning = hsl(17f, 87f, 18f),
    warningForeground = hsl(32f, 100f, 74f),
    border = hsl(0f, 0f, 32f),
    input = hsl(0f, 0f, 25f)
)

val LightFrigateColors = FrigateColors(
    isDark = false,
    background = hsl(0f, 0f, 100f),
    backgroundAlt = hsl(0f, 0f, 98.5f),
    foreground = hsl(222.2f, 84f, 4.9f),
    card = hsl(0f, 0f, 100f),
    popover = hsl(0f, 0f, 100f),
    popoverForeground = hsl(222.2f, 84f, 4.9f),
    primary = hsl(222.2f, 47.4f, 11.2f),
    primaryVariant = hsl(222.2f, 47.4f, 24.2f),
    primaryForeground = hsl(210f, 40f, 98f),
    secondary = hsl(210f, 20f, 94.1f),
    secondaryForeground = hsl(222.2f, 17.4f, 36.2f),
    secondaryHighlight = hsl(210f, 17.4f, 94f),
    neutral = hsl(0f, 0f, 45.1f),
    muted = hsl(210f, 40f, 96.1f),
    mutedForeground = hsl(215.4f, 6.3f, 46.9f),
    accent = hsl(210f, 40f, 96.1f),
    accentForeground = hsl(222.2f, 47.4f, 11.2f),
    destructive = hsl(0f, 84.2f, 60.2f),
    destructiveForeground = hsl(0f, 100f, 83f),
    warning = hsl(17f, 87f, 18f),
    warningForeground = hsl(32f, 100f, 74f),
    border = hsl(214.3f, 31.8f, 91.4f),
    input = hsl(0f, 0f, 85f)
)

val LocalFrigateColors = staticCompositionLocalOf { DarkFrigateColors }

/** The grey gradient behind Frigate's overlay chips (`from-gray-400 to-gray-500`). */
val ChipGray = listOf(Color(0xFF9CA3AF), Color(0xFF6B7280))

/** The chip gradient for an item that has been reviewed (`from-green-600 to-green-700`). */
val ChipGreen = listOf(Color(0xFF16A34A), Color(0xFF15803D))
