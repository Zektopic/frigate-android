package com.zektopic.frigate.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.zektopic.frigate.ai.MotionBox
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.data.EventEntity
import com.zektopic.frigate.data.UiSettings
import com.zektopic.frigate.media.StreamState
import com.zektopic.frigate.service.StatsSample
import com.zektopic.frigate.ui.theme.FrigateColors

/** Everything the screens render, gathered in one place by MainActivity. */
@Immutable
data class NvrUiState(
    val cameras: List<CameraConfigEntity> = emptyList(),
    val events: List<EventEntity> = emptyList(),
    val configYaml: String = "",
    val streamStates: Map<String, StreamState> = emptyMap(),
    val activeMotion: Set<String> = emptySet(),
    val activeAlerts: Set<String> = emptySet(),
    val camerasOverBudget: List<String> = emptyList(),
    val stats: List<StatsSample> = emptyList(),
    val isServiceRunning: Boolean = false,
    val ui: UiSettings = UiSettings()
) {
    /** A camera's connection state, or null when the service is not running at all. */
    fun streamState(cameraId: String): StreamState? = if (isServiceRunning) streamStates[cameraId] else null
}

interface NvrActions {
    suspend fun saveConfig(yaml: String)
    fun startService()
    fun stopService()
    fun sendTestNotification()
    suspend fun clearAllRecordings()
    fun setReviewed(eventIds: List<Long>, reviewed: Boolean)
    fun deleteEvents(events: List<EventEntity>)
    fun motionBoxes(cameraId: String): List<MotionBox>
    fun updateUi(settings: UiSettings)
}

enum class Page { LIVE, REVIEW, SYSTEM, SETTINGS, CONFIG }

/** One line of the status bar: something the operator should know about. */
data class StatusMessage(val text: String, val warning: Boolean, val page: Page)

/**
 * The web UI's `potentialProblems`, for what can go wrong here: the service not
 * running, a camera that is down, a camera the device has no room to run.
 */
fun NvrUiState.statusMessages(): List<StatusMessage> = buildList {
    if (!isServiceRunning) {
        if (cameras.isNotEmpty()) add(StatusMessage("NVR service is stopped", false, Page.LIVE))
        return@buildList
    }
    for (camera in cameras) {
        if (camera.isEnabled && streamStates[camera.id] == StreamState.OFFLINE && camera.name !in camerasOverBudget) {
            add(StatusMessage("${camera.name} is offline", false, Page.SYSTEM))
        }
    }
    for (name in camerasOverBudget) {
        add(StatusMessage("$name is not running: device stream limit reached", true, Page.SETTINGS))
    }
    val cpu = stats.lastOrNull()?.cpuPercent ?: 0f
    if (cpu >= 80f) add(StatusMessage("High CPU usage (${cpu.toInt()}%)", true, Page.SYSTEM))
}

fun FrigateColors.cpuColor(percent: Int): Color = when {
    percent < 50 -> success
    percent < 80 -> Color(0xFFFB923C) // orange-400
    else -> danger
}
