package com.zektopic.frigate.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zektopic.frigate.data.RecordingStore
import com.zektopic.frigate.media.DecoderPolicy
import com.zektopic.frigate.media.DevicePerformance
import com.zektopic.frigate.media.StreamDiagnostics
import com.zektopic.frigate.media.StreamState
import com.zektopic.frigate.ui.NvrUiState
import com.zektopic.frigate.ui.components.FrigateLogo
import com.zektopic.frigate.ui.components.Panel
import com.zektopic.frigate.ui.components.SectionLabel
import com.zektopic.frigate.ui.components.ToggleItem
import com.zektopic.frigate.ui.components.formatBytes
import com.zektopic.frigate.ui.components.rememberNow
import com.zektopic.frigate.ui.components.timeAgo
import com.zektopic.frigate.ui.live.ZoneColors
import com.zektopic.frigate.ui.theme.FrigateTheme
import com.zektopic.frigate.ui.theme.FrigateType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAB_GENERAL = "general"
private const val TAB_STORAGE = "storage"
private const val TAB_CAMERAS = "cameras"

// The web UI's thresholds for the detector graphs (types/graph.ts)
private val InferenceThreshold = Threshold(50f, 100f)
private val CpuThreshold = Threshold(25f, 50f)
private val MemoryThreshold = Threshold(80f, 90f)

/** The web UI's System page: General, Storage and Cameras, each a set of graphs. */
@Composable
fun SystemScreen(state: NvrUiState, wide: Boolean) {
    val colors = FrigateTheme.colors
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(TAB_GENERAL) }
    val now = rememberNow(1_000L)
    val lastUpdated = state.stats.lastOrNull()?.timestamp
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Box(Modifier.fillMaxWidth().height(44.dp)) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                SystemTab(Icons.Filled.MonitorHeart, "General", tab == TAB_GENERAL, wide) { tab = TAB_GENERAL }
                SystemTab(Icons.Filled.Storage, "Storage", tab == TAB_STORAGE, wide) { tab = TAB_STORAGE }
                SystemTab(Icons.Filled.Videocam, "Cameras", tab == TAB_CAMERAS, wide) { tab = TAB_CAMERAS }
            }
            if (!wide) FrigateLogo(Modifier.align(Alignment.Center).size(32.dp))
            if (lastUpdated != null) {
                Text(
                    "Last refreshed: ${timeAgo(now, lastUpdated)}",
                    style = FrigateType.sm,
                    color = colors.mutedForeground,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("System", style = FrigateType.base.copy(fontWeight = FontWeight.Medium), color = colors.primary)
            Text(version, style = FrigateType.sm, color = colors.mutedForeground)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (tab) {
                TAB_GENERAL -> GeneralMetrics(state, wide)
                TAB_STORAGE -> StorageMetrics(state, wide)
                else -> CameraMetrics(state, wide)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SystemTab(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, wide: Boolean, onClick: () -> Unit) {
    ToggleItem(selected, onClick) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(16.dp))
        if (wide) Text(label)
    }
}

/** `grid grid-cols-1 md:grid-cols-3`: panels side by side on a wide screen, stacked on a phone. */
@Composable
private fun PanelGrid(wide: Boolean, panels: List<@Composable (Modifier) -> Unit>) {
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (panel in panels) panel(Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (panel in panels) panel(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(text, style = FrigateType.base, color = FrigateTheme.colors.primary, modifier = Modifier.padding(bottom = 20.dp))
}

@Composable
private fun NoStats() {
    Text(
        "The NVR service is not running, so there is nothing to measure.",
        style = FrigateType.sm,
        color = FrigateTheme.colors.mutedForeground
    )
}

@Composable
private fun GeneralMetrics(state: NvrUiState, wide: Boolean) {
    val stats = state.stats
    val times = stats.map { it.timestamp }

    SectionLabel("Motion Detection")
    if (stats.isEmpty()) {
        NoStats()
    } else {
        PanelGrid(
            wide,
            listOf(
                { modifier ->
                    Panel(modifier, wide) {
                        PanelTitle("Detection Speed")
                        ThresholdBarGraph("motion", "ms", stats.map { it.motionMs }, times, InferenceThreshold)
                    }
                },
                { modifier ->
                    Panel(modifier, wide) {
                        PanelTitle("CPU Usage")
                        ThresholdBarGraph("frigate", "%", stats.map { it.cpuPercent }, times, CpuThreshold, minMax = 25f)
                    }
                },
                { modifier ->
                    Panel(modifier, wide) {
                        PanelTitle("Memory Usage")
                        ThresholdBarGraph("device", "%", stats.map { it.memoryPercent }, times, MemoryThreshold, minMax = 100f)
                    }
                }
            )
        )
    }

    SectionLabel("Hardware Info", Modifier.padding(top = 16.dp))
    val budget = DevicePerformance.cachedBudget()
    Panel(Modifier.fillMaxWidth(), wide) {
        InfoRow("Device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        InfoRow("Chipset", DecoderPolicy.deviceVendor().label)
        InfoRow("Android", "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        if (budget != null) {
            InfoRow("Memory", "${budget.totalRamMb} MB, ${budget.cores} cores")
            InfoRow("Performance tier", budget.tier.name.lowercase().replaceFirstChar { it.uppercase() } + if (DevicePerformance.lowResource) " (low-resource mode)" else "")
            InfoRow("Concurrent streams", "${DevicePerformance.effectiveMaxStreams(budget, DevicePerformance.streamCapOverride)}" + (DevicePerformance.streamCapOverride?.let { " (set in config)" } ?: ""))
            InfoRow("Concurrent recordings", "${budget.maxConcurrentEncoders}")
            InfoRow("Detect FPS limit", "${budget.detectFpsCap}")
            InfoRow("Decoder instances", "${budget.reportedDecoderInstances}", last = true)
        } else {
            InfoRow("Performance tier", "Measured when the NVR starts", last = true)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, last: Boolean = false) {
    val colors = FrigateTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = FrigateType.sm, color = colors.mutedForeground)
        Text(value, style = FrigateType.sm, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (!last) HorizontalDivider(color = colors.secondary)
}

private data class StorageSnapshot(
    val totalBytes: Long?,
    val freeBytes: Long?,
    val recordingBytes: Long,
    val clipCount: Int,
    val perCamera: List<Pair<String, Long>>,
    val location: String,
    val available: Boolean
)

@Composable
private fun StorageMetrics(state: NvrUiState, wide: Boolean) {
    val colors = FrigateTheme.colors
    val context = LocalContext.current
    val store = remember { RecordingStore(context.applicationContext) }
    var snapshot by remember { mutableStateOf<StorageSnapshot?>(null) }
    val cameraIds = state.cameras.map { it.id }

    // Listing walks every recording, so refresh at a human pace rather than per frame.
    LaunchedEffect(cameraIds) {
        while (true) {
            snapshot = withContext(Dispatchers.IO) {
                val entries = store.list()
                val volume = store.volumeStats()
                // Recordings are named <camera id>_<timestamp>; the longest id wins so
                // "front" does not claim "front_door"'s files.
                val byLength = cameraIds.sortedByDescending { it.length }
                val perCamera = LinkedHashMap<String, Long>()
                for (entry in entries) {
                    val owner = byLength.firstOrNull { entry.name.startsWith(it + "_") } ?: "other"
                    perCamera[owner] = (perCamera[owner] ?: 0L) + entry.sizeBytes
                }
                StorageSnapshot(
                    totalBytes = volume?.first,
                    freeBytes = volume?.second,
                    recordingBytes = entries.sumOf { it.sizeBytes },
                    clipCount = entries.count { it.name.endsWith(".mp4", ignoreCase = true) },
                    perCamera = perCamera.entries.sortedByDescending { it.value }.map { it.key to it.value },
                    location = store.displayLocation(),
                    available = store.isAvailable()
                )
            }
            delay(30_000)
        }
    }

    val data = snapshot
    SectionLabel("Overview")
    if (data == null) {
        Text("Reading storage…", style = FrigateType.sm, color = colors.mutedForeground)
        return
    }
    PanelGrid(
        wide,
        listOf(
            { modifier ->
                Panel(modifier, wide) {
                    PanelTitle("Recordings")
                    val total = data.totalBytes
                    val free = data.freeBytes
                    if (total != null && free != null && total > 0) {
                        StorageBar(formatBytes(total - free), formatBytes(total), (total - free).toFloat() / total)
                    } else {
                        // A cloud or network folder: Android will not say how full it is.
                        Text("This location does not report its capacity", style = FrigateType.xs, color = colors.mutedForeground)
                    }
                    Text(data.location, style = FrigateType.xs, color = colors.mutedForeground, modifier = Modifier.padding(top = 10.dp))
                    if (!data.available) {
                        Text(
                            "This folder is not reachable; new recordings are going to the default location.",
                            style = FrigateType.xs,
                            color = colors.danger,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            { modifier ->
                Panel(modifier, wide) {
                    PanelTitle("Frigate Recordings")
                    val total = data.totalBytes
                    StorageBar(
                        formatBytes(data.recordingBytes),
                        total?.let { formatBytes(it) } ?: "—",
                        if (total != null && total > 0) data.recordingBytes.toFloat() / total else 0f
                    )
                    Text("${data.clipCount} clips", style = FrigateType.xs, color = colors.mutedForeground, modifier = Modifier.padding(top = 10.dp))
                }
            }
        )
    )

    SectionLabel("Camera Storage", Modifier.padding(top = 16.dp))
    Panel(Modifier.fillMaxWidth(), wide) {
        if (data.perCamera.isEmpty()) {
            Text("No recordings yet", style = FrigateType.sm, color = colors.mutedForeground)
        } else {
            val total = data.recordingBytes.coerceAtLeast(1L)
            SegmentedBar(data.perCamera.mapIndexed { index, (_, bytes) -> ZoneColors[index % ZoneColors.size] to bytes.toFloat() / total })
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp)) {
                TableHeader("Camera", 2f)
                TableHeader("Storage Used", 1f)
                TableHeader("Percentage of Total", 1f)
            }
            data.perCamera.forEachIndexed { index, (id, bytes) ->
                HorizontalDivider(color = colors.secondary)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(12.dp).background(ZoneColors[index % ZoneColors.size], com.zektopic.frigate.ui.components.RoundedMd))
                        Text(
                            state.cameras.firstOrNull { it.id == id }?.name ?: id.replace('_', ' ').replaceFirstChar { it.uppercase() },
                            style = FrigateType.sm.copy(fontWeight = FontWeight.Medium),
                            color = colors.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TableCell(formatBytes(bytes), 1f)
                    TableCell("%.1f%%".format(100f * bytes / total), 1f)
                }
            }
        }
    }
}

@Composable
private fun RowScope.TableHeader(text: String, weight: Float) {
    Text(
        text,
        style = FrigateType.xs.copy(fontWeight = FontWeight.Medium),
        color = FrigateTheme.colors.mutedForeground,
        modifier = Modifier.weight(weight),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun RowScope.TableCell(text: String, weight: Float, color: Color = FrigateTheme.colors.primary) {
    Text(text, style = FrigateType.sm, color = color, modifier = Modifier.weight(weight), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CameraMetrics(state: NvrUiState, wide: Boolean) {
    val colors = FrigateTheme.colors
    val stats = state.stats
    val times = stats.map { it.timestamp }

    SectionLabel("Overview")
    Panel(Modifier.fillMaxWidth(), wide) {
        if (state.cameras.isEmpty()) {
            Text("No cameras configured", style = FrigateType.sm, color = colors.mutedForeground)
        } else {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                TableHeader("Camera", 1.6f)
                TableHeader("Status", 1f)
                TableHeader("Stream", 1f)
                TableHeader("Decoder", 1.4f)
            }
            for (camera in state.cameras) {
                val diag = StreamDiagnostics.byCamera[camera.id]
                val streamState = state.streamState(camera.id)
                val (status, statusColor) = when {
                    !camera.isEnabled -> "Disabled" to colors.mutedForeground
                    camera.name in state.camerasOverBudget -> "Over limit" to Color(0xFFFB923C)
                    streamState == StreamState.LIVE -> "Live" to colors.success
                    streamState == StreamState.CONNECTING -> "Connecting" to colors.mutedForeground
                    streamState == StreamState.RETRYING -> "Retrying" to Color(0xFFFB923C)
                    streamState == StreamState.OFFLINE -> "Offline" to colors.danger
                    else -> "Stopped" to colors.mutedForeground
                }
                HorizontalDivider(color = colors.secondary)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        camera.name,
                        style = FrigateType.sm.copy(fontWeight = FontWeight.Medium),
                        color = colors.primary,
                        modifier = Modifier.weight(1.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TableCell(status, 1f, statusColor)
                    // The stream's own resolution once the decoder has reported it
                    TableCell(diag?.takeIf { it.videoWidth > 0 }?.let { "${it.videoWidth}x${it.videoHeight}" } ?: "—", 1f)
                    // Blank until MediaCodec reports a decoder: never a guess
                    val decoder = diag?.decoderName?.takeIf { streamState != null }?.let { name ->
                        val short = name.substringAfterLast('.', name)
                        when (diag.hardwareAccelerated) {
                            true -> "$short ∙ HW"
                            false -> "$short ∙ SW"
                            null -> short
                        }
                    }
                    TableCell(decoder ?: "—", 1.4f, if (decoder != null) colors.primary else colors.mutedForeground)
                }
            }
        }
    }

    if (stats.isEmpty()) {
        if (state.cameras.isNotEmpty()) NoStats()
        return
    }
    for (camera in state.cameras) {
        if (stats.none { camera.id in it.cameras }) continue
        fun series(pick: (com.zektopic.frigate.service.CameraStats) -> Float) =
            stats.map { sample -> sample.cameras[camera.id]?.let(pick) ?: 0f }

        SectionLabel(camera.name, Modifier.padding(top = 16.dp))
        PanelGrid(
            wide,
            listOf(
                { modifier ->
                    Panel(modifier, wide) {
                        PanelTitle("Frames / Detections")
                        CameraLineGraph(
                            series = listOf(
                                GraphSeries("camera", series { it.cameraFps }),
                                GraphSeries("detect", series { it.detectFps }),
                                GraphSeries("skipped", series { it.skippedFps })
                            ),
                            unit = " fps",
                            timestamps = times
                        )
                    }
                },
                { modifier ->
                    Panel(modifier, wide) {
                        PanelTitle("Detection Speed")
                        ThresholdBarGraph("motion", "ms", series { it.motionMs }, times, InferenceThreshold)
                        val diag = StreamDiagnostics.byCamera[camera.id]
                        if (diag != null && diag.reconnectCount > 0) {
                            Text(
                                "${diag.reconnectCount} reconnects" + (diag.lastReconnectReason?.let { ", last: $it" } ?: ""),
                                style = FrigateType.xs,
                                color = colors.mutedForeground,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            )
        )
    }
}
