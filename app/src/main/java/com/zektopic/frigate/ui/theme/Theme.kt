package com.zektopic.frigate.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

private val DarkColorScheme = darkColorScheme(
    primary = CyberCyan,
    secondary = ElectricEmerald,
    tertiary = HotPink,
    background = DarkVoid,
    surface = CardCarbon,
    onBackground = LightWhite,
    onSurface = LightWhite,
    onPrimary = DarkVoid,
    outline = SlateBorder
)

@Composable
fun FrigateAndroidTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DarkVoid.toArgb()
            window.navigationBarColor = DarkVoid.toArgb()
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
