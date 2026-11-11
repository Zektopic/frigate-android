package com.zektopic.frigate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zektopic.frigate.ui.theme.ChipGray
import com.zektopic.frigate.ui.theme.FrigateTheme
import com.zektopic.frigate.ui.theme.FrigateType
import kotlinx.coroutines.delay

// Tailwind's radii, by the names the web UI's class lists use.
val RoundedMd = RoundedCornerShape(6.dp)
val RoundedLg = RoundedCornerShape(8.dp)
val Rounded2xl = RoundedCornerShape(16.dp)

/** `rounded-lg md:rounded-2xl`: tiles and cards are rounder on a wide layout. */
fun tileShape(wide: Boolean): RoundedCornerShape = if (wide) Rounded2xl else RoundedLg

/** The pill laid over video: camera names, labels, time. */
@Composable
fun Chip(
    modifier: Modifier = Modifier,
    gradient: List<Color> = ChipGray,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .clip(Rounded2xl)
            .background(Brush.linearGradient(gradient))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            ProvideTextStyle(FrigateType.xs, content = { content() })
        }
    }
}

enum class ButtonVariant { Default, Select, Destructive, Outline, Ghost }

/** The web UI's `Button`: `h-9 rounded-md px-3` when small, `h-10 px-4` otherwise. */
@Composable
fun FrigateButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Default,
    enabled: Boolean = true,
    small: Boolean = true,
    shape: RoundedCornerShape = RoundedMd,
    content: @Composable RowScope.() -> Unit
) {
    val colors = FrigateTheme.colors
    val (container, contentColor) = when (variant) {
        ButtonVariant.Default -> colors.secondary to colors.primary
        ButtonVariant.Select -> colors.selected to colors.selectedForeground
        ButtonVariant.Destructive -> colors.destructive to Color.White
        ButtonVariant.Outline -> colors.background to colors.primary
        ButtonVariant.Ghost -> Color.Transparent to colors.primary
    }
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(container)
            .then(if (variant == ButtonVariant.Outline) Modifier.border(1.dp, colors.input, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = if (small) 36.dp else 40.dp)
            .padding(horizontal = if (small) 12.dp else 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(FrigateType.sm.copy(fontWeight = FontWeight.Medium), content = { content() })
        }
    }
}

/**
 * The square icon button used for navigation and camera feature toggles:
 * `rounded-lg p-[6px]` around a `size-5` icon, `bg-selected` when on.
 */
@Composable
fun IconSquareButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    enabled: Boolean = true
) {
    val colors = FrigateTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedLg)
            .background(if (active) colors.selected else colors.secondary)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) Color.White else colors.secondaryForeground,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** One entry of a `ToggleGroup`: `rounded-md px-3`, `bg-accent` when on. */
@Composable
fun ToggleItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val colors = FrigateTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedMd)
            .background(if (selected) colors.accent else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .height(36.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (selected) colors.accentForeground else colors.mutedForeground
        ) {
            ProvideTextStyle(FrigateType.sm.copy(fontWeight = FontWeight.Medium), content = { content() })
        }
    }
}

@Composable
fun ActivityIndicator(modifier: Modifier = Modifier, size: Dp = 30.dp) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = FrigateTheme.colors.selected,
        trackColor = Color.Transparent,
        strokeWidth = if (size < 20.dp) 2.dp else 3.dp
    )
}

/** The centred icon, title and optional action shown when a page has nothing to list. */
@Composable
fun EmptyCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    buttonText: String? = null,
    onButton: (() -> Unit)? = null
) {
    val colors = FrigateTheme.colors
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(32.dp))
        Text(title, style = FrigateType.base, color = colors.primary, textAlign = TextAlign.Center)
        if (description != null) {
            Text(
                description,
                style = FrigateType.sm,
                color = colors.secondaryForeground,
                textAlign = TextAlign.Center
            )
        }
        if (buttonText != null && onButton != null) {
            Spacer(Modifier.height(4.dp))
            FrigateButton(onClick = onButton, variant = ButtonVariant.Select) { Text(buttonText) }
        }
    }
}

/** A `text-sm font-medium text-muted-foreground` section heading. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = FrigateType.sm.copy(fontWeight = FontWeight.Medium),
        color = FrigateTheme.colors.mutedForeground
    )
}

/** `rounded-lg bg-background_alt p-2.5 md:rounded-2xl`: the panel behind every graph. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(tileShape(wide))
            .background(FrigateTheme.colors.backgroundAlt)
            .padding(10.dp),
        content = content
    )
}

@Composable
fun FrigateSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val colors = FrigateTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.background,
            checkedTrackColor = colors.selected,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = colors.background,
            uncheckedTrackColor = colors.input,
            uncheckedBorderColor = Color.Transparent
        )
    )
}

@Composable
fun FrigateSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val colors = FrigateTheme.colors
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = colors.primary,
            activeTrackColor = colors.primary,
            inactiveTrackColor = colors.secondary,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent
        )
    )
}

@Composable
fun frigateTextFieldColors() = FrigateTheme.colors.let { colors ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.selected,
        unfocusedBorderColor = colors.input,
        cursorColor = colors.primary,
        focusedTextColor = colors.primary,
        unfocusedTextColor = colors.primary,
        focusedLabelColor = colors.primary,
        unfocusedLabelColor = colors.mutedForeground,
        focusedPlaceholderColor = colors.mutedForeground,
        unfocusedPlaceholderColor = colors.mutedForeground,
        focusedSupportingTextColor = colors.mutedForeground,
        unfocusedSupportingTextColor = colors.mutedForeground,
        focusedContainerColor = colors.background,
        unfocusedContainerColor = colors.background
    )
}

/** A label with a description on the left and a control on the right. */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    control: @Composable () -> Unit
) {
    val colors = FrigateTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = FrigateType.sm, color = if (enabled) colors.primary else colors.mutedForeground)
            if (description != null) {
                Text(description, style = FrigateType.xs, color = colors.mutedForeground)
            }
        }
        control()
    }
}

/** Frigate's `TimeAgo` in its dense form: "Just now", "42s ago", "5m ago", "3h ago", "2d ago". */
fun timeAgo(nowMs: Long, timeMs: Long): String {
    val elapsed = (nowMs - timeMs) / 1000
    return when {
        elapsed < 10 -> "Just now"
        elapsed < 60 -> "${elapsed}s ago"
        elapsed < 3_600 -> "${elapsed / 60}m ago"
        elapsed < 86_400 -> "${elapsed / 3_600}h ago"
        elapsed < 2_592_000 -> "${elapsed / 86_400}d ago"
        elapsed < 31_536_000 -> "${elapsed / 2_592_000}mo ago"
        else -> "${elapsed / 31_536_000}yr ago"
    }
}

/** The current time, re-read every [intervalMs] so relative times on screen stay true. */
@Composable
fun rememberNow(intervalMs: Long = 10_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            delay(intervalMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/** The web UI's `AlertDialog`: `bg-background` with a border, actions bottom right. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false
) {
    val colors = FrigateTheme.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, colors.input, RoundedLg),
        shape = RoundedLg,
        containerColor = colors.background,
        tonalElevation = 0.dp,
        title = { Text(title, style = FrigateType.lg.copy(fontWeight = FontWeight.SemiBold), color = colors.primary) },
        text = { Text(text, style = FrigateType.sm, color = colors.mutedForeground) },
        confirmButton = {
            FrigateButton(
                onClick = onConfirm,
                variant = if (destructive) ButtonVariant.Destructive else ButtonVariant.Select
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            FrigateButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
