package com.zektopic.frigate.ui.dashboard

import android.widget.Toast
import android.widget.VideoView
import android.widget.MediaController
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.data.EventEntity
import com.zektopic.frigate.media.StreamState
import com.zektopic.frigate.ui.settings.CameraFeatures
import com.zektopic.frigate.ui.settings.CameraYamlEditor
import com.zektopic.frigate.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    cameraConfigs: List<CameraConfigEntity>,
    events: List<EventEntity>,
    systemConfig: com.zektopic.frigate.data.SystemConfigEntity?,
    latestFrames: Map<String, Bitmap>,
    streamStates: Map<String, StreamState>,
    onSaveConfig: suspend (String) -> Unit,
    onAddMockCamera: () -> Unit,
    onTriggerTestNotification: () -> Unit,
    onStartNvrService: () -> Unit,
    onStopNvrService: () -> Unit,
    onClearAllRecordings: suspend () -> Unit,
    isServiceRunning: Boolean
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val settingsScope = rememberCoroutineScope()

    // Per-camera live feature flags, parsed once from the persisted YAML. The tile
    // toggles read these and write back through onSaveConfig (YAML = source of truth).
    val persistedConfigYaml = systemConfig?.configYaml ?: ""
    val cameraFeatures = remember(persistedConfigYaml) {
        CameraYamlEditor.readAllCameraFeatures(persistedConfigYaml)
    }
    val onToggleFeature: (String, CameraYamlEditor.Feature, Boolean) -> Unit =
        { cameraId, feature, enabled ->
            settingsScope.launch {
                try {
                    onSaveConfig(CameraYamlEditor.setCameraFeature(persistedConfigYaml, cameraId, feature, enabled))
                } catch (_: Exception) { /* surfaced via config reload */ }
            }
        }

    // Real app CPU usage: delta of process CPU time over wall time, normalized per core
    var currentCpuUsage by remember { mutableIntStateOf(0) }
    LaunchedEffect(isServiceRunning) {
        if (isServiceRunning) {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            var lastCpuTime = android.os.Process.getElapsedCpuTime()
            var lastWallTime = System.currentTimeMillis()
            while (true) {
                delay(2000)
                val cpuTime = android.os.Process.getElapsedCpuTime()
                val wallTime = System.currentTimeMillis()
                val elapsed = (wallTime - lastWallTime).coerceAtLeast(1)
                currentCpuUsage = (100.0 * (cpuTime - lastCpuTime) / (elapsed * cores)).toInt().coerceIn(0, 100)
                lastCpuTime = cpuTime
                lastWallTime = wallTime
            }
        } else {
            currentCpuUsage = 0
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Screen content router — sections mirror the Frigate web UI
    @Composable
    fun TargetScreenContent(tabIndex: Int) {
        when (tabIndex) {
            0 -> LiveDashboardScreen(
                cameraConfigs = cameraConfigs,
                latestFrames = latestFrames,
                streamStates = streamStates,
                events = events,
                isServiceRunning = isServiceRunning,
                onStart = onStartNvrService,
                onStop = onStopNvrService,
                onAddMockCamera = onAddMockCamera,
                currentCpu = currentCpuUsage,
                cameraFeatures = cameraFeatures,
                onToggleFeature = onToggleFeature
            )
            1 -> RecordingsScreen(
                events = events
            )
            2 -> SystemScreen(
                cameraConfigs = cameraConfigs,
                isServiceRunning = isServiceRunning,
                cpu = currentCpuUsage,
                streamStates = streamStates
            )
            3 -> ConfigScreen(
                systemConfig = systemConfig,
                cameraConfigs = cameraConfigs,
                events = events,
                onSaveConfig = onSaveConfig,
                onClearAllRecordings = onClearAllRecordings
            )
        }
    }

    if (isLandscape) {
        // Landscape Orientation: Sidebar layout
        Row(modifier = Modifier.fillMaxSize().background(SlateBg)) {
            NavigationRail(
                containerColor = SlateNav,
                contentColor = FrigateBlue,
                modifier = Modifier.drawBehind {
                    val strokeWidth = 1.dp.toPx()
                    drawLine(
                        color = SlateBorder,
                        start = Offset(size.width - strokeWidth / 2, 0f),
                        end = Offset(size.width - strokeWidth / 2, size.height),
                        strokeWidth = strokeWidth
                    )
                }
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Frigate Logo",
                    tint = FrigateBlue,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.weight(1f))

                val destinations = listOf("Live", "Review", "System", "Settings")
                val icons = listOf(
                    Icons.Default.Videocam,
                    Icons.Default.History,
                    Icons.Default.Monitor,
                    Icons.Default.Settings
                )

                destinations.forEachIndexed { index, title ->
                    NavigationRailItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icons[index], contentDescription = title) },
                        label = { Text(title, fontSize = 10.sp, fontWeight = FontWeight.Medium) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = TextPrimary,
                            selectedTextColor = TextPrimary,
                            indicatorColor = FrigateBlue,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted
                        )
                    )
                }
                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = onTriggerTestNotification) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = "Test Notification",
                        tint = FrigateBlue
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                TargetScreenContent(selectedTab)
            }
        }
    } else {
        // Portrait Orientation: Bottom Navigation layout
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Frigate Android Icon",
                                tint = FrigateBlue
                            )
                            Text(
                                text = "Frigate",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = TextPrimary
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onTriggerTestNotification) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = "Test Notification",
                                tint = FrigateBlue
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = SlateBg
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = SlateNav,
                    modifier = Modifier.drawBehind {
                        val strokeWidth = 1.dp.toPx()
                        drawLine(
                            color = SlateBorder,
                            start = Offset(0f, strokeWidth / 2),
                            end = Offset(size.width, strokeWidth / 2),
                            strokeWidth = strokeWidth
                        )
                    }
                ) {
                    val destinations = listOf("Live", "Review", "System", "Settings")
                    val icons = listOf(
                        Icons.Default.Videocam,
                        Icons.Default.History,
                        Icons.Default.Monitor,
                        Icons.Default.Settings
                    )

                    destinations.forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(icons[index], contentDescription = title) },
                            label = { Text(title, fontSize = 9.sp, fontWeight = FontWeight.Medium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = TextPrimary,
                                selectedTextColor = TextPrimary,
                                indicatorColor = FrigateBlue,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted
                            )
                        )
                    }
                }
            },
            containerColor = SlateBg
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                TargetScreenContent(selectedTab)
            }
        }
    }
}

// -------------------------------------------------------------
// Live Tab
// -------------------------------------------------------------
@Composable
fun LiveDashboardScreen(
    cameraConfigs: List<CameraConfigEntity>,
    latestFrames: Map<String, Bitmap>,
    streamStates: Map<String, StreamState>,
    events: List<EventEntity>,
    isServiceRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAddMockCamera: () -> Unit,
    currentCpu: Int,
    cameraFeatures: Map<String, CameraFeatures> = emptyMap(),
    onToggleFeature: (String, CameraYamlEditor.Feature, Boolean) -> Unit = { _, _, _ -> }
) {
    // Frigate's Live view groups: "All Cameras" grid or the composite Birdseye view
    var selectedGroup by remember { mutableIntStateOf(0) }
    var fullscreenCameraId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ServiceStatusHeaderCard(
            isServiceRunning = isServiceRunning,
            camerasTotal = cameraConfigs.size,
            camerasOnline = cameraConfigs.count { streamStates[it.id] == StreamState.LIVE },
            cpu = currentCpu,
            onStart = onStart,
            onStop = onStop
        )

        // Camera group selector pills, like Frigate's Live group bar
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All Cameras", "Birdseye").forEachIndexed { index, label ->
                val selected = selectedGroup == index
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) FrigateBlue else SlateCard)
                        .clickable { selectedGroup = index }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (selected) TextPrimary else TextMuted
                    )
                }
            }
        }

        if (selectedGroup == 1) {
            BirdseyeScreen(
                cameraConfigs = cameraConfigs,
                latestFrames = latestFrames,
                isServiceRunning = isServiceRunning,
                events = events
            )
        } else if (cameraConfigs.isEmpty()) {
            EmptyCamerasView(onAddMockCamera)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 320.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(cameraConfigs) { camera ->
                    CameraLiveFeedCard(
                        camera = camera,
                        latestFrame = latestFrames[camera.id],
                        streamState = streamStates[camera.id],
                        isServiceRunning = isServiceRunning,
                        features = cameraFeatures[camera.id] ?: CameraFeatures(),
                        onToggleFeature = { feature, enabled -> onToggleFeature(camera.id, feature, enabled) },
                        onClick = { fullscreenCameraId = camera.id }
                    )
                }
            }
        }
    }

    // Fullscreen single-camera view, like Frigate's camera page
    fullscreenCameraId?.let { camId ->
        val camera = cameraConfigs.find { it.id == camId }
        if (camera != null) {
            FullscreenCameraDialog(
                camera = camera,
                latestFrame = latestFrames[camId],
                streamState = if (isServiceRunning) streamStates[camId] else null,
                onDismiss = { fullscreenCameraId = null }
            )
        } else {
            fullscreenCameraId = null
        }
    }
}

@Composable
fun FullscreenCameraDialog(
    camera: CameraConfigEntity,
    latestFrame: Bitmap?,
    streamState: StreamState?,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss)
        ) {
            if (latestFrame != null && streamState == StreamState.LIVE) {
                androidx.compose.foundation.Image(
                    bitmap = latestFrame.asImageBitmap(),
                    contentDescription = camera.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.VideocamOff,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No live frames from this camera", color = TextMuted, fontSize = 13.sp)
                }
            }

            // Top bar overlay: name + status + close
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .align(Alignment.TopStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = camera.name,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                val (label, color) = when (streamState) {
                    StreamState.LIVE -> "Live" to StatusGreen
                    StreamState.CONNECTING -> "Connecting" to TextMuted
                    StreamState.RETRYING -> "Retrying" to StatusAmber
                    StreamState.OFFLINE -> "Offline" to StatusRed
                    null -> "Stopped" to TextMuted
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.width(12.dp))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                }
            }
        }
    }
}

@Composable
fun ServiceStatusHeaderCard(
    isServiceRunning: Boolean,
    camerasTotal: Int,
    camerasOnline: Int,
    cpu: Int,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    // Compact single-row status bar keeps vertical space for the camera wall
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (isServiceRunning) StatusGreen else StatusRed)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isServiceRunning) "NVR running" else "NVR stopped",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    text = if (isServiceRunning)
                        "$camerasTotal cameras · $camerasOnline online · CPU $cpu%"
                    else
                        "$camerasTotal cameras configured",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
            Button(
                onClick = {
                    if (isServiceRunning) {
                        onStop()
                        Toast.makeText(context, "NVR Service Stopped", Toast.LENGTH_SHORT).show()
                    } else {
                        onStart()
                        Toast.makeText(context, "NVR Service Started", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning) StatusRed else FrigateBlue,
                    contentColor = TextPrimary
                ),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isServiceRunning) "Stop" else "Start",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun EmptyCamerasView(onAddMockCamera: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.VideocamOff,
            contentDescription = "No Cameras",
            tint = TextMuted,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "No Cameras Configured",
            color = TextMuted,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onAddMockCamera,
            colors = ButtonDefaults.buttonColors(containerColor = FrigateBlue, contentColor = SlateBg)
        ) {
            Text("Setup Sample Cameras", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun CameraLiveFeedCard(
    camera: CameraConfigEntity,
    latestFrame: Bitmap?,
    streamState: StreamState?,
    isServiceRunning: Boolean,
    features: CameraFeatures = CameraFeatures(),
    onToggleFeature: (CameraYamlEditor.Feature, Boolean) -> Unit = { _, _ -> },
    onClick: () -> Unit = {}
) {
    // Real, persisted per-camera toggles (backed by the camera YAML, consumed by the service)
    val isDetectEnabled = features.detect
    val isRecordEnabled = features.record
    val isSnapshotsEnabled = features.snapshots

    val effectiveState = if (isServiceRunning) streamState else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCard)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = camera.name,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = if (camera.rtspUrl.isEmpty()) "Local Camera Sensor" else camera.rtspUrl,
                        fontSize = 10.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Per-camera connection status chip driven by the real stream state
                val (chipText, chipColor) = when (effectiveState) {
                    StreamState.LIVE -> "LIVE" to StatusGreen
                    StreamState.CONNECTING -> "CONNECTING" to TextMuted
                    StreamState.RETRYING -> "RETRYING" to StatusAmber
                    StreamState.OFFLINE -> "OFFLINE" to StatusRed
                    null -> "STOPPED" to TextMuted
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(chipColor)
                    )
                    Text(
                        text = chipText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = chipColor
                    )
                }
            }

            // Live feed display area — 16:9 like Frigate's tiles, tap to expand
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color(0xFF020617))
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                when {
                    !isServiceRunning -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.PlayDisabled,
                                contentDescription = "Feed Paused",
                                tint = TextMuted,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Start the NVR service to stream", fontSize = 11.sp, color = TextMuted)
                        }
                    }
                    latestFrame != null && effectiveState == StreamState.LIVE -> {
                        androidx.compose.foundation.Image(
                            bitmap = latestFrame.asImageBitmap(),
                            contentDescription = "Live Feed",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                    effectiveState == StreamState.OFFLINE || effectiveState == StreamState.RETRYING -> {
                        // Frigate-style offline tile: honest error state, no fake frames
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.VideocamOff,
                                contentDescription = "Camera Offline",
                                tint = if (effectiveState == StreamState.OFFLINE) StatusRed else StatusAmber,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No frames have been received, check camera connection",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                            if (effectiveState == StreamState.RETRYING) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Reconnecting…", fontSize = 10.sp, color = StatusAmber)
                            }
                        }
                    }
                    else -> {
                        // CONNECTING (or LIVE without a frame yet)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = FrigateBlue,
                                strokeWidth = 2.dp,
                                trackColor = Color.Transparent
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Connecting…", fontSize = 11.sp, color = TextMuted)
                        }
                    }
                }

                // Overlay: camera name chip bottom-left, REC indicator top-left (Frigate style)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    if (effectiveState == StreamState.LIVE && isRecordEnabled) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.align(Alignment.TopStart)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(StatusRed)
                            )
                            Text(
                                text = "REC",
                                color = StatusRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = camera.name,
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Toggles Row (Detect, Record, Snapshots)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ToggleButton(label = "Detect", active = isDetectEnabled, onToggle = { onToggleFeature(CameraYamlEditor.Feature.DETECT, !isDetectEnabled) }, activeColor = StatusGreen)
                ToggleButton(label = "Record", active = isRecordEnabled, onToggle = { onToggleFeature(CameraYamlEditor.Feature.RECORD, !isRecordEnabled) }, activeColor = FrigateBlue)
                ToggleButton(label = "Snapshots", active = isSnapshotsEnabled, onToggle = { onToggleFeature(CameraYamlEditor.Feature.SNAPSHOTS, !isSnapshotsEnabled) }, activeColor = StatusAmber)
            }
        }
    }
}

@Composable
fun ToggleButton(label: String, active: Boolean, onToggle: () -> Unit, activeColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) activeColor.copy(alpha = 0.15f) else SlateCard)
            .border(1.dp, if (active) activeColor else SlateBorder, RoundedCornerShape(6.dp))
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = if (active) activeColor else TextMuted
        )
    }
}

// -------------------------------------------------------------
// Birdseye Tab
// -------------------------------------------------------------
@Composable
fun BirdseyeScreen(
    cameraConfigs: List<CameraConfigEntity>,
    latestFrames: Map<String, Bitmap>,
    isServiceRunning: Boolean,
    events: List<EventEntity> = emptyList()
) {
    // Re-evaluate "recent motion" on a ticking clock, driven by REAL motion
    // events from the detection pipeline (Frigate's Birdseye shows cameras
    // with current activity, never fabricated triggers)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isServiceRunning) {
        while (isServiceRunning) {
            now = System.currentTimeMillis()
            delay(5000)
        }
    }

    val motionWindowMs = 30_000L
    val camerasWithMotion = cameraConfigs.filter { camera ->
        events.any { it.cameraId == camera.id && now - it.timestamp <= motionWindowMs }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isServiceRunning) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Start the NVR service to activate Birdseye",
                    color = TextMuted,
                    fontWeight = FontWeight.Medium
                )
            }
        } else if (camerasWithMotion.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                    .background(SlateCard),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Birdseye Idle",
                        tint = TextMuted.copy(alpha = 0.4f),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No cameras with recent activity",
                        fontWeight = FontWeight.Medium,
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 250.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(camerasWithMotion) { camera ->
                    BirdseyeCameraCard(
                        camera = camera,
                        latestFrame = latestFrames[camera.id]
                    )
                }
            }
        }
    }
}

@Composable
fun BirdseyeCameraCard(camera: CameraConfigEntity, latestFrame: Bitmap?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCard)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Color(0xFF020617)),
            contentAlignment = Alignment.Center
        ) {
            if (latestFrame != null) {
                androidx.compose.foundation.Image(
                    bitmap = latestFrame.asImageBitmap(),
                    contentDescription = "Birdseye Feed",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.VideocamOff,
                    contentDescription = "No frame",
                    tint = TextMuted,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Camera name chip + motion badge, Frigate overlay style
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = camera.name,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(StatusGreen.copy(alpha = 0.85f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "Motion",
                    color = TextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// -------------------------------------------------------------
// Recordings Tab
// -------------------------------------------------------------
@Composable
fun RecordingsScreen(events: List<EventEntity>) {
    var selectedCameraFilter by remember { mutableStateOf("ALL") }
    var activePlaybackEvent by remember { mutableStateOf<EventEntity?>(null) }

    val cameraList = remember(events) {
        listOf("ALL") + events.map { it.cameraId }.distinct().sorted()
    }

    val filteredEvents = remember(events, selectedCameraFilter) {
        if (selectedCameraFilter == "ALL") {
            events
        } else {
            events.filter { it.cameraId == selectedCameraFilter }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        activePlaybackEvent?.let { event ->
            if (!event.videoPath.isNullOrEmpty()) {
                VideoPlayer(
                    videoUrl = event.videoPath,
                    onClose = { activePlaybackEvent = null }
                )
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth().border(1.dp, ErrorRed, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = SlateCard)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("No video playback available for this motion event.", color = ErrorRed, fontSize = 12.sp)
                        IconButton(onClick = { activePlaybackEvent = null }) {
                            Icon(Icons.Default.Close, "Close", tint = TextMuted)
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SlateBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = SlateCard)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Review",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = FrigateBlue
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Filter:", fontSize = 11.sp, color = TextMuted)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(cameraList) { cam ->
                            val selected = selectedCameraFilter == cam
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) FrigateBlue.copy(alpha = 0.15f) else SlateCard)
                                    .border(1.dp, if (selected) FrigateBlue else SlateBorder, RoundedCornerShape(6.dp))
                                    .clickable { selectedCameraFilter = cam }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (cam == "ALL") "All cameras" else cam.replace('_', ' '),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (selected) FrigateBlue else TextMuted
                                )
                            }
                        }
                    }
                }
            }
        }

        if (filteredEvents.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = TextMuted.copy(alpha = 0.4f),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No events yet", color = TextMuted, fontWeight = FontWeight.Medium)
                    Text(
                        "Motion events will appear here as they are detected",
                        color = TextMuted.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(filteredEvents) { event ->
                    RecordingListItem(
                        event = event,
                        onPlayClick = { activePlaybackEvent = event }
                    )
                }
            }
        }
    }
}

@Composable
fun VideoPlayer(videoUrl: String, onClose: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, FrigateBlue, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCard)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recording playback",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = FrigateBlue
                )
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close Video", tint = StatusRed)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
            ) {
                // setVideoURI, not setVideoPath: clips recorded to a user-chosen folder
                // are content:// documents, which setVideoPath(String) mangles.
                val videoUri = remember(videoUrl) {
                    com.zektopic.frigate.data.RecordingStore.toPlayableUri(videoUrl)
                }
                AndroidView(
                    factory = { context ->
                        VideoView(context).apply {
                            setVideoURI(videoUri)
                            tag = videoUrl
                            val mediaController = MediaController(context)
                            mediaController.setAnchorView(this)
                            setMediaController(mediaController)
                            setOnPreparedListener { mp ->
                                mp.isLooping = true
                                start()
                            }
                        }
                    },
                    update = { view ->
                        // Guard to avoid resetting same path during recomposition
                        if (view.tag != videoUrl) {
                            view.setVideoURI(videoUri)
                            view.tag = videoUrl
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Text(
                text = "File Stream: $videoUrl",
                fontSize = 9.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun RecordingListItem(event: EventEntity, onPlayClick: () -> Unit) {
    // Relative time ("2 min. ago") like Frigate's review list; exact time as detail
    val relativeTime = remember(event.timestamp) {
        android.text.format.DateUtils.getRelativeTimeSpanString(
            event.timestamp,
            System.currentTimeMillis(),
            android.text.format.DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }
    val dateFormatted = remember(event.timestamp) {
        val date = java.util.Date(event.timestamp)
        val format = java.text.SimpleDateFormat("MMM dd, HH:mm:ss", java.util.Locale.getDefault())
        format.format(date)
    }
    // Decode the snapshot thumbnail off the composition thread. The stored ref is an
    // absolute path for the default location and a document URI when recordings go to a
    // user-chosen folder — the latter is a provider round-trip, far too slow to do inline
    // while a LazyColumn is scrolling.
    val snapshotContext = LocalContext.current
    val snapshot by produceState<android.graphics.Bitmap?>(null, event.snapshotPath) {
        val ref = event.snapshotPath
        value = if (ref == null) null else withContext(Dispatchers.IO) {
            try {
                com.zektopic.frigate.data.RecordingStore(snapshotContext).openInput(ref)
                    ?.use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
        }
    }
    val hasClip = !event.videoPath.isNullOrEmpty()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SlateBorder, RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp, 40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(SlateNav),
                    contentAlignment = Alignment.Center
                ) {
                    if (snapshot != null) {
                        androidx.compose.foundation.Image(
                            bitmap = snapshot!!.asImageBitmap(),
                            contentDescription = "Event snapshot",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Column {
                    Text(
                        text = event.label.replaceFirstChar { it.uppercase() } + " detected",
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "${event.cameraId.replace('_', ' ')} · $relativeTime",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                    Text(
                        text = dateFormatted,
                        fontSize = 10.sp,
                        color = TextMuted.copy(alpha = 0.7f)
                    )
                }
            }

            if (hasClip) {
                Button(
                    onClick = onPlayClick,
                    colors = ButtonDefaults.buttonColors(containerColor = FrigateBlue, contentColor = TextPrimary),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text("Recording…", fontSize = 10.sp, color = TextMuted, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            }
        }
    }
}

// -------------------------------------------------------------
// System Tab
// -------------------------------------------------------------
@Composable
fun SystemScreen(
    cameraConfigs: List<CameraConfigEntity>,
    isServiceRunning: Boolean,
    cpu: Int,
    streamStates: Map<String, StreamState> = emptyMap()
) {
    val context = LocalContext.current
    var cpuVal by remember { mutableFloatStateOf(0.0f) }
    var ramVal by remember { mutableFloatStateOf(0.0f) }
    // Null when the recording destination is a provider whose free space Android will
    // not report (cloud/network trees) — the gauge shows nothing rather than quoting
    // internal storage's numbers while clips go somewhere else entirely.
    var diskVal by remember { mutableStateOf<Float?>(0.0f) }
    // Real decoder actually initialized per camera, read from StreamDiagnostics.
    // Empty until MediaCodec reports one — never a placeholder or a guess.
    var decoders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val recordingStore = remember { com.zektopic.frigate.data.RecordingStore(context) }

    // Real device metrics: RAM via ActivityManager, disk via StatFs
    LaunchedEffect(isServiceRunning, cpu) {
        cpuVal = if (isServiceRunning) cpu / 100.0f else 0.0f
        while (true) {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            ramVal = if (memInfo.totalMem > 0) {
                ((memInfo.totalMem - memInfo.availMem).toDouble() / memInfo.totalMem).toFloat()
            } else 0.0f

            // Measure the volume recordings actually land on, not always internal storage.
            // Off the main thread: volumeStats() walks every mounted volume.
            val volume = withContext(Dispatchers.IO) { recordingStore.volumeStats() }
            diskVal = volume?.let { (total, available) ->
                if (total > 0) ((total - available).toDouble() / total).toFloat() else 0.0f
            }

            decoders = if (isServiceRunning) {
                com.zektopic.frigate.media.StreamDiagnostics.byCamera
                    .mapNotNull { (id, entry) ->
                        val name = entry.decoderName ?: return@mapNotNull null
                        // Strip the vendor prefix — the tail is what identifies the codec
                        val short = name.substringAfterLast('.', name)
                        val kind = when (entry.hardwareAccelerated) {
                            true -> "HW"
                            false -> "SW"
                            null -> null
                        }
                        id to listOfNotNull(short, kind).joinToString(" · ")
                    }.toMap()
            } else emptyMap()

            delay(5000)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // System Diagnostics Card with Canvas Gauges
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SlateBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SlateCard)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "System metrics",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = TextPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GaugeIndicator(label = "App CPU", value = cpuVal, color = FrigateBlue)
                    GaugeIndicator(label = "Memory", value = ramVal, color = StatusGreen)
                    GaugeIndicator(label = "Storage", value = diskVal, color = StatusAmber)
                }
            }
        }

        // Camera performance stats table
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, SlateBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SlateCard)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Cameras",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = TextPrimary
                )
                Divider(color = SlateBorder)

                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("Camera", modifier = Modifier.weight(1.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                    Text("Detect FPS", modifier = Modifier.weight(0.9f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                    Text("Status", modifier = Modifier.weight(0.9f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                    Text("Resolution", modifier = Modifier.weight(1.1f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                    Text("Decoder", modifier = Modifier.weight(1.3f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                }

                if (cameraConfigs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No cameras metrics available", color = TextMuted, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(cameraConfigs) { camera ->
                            val state = if (isServiceRunning) streamStates[camera.id] else null
                            val (stateText, stateColor) = when (state) {
                                StreamState.LIVE -> "Live" to StatusGreen
                                StreamState.CONNECTING -> "Connecting" to TextMuted
                                StreamState.RETRYING -> "Retrying" to StatusAmber
                                StreamState.OFFLINE -> "Offline" to StatusRed
                                null -> "Stopped" to TextMuted
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(camera.name, modifier = Modifier.weight(1.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("${camera.fps} FPS", modifier = Modifier.weight(0.9f), fontSize = 11.sp, color = TextPrimary)
                                Text(
                                    text = stateText,
                                    modifier = Modifier.weight(0.9f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = stateColor
                                )
                                Text("${camera.detectWidth}x${camera.detectHeight}", modifier = Modifier.weight(1.1f), fontSize = 11.sp, color = TextPrimary)
                                // Blank until MediaCodec reports a decoder — no placeholder
                                val decoder = decoders[camera.id]
                                Text(
                                    text = decoder ?: "—",
                                    modifier = Modifier.weight(1.3f),
                                    fontSize = 11.sp,
                                    color = if (decoder != null) TextPrimary else TextMuted,
                                    maxLines = 1
                                )
                            }
                            Divider(color = SlateBorder.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GaugeIndicator(label: String, value: Float?, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(4.dp)
    ) {
        Box(
            modifier = Modifier.size(72.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    color = SlateBorder,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )
                if (value != null) {
                    drawArc(
                        color = color,
                        startAngle = 135f,
                        sweepAngle = 270f * value,
                        useCenter = false,
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            Text(
                text = value?.let { "${(it * 100).toInt()}%" } ?: "—",
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                color = TextPrimary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextMuted
        )
    }
}

// -------------------------------------------------------------
// Config Tab
// -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    systemConfig: com.zektopic.frigate.data.SystemConfigEntity?,
    cameraConfigs: List<CameraConfigEntity>,
    events: List<EventEntity>,
    onSaveConfig: suspend (String) -> Unit,
    onClearAllRecordings: suspend () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var yamlText by remember(systemConfig) { mutableStateOf(systemConfig?.configYaml ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    // Camera manager state
    var showWizard by remember { mutableStateOf(false) }
    var editingCamera by remember { mutableStateOf<CameraConfigEntity?>(null) }
    var deletingCamera by remember { mutableStateOf<CameraConfigEntity?>(null) }
    var advancedExpanded by remember { mutableStateOf(false) }
    val persistedYaml = systemConfig?.configYaml ?: ""

    if (showWizard) {
        com.zektopic.frigate.ui.settings.CameraWizardDialog(
            existingCamera = editingCamera,
            currentYaml = persistedYaml,
            onSave = { newYaml ->
                onSaveConfig(newYaml)
            },
            onDismiss = {
                showWizard = false
                editingCamera = null
            }
        )
    }

    deletingCamera?.let { cam ->
        AlertDialog(
            onDismissRequest = { deletingCamera = null },
            containerColor = SlateCard,
            title = { Text("Remove ${cam.name}?", color = TextPrimary) },
            text = {
                Text(
                    "The camera and its settings are removed from the configuration. Recorded events are kept.",
                    color = TextMuted, fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = cam
                        deletingCamera = null
                        coroutineScope.launch {
                            try {
                                onSaveConfig(
                                    com.zektopic.frigate.ui.settings.CameraYamlEditor.removeCamera(persistedYaml, target.id)
                                )
                                Toast.makeText(context, "${target.name} removed", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                errorMessage = "Failed to remove camera: ${e.message}"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed, contentColor = TextPrimary)
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deletingCamera = null }) { Text("Cancel", color = TextMuted) }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Cameras section ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Cameras",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { editingCamera = null; showWizard = true },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FrigateBlue, contentColor = TextPrimary),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add camera", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (cameraConfigs.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = SlateCard)
            ) {
                Text(
                    "No cameras configured yet. Use \"Add camera\" to set one up.",
                    color = TextMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            cameraConfigs.forEach { camera ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, SlateBorder, RoundedCornerShape(8.dp)),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = SlateCard)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = if (camera.isEnabled) FrigateBlue else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(camera.name, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                            Text(
                                text = camera.rtspUrl.ifEmpty { "Local device camera" },
                                fontSize = 10.sp, color = TextMuted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${camera.detectWidth}×${camera.detectHeight} @ ${camera.fps} FPS · keep ${camera.recordingRetentionDays}d" +
                                    if (!camera.isEnabled) " · disabled" else "",
                                fontSize = 10.sp, color = TextMuted
                            )
                        }
                        IconButton(onClick = { editingCamera = camera; showWizard = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { deletingCamera = camera }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = StatusRed.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── Global configuration, notifications, storage, about ─────────
        com.zektopic.frigate.ui.settings.GlobalConfigSection(
            currentYaml = persistedYaml,
            onSave = onSaveConfig
        )
        com.zektopic.frigate.ui.settings.NotificationsSection(cameraConfigs = cameraConfigs)
        com.zektopic.frigate.ui.settings.StorageSection(onClearAllRecordings = onClearAllRecordings)
        com.zektopic.frigate.ui.settings.AboutSection(
            cameraConfigs = cameraConfigs,
            events = events,
            configYaml = persistedYaml
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ── Advanced: raw YAML editor ───────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { advancedExpanded = !advancedExpanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "Advanced: edit configuration YAML",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        }

        if (advancedExpanded) {
        errorMessage?.let { error ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ErrorRed, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Error Icon",
                        tint = ErrorRed
                    )
                    Text(
                        text = error,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SlateBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Configuration editor",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = FrigateBlue
                )
                Text(
                    text = "Provide standard config format to modify camera lists, frame sizes, and FPS parameters dynamically.",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }

        Box(
            modifier = Modifier
                .height(340.dp)
                .fillMaxWidth()
                .background(SlateNav, RoundedCornerShape(12.dp))
                .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
        ) {
            OutlinedTextField(
                value = yamlText,
                onValueChange = {
                    yamlText = it
                    errorMessage = null
                },
                modifier = Modifier.fillMaxSize(),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TextPrimary
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = FrigateBlue,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                placeholder = {
                    Text(
                        text = "# Enter your YAML NVR config here...",
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    yamlText = systemConfig?.configYaml ?: ""
                    errorMessage = null
                },
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, SlateBorder, RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
            ) {
                Text(
                    text = "Reset",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = {
                    errorMessage = null
                    try {
                        if (yamlText.isBlank()) {
                            errorMessage = "Configuration cannot be empty"
                            return@Button
                        }

                        val parsed = com.zektopic.frigate.data.YamlConfigParser.parseConfig(yamlText)
                        if (parsed.isEmpty()) {
                            errorMessage = "No camera blocks resolved. Please verify formatting."
                            return@Button
                        }

                        isSaving = true
                        coroutineScope.launch {
                            try {
                                onSaveConfig(yamlText)
                                Toast.makeText(context, "Configuration Saved!", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                errorMessage = "Database Error: ${e.localizedMessage}"
                            } finally {
                                isSaving = false
                            }
                        }
                    } catch (e: Exception) {
                        errorMessage = "YAML Syntax Error: ${e.localizedMessage}"
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FrigateBlue,
                    contentColor = TextPrimary
                ),
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = TextPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "Save config",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
            }
        }
        } // end advanced section
    }
}
