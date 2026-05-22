package com.zektopic.frigate.ui.dashboard

import android.widget.Toast
import android.widget.VideoView
import android.widget.MediaController
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import com.zektopic.frigate.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    cameraConfigs: List<CameraConfigEntity>,
    events: List<EventEntity>,
    systemConfig: com.zektopic.frigate.data.SystemConfigEntity?,
    onSaveConfig: suspend (String) -> Unit,
    onAddMockCamera: () -> Unit,
    onTriggerTestNotification: () -> Unit,
    onStartNvrService: () -> Unit,
    onStopNvrService: () -> Unit,
    isServiceRunning: Boolean
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    // Dynamic stats to simulate live background service metrics
    var currentFps by remember { mutableFloatStateOf(0.0f) }
    var currentCpuUsage by remember { mutableIntStateOf(0) }
    var currentInferenceTime by remember { mutableIntStateOf(0) }

    LaunchedEffect(isServiceRunning) {
        if (isServiceRunning) {
            while (true) {
                currentFps = (14.0f + (Math.random() * 2 - 1.0)).toFloat()
                currentCpuUsage = (15 + (Math.random() * 6 - 3)).toInt()
                currentInferenceTime = 1 // 1ms for motion detector
                delay(2000)
            }
        } else {
            currentFps = 0.0f
            currentCpuUsage = 0
            currentInferenceTime = 0
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Screen content router
    @Composable
    fun TargetScreenContent(tabIndex: Int) {
        when (tabIndex) {
            0 -> LiveDashboardScreen(
                cameraConfigs = cameraConfigs,
                isServiceRunning = isServiceRunning,
                onStart = onStartNvrService,
                onStop = onStopNvrService,
                onAddMockCamera = onAddMockCamera,
                currentFps = currentFps,
                currentCpu = currentCpuUsage,
                currentInferenceTime = currentInferenceTime
            )
            1 -> BirdseyeScreen(
                cameraConfigs = cameraConfigs,
                isServiceRunning = isServiceRunning
            )
            2 -> RecordingsScreen(
                events = events
            )
            3 -> SystemScreen(
                cameraConfigs = cameraConfigs,
                isServiceRunning = isServiceRunning,
                cpu = currentCpuUsage
            )
            4 -> ConfigScreen(
                systemConfig = systemConfig,
                onSaveConfig = onSaveConfig
            )
        }
    }

    if (isLandscape) {
        // Landscape Orientation: Sidebar layout
        Row(modifier = Modifier.fillMaxSize().background(DarkVoid)) {
            NavigationRail(
                containerColor = DeepCharcoal,
                contentColor = CyberCyan,
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
                    tint = CyberCyan,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.weight(1f))

                val destinations = listOf("Live", "Birdseye", "Recordings", "System", "Config")
                val icons = listOf(
                    Icons.Default.Videocam,
                    Icons.Default.Visibility,
                    Icons.Default.History,
                    Icons.Default.Info,
                    Icons.Default.Settings
                )

                destinations.forEachIndexed { index, title ->
                    NavigationRailItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icons[index], contentDescription = title) },
                        label = { Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = DarkVoid,
                            selectedTextColor = CyberCyan,
                            indicatorColor = CyberCyan,
                            unselectedIconColor = SoftGray,
                            unselectedTextColor = SoftGray
                        )
                    )
                }
                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = onTriggerTestNotification) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = "Test Notification",
                        tint = CyberCyan
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
                                tint = CyberCyan
                            )
                            Text(
                                text = "FRIGATE NVR",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 2.sp
                                ),
                                color = LightWhite
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onTriggerTestNotification) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = "Test Notification",
                                tint = CyberCyan
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = DarkVoid
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = DeepCharcoal,
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
                    val destinations = listOf("Live", "Birdseye", "Recordings", "System", "Config")
                    val icons = listOf(
                        Icons.Default.Videocam,
                        Icons.Default.Visibility,
                        Icons.Default.History,
                        Icons.Default.Info,
                        Icons.Default.Settings
                    )

                    destinations.forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(icons[index], contentDescription = title) },
                            label = { Text(title, fontSize = 9.sp, fontWeight = FontWeight.Bold) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = DarkVoid,
                                selectedTextColor = CyberCyan,
                                indicatorColor = CyberCyan,
                                unselectedIconColor = SoftGray,
                                unselectedTextColor = SoftGray
                            )
                        )
                    }
                }
            },
            containerColor = DarkVoid
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
    isServiceRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAddMockCamera: () -> Unit,
    currentFps: Float,
    currentCpu: Int,
    currentInferenceTime: Int
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ServiceStatusHeaderCard(
            isServiceRunning = isServiceRunning,
            fps = currentFps,
            inferenceMs = currentInferenceTime,
            cpu = currentCpu,
            onStart = onStart,
            onStop = onStop
        )

        if (cameraConfigs.isEmpty()) {
            EmptyCamerasView(onAddMockCamera)
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(cameraConfigs) { camera ->
                    CameraLiveFeedCard(camera = camera, isServiceRunning = isServiceRunning)
                }
            }
        }
    }
}

@Composable
fun ServiceStatusHeaderCard(
    isServiceRunning: Boolean,
    fps: Float,
    inferenceMs: Int,
    cpu: Int,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SlateBorder, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardCarbon)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (isServiceRunning) ElectricEmerald else HotPink)
                    )
                    Text(
                        text = if (isServiceRunning) "NVR CORE ACTIVE" else "NVR CORE INACTIVE",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isServiceRunning) ElectricEmerald else HotPink
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
                        containerColor = if (isServiceRunning) HotPink.copy(alpha = 0.2f) else CyberCyan.copy(alpha = 0.2f),
                        contentColor = if (isServiceRunning) HotPink else CyberCyan
                    ),
                    modifier = Modifier.border(
                        1.dp,
                        if (isServiceRunning) HotPink else CyberCyan,
                        RoundedCornerShape(8.dp)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isServiceRunning) "STOP CORE" else "START NVR",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Divider(color = SlateBorder)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricWidget(label = "System FPS", value = if (isServiceRunning) String.format("%.1f", fps) else "0.0", color = CyberCyan)
                MetricWidget(label = "Analysis Latency", value = if (isServiceRunning) "${inferenceMs}ms" else "0ms", color = ElectricEmerald)
                MetricWidget(label = "CPU Load", value = if (isServiceRunning) "$cpu%" else "0%", color = WarningYellow)
            }
        }
    }
}

@Composable
fun RowScope.MetricWidget(label: String, value: String, color: Color) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            color = color
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = SoftGray
        )
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
            tint = SoftGray,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "No Cameras Configured",
            color = SoftGray,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onAddMockCamera,
            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = DarkVoid)
        ) {
            Text("Setup Sample Cameras", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun CameraLiveFeedCard(camera: CameraConfigEntity, isServiceRunning: Boolean) {
    var detectedObject by remember { mutableStateOf<String?>(null) }
    var motionPercentage by remember { mutableDoubleStateOf(0.0) }

    // Settings switches state representing Frigate toggles
    var isDetectEnabled by remember { mutableStateOf(camera.isEnabled) }
    var isRecordEnabled by remember { mutableStateOf(true) }
    var isSnapshotsEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(isServiceRunning, isDetectEnabled) {
        if (isServiceRunning && isDetectEnabled) {
            while (true) {
                motionPercentage = Math.random() * 0.05
                detectedObject = if (motionPercentage > 0.02) {
                    if (Math.random() > 0.4) "motion" else null
                } else {
                    null
                }
                delay(3000)
            }
        } else {
            motionPercentage = 0.0
            detectedObject = null
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SlateBorder, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardCarbon)
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
                        text = camera.name.uppercase(),
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LightWhite
                    )
                    Text(
                        text = if (camera.rtspUrl.isEmpty()) "Local Camera Sensor" else camera.rtspUrl,
                        fontSize = 10.sp,
                        color = SoftGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (detectedObject != null && isDetectEnabled) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(ElectricEmerald.copy(alpha = 0.15f))
                                .border(1.dp, ElectricEmerald, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "MOTION ALERT",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = ElectricEmerald
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isServiceRunning) ElectricEmerald.copy(alpha = 0.15f) else SoftGray.copy(alpha = 0.15f)
                            )
                            .border(
                                1.dp,
                                if (isServiceRunning) ElectricEmerald else SoftGray,
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isServiceRunning) "ACTIVE" else "OFFLINE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isServiceRunning) ElectricEmerald else SoftGray
                        )
                    }
                }
            }

            // Feed Simulator Display Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFF13131A), Color(0xFF070709))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Background scanline/mesh effects to mimic terminal monitor
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    repeat(9) {
                        Divider(color = Color.White.copy(alpha = 0.015f), thickness = 1.dp)
                    }
                }

                if (isServiceRunning) {
                    Icon(
                        imageVector = Icons.Default.FilterCenterFocus,
                        contentDescription = "Focus Area",
                        tint = if (detectedObject != null && isDetectEnabled) HotPink.copy(alpha = 0.6f) else CyberCyan.copy(alpha = 0.3f),
                        modifier = Modifier.size(48.dp)
                    )

                    // Draw motion alert reticle boundary box
                    if (detectedObject != null && isDetectEnabled) {
                        Box(
                            modifier = Modifier
                                .size(width = 130.dp, height = 100.dp)
                                .offset(x = (-20).dp, y = (-10).dp)
                                .border(2.dp, ElectricEmerald, RoundedCornerShape(4.dp))
                                .background(ElectricEmerald.copy(alpha = 0.03f))
                        ) {
                            Text(
                                text = "MOTION: ${(motionPercentage * 1000).toInt() / 10.0}%",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = DarkVoid,
                                modifier = Modifier
                                    .background(ElectricEmerald)
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                    .align(Alignment.TopStart)
                            )
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.PlayDisabled,
                            contentDescription = "Feed Paused",
                            tint = SoftGray,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Start NVR Core to Stream", fontSize = 11.sp, color = SoftGray)
                    }
                }

                // Grid indicators overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    if (isServiceRunning && isRecordEnabled) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.align(Alignment.TopStart)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(HotPink)
                            )
                            Text(
                                text = "REC",
                                color = HotPink,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                    Text(
                        text = if (isServiceRunning && isDetectEnabled) "MOTION: " + String.format("%.1f%%", motionPercentage * 100) else "MOTION: OFF",
                        color = LightWhite.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }
            }

            // Toggles Row (Detect, Record, Snapshots)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ToggleButton(label = "DETECT", active = isDetectEnabled, onToggle = { isDetectEnabled = !isDetectEnabled }, activeColor = ElectricEmerald)
                ToggleButton(label = "RECORD", active = isRecordEnabled, onToggle = { isRecordEnabled = !isRecordEnabled }, activeColor = CyberCyan)
                ToggleButton(label = "SNAPSHOTS", active = isSnapshotsEnabled, onToggle = { isSnapshotsEnabled = !isSnapshotsEnabled }, activeColor = WarningYellow)
            }
        }
    }
}

@Composable
fun ToggleButton(label: String, active: Boolean, onToggle: () -> Unit, activeColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) activeColor.copy(alpha = 0.15f) else CardCarbon)
            .border(1.dp, if (active) activeColor else SlateBorder, RoundedCornerShape(6.dp))
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (active) activeColor else SoftGray
        )
    }
}

// -------------------------------------------------------------
// Birdseye Tab
// -------------------------------------------------------------
@Composable
fun BirdseyeScreen(cameraConfigs: List<CameraConfigEntity>, isServiceRunning: Boolean) {
    val motionStates = remember { mutableStateMapOf<String, Double>() }

    LaunchedEffect(isServiceRunning) {
        if (isServiceRunning) {
            while (true) {
                cameraConfigs.forEach { camera ->
                    motionStates[camera.id] = Math.random() * 0.05
                }
                delay(3000)
            }
        } else {
            motionStates.clear()
        }
    }

    val camerasWithMotion = cameraConfigs.filter {
        (motionStates[it.id] ?: 0.0) > 0.02
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SlateBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = CardCarbon)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "BIRDSEYE VIEW",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = CyberCyan
                )
                Text(
                    text = "Displays active camera feeds dynamically resizing when motion triggers occur.",
                    fontSize = 11.sp,
                    color = SoftGray
                )
            }
        }

        if (!isServiceRunning) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Start NVR Core to activate Birdseye View",
                    color = SoftGray,
                    fontWeight = FontWeight.Bold
                )
            }
        } else if (camerasWithMotion.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .background(CardCarbon),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Birdseye Idle",
                        tint = SoftGray.copy(alpha = 0.4f),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "BIRDSEYE: STANDING BY",
                        fontWeight = FontWeight.Bold,
                        color = SoftGray,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "No active motion triggers on NVR.",
                        color = SoftGray,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 250.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(camerasWithMotion) { camera ->
                    BirdseyeCameraCard(camera = camera, motionVal = motionStates[camera.id] ?: 0.0)
                }
            }
        }
    }
}

@Composable
fun BirdseyeCameraCard(camera: CameraConfigEntity, motionVal: Double) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ElectricEmerald, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardCarbon)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = camera.name.uppercase(),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    color = LightWhite
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(ElectricEmerald.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "MOTION: ${String.format("%.1f%%", motionVal * 100)}",
                        color = ElectricEmerald,
                        fontWeight = FontWeight.Black,
                        fontSize = 8.sp
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(Color(0xFF09090D)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    repeat(8) {
                        Divider(color = Color.White.copy(alpha = 0.01f), thickness = 1.dp)
                    }
                }

                Box(
                    modifier = Modifier
                        .size(width = 110.dp, height = 80.dp)
                        .border(2.dp, ElectricEmerald, RoundedCornerShape(4.dp))
                        .background(ElectricEmerald.copy(alpha = 0.05f))
                ) {
                    Text(
                        text = "TRACKING",
                        color = DarkVoid,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(ElectricEmerald)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }

                Text(
                    text = "● BIRDSEYE GRID FEED",
                    color = ElectricEmerald,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
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
                    colors = CardDefaults.cardColors(containerColor = CardCarbon)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("No video playback available for this motion event.", color = ErrorRed, fontSize = 12.sp)
                        IconButton(onClick = { activePlaybackEvent = null }) {
                            Icon(Icons.Default.Close, "Close", tint = SoftGray)
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SlateBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = CardCarbon)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "RECORDED CLIPS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = CyberCyan
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Filter:", fontSize = 11.sp, color = SoftGray)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(cameraList) { cam ->
                            val selected = selectedCameraFilter == cam
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) CyberCyan.copy(alpha = 0.15f) else CardCarbon)
                                    .border(1.dp, if (selected) CyberCyan else SlateBorder, RoundedCornerShape(6.dp))
                                    .clickable { selectedCameraFilter = cam }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = cam.uppercase(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) CyberCyan else SoftGray
                                )
                            }
                        }
                    }
                }
            }
        }

        if (filteredEvents.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No recordings logs found in database", color = SoftGray, fontWeight = FontWeight.Bold)
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
            .border(1.dp, CyberCyan, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardCarbon)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RECORDING SEGMENT PLAYER",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = CyberCyan
                )
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close Video", tint = HotPink)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { context ->
                        VideoView(context).apply {
                            setVideoPath(videoUrl)
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
                        // Standard update lifecycle to reload URL if it changes
                        view.setVideoPath(videoUrl)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Text(
                text = "File Stream: $videoUrl",
                fontSize = 9.sp,
                color = SoftGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun RecordingListItem(event: EventEntity, onPlayClick: () -> Unit) {
    val dateFormatted = remember(event.timestamp) {
        val date = java.util.Date(event.timestamp)
        val format = java.text.SimpleDateFormat("MMM dd, HH:mm:ss", java.util.Locale.getDefault())
        format.format(date)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SlateBorder, RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CardCarbon)
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
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberCyan.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Icon",
                        tint = CyberCyan
                    )
                }

                Column {
                    Text(
                        text = "Motion Clip [${event.label.uppercase()}]",
                        fontWeight = FontWeight.Bold,
                        color = LightWhite,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Camera: ${event.cameraId} • Zone: ${event.zone ?: "Main Zone"}",
                        fontSize = 11.sp,
                        color = SoftGray
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = onPlayClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberCyan.copy(alpha = 0.15f),
                        contentColor = CyberCyan
                    ),
                    modifier = Modifier.border(1.dp, CyberCyan, RoundedCornerShape(6.dp)),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("PLAY", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = dateFormatted,
                    fontSize = 9.sp,
                    color = SoftGray
                )
            }
        }
    }
}

// -------------------------------------------------------------
// System Tab
// -------------------------------------------------------------
@Composable
fun SystemScreen(cameraConfigs: List<CameraConfigEntity>, isServiceRunning: Boolean, cpu: Int) {
    var cpuVal by remember { mutableFloatStateOf(0.0f) }
    var ramVal by remember { mutableFloatStateOf(0.0f) }
    var diskVal by remember { mutableFloatStateOf(0.48f) }

    LaunchedEffect(isServiceRunning, cpu) {
        if (isServiceRunning) {
            cpuVal = cpu / 100.0f
            while (true) {
                ramVal = (0.35f + Math.random() * 0.04f).toFloat()
                diskVal = (0.48f + Math.random() * 0.001f).toFloat()
                delay(3000)
            }
        } else {
            cpuVal = 0.0f
            ramVal = 0.0f
            diskVal = 0.48f
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
            colors = CardDefaults.cardColors(containerColor = CardCarbon)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "SYSTEM ENGINE DIAGNOSTICS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = CyberCyan
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GaugeIndicator(label = "CPU LOAD", value = cpuVal, color = CyberCyan)
                    GaugeIndicator(label = "RAM ENGINE", value = ramVal, color = ElectricEmerald)
                    GaugeIndicator(label = "DISK SPACE", value = diskVal, color = WarningYellow)
                }
            }
        }

        // Camera performance stats table
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, SlateBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = CardCarbon)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "CAMERA STREAMING METRICS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = CyberCyan
                )
                Divider(color = SlateBorder)

                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("Camera", modifier = Modifier.weight(1.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SoftGray)
                    Text("Target", modifier = Modifier.weight(0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SoftGray)
                    Text("Actual", modifier = Modifier.weight(0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SoftGray)
                    Text("Bitrate", modifier = Modifier.weight(0.9f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SoftGray)
                    Text("Resolution", modifier = Modifier.weight(1.1f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SoftGray)
                }

                if (cameraConfigs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No cameras metrics available", color = SoftGray, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(cameraConfigs) { camera ->
                            var dynamicFps by remember { mutableFloatStateOf(camera.fps.toFloat()) }
                            var dynamicBitrate by remember { mutableIntStateOf(1024) }

                            LaunchedEffect(isServiceRunning) {
                                if (isServiceRunning) {
                                    while (true) {
                                        dynamicFps = (camera.fps + (Math.random() * 0.4f - 0.2f)).toFloat()
                                        dynamicBitrate = (1200 + (Math.random() * 400 - 200)).toInt()
                                        delay(2500)
                                    }
                                } else {
                                    dynamicFps = 0.0f
                                    dynamicBitrate = 0
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(camera.name, modifier = Modifier.weight(1.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LightWhite)
                                Text("${camera.fps} FPS", modifier = Modifier.weight(0.8f), fontSize = 11.sp, color = LightWhite)
                                Text(
                                    text = if (isServiceRunning) String.format("%.1f", dynamicFps) + " FPS" else "0.0 FPS",
                                    modifier = Modifier.weight(0.8f),
                                    fontSize = 11.sp,
                                    color = ElectricEmerald
                                )
                                Text(
                                    text = if (isServiceRunning) "${dynamicBitrate}Kbps" else "0Kbps",
                                    modifier = Modifier.weight(0.9f),
                                    fontSize = 11.sp,
                                    color = CyberCyan
                                )
                                Text("${camera.detectWidth}x${camera.detectHeight}", modifier = Modifier.weight(1.1f), fontSize = 11.sp, color = LightWhite)
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
fun GaugeIndicator(label: String, value: Float, color: Color) {
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
                drawArc(
                    color = color,
                    startAngle = 135f,
                    sweepAngle = 270f * value,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            Text(
                text = "${(value * 100).toInt()}%",
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                color = LightWhite
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = SoftGray
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
    onSaveConfig: suspend (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var yamlText by remember(systemConfig) { mutableStateOf(systemConfig?.configYaml ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                        color = LightWhite,
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
            colors = CardDefaults.cardColors(containerColor = CardCarbon),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "FRIGATE YAML CONFIGURATION",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = CyberCyan
                )
                Text(
                    text = "Provide standard config format to modify camera lists, frame sizes, and FPS parameters dynamically.",
                    fontSize = 11.sp,
                    color = SoftGray
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(DeepCharcoal, RoundedCornerShape(12.dp))
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
                    color = LightWhite
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = CyberCyan,
                    focusedTextColor = LightWhite,
                    unfocusedTextColor = LightWhite
                ),
                placeholder = {
                    Text(
                        text = "# Enter your YAML NVR config here...",
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = SoftGray
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
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SoftGray)
            ) {
                Text(
                    text = "RESET",
                    fontWeight = FontWeight.Bold,
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
                    containerColor = CyberCyan,
                    contentColor = DarkVoid
                ),
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = DarkVoid,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "SAVE CONFIG",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
