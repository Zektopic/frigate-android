package com.zektopic.frigate.ui.live

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.zektopic.frigate.ai.MotionBox
import com.zektopic.frigate.ai.RelPoint
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.data.CameraRuntimeConfig
import com.zektopic.frigate.data.YamlConfigParser
import com.zektopic.frigate.media.StreamDiagnostics
import com.zektopic.frigate.ui.NvrActions
import com.zektopic.frigate.ui.NvrUiState
import com.zektopic.frigate.ui.components.ButtonVariant
import com.zektopic.frigate.ui.components.FrigateButton
import com.zektopic.frigate.ui.components.FrigateSwitch
import com.zektopic.frigate.ui.components.IconSquareButton
import com.zektopic.frigate.ui.components.RoundedLg
import com.zektopic.frigate.ui.components.RoundedMd
import com.zektopic.frigate.ui.components.SettingRow
import com.zektopic.frigate.ui.settings.CameraFeatures
import com.zektopic.frigate.ui.settings.CameraYamlEditor
import com.zektopic.frigate.ui.theme.FrigateTheme
import com.zektopic.frigate.ui.theme.FrigateType
import kotlinx.coroutines.launch

/** Zone outline colours, cycled in the order zones are declared. */
val ZoneColors = listOf(
    Color(0xFF22C55E), Color(0xFF3B82F6), Color(0xFFEAB308), Color(0xFFA855F7),
    Color(0xFF14B8A6), Color(0xFFF97316), Color(0xFFEC4899)
)

/**
 * A single camera, full size: the web UI's `LiveCameraView`. Back and History on the
 * left of the top bar, the camera's feature toggles on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCameraView(
    camera: CameraConfigEntity,
    state: NvrUiState,
    frame: Bitmap?,
    actions: NvrActions,
    wide: Boolean,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val colors = FrigateTheme.colors
    val scope = rememberCoroutineScope()
    var showStats by rememberSaveable { mutableStateOf(false) }
    var debug by rememberSaveable { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }

    val features = remember(state.configYaml, camera.id) {
        CameraYamlEditor.readAllCameraFeatures(state.configYaml)[camera.id] ?: CameraFeatures()
    }
    val runtime = remember(state.configYaml, camera.id) {
        YamlConfigParser.parseRuntimeConfigs(state.configYaml)[camera.id] ?: CameraRuntimeConfig()
    }

    fun toggle(feature: CameraYamlEditor.Feature, enabled: Boolean) {
        scope.launch {
            try {
                actions.saveConfig(CameraYamlEditor.setCameraFeature(state.configYaml, camera.id, feature, enabled))
            } catch (_: Exception) {
                // The switch reads back from the saved config, so a failed save shows as no change.
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (wide) 12.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FrigateButton(onClick = onBack, shape = RoundedLg) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.secondaryForeground, modifier = Modifier.size(20.dp))
                    if (wide) Text("Back")
                }
                FrigateButton(onClick = onOpenHistory, shape = RoundedLg) {
                    Icon(Icons.Filled.History, contentDescription = "History", tint = colors.secondaryForeground, modifier = Modifier.size(20.dp))
                    if (wide) Text("History")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (wide) {
                    IconSquareButton(
                        icon = if (features.detect) Icons.Filled.Sensors else Icons.Filled.SensorsOff,
                        contentDescription = if (features.detect) "Disable detect" else "Enable detect",
                        active = features.detect,
                        onClick = { toggle(CameraYamlEditor.Feature.DETECT, !features.detect) }
                    )
                    IconSquareButton(
                        icon = if (features.record) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                        contentDescription = if (features.record) "Disable recording" else "Enable recording",
                        active = features.record,
                        onClick = { toggle(CameraYamlEditor.Feature.RECORD, !features.record) }
                    )
                    IconSquareButton(
                        icon = if (features.snapshots) Icons.Filled.PhotoCamera else Icons.Filled.NoPhotography,
                        contentDescription = if (features.snapshots) "Disable snapshots" else "Enable snapshots",
                        active = features.snapshots,
                        onClick = { toggle(CameraYamlEditor.Feature.SNAPSHOTS, !features.snapshots) }
                    )
                    IconSquareButton(Icons.Filled.QueryStats, "Stream stats", showStats, { showStats = !showStats })
                    IconSquareButton(Icons.Filled.BugReport, "Debug view", debug, { debug = !debug })
                } else {
                    IconSquareButton(Icons.Filled.Settings, "Camera settings", false, { showSheet = true }, size = 36.dp)
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val ratio = camera.detectWidth.toFloat() / camera.detectHeight.coerceAtLeast(1)
            val containerRatio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)

            // Pinch to zoom, drag to pan while zoomed, double-tap to reset
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val transform = rememberTransformableState { zoom, pan, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                offset = if (scale == 1f) Offset.Zero else offset + pan
            }

            Box(
                modifier = Modifier
                    .aspectRatio(ratio, matchHeightConstraintsFirst = containerRatio > ratio)
                    .clip(RoundedLg)
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        })
                    }
                    .transformable(transform)
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                ) {
                    LiveTile(
                        camera = camera,
                        frame = frame,
                        streamState = state.streamState(camera.id),
                        overBudget = camera.name in state.camerasOverBudget,
                        motion = camera.id in state.activeMotion,
                        alerting = camera.id in state.activeAlerts,
                        showName = false,
                        wide = wide,
                        compact = false,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    if (debug && frame != null) {
                        // Re-read on every new frame; the boxes belong to a frame, not to time.
                        val boxes = remember(frame) { actions.motionBoxes(camera.id) }
                        DebugOverlay(runtime, boxes, frame.width, frame.height)
                    }
                }
                if (showStats) PlayerStats(camera, state, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = colors.background,
            contentColor = colors.primary
        ) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingRow("Detect", description = "Log motion events and send alerts") {
                    FrigateSwitch(features.detect, { toggle(CameraYamlEditor.Feature.DETECT, it) })
                }
                SettingRow("Recording", description = "Save a clip when there is motion") {
                    FrigateSwitch(features.record, { toggle(CameraYamlEditor.Feature.RECORD, it) })
                }
                SettingRow("Snapshots", description = "Save a still image with each event") {
                    FrigateSwitch(features.snapshots, { toggle(CameraYamlEditor.Feature.SNAPSHOTS, it) })
                }
                SettingRow("Stream stats", description = "Decoder, resolution and frame rates") {
                    FrigateSwitch(showStats, { showStats = it })
                }
                SettingRow("Debug view", description = "Draw motion boxes, zones and masks") {
                    FrigateSwitch(debug, { debug = it })
                }
            }
        }
    }
}

/** Motion boxes in red, zones outlined in their colour, masks hatched out. */
@Composable
fun DebugOverlay(runtime: CameraRuntimeConfig, boxes: List<MotionBox>, frameWidth: Int, frameHeight: Int) {
    Canvas(Modifier.fillMaxSize()) {
        val stroke = Stroke(width = 2.dp.toPx())
        for (mask in runtime.motion.masks) {
            val path = polygonPath(mask)
            drawPath(path, Color.Black.copy(alpha = 0.55f))
            drawPath(path, Color(0xFF9CA3AF), style = stroke)
        }
        runtime.zones.forEachIndexed { index, zone ->
            val color = ZoneColors[index % ZoneColors.size]
            val path = polygonPath(zone.polygon)
            drawPath(path, color.copy(alpha = 0.15f))
            drawPath(path, color, style = stroke)
        }
        if (frameWidth > 0 && frameHeight > 0) {
            val sx = size.width / frameWidth
            val sy = size.height / frameHeight
            for (box in boxes) {
                drawRect(
                    color = Color(0xFFEF4444),
                    topLeft = Offset(box.x1 * sx, box.y1 * sy),
                    size = Size((box.x2 - box.x1) * sx, (box.y2 - box.y1) * sy),
                    style = stroke
                )
            }
        }
    }
}

fun DrawScope.polygonPath(points: List<RelPoint>): Path = Path().apply {
    points.forEachIndexed { index, point ->
        val x = point.x * size.width
        val y = point.y * size.height
        if (index == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** The web UI's `PlayerStats` box. Every value is measured; unknown ones show a dash. */
@Composable
private fun PlayerStats(camera: CameraConfigEntity, state: NvrUiState, modifier: Modifier = Modifier) {
    val diag = StreamDiagnostics.byCamera[camera.id]
    val stats = state.stats.lastOrNull()?.cameras?.get(camera.id)
    val rows = listOf(
        "Stream Type" to "RTSP",
        "Resolution" to (diag?.takeIf { it.videoWidth > 0 }?.let { "${it.videoWidth}x${it.videoHeight}" } ?: "—"),
        "Detect" to "${camera.detectWidth}x${camera.detectHeight}",
        "Decoder" to (diag?.decoderName?.substringAfterLast('.')?.let { name ->
            when (diag.hardwareAccelerated) {
                true -> "$name (HW)"
                false -> "$name (SW)"
                null -> name
            }
        } ?: "—"),
        "Camera FPS" to (stats?.let { "%.1f".format(it.cameraFps) } ?: "—"),
        "Detect FPS" to (stats?.let { "%.1f".format(it.detectFps) } ?: "—"),
        "Motion" to (stats?.let { "%.1f ms".format(it.motionMs) } ?: "—"),
        "Reconnects" to "${diag?.reconnectCount ?: 0}"
    )
    Column(
        modifier = modifier
            .clip(RoundedMd)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for ((label, value) in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$label:", style = FrigateType.xs, color = Color.White.copy(alpha = 0.7f))
                Text(value, style = FrigateType.xs, color = Color.White)
            }
        }
    }
}
