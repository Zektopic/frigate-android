package com.zektopic.frigate.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zektopic.frigate.R

/** Inter, the typeface the Frigate web UI is set in (bundled from its own font files). */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

private fun style(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp
)

/**
 * Tailwind's type scale, which is all the web UI uses: `text-xs` 12/16, `text-sm`
 * 14/20, `text-base` 16/24, `text-lg` 18/28, `text-xl` 20/28, `text-2xl` 24/32.
 */
object FrigateType {
    val xs = style(12, 16)
    val sm = style(14, 20)
    val base = style(16, 24)
    val lg = style(18, 28)
    val xl = style(20, 28)
    val xxl = style(24, 32)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
}

val Typography = Typography(
    displayLarge = style(30, 36, FontWeight.Bold),
    headlineLarge = style(24, 32, FontWeight.SemiBold),
    headlineMedium = style(20, 28, FontWeight.SemiBold),
    headlineSmall = style(18, 28, FontWeight.SemiBold),
    titleLarge = style(20, 28, FontWeight.SemiBold),
    titleMedium = style(16, 24, FontWeight.Medium),
    titleSmall = style(14, 20, FontWeight.Medium),
    bodyLarge = style(16, 24),
    bodyMedium = style(14, 20),
    bodySmall = style(12, 16),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(12, 16, FontWeight.Medium),
    labelSmall = style(11, 16, FontWeight.Medium)
)
