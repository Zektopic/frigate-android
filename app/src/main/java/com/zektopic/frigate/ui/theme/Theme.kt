package com.zektopic.frigate.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Mirrors the web UI's Dark Mode menu: follow the device, or force one. */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = values().firstOrNull { it.key == key } ?: SYSTEM
    }
}

object FrigateTheme {
    val colors: FrigateColors
        @Composable @ReadOnlyComposable get() = LocalFrigateColors.current
}

/**
 * Material components (menus, sheets, switches, sliders) take their colours from
 * here, so they land on Frigate's tokens without restyling each call site.
 */
private fun FrigateColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = selected,
        onPrimary = selectedForeground,
        primaryContainer = selected,
        onPrimaryContainer = selectedForeground,
        secondary = secondary,
        onSecondary = primary,
        secondaryContainer = accent,
        onSecondaryContainer = accentForeground,
        tertiary = success,
        background = background,
        onBackground = primary,
        surface = popover,
        onSurface = popoverForeground,
        surfaceVariant = secondary,
        onSurfaceVariant = mutedForeground,
        // Material tints raised surfaces with this; Frigate's are flat.
        surfaceTint = Color.Transparent,
        error = danger,
        onError = Color.White,
        errorContainer = destructive,
        onErrorContainer = destructiveForeground,
        outline = input,
        outlineVariant = secondaryHighlight,
        scrim = Color.Black
    )
}

@Composable
fun FrigateAndroidTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkFrigateColors else LightFrigateColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.backgroundAlt.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalFrigateColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = Typography,
            content = content
        )
    }
}
