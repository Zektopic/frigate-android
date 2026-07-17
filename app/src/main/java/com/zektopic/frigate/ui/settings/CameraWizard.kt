package com.zektopic.frigate.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

// ---------------------------------------------------------------------------
// YAML config editing
// ---------------------------------------------------------------------------

/** Form state collected by the wizard; maps 1:1 onto a Frigate camera block. */
data class CameraDraft(
    val originalId: String? = null, // set when editing an existing camera
    val name: String = "",
    val rtspUrl: String = "",
    val detectWidth: Int = 640,
    val detectHeight: Int = 360,
    val fps: Int = 5,
    val motionThresholdPercent: Int = 2,
    val retentionDays: Float = 3.0f,
    val enabled: Boolean = true
) {
    val cameraId: String
        get() = originalId ?: name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}

object CameraYamlEditor {

    private fun yaml(): Yaml {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            isPrettyFlow = true
        }
        return Yaml(options)
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadRoot(yamlText: String): MutableMap<String, Any> {
        val loaded = Yaml().load<Any>(yamlText.ifBlank { "{}" })
        return (loaded as? MutableMap<String, Any>) ?: linkedMapOf()
    }

    /** Insert or replace `cameras.<id>` in the stored config YAML. */
    @Suppress("UNCHECKED_CAST")
    fun upsertCamera(yamlText: String, draft: CameraDraft): String {
        val root = loadRoot(yamlText)
        val cameras = (root["cameras"] as? MutableMap<String, Any>) ?: linkedMapOf()

        cameras[draft.cameraId] = linkedMapOf<String, Any>(
            "enabled" to draft.enabled,
            "ffmpeg" to linkedMapOf(
                "inputs" to listOf(
                    linkedMapOf(
                        "path" to draft.rtspUrl.trim(),
                        "roles" to listOf("detect", "record")
                    )
                )
            ),
            "detect" to linkedMapOf(
                "width" to draft.detectWidth,
                "height" to draft.detectHeight,
                "fps" to draft.fps
            ),
            "motion" to linkedMapOf(
                "threshold" to draft.motionThresholdPercent
            ),
            "record" to linkedMapOf(
                "enabled" to true,
                "retain" to linkedMapOf("days" to draft.retentionDays)
            )
        )
        root["cameras"] = cameras
        return yaml().dump(root)
    }

    /** Remove `cameras.<id>` from the stored config YAML. */
    @Suppress("UNCHECKED_CAST")
    fun removeCamera(yamlText: String, cameraId: String): String {
        val root = loadRoot(yamlText)
        val cameras = (root["cameras"] as? MutableMap<String, Any>) ?: return yamlText
        cameras.remove(cameraId)
        root["cameras"] = cameras
        return yaml().dump(root)
    }
}

// ---------------------------------------------------------------------------
// RTSP connection test
// ---------------------------------------------------------------------------

data class RtspTestResult(val reachable: Boolean, val message: String)

/**
 * Minimal RTSP probe: TCP connect + DESCRIBE, report reachability and the
 * advertised video codec. Credentials are left to the player (a 401 still
 * proves the camera is alive).
 */
suspend fun testRtspConnection(url: String): RtspTestResult = withContext(Dispatchers.IO) {
    try {
        val uri = java.net.URI(url.trim())
        if (!uri.scheme.equals("rtsp", ignoreCase = true) || uri.host.isNullOrEmpty()) {
            return@withContext RtspTestResult(false, "Not a valid rtsp:// URL")
        }
        val port = if (uri.port > 0) uri.port else 554
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(uri.host, port), 5000)
            socket.soTimeout = 5000
            val requestUrl = buildString {
                append("rtsp://").append(uri.host)
                if (uri.port > 0) append(":").append(uri.port)
                append(uri.rawPath ?: "")
                if (!uri.rawQuery.isNullOrEmpty()) append("?").append(uri.rawQuery)
            }
            socket.getOutputStream().apply {
                write("DESCRIBE $requestUrl RTSP/1.0\r\nCSeq: 1\r\nAccept: application/sdp\r\nUser-Agent: FrigateAndroid\r\n\r\n".toByteArray())
                flush()
            }

            val input = socket.getInputStream()
            val response = StringBuilder()
            val buf = ByteArray(8192)
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline && response.length < 32_768) {
                val n = try { input.read(buf) } catch (e: java.net.SocketTimeoutException) { break }
                if (n <= 0) break
                response.append(String(buf, 0, n, Charsets.ISO_8859_1))
                if (response.contains("\r\n\r\n") && response.contains("a=rtpmap")) break
                if (response.contains("\r\n\r\n") && !response.startsWith("RTSP/1.0 200")) break
            }

            val text = response.toString()
            val statusLine = text.lineSequence().firstOrNull() ?: ""
            return@withContext when {
                statusLine.contains(" 200") -> {
                    val codecs = Regex("a=rtpmap:\\d+ ([A-Za-z0-9-]+)/")
                        .findAll(text).map { it.groupValues[1] }.distinct().joinToString(", ")
                    RtspTestResult(true, if (codecs.isNotEmpty()) "Camera reachable — codec: $codecs" else "Camera reachable")
                }
                statusLine.contains(" 401") ->
                    RtspTestResult(true, "Camera reachable — requires authentication (credentials in the URL will be used)")
                statusLine.isNotEmpty() ->
                    RtspTestResult(false, "Camera answered: ${statusLine.trim()}")
                else ->
                    RtspTestResult(false, "Connected, but no RTSP response (is this an RTSP server?)")
            }
        }
    } catch (e: Exception) {
        RtspTestResult(false, "Connection failed: ${e.message ?: e.javaClass.simpleName}")
    }
}

// ---------------------------------------------------------------------------
// Wizard UI
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraWizardDialog(
    existingCamera: CameraConfigEntity?,
    currentYaml: String,
    onSave: suspend (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var draft by remember {
        mutableStateOf(
            if (existingCamera != null) CameraDraft(
                originalId = existingCamera.id,
                name = existingCamera.name,
                rtspUrl = existingCamera.rtspUrl,
                detectWidth = existingCamera.detectWidth,
                detectHeight = existingCamera.detectHeight,
                fps = existingCamera.fps,
                motionThresholdPercent = (existingCamera.motionThreshold * 100).toInt().coerceIn(1, 50),
                retentionDays = existingCamera.recordingRetentionDays,
                enabled = existingCamera.isEnabled
            ) else CameraDraft()
        )
    }
    var testResult by remember { mutableStateOf<RtspTestResult?>(null) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val stepTitles = listOf("Camera", "Detection & recording", "Test & save")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .border(1.dp, SlateBorder, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SlateCard)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (existingCamera == null) "Add camera" else "Edit camera",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                // Step indicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    stepTitles.forEachIndexed { index, title ->
                        val active = index == step
                        val done = index < step
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active || done) FrigateBlue else SlateBorder),
                            contentAlignment = Alignment.Center
                        ) {
                            if (done) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(12.dp))
                            } else {
                                Text("${index + 1}", color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            text = title,
                            fontSize = 11.sp,
                            color = if (active) TextPrimary else TextMuted,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                        )
                        if (index < stepTitles.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .width(14.dp)
                                    .height(1.dp)
                                    .background(SlateBorder)
                            )
                        }
                    }
                }

                Divider(color = SlateBorder)

                when (step) {
                    0 -> WizardStepIdentity(draft, isEditing = existingCamera != null) { draft = it; testResult = null }
                    1 -> WizardStepSettings(draft) { draft = it }
                    2 -> WizardStepTest(
                        draft = draft,
                        testing = testing,
                        testResult = testResult,
                        onTest = {
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = testRtspConnection(draft.rtspUrl)
                                testing = false
                            }
                        }
                    )
                }

                saveError?.let {
                    Text(it, color = ErrorRed, fontSize = 12.sp)
                }

                // Navigation buttons
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (step > 0) {
                        OutlinedButton(
                            onClick = { step-- },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
                        ) { Text("Back") }
                    }
                    if (step < 2) {
                        val stepValid = when (step) {
                            0 -> draft.name.isNotBlank() && draft.rtspUrl.trim().startsWith("rtsp://")
                            else -> true
                        }
                        Button(
                            onClick = { step++ },
                            enabled = stepValid,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = FrigateBlue, contentColor = TextPrimary)
                        ) { Text("Next") }
                    } else {
                        Button(
                            onClick = {
                                saving = true
                                saveError = null
                                scope.launch {
                                    try {
                                        val newYaml = CameraYamlEditor.upsertCamera(currentYaml, draft)
                                        onSave(newYaml)
                                        onDismiss()
                                    } catch (e: Exception) {
                                        saveError = "Failed to save: ${e.message}"
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                            enabled = !saving,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = FrigateBlue, contentColor = TextPrimary)
                        ) {
                            if (saving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
                            } else {
                                Text(if (existingCamera == null) "Add camera" else "Save changes")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WizardStepIdentity(draft: CameraDraft, isEditing: Boolean, onChange: (CameraDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            label = { Text("Camera name") },
            supportingText = {
                if (!isEditing && draft.name.isNotBlank()) {
                    Text("ID: ${draft.cameraId}", color = TextMuted)
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = wizardFieldColors()
        )
        OutlinedTextField(
            value = draft.rtspUrl,
            onValueChange = { onChange(draft.copy(rtspUrl = it)) },
            label = { Text("RTSP URL") },
            placeholder = { Text("rtsp://user:pass@192.168.1.10:554/stream", color = TextMuted) },
            supportingText = { Text("Include credentials in the URL if the camera needs them", color = TextMuted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = wizardFieldColors()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WizardStepSettings(draft: CameraDraft, onChange: (CameraDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Detection resolution", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(640 to 360, 1280 to 720).forEach { (w, h) ->
                val selected = draft.detectWidth == w && draft.detectHeight == h
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) FrigateBlue else SlateNav)
                        .border(1.dp, if (selected) FrigateBlue else SlateBorder, RoundedCornerShape(6.dp))
                        .clickable { onChange(draft.copy(detectWidth = w, detectHeight = h)) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("${w}×${h}", fontSize = 12.sp, color = if (selected) TextPrimary else TextMuted)
                }
            }
        }

        Text("Detection FPS: ${draft.fps}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Slider(
            value = draft.fps.toFloat(),
            onValueChange = { onChange(draft.copy(fps = it.toInt().coerceIn(1, 15))) },
            valueRange = 1f..15f,
            steps = 13,
            colors = SliderDefaults.colors(thumbColor = FrigateBlue, activeTrackColor = FrigateBlue)
        )

        Text(
            "Motion sensitivity: ${draft.motionThresholdPercent}% pixel change",
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary
        )
        Slider(
            value = draft.motionThresholdPercent.toFloat(),
            onValueChange = { onChange(draft.copy(motionThresholdPercent = it.toInt().coerceIn(1, 50))) },
            valueRange = 1f..50f,
            colors = SliderDefaults.colors(thumbColor = FrigateBlue, activeTrackColor = FrigateBlue)
        )
        Text(
            "Lower = more sensitive (more events). 2% is a good default.",
            fontSize = 11.sp, color = TextMuted
        )

        Text("Recording retention: ${draft.retentionDays} days", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Slider(
            value = draft.retentionDays,
            onValueChange = { onChange(draft.copy(retentionDays = (it * 2).toInt() / 2f)) },
            valueRange = 0.5f..14f,
            colors = SliderDefaults.colors(thumbColor = FrigateBlue, activeTrackColor = FrigateBlue)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Camera enabled", fontSize = 12.sp, color = TextPrimary, modifier = Modifier.weight(1f))
            Switch(
                checked = draft.enabled,
                onCheckedChange = { onChange(draft.copy(enabled = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = FrigateBlue)
            )
        }
    }
}

@Composable
private fun WizardStepTest(
    draft: CameraDraft,
    testing: Boolean,
    testResult: RtspTestResult?,
    onTest: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateNav),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(draft.name, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                Text(draft.rtspUrl, fontSize = 11.sp, color = TextMuted)
                Text(
                    "${draft.detectWidth}×${draft.detectHeight} @ ${draft.fps} FPS · motion ${draft.motionThresholdPercent}% · keep ${draft.retentionDays}d",
                    fontSize = 11.sp, color = TextMuted
                )
            }
        }

        OutlinedButton(
            onClick = onTest,
            enabled = !testing,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = FrigateBlue)
        ) {
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = FrigateBlue, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Testing…")
            } else {
                Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Test connection")
            }
        }

        testResult?.let { result ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (result.reachable) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (result.reachable) StatusGreen else StatusRed,
                    modifier = Modifier.size(18.dp)
                )
                Text(result.message, fontSize = 12.sp, color = if (result.reachable) StatusGreen else StatusRed)
            }
            if (!result.reachable) {
                Text(
                    "You can still save — the camera will show as offline until it becomes reachable.",
                    fontSize = 11.sp, color = TextMuted
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun wizardFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = FrigateBlue,
    unfocusedBorderColor = SlateBorder,
    cursorColor = FrigateBlue,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = FrigateBlue,
    unfocusedLabelColor = TextMuted
)
