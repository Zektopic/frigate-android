package com.zektopic.frigate.ui.dashboard

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onAddMockCamera: () -> Unit,
    onTriggerTestNotification: () -> Unit,
    onStartNvrService: () -> Unit,
    onStopNvrService: () -> Unit,
    isServiceRunning: Boolean
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    // Dynamic stats to simulate live background worker
    var currentFps by remember { mutableFloatStateOf(0.0f) }
    var currentInferenceTime by remember { mutableIntStateOf(0) }
    var currentCpuUsage by remember { mutableIntStateOf(0) }

    LaunchedEffect(isServiceRunning) {
        if (isServiceRunning) {
            while (true) {
                currentFps = (12.0f + (Math.random() * 3 - 1.5)).toFloat()
                currentInferenceTime = (22 + (Math.random() * 8 - 4)).toInt()
                currentCpuUsage = (28 + (Math.random() * 10 - 5)).toInt()
                delay(2000)
            }
        } else {
            currentFps = 0.0f
            currentInferenceTime = 0
            currentCpuUsage = 0
        }
    }

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
                            text = "FRIGATE ANDROID",
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
        containerColor = DarkVoid
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Service Status Card
            ServiceStatusHeaderCard(
                isServiceRunning = isServiceRunning,
                fps = currentFps,
                inferenceMs = currentInferenceTime,
                cpu = currentCpuUsage,
                onStart = {
                    onStartNvrService()
                    Toast.makeText(context, "NVR Foreground Service Started", Toast.LENGTH_SHORT).show()
                },
                onStop = {
                    onStopNvrService()
                    Toast.makeText(context, "NVR Foreground Service Stopped", Toast.LENGTH_SHORT).show()
                }
            )

            // Tabs layout: Cameras vs Events Log
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = CardCarbon,
                contentColor = CyberCyan,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = CyberCyan
                    )
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("CAMERAS", fontWeight = FontWeight.Bold, color = if (selectedTab == 0) CyberCyan else SoftGray) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("EVENTS LOG", fontWeight = FontWeight.Bold, color = if (selectedTab == 1) CyberCyan else SoftGray) }
                )
            }

            // Main body content switching based on tabs
            Box(modifier = Modifier.weight(1f)) {
                if (selectedTab == 0) {
                    CamerasTabContent(
                        cameraConfigs = cameraConfigs,
                        isServiceRunning = isServiceRunning,
                        onAddMockCamera = onAddMockCamera
                    )
                } else {
                    EventsTabContent(events = events)
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
                        text = if (isServiceRunning) "NVR CORE RUNNING" else "NVR CORE IDLE",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isServiceRunning) ElectricEmerald else HotPink
                    )
                }

                Button(
                    onClick = { if (isServiceRunning) onStop() else onStart() },
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
                        text = if (isServiceRunning) "STOP" else "START NVR",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            Divider(color = SlateBorder)

            // Dynamic NVR Metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricWidget(label = "System FPS", value = if (isServiceRunning) String.format("%.1f", fps) else "0.0", color = CyberCyan)
                MetricWidget(label = "NPU Latency", value = if (isServiceRunning) "${inferenceMs}ms" else "0ms", color = ElectricEmerald)
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
fun CamerasTabContent(
    cameraConfigs: List<CameraConfigEntity>,
    isServiceRunning: Boolean,
    onAddMockCamera: () -> Unit
) {
    if (cameraConfigs.isEmpty()) {
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
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(cameraConfigs) { camera ->
                CameraLiveFeedCard(camera = camera, isServiceRunning = isServiceRunning)
            }
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(
                        onClick = onAddMockCamera,
                        colors = ButtonDefaults.textButtonColors(contentColor = CyberCyan)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add Camera")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Another Mock Feed", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun CameraLiveFeedCard(camera: CameraConfigEntity, isServiceRunning: Boolean) {
    var detectedObject by remember { mutableStateOf<String?>(null) }
    var motionPercentage by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(isServiceRunning) {
        if (isServiceRunning) {
            while (true) {
                motionPercentage = Math.random() * 0.05
                detectedObject = if (motionPercentage > 0.02) {
                    if (Math.random() > 0.4) "person" else "car"
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
            // Live Feed Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
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

                // Badges for active status
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (detectedObject != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(CyberCyan.copy(alpha = 0.15f))
                                .border(1.dp, CyberCyan, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = detectedObject!!.uppercase(),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberCyan
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isServiceRunning) ElectricEmerald.copy(alpha = 0.15f) else SoftGray.copy(
                                    alpha = 0.15f
                                )
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

            // Mock Visual Feed Grid Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFF1E1E28), Color(0xFF0F0F13))
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
                    // Render simulated scanning reticle
                    Icon(
                        imageVector = Icons.Default.FilterCenterFocus,
                        contentDescription = "Focus Area",
                        tint = if (detectedObject != null) HotPink.copy(alpha = 0.6f) else CyberCyan.copy(alpha = 0.3f),
                        modifier = Modifier.size(48.dp)
                    )

                    // Display mock AI bounding box if object is present
                    if (detectedObject != null) {
                        Box(
                            modifier = Modifier
                                .size(width = 120.dp, height = 100.dp)
                                .offset(x = (-30).dp, y = (-10).dp)
                                .border(2.dp, CyberCyan, RoundedCornerShape(4.dp))
                                .background(CyberCyan.copy(alpha = 0.05f))
                        ) {
                            Text(
                                text = "${detectedObject!!} 92%",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = DarkVoid,
                                modifier = Modifier
                                    .background(CyberCyan)
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

                // Grid stats overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    Text(
                        text = "REC",
                        color = HotPink,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                    Text(
                        text = if (isServiceRunning) "MOTION: " + String.format("%.1f%%", motionPercentage * 100) else "MOTION: --",
                        color = LightWhite.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }
            }
        }
    }
}

@Composable
fun EventsTabContent(events: List<EventEntity>) {
    if (events.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.HistoryToggleOff,
                contentDescription = "No Events",
                tint = SoftGray,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No detection events recorded",
                color = SoftGray,
                fontWeight = FontWeight.Bold
            )
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(events) { event ->
                EventListItem(event = event)
            }
        }
    }
}

@Composable
fun EventListItem(event: EventEntity) {
    val timeFormatted = remember(event.timestamp) {
        val date = java.util.Date(event.timestamp)
        val format = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
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
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when (event.label) {
                                "person" -> CyberCyan.copy(alpha = 0.15f)
                                "car" -> ElectricEmerald.copy(alpha = 0.15f)
                                else -> SoftGray.copy(alpha = 0.15f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (event.label) {
                            "person" -> Icons.Default.Person
                            "car" -> Icons.Default.DirectionsCar
                            else -> Icons.Default.Visibility
                        },
                        contentDescription = "Event icon",
                        tint = when (event.label) {
                            "person" -> CyberCyan
                            "car" -> ElectricEmerald
                            else -> SoftGray
                        }
                    )
                }

                Column {
                    Text(
                        text = "${event.label.uppercase()} detected",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LightWhite
                    )
                    Text(
                        text = "Camera: ${event.cameraId} • Zone: ${event.zone ?: "All"}",
                        fontSize = 11.sp,
                        color = SoftGray
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = String.format("%.0f%%", event.confidence * 100),
                    fontWeight = FontWeight.Black,
                    color = CyberCyan,
                    fontSize = 14.sp
                )
                Text(
                    text = timeFormatted,
                    fontSize = 10.sp,
                    color = SoftGray
                )
            }
        }
    }
}
