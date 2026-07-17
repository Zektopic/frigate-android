package com.zektopic.frigate.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zektopic.frigate.data.AppPreferences
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.data.EventEntity
import com.zektopic.frigate.data.NotificationSettings
import com.zektopic.frigate.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------------------
// Shared section chrome
// ---------------------------------------------------------------------------

@Composable
fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCard)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = FrigateBlue, modifier = Modifier.size(18.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = FrigateBlue,
    unfocusedBorderColor = SlateBorder,
    cursorColor = FrigateBlue,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = FrigateBlue,
    unfocusedLabelColor = TextMuted
)

// ---------------------------------------------------------------------------
// Global configuration (MQTT / detection defaults / tracked objects)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalConfigSection(
    currentYaml: String,
    onSave: suspend (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(currentYaml) { mutableStateOf(CameraYamlEditor.readGlobals(currentYaml)) }
    var objectsText by remember(currentYaml) { mutableStateOf(draft.trackedObjects.joinToString(", ")) }
    var saving by remember { mutableStateOf(false) }

    SettingsSectionCard("Global configuration", Icons.Default.Tune) {
        OutlinedTextField(
            value = draft.mqttHost,
            onValueChange = { draft = draft.copy(mqttHost = it) },
            label = { Text("MQTT host (optional)") },
            placeholder = { Text("192.168.1.10", color = TextMuted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = settingsFieldColors()
        )
        OutlinedTextField(
            value = if (draft.mqttPort == 0) "" else draft.mqttPort.toString(),
            onValueChange = { draft = draft.copy(mqttPort = it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) },
            label = { Text("MQTT port") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            colors = settingsFieldColors()
        )

        Text("Default detection resolution", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(640 to 360, 1280 to 720, 1920 to 1080).forEach { (w, h) ->
                val selected = draft.detectWidth == w && draft.detectHeight == h
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) FrigateBlue else SlateNav)
                        .border(1.dp, if (selected) FrigateBlue else SlateBorder, RoundedCornerShape(6.dp))
                        .clickable { draft = draft.copy(detectWidth = w, detectHeight = h) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("${w}×${h}", fontSize = 11.sp, color = if (selected) TextPrimary else TextMuted)
                }
            }
        }

        Text("Default detection FPS: ${draft.detectFps}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Slider(
            value = draft.detectFps.toFloat(),
            onValueChange = { draft = draft.copy(detectFps = it.toInt().coerceIn(1, 15)) },
            valueRange = 1f..15f,
            steps = 13,
            colors = SliderDefaults.colors(thumbColor = FrigateBlue, activeTrackColor = FrigateBlue)
        )

        OutlinedTextField(
            value = objectsText,
            onValueChange = { objectsText = it },
            label = { Text("Tracked objects (comma separated)") },
            placeholder = { Text("person, car, bird", color = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = settingsFieldColors()
        )

        Button(
            onClick = {
                saving = true
                val objects = objectsText.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                val finalDraft = draft.copy(trackedObjects = objects.ifEmpty { listOf("person") })
                scope.launch {
                    try {
                        onSave(CameraYamlEditor.upsertGlobals(currentYaml, finalDraft))
                        Toast.makeText(context, "Global settings saved", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        saving = false
                    }
                }
            },
            enabled = !saving,
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = FrigateBlue, contentColor = TextPrimary),
            modifier = Modifier.align(Alignment.End)
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
            else Text("Save globals", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------

@Composable
fun NotificationsSection(cameraConfigs: List<CameraConfigEntity>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPreferences(context.applicationContext) }
    var settings by remember { mutableStateOf(NotificationSettings()) }

    LaunchedEffect(Unit) {
        prefs.notificationSettings.collectLatest { settings = it }
    }

    SettingsSectionCard("Notifications", Icons.Default.Notifications) {
        SettingsSwitchRow(
            label = "Enable notifications",
            description = "Master switch for all alerts",
            checked = settings.notificationsEnabled,
            onCheckedChange = { scope.launch { prefs.setNotificationsEnabled(it) } }
        )
        SettingsSwitchRow(
            label = "Alert on motion",
            description = "Post a notification when motion is detected",
            checked = settings.notifyOnMotion,
            enabled = settings.notificationsEnabled,
            onCheckedChange = { scope.launch { prefs.setNotifyOnMotion(it) } }
        )

        if (cameraConfigs.isNotEmpty()) {
            Divider(color = SlateBorder)
            Text("Per-camera alerts", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            cameraConfigs.forEach { camera ->
                val muted = camera.id in settings.mutedCameraIds
                SettingsSwitchRow(
                    label = camera.name,
                    description = if (muted) "Muted" else "Alerts on",
                    checked = !muted,
                    enabled = settings.notificationsEnabled && settings.notifyOnMotion,
                    onCheckedChange = { on -> scope.launch { prefs.setCameraMuted(camera.id, !on) } }
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = if (enabled) TextPrimary else TextMuted)
            Text(description, fontSize = 10.sp, color = TextMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = FrigateBlue)
        )
    }
}

// ---------------------------------------------------------------------------
// Storage management
// ---------------------------------------------------------------------------

@Composable
fun StorageSection(onClearAllRecordings: suspend () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var totalBytes by remember { mutableStateOf(0L) }
    var clipCount by remember { mutableStateOf(0) }
    var refreshKey by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        withContext(Dispatchers.IO) {
            val dir = File(context.getExternalFilesDir(null), "frigate_recordings")
            val files = dir.listFiles() ?: emptyArray()
            totalBytes = files.sumOf { it.length() }
            clipCount = files.count { it.extension.equals("mp4", ignoreCase = true) }
        }
    }

    SettingsSectionCard("Storage", Icons.Default.Storage) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StorageStat("Recordings", "$clipCount")
            StorageStat("On disk", formatBytes(totalBytes))
        }
        OutlinedButton(
            onClick = { confirmClear = true },
            enabled = !clearing && totalBytes > 0,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed)
        ) {
            if (clearing) {
                CircularProgressIndicator(Modifier.size(16.dp), color = StatusRed, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Clear all recordings", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = SlateCard,
            title = { Text("Clear all recordings?", color = TextPrimary) },
            text = { Text("This permanently deletes every recorded clip, snapshot, and event. This cannot be undone.", color = TextMuted, fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        clearing = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val dir = File(context.getExternalFilesDir(null), "frigate_recordings")
                                    dir.listFiles()?.forEach { it.delete() }
                                }
                                onClearAllRecordings()
                                Toast.makeText(context, "Recordings cleared", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Clear failed: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                clearing = false
                                refreshKey++
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed, contentColor = TextPrimary)
                ) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = TextMuted) } }
        )
    }
}

@Composable
private fun RowScope.StorageStat(label: String, value: String) {
    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text(label, fontSize = 10.sp, color = TextMuted)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

// ---------------------------------------------------------------------------
// About / diagnostics
// ---------------------------------------------------------------------------

@Composable
fun AboutSection(
    cameraConfigs: List<CameraConfigEntity>,
    events: List<EventEntity>,
    configYaml: String
) {
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
        } catch (e: Exception) { "—" }
    }
    val configVersion = remember(configYaml) {
        Regex("(?m)^version:\\s*(.+)$").find(configYaml)?.groupValues?.get(1)?.trim() ?: "—"
    }
    val storagePath = remember {
        File(context.getExternalFilesDir(null), "frigate_recordings").absolutePath
    }

    SettingsSectionCard("About", Icons.Default.Info) {
        AboutRow("App version", versionName)
        AboutRow("Config version", configVersion)
        AboutRow("Cameras configured", "${cameraConfigs.size}")
        AboutRow("Events logged", "${events.size}")
        AboutRow("Android API", "${android.os.Build.VERSION.SDK_INT}")
        AboutRow("Device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        Divider(color = SlateBorder)
        Text("Recordings path", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Text(storagePath, fontSize = 10.sp, color = TextMuted)
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = TextMuted)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
    }
}
