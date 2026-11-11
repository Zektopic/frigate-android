package com.zektopic.frigate.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zektopic.frigate.ui.NvrActions
import com.zektopic.frigate.ui.NvrUiState
import com.zektopic.frigate.ui.components.FrigateButton
import com.zektopic.frigate.ui.components.FrigateLogo
import com.zektopic.frigate.ui.components.RoundedLg
import com.zektopic.frigate.ui.components.RoundedMd
import com.zektopic.frigate.ui.theme.FrigateTheme
import com.zektopic.frigate.ui.theme.FrigateType

/** Keys of the settings pages, named after the web UI's. */
object SettingsPages {
    const val UI = "uiSettings"
    const val CAMERA_MANAGEMENT = "cameraManagement"
    const val GLOBAL_DETECT = "globalDetect"
    const val GLOBAL_MOTION = "globalMotion"
    const val MOTION_TUNER = "motionTuner"
    const val MASKS_AND_ZONES = "masksAndZones"
    const val MQTT = "systemMqtt"
    const val STORAGE = "systemStorage"
    const val PERFORMANCE = "systemPerformance"
    const val NOTIFICATIONS = "notifications"
    const val ABOUT = "about"
}

private data class SettingsItem(val key: String, val title: String)
private data class SettingsGroup(val label: String, val items: List<SettingsItem>)

// The same grouping as the web UI's settings sidebar, with the pages this app has.
private val settingsGroups = listOf(
    SettingsGroup("General", listOf(SettingsItem(SettingsPages.UI, "UI settings"))),
    SettingsGroup(
        "Global configuration",
        listOf(
            SettingsItem(SettingsPages.CAMERA_MANAGEMENT, "Camera management"),
            SettingsItem(SettingsPages.GLOBAL_DETECT, "Detect"),
            SettingsItem(SettingsPages.GLOBAL_MOTION, "Motion")
        )
    ),
    SettingsGroup(
        "Cameras",
        listOf(
            SettingsItem(SettingsPages.MOTION_TUNER, "Motion tuner"),
            SettingsItem(SettingsPages.MASKS_AND_ZONES, "Masks / Zones")
        )
    ),
    SettingsGroup(
        "System",
        listOf(
            SettingsItem(SettingsPages.STORAGE, "Storage"),
            SettingsItem(SettingsPages.PERFORMANCE, "Performance"),
            SettingsItem(SettingsPages.MQTT, "MQTT")
        )
    ),
    SettingsGroup("Notifications", listOf(SettingsItem(SettingsPages.NOTIFICATIONS, "Notifications"))),
    SettingsGroup("About", listOf(SettingsItem(SettingsPages.ABOUT, "About")))
)

private fun titleOf(key: String): String =
    settingsGroups.flatMap { it.items }.firstOrNull { it.key == key }?.title ?: "Settings"

/**
 * The web UI's Settings page. Wide: a grouped menu on the left, the page beside it.
 * Phone: the menu is the page, and each entry opens over it with a way back.
 */
@Composable
fun SettingsScreen(
    state: NvrUiState,
    frames: Map<String, Bitmap>,
    actions: NvrActions,
    wide: Boolean,
    pageKey: String?,
    onPageChange: (String?) -> Unit
) {
    val colors = FrigateTheme.colors
    val border = colors.secondary

    val page: @Composable (String) -> Unit = { key ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(if (wide) 16.dp else 12.dp)
                .widthIn(max = 900.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (key) {
                SettingsPages.UI -> UiSettingsPage(state, actions)
                SettingsPages.CAMERA_MANAGEMENT -> CameraManagementPage(state, actions)
                SettingsPages.GLOBAL_DETECT -> GlobalDetectPage(state, actions)
                SettingsPages.GLOBAL_MOTION -> GlobalMotionPage(state, actions)
                SettingsPages.MOTION_TUNER -> MotionTunerPage(state, frames, actions, wide)
                SettingsPages.MASKS_AND_ZONES -> MasksAndZonesPage(state, frames, actions, wide)
                SettingsPages.MQTT -> MqttPage(state, actions)
                SettingsPages.STORAGE -> StoragePage(actions)
                SettingsPages.PERFORMANCE -> PerformancePage(state, actions)
                SettingsPages.NOTIFICATIONS -> NotificationsPage(state)
                else -> AboutPage(state)
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (wide) {
        val current = pageKey ?: SettingsPages.UI
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 64.dp)
                    .drawBehind { drawLine(border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Settings", style = FrigateType.xl.copy(fontWeight = FontWeight.SemiBold), color = colors.primary)
            }
            Row(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(240.dp)
                        .fillMaxHeight()
                        .drawBehind {
                            val x = size.width - 0.5.dp.toPx()
                            drawLine(border, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                        }
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (group in settingsGroups) {
                        Text(
                            group.label,
                            style = FrigateType.sm.copy(fontWeight = FontWeight.Medium),
                            color = colors.primary,
                            modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp)
                        )
                        for (item in group.items) {
                            val active = item.key == current
                            Text(
                                item.title,
                                style = FrigateType.sm.copy(fontWeight = if (active) FontWeight.Medium else FontWeight.Normal),
                                color = if (active) colors.primary else colors.secondaryForeground,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedMd)
                                    .background(if (active) colors.secondary else Color.Transparent)
                                    .clickable(role = Role.Tab) { onPageChange(item.key) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Text(
                        titleOf(current),
                        style = FrigateType.lg.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp)
                    )
                    page(current)
                }
            }
        }
    } else if (pageKey == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                FrigateLogo(Modifier.size(32.dp))
            }
            Text(
                "Settings",
                style = FrigateType.lg,
                color = colors.primary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                for (group in settingsGroups) {
                    Text(
                        group.label,
                        style = FrigateType.sm.copy(fontWeight = FontWeight.Medium),
                        color = colors.secondaryForeground,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp)
                    )
                    for (item in group.items) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedLg)
                                .clickable(role = Role.Button) { onPageChange(item.key) }
                                .defaultMinSize(minHeight = 44.dp)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(item.title, style = FrigateType.base, color = colors.primary)
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = colors.secondaryForeground,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FrigateButton(onClick = { onPageChange(null) }, shape = RoundedLg) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.secondaryForeground, modifier = Modifier.size(20.dp))
                }
                Text(titleOf(pageKey), style = FrigateType.lg.copy(fontWeight = FontWeight.SemiBold), color = colors.primary)
            }
            page(pageKey)
        }
    }
}
