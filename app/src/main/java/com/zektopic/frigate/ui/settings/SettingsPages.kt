package com.zektopic.frigate.ui.settings

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zektopic.frigate.ai.RelPoint
import com.zektopic.frigate.data.AppPreferences
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.data.CameraRuntimeConfig
import com.zektopic.frigate.data.NotificationSettings
import com.zektopic.frigate.data.RecordingStore
import com.zektopic.frigate.data.YamlConfigParser
import com.zektopic.frigate.media.DevicePerformance
import com.zektopic.frigate.ui.NvrActions
import com.zektopic.frigate.ui.NvrUiState
import com.zektopic.frigate.ui.components.ActivityIndicator
import com.zektopic.frigate.ui.components.ButtonVariant
import com.zektopic.frigate.ui.components.ConfirmDialog
import com.zektopic.frigate.ui.components.FrigateButton
import com.zektopic.frigate.ui.components.FrigateSlider
import com.zektopic.frigate.ui.components.FrigateSwitch
import com.zektopic.frigate.ui.components.Panel
import com.zektopic.frigate.ui.components.RoundedLg
import com.zektopic.frigate.ui.components.RoundedMd
import com.zektopic.frigate.ui.components.SettingRow
import com.zektopic.frigate.ui.components.formatBytes
import com.zektopic.frigate.ui.components.frigateTextFieldColors
import com.zektopic.frigate.ui.live.DebugOverlay
import com.zektopic.frigate.ui.live.LiveTile
import com.zektopic.frigate.ui.live.ZoneColors
import com.zektopic.frigate.ui.theme.FrigateTheme
import com.zektopic.frigate.ui.theme.FrigateType
import com.zektopic.frigate.ui.theme.ThemeMode
import com.zektopic.frigate.util.CredentialRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

/** Saves an edited config and reports the outcome; pages share one so they behave alike. */
@Stable
private class ConfigSaver(
    private val scope: CoroutineScope,
    private val actions: NvrActions,
    private val context: android.content.Context
) {
    var saving by mutableStateOf(false)
        private set

    fun save(yaml: String, message: String? = "Settings saved") {
        saving = true
        scope.launch {
            try {
                actions.saveConfig(yaml)
                if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                saving = false
            }
        }
    }
}

@Composable
private fun rememberConfigSaver(actions: NvrActions): ConfigSaver {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(actions) { ConfigSaver(scope, actions, context) }
}

@Composable
private fun PageText(text: String) {
    Text(text, style = FrigateType.sm, color = FrigateTheme.colors.mutedForeground)
}

@Composable
private fun Heading(text: String) {
    Text(text, style = FrigateType.base.copy(fontWeight = FontWeight.Medium), color = FrigateTheme.colors.primary)
}

@Composable
private fun SaveRow(enabled: Boolean, saving: Boolean, onSave: () -> Unit, onReset: (() -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onReset != null) FrigateButton(onClick = onReset, enabled = enabled && !saving, small = false) { Text("Reset") }
        FrigateButton(onClick = onSave, enabled = enabled && !saving, variant = ButtonVariant.Select, small = false) {
            if (saving) ActivityIndicator(size = 16.dp)
            Text("Save")
        }
    }
}

@Composable
private fun LabeledSlider(
    title: String,
    description: String,
    value: Float,
    display: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    val colors = FrigateTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = FrigateType.sm.copy(fontWeight = FontWeight.Medium), color = colors.primary)
            Text(display, style = FrigateType.sm, color = colors.primary)
        }
        Text(description, style = FrigateType.xs, color = colors.mutedForeground)
        FrigateSlider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/** The camera dropdown at the top of the per-camera pages. */
@Composable
private fun CameraSelector(cameras: List<CameraConfigEntity>, selected: CameraConfigEntity, onSelect: (String) -> Unit) {
    val colors = FrigateTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        FrigateButton(onClick = { open = true }, variant = ButtonVariant.Select) {
            Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(selected.name)
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (camera in cameras) {
                Text(
                    camera.name,
                    style = FrigateType.sm,
                    color = colors.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (camera.id == selected.id) colors.accent else Color.Transparent)
                        .clickable {
                            open = false
                            onSelect(camera.id)
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun NoCamerasNote() {
    PageText("No cameras are configured yet. Add one under Camera management.")
}

// ---------------------------------------------------------------------------
// General: UI settings
// ---------------------------------------------------------------------------

@Composable
fun UiSettingsPage(state: NvrUiState, actions: NvrActions) {
    val ui = state.ui
    Heading("Live dashboard")
    SettingRow(
        "Always show camera names",
        description = "Always show the camera names in a chip in the multi-camera live view dashboard."
    ) { FrigateSwitch(ui.alwaysShowCameraNames, { actions.updateUi(ui.copy(alwaysShowCameraNames = it)) }) }
    SettingRow(
        "Dashboard layout",
        description = if (ui.liveLayout == null) "Following the default for this screen."
        else "Set to ${ui.liveLayout}. Reset to follow the default for this screen."
    ) {
        FrigateButton(onClick = { actions.updateUi(ui.copy(liveLayout = null)) }, enabled = ui.liveLayout != null) { Text("Reset") }
    }
    HorizontalDivider(color = FrigateTheme.colors.secondary)

    Heading("Appearance")
    SettingRow("Dark mode", description = "Follow the device, or always use one.") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (mode in ThemeMode.values()) {
                FrigateButton(
                    onClick = { actions.updateUi(ui.copy(themeMode = mode)) },
                    variant = if (ui.themeMode == mode) ButtonVariant.Select else ButtonVariant.Default
                ) { Text(mode.label) }
            }
        }
    }
    SettingRow("24-hour time", description = "Show times as 14:30 rather than 2:30 PM.") {
        FrigateSwitch(ui.use24HourTime, { actions.updateUi(ui.copy(use24HourTime = it)) })
    }
}

// ---------------------------------------------------------------------------
// Global configuration
// ---------------------------------------------------------------------------

@Composable
fun CameraManagementPage(state: NvrUiState, actions: NvrActions) {
    val colors = FrigateTheme.colors
    val saver = rememberConfigSaver(actions)
    var showWizard by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CameraConfigEntity?>(null) }
    var deleting by remember { mutableStateOf<CameraConfigEntity?>(null) }

    PageText("Add, edit, disable or remove cameras. Changes take effect as soon as they are saved.")
    FrigateButton(onClick = { editing = null; showWizard = true }, variant = ButtonVariant.Select, small = false) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("Add Camera")
    }

    if (state.cameras.isEmpty()) {
        NoCamerasNote()
    } else {
        Panel(Modifier.fillMaxWidth()) {
            state.cameras.forEachIndexed { index, camera ->
                if (index > 0) HorizontalDivider(color = colors.secondary)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(camera.name, style = FrigateType.sm.copy(fontWeight = FontWeight.Medium), color = colors.primary)
                        Text(
                            // Masked: a settings list is the screen most likely to be shown to someone else.
                            if (camera.rtspUrl.isEmpty()) "Device camera" else CredentialRedactor.redact(camera.rtspUrl),
                            style = FrigateType.xs,
                            color = colors.mutedForeground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${camera.detectWidth}x${camera.detectHeight} @ ${camera.fps} FPS ∙ keep ${camera.recordingRetentionDays}d" +
                                if (camera.detectRtspUrl.isNotEmpty()) " ∙ substream" else "",
                            style = FrigateType.xs,
                            color = colors.mutedForeground
                        )
                    }
                    FrigateSwitch(
                        checked = camera.isEnabled,
                        onCheckedChange = { saver.save(CameraYamlEditor.setCameraEnabled(state.configYaml, camera.id, it), null) },
                        enabled = !saver.saving
                    )
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Edit ${camera.name}",
                        tint = colors.secondaryForeground,
                        modifier = Modifier.clip(RoundedMd).clickable { editing = camera; showWizard = true }.padding(6.dp).size(18.dp)
                    )
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete ${camera.name}",
                        tint = colors.secondaryForeground,
                        modifier = Modifier.clip(RoundedMd).clickable { deleting = camera }.padding(6.dp).size(18.dp)
                    )
                }
            }
        }
    }

    if (showWizard) {
        CameraWizardDialog(
            existingCamera = editing,
            currentYaml = state.configYaml,
            onSave = { actions.saveConfig(it) },
            onDismiss = { showWizard = false; editing = null }
        )
    }
    deleting?.let { camera ->
        ConfirmDialog(
            title = "Delete ${camera.name}?",
            text = "The camera and its settings are removed from the configuration. Its recordings are kept.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                deleting = null
                saver.save(CameraYamlEditor.removeCamera(state.configYaml, camera.id), "${camera.name} removed")
            },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
fun GlobalDetectPage(state: NvrUiState, actions: NvrActions) {
    val saver = rememberConfigSaver(actions)
    val stored = remember(state.configYaml) { CameraYamlEditor.readGlobals(state.configYaml) }
    var draft by remember(stored) { mutableStateOf(stored) }

    PageText("Defaults for every camera that does not set its own detect resolution and frame rate.")
    Heading("Resolution")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(640 to 360, 1280 to 720, 1920 to 1080).forEach { (w, h) ->
            val selected = draft.detectWidth == w && draft.detectHeight == h
            FrigateButton(
                onClick = { draft = draft.copy(detectWidth = w, detectHeight = h) },
                variant = if (selected) ButtonVariant.Select else ButtonVariant.Default
            ) { Text("${w}x$h") }
        }
    }
    LabeledSlider(
        title = "FPS",
        description = "Frames per second analysed for motion. 5 is recommended; the device's own limit applies on top.",
        value = draft.detectFps.toFloat(),
        display = "${draft.detectFps}",
        range = 1f..15f
    ) { draft = draft.copy(detectFps = it.toInt().coerceIn(1, 15)) }
    SaveRow(enabled = draft != stored, saving = saver.saving, onSave = { saver.save(CameraYamlEditor.upsertGlobals(state.configYaml, draft)) }) {
        draft = stored
    }
}

@Composable
private fun MotionControls(draft: MotionDraft, onChange: (MotionDraft) -> Unit) {
    LabeledSlider(
        title = "Threshold",
        description = "How much a pixel's brightness must change to count as motion. Default: 30",
        value = draft.threshold.toFloat(),
        display = "${draft.threshold}",
        range = 10f..80f
    ) { onChange(draft.copy(threshold = it.toInt())) }
    LabeledSlider(
        title = "Contour Area",
        description = "Which groups of changed pixels qualify as motion. Default: 10",
        value = draft.contourArea.toFloat(),
        display = "${draft.contourArea}",
        range = 10f..200f
    ) { onChange(draft.copy(contourArea = it.toInt())) }
    SettingRow("Improve Contrast", description = "Improve contrast for darker scenes. Default: ON") {
        FrigateSwitch(draft.improveContrast, { onChange(draft.copy(improveContrast = it)) })
    }
}

@Composable
fun GlobalMotionPage(state: NvrUiState, actions: NvrActions) {
    val saver = rememberConfigSaver(actions)
    val stored = remember(state.configYaml) { CameraYamlEditor.readGlobalMotion(state.configYaml) }
    var draft by remember(stored) { mutableStateOf(stored) }

    PageText("Motion detection defaults for every camera. Use the Motion tuner to adjust one camera while watching it.")
    MotionControls(draft) { draft = it }
    SaveRow(enabled = draft != stored, saving = saver.saving, onSave = { saver.save(CameraYamlEditor.setMotion(state.configYaml, null, draft)) }) {
        draft = stored
    }
}

// ---------------------------------------------------------------------------
// Cameras: motion tuner
// ---------------------------------------------------------------------------

@Composable
private fun CameraPreview(
    camera: CameraConfigEntity,
    state: NvrUiState,
    frame: Bitmap?,
    wide: Boolean,
    overlay: @Composable () -> Unit
) {
    Box(
        Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .aspectRatio(camera.detectWidth.toFloat() / camera.detectHeight.coerceAtLeast(1))
            // The overlays are siblings of the tile, so they need the tile's corners too
            .clip(com.zektopic.frigate.ui.components.tileShape(wide))
    ) {
        LiveTile(
            camera = camera,
            frame = frame,
            streamState = state.streamState(camera.id),
            overBudget = camera.name in state.camerasOverBudget,
            motion = false,
            alerting = false,
            showName = false,
            wide = wide,
            compact = true,
            modifier = Modifier.fillMaxSize()
        )
        overlay()
    }
}

/** The web UI's Motion Tuner: watch the motion boxes while adjusting what produces them. */
@Composable
fun MotionTunerPage(state: NvrUiState, frames: Map<String, Bitmap>, actions: NvrActions, wide: Boolean) {
    if (state.cameras.isEmpty()) {
        NoCamerasNote()
        return
    }
    val saver = rememberConfigSaver(actions)
    var cameraId by rememberSaveable { mutableStateOf(state.cameras.first().id) }
    val camera = state.cameras.firstOrNull { it.id == cameraId } ?: state.cameras.first()
    val runtime = remember(state.configYaml, camera.id) {
        YamlConfigParser.parseRuntimeConfigs(state.configYaml)[camera.id] ?: CameraRuntimeConfig()
    }
    val stored = remember(runtime) { MotionDraft.from(runtime.motion) }
    var draft by remember(stored) { mutableStateOf(stored) }
    val frame = frames[camera.id]

    PageText(
        "Frigate uses motion detection as a first check for anything happening in the frame. The red boxes show " +
            "what the saved settings detect on this camera; save to see a change take effect."
    )
    CameraSelector(state.cameras, camera) { cameraId = it }
    CameraPreview(camera, state, frame, wide) {
        if (frame != null) {
            val boxes = remember(frame) { actions.motionBoxes(camera.id) }
            DebugOverlay(runtime, boxes, frame.width, frame.height)
        }
    }
    MotionControls(draft) { draft = it }
    SaveRow(enabled = draft != stored, saving = saver.saving, onSave = { saver.save(CameraYamlEditor.setMotion(state.configYaml, camera.id, draft)) }) {
        draft = stored
    }
}

// ---------------------------------------------------------------------------
// Cameras: masks and zones
// ---------------------------------------------------------------------------

private enum class Drawing { NONE, ZONE, MASK }

/**
 * The web UI's Masks / Zones editor: draw a polygon on the camera's picture to name an
 * area (a zone) or to have motion in it ignored (a motion mask).
 */
@Composable
fun MasksAndZonesPage(state: NvrUiState, frames: Map<String, Bitmap>, actions: NvrActions, wide: Boolean) {
    if (state.cameras.isEmpty()) {
        NoCamerasNote()
        return
    }
    val colors = FrigateTheme.colors
    val saver = rememberConfigSaver(actions)
    var cameraId by rememberSaveable { mutableStateOf(state.cameras.first().id) }
    val camera = state.cameras.firstOrNull { it.id == cameraId } ?: state.cameras.first()
    val runtime = remember(state.configYaml, camera.id) {
        YamlConfigParser.parseRuntimeConfigs(state.configYaml)[camera.id] ?: CameraRuntimeConfig()
    }
    val masks = remember(state.configYaml, camera.id) {
        CameraYamlEditor.readMotionMasks(state.configYaml, camera.id, camera.detectWidth, camera.detectHeight)
    }
    var drawing by remember(camera.id) { mutableStateOf(Drawing.NONE) }
    var points by remember(camera.id) { mutableStateOf(emptyList<RelPoint>()) }
    var zoneName by remember(camera.id) { mutableStateOf("") }

    fun stopDrawing() {
        drawing = Drawing.NONE
        points = emptyList()
        zoneName = ""
    }

    CameraSelector(state.cameras, camera) { cameraId = it }
    CameraPreview(camera, state, frames[camera.id], wide) {
        DebugOverlay(runtime, emptyList(), 0, 0)
        if (drawing != Drawing.NONE) {
            val color = if (drawing == Drawing.ZONE) ZoneColors[runtime.zones.size % ZoneColors.size] else Color(0xFFEF4444)
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(drawing) {
                        detectTapGestures { tap ->
                            points = points + RelPoint(
                                (tap.x / size.width).coerceIn(0f, 1f),
                                (tap.y / size.height).coerceIn(0f, 1f)
                            )
                        }
                    }
            ) {
                if (points.size >= 2) {
                    val path = Path().apply {
                        points.forEachIndexed { index, p ->
                            if (index == 0) moveTo(p.x * size.width, p.y * size.height)
                            else lineTo(p.x * size.width, p.y * size.height)
                        }
                        if (points.size >= 3) close()
                    }
                    if (points.size >= 3) drawPath(path, color.copy(alpha = 0.25f))
                    drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                }
                for (p in points) {
                    drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(p.x * size.width, p.y * size.height))
                    drawCircle(color, radius = 3.5.dp.toPx(), center = Offset(p.x * size.width, p.y * size.height))
                }
            }
        }
    }

    if (drawing != Drawing.NONE) {
        Heading(if (drawing == Drawing.ZONE) "New Zone" else "New Motion Mask")
        PageText(
            if (drawing == Drawing.ZONE) "Tap the picture to place each corner of the zone. Motion whose lower edge falls inside is attributed to it."
            else "Tap the picture to place each corner. Motion inside the mask is ignored: use it for timestamps, trees and sky, not for areas you simply do not care about."
        )
        if (drawing == Drawing.ZONE) {
            OutlinedTextField(
                value = zoneName,
                onValueChange = { zoneName = it },
                label = { Text("Name") },
                singleLine = true,
                textStyle = FrigateType.sm,
                shape = RoundedMd,
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                colors = frigateTextFieldColors(),
                supportingText = {
                    val id = CameraYamlEditor.zoneId(zoneName)
                    if (id.isNotEmpty()) Text("Saved as $id")
                }
            )
        }
        val zoneId = CameraYamlEditor.zoneId(zoneName)
        val canSave = points.size >= 3 && (drawing == Drawing.MASK || zoneId.isNotEmpty())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrigateButton(onClick = { points = points.dropLast(1) }, enabled = points.isNotEmpty(), small = false) { Text("Undo") }
            FrigateButton(onClick = { stopDrawing() }, small = false) { Text("Cancel") }
            FrigateButton(
                onClick = {
                    val yaml = if (drawing == Drawing.ZONE) CameraYamlEditor.setZone(state.configYaml, camera.id, zoneId, points)
                    else CameraYamlEditor.addMotionMask(state.configYaml, camera.id, points)
                    saver.save(yaml, if (drawing == Drawing.ZONE) "Zone saved" else "Motion mask saved")
                    stopDrawing()
                },
                enabled = canSave && !saver.saving,
                variant = ButtonVariant.Select,
                small = false
            ) { Text("Save") }
        }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Heading("Zones")
            PageText("Zones let you name an area of the frame, so alerts can be limited to it.")
        }
        FrigateButton(onClick = { drawing = Drawing.ZONE }) {
            Icon(Icons.Filled.Add, contentDescription = "Add zone", tint = colors.secondaryForeground, modifier = Modifier.size(18.dp))
        }
    }
    runtime.zones.forEachIndexed { index, zone ->
        PolygonRow(zone.name.replace('_', ' '), ZoneColors[index % ZoneColors.size]) {
            saver.save(CameraYamlEditor.removeZone(state.configYaml, camera.id, zone.name), "Zone removed")
        }
    }
    HorizontalDivider(color = colors.secondary)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Heading("Motion Masks")
            PageText("Motion masks stop unwanted kinds of motion from triggering detection.")
        }
        FrigateButton(onClick = { drawing = Drawing.MASK }) {
            Icon(Icons.Filled.Add, contentDescription = "Add motion mask", tint = colors.secondaryForeground, modifier = Modifier.size(18.dp))
        }
    }
    masks.forEachIndexed { index, mask ->
        PolygonRow("Motion Mask ${index + 1}", Color(0xFF9CA3AF)) {
            saver.save(CameraYamlEditor.removeMotionMask(state.configYaml, camera.id, mask.key), "Motion mask removed")
        }
    }
}

@Composable
private fun PolygonRow(name: String, color: Color, onDelete: () -> Unit) {
    val colors = FrigateTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedLg)
            .background(colors.backgroundAlt)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Filled.Circle, contentDescription = null, tint = color, modifier = Modifier.size(10.dp))
        Text(name, style = FrigateType.sm, color = colors.primary, modifier = Modifier.weight(1f))
        Icon(
            Icons.Outlined.Delete,
            contentDescription = "Delete $name",
            tint = colors.secondaryForeground,
            modifier = Modifier.clip(RoundedMd).clickable(onClick = onDelete).padding(6.dp).size(18.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// System
// ---------------------------------------------------------------------------

@Composable
fun MqttPage(state: NvrUiState, actions: NvrActions) {
    val saver = rememberConfigSaver(actions)
    val stored = remember(state.configYaml) { CameraYamlEditor.readGlobals(state.configYaml) }
    var draft by remember(stored) { mutableStateOf(stored) }

    PageText(
        "The broker Frigate publishes to. These values are kept in the configuration so it stays interchangeable " +
            "with a Frigate server's; this app does not publish to MQTT itself."
    )
    OutlinedTextField(
        value = draft.mqttHost,
        onValueChange = { draft = draft.copy(mqttHost = it) },
        label = { Text("Host") },
        singleLine = true,
        textStyle = FrigateType.sm,
        shape = RoundedMd,
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
        colors = frigateTextFieldColors()
    )
    OutlinedTextField(
        value = if (draft.mqttPort == 0) "" else draft.mqttPort.toString(),
        onValueChange = { text -> draft = draft.copy(mqttPort = text.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0) },
        label = { Text("Port") },
        singleLine = true,
        textStyle = FrigateType.sm,
        shape = RoundedMd,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
        colors = frigateTextFieldColors()
    )
    SaveRow(enabled = draft != stored, saving = saver.saving, onSave = { saver.save(CameraYamlEditor.upsertGlobals(state.configYaml, draft)) }) {
        draft = stored
    }
}

@Composable
fun StoragePage(actions: NvrActions) {
    val colors = FrigateTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { RecordingStore(context) }

    var totalBytes by remember { mutableLongStateOf(0L) }
    var clipCount by remember { mutableIntStateOf(0) }
    var location by remember { mutableStateOf(store.displayLocation()) }
    var isCustom by remember { mutableStateOf(store.isCustomDestination) }
    var available by remember { mutableStateOf(true) }
    var freeBytes by remember { mutableStateOf<Long?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }

    // The system folder picker. Any tree it returns is fair game: internal storage,
    // an SD card, a USB stick, or a cloud provider.
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val tree = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && tree != null) {
            Toast.makeText(
                context,
                if (store.setDestination(tree)) "Recordings will be saved to the selected folder"
                else "Could not keep access to that folder",
                Toast.LENGTH_LONG
            ).show()
            refreshKey++
        }
    }

    LaunchedEffect(refreshKey) {
        withContext(Dispatchers.IO) {
            val entries = store.list()
            totalBytes = entries.sumOf { it.sizeBytes }
            clipCount = entries.count { it.name.endsWith(".mp4", ignoreCase = true) }
            location = store.displayLocation()
            isCustom = store.isCustomDestination
            available = store.isAvailable()
            freeBytes = store.volumeStats()?.second
        }
    }

    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            StorageStat("Recordings", "$clipCount", Modifier.weight(1f))
            StorageStat("On disk", formatBytes(totalBytes), Modifier.weight(1f))
            StorageStat("Free", freeBytes?.let { formatBytes(it) } ?: "—", Modifier.weight(1f))
        }
    }

    Heading("Recording location")
    Text(location, style = FrigateType.sm, color = colors.primary)
    PageText(if (isCustom) "Custom folder" else "Default app storage")
    // A persisted folder grant outlives the card. Say so, loudly, rather than letting
    // the service quietly fall back for a week.
    if (!available) {
        Text(
            "This folder is not reachable right now: the card may be removed or reformatted. " +
                "New recordings are being saved to the default location.",
            style = FrigateType.sm,
            color = colors.danger
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FrigateButton(onClick = { pickFolder.launch(RecordingStore.pickerIntent()) }, small = false) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = colors.secondaryForeground, modifier = Modifier.size(18.dp))
            Text("Choose folder")
        }
        if (isCustom) {
            FrigateButton(
                onClick = {
                    store.clearDestination()
                    refreshKey++
                    Toast.makeText(context, "Using default storage", Toast.LENGTH_SHORT).show()
                },
                small = false
            ) { Text("Use default") }
        }
    }
    PageText("Changing the location affects new recordings only. Existing clips stay where they are and keep playing.")
    PageText("Recording never stops for lack of space: when free space drops below 1 GB, the oldest recordings are deleted until 2 GB is free.")

    HorizontalDivider(color = colors.secondary)
    FrigateButton(
        onClick = { confirmClear = true },
        enabled = !clearing && totalBytes > 0,
        variant = ButtonVariant.Destructive,
        small = false
    ) {
        if (clearing) ActivityIndicator(size = 16.dp)
        else Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("Delete all recordings")
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Delete all recordings?",
            text = "This permanently deletes every recorded clip, snapshot and review item, in the current folder and in the default location. This cannot be undone.",
            confirmLabel = "Delete all",
            destructive = true,
            onConfirm = {
                confirmClear = false
                clearing = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.deleteAll() }
                        actions.clearAllRecordings()
                        Toast.makeText(context, "Recordings deleted", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        clearing = false
                        refreshKey++
                    }
                }
            },
            onDismiss = { confirmClear = false }
        )
    }
}

@Composable
private fun StorageStat(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = FrigateTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = FrigateType.xl.copy(fontWeight = FontWeight.SemiBold), color = colors.primary)
        Text(label, style = FrigateType.xs, color = colors.mutedForeground)
    }
}

/** How hard this device is asked to work: the fork's low-resource switch and the stream cap. */
@Composable
fun PerformancePage(state: NvrUiState, actions: NvrActions) {
    val colors = FrigateTheme.colors
    val saver = rememberConfigSaver(actions)
    val lowResource = remember(state.configYaml) { YamlConfigParser.parseLowResource(state.configYaml) }
    val streamCap = remember(state.configYaml) { YamlConfigParser.parseMaxConcurrentStreams(state.configYaml) }
    val budget = DevicePerformance.cachedBudget()

    PageText("The app measures the device when the NVR starts and stays inside what it can sustain. These settings override that measurement.")
    SettingRow(
        "Low-resource mode",
        description = "Pin the device to its cheapest tier: at most 2 streams, 1 recording at a time, 3 FPS detection. For a device that overheats or is short of memory."
    ) {
        FrigateSwitch(lowResource, { saver.save(CameraYamlEditor.setLowResource(state.configYaml, it), null) }, enabled = !saver.saving)
    }
    SettingRow(
        "Concurrent streams",
        description = "How many cameras to run at once. Automatic uses the measured limit; a number you set is still capped by the device's decoder pool."
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FrigateButton(
                onClick = { saver.save(CameraYamlEditor.setMaxConcurrentStreams(state.configYaml, ((streamCap ?: 2) - 1).takeIf { it >= 1 }), null) },
                enabled = streamCap != null && !saver.saving
            ) { Icon(Icons.Filled.Remove, contentDescription = "Fewer", modifier = Modifier.size(16.dp)) }
            Text(
                streamCap?.toString() ?: "Auto",
                style = FrigateType.sm.copy(fontWeight = FontWeight.Medium),
                color = colors.primary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            FrigateButton(
                onClick = { saver.save(CameraYamlEditor.setMaxConcurrentStreams(state.configYaml, (streamCap ?: 0) + 1), null) },
                enabled = !saver.saving && (streamCap ?: 0) < 32
            ) { Icon(Icons.Filled.Add, contentDescription = "More", modifier = Modifier.size(16.dp)) }
        }
    }
    if (budget != null) {
        Panel(Modifier.fillMaxWidth()) {
            Text("In effect now", style = FrigateType.sm.copy(fontWeight = FontWeight.Medium), color = colors.primary)
            Text(
                "${DevicePerformance.effectiveMaxStreams(budget, DevicePerformance.streamCapOverride)} streams ∙ " +
                    "${budget.maxConcurrentEncoders} recordings ∙ ${budget.detectFpsCap} FPS ∙ " +
                    "${budget.tier.name.lowercase()} tier",
                style = FrigateType.xs,
                color = colors.mutedForeground,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Notifications and About
// ---------------------------------------------------------------------------

@Composable
fun NotificationsPage(state: NvrUiState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPreferences(context.applicationContext) }
    var settings by remember { mutableStateOf(NotificationSettings()) }
    LaunchedEffect(Unit) { prefs.notificationSettings.collectLatest { settings = it } }

    PageText("Alerts are posted for review items filed as alerts. Detections never notify.")
    SettingRow("Enable notifications", description = "Master switch for every alert") {
        FrigateSwitch(settings.notificationsEnabled, { scope.launch { prefs.setNotificationsEnabled(it) } })
    }
    SettingRow("Alert on motion", description = "Post a notification when an alert starts", enabled = settings.notificationsEnabled) {
        FrigateSwitch(settings.notifyOnMotion, { scope.launch { prefs.setNotifyOnMotion(it) } }, enabled = settings.notificationsEnabled)
    }
    if (state.cameras.isNotEmpty()) {
        HorizontalDivider(color = FrigateTheme.colors.secondary)
        Heading("Cameras")
        val enabled = settings.notificationsEnabled && settings.notifyOnMotion
        for (camera in state.cameras) {
            val muted = camera.id in settings.mutedCameraIds
            SettingRow(camera.name, description = if (muted) "Muted" else "Alerts on", enabled = enabled) {
                FrigateSwitch(!muted, { on -> scope.launch { prefs.setCameraMuted(camera.id, !on) } }, enabled = enabled)
            }
        }
    }
}

@Composable
fun AboutPage(state: NvrUiState) {
    val colors = FrigateTheme.colors
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
        } catch (e: Exception) {
            "—"
        }
    }
    val configVersion = remember(state.configYaml) {
        Regex("(?m)^version:\\s*(.+)$").find(state.configYaml)?.groupValues?.get(1)?.trim() ?: "—"
    }
    // Read on every composition, not remembered: the destination can change on the
    // Storage page, and a pinned value would keep showing the old folder.
    val storagePath = RecordingStore(context).displayLocation()

    Panel(Modifier.fillMaxWidth()) {
        val rows = listOf(
            "App version" to versionName,
            "Config version" to configVersion,
            "Cameras configured" to "${state.cameras.size}",
            "Android API" to "${android.os.Build.VERSION.SDK_INT}",
            "Device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "Web console" to "Port 8080 on this device"
        )
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) HorizontalDivider(color = colors.secondary)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = FrigateType.sm, color = colors.mutedForeground)
                Text(value, style = FrigateType.sm, color = colors.primary)
            }
        }
    }
    Heading("Recordings path")
    PageText(storagePath)
}
