package com.zektopic.frigate.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.ui.components.ActivityIndicator
import com.zektopic.frigate.ui.components.ButtonVariant
import com.zektopic.frigate.ui.components.FrigateButton
import com.zektopic.frigate.ui.components.FrigateSlider
import com.zektopic.frigate.ui.components.FrigateSwitch
import com.zektopic.frigate.ui.components.RoundedLg
import com.zektopic.frigate.ui.components.RoundedMd
import com.zektopic.frigate.ui.components.SettingRow
import com.zektopic.frigate.ui.components.frigateTextFieldColors
import com.zektopic.frigate.ui.theme.FrigateTheme
import com.zektopic.frigate.ui.theme.FrigateType
import com.zektopic.frigate.util.CredentialRedactor
import kotlinx.coroutines.launch

private val wizardSteps = listOf("Camera", "Detection & recording", "Test & save")

/** Add or edit a camera in three steps, ending with a live connection test. */
@Composable
fun CameraWizardDialog(
    existingCamera: CameraConfigEntity?,
    currentYaml: String,
    onSave: suspend (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = FrigateTheme.colors
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var draft by remember {
        mutableStateOf(
            if (existingCamera != null) CameraDraft(
                originalId = existingCamera.id,
                name = existingCamera.name,
                rtspUrl = existingCamera.rtspUrl,
                detectUrl = existingCamera.detectRtspUrl,
                detectWidth = existingCamera.detectWidth,
                detectHeight = existingCamera.detectHeight,
                fps = existingCamera.fps,
                retentionDays = existingCamera.recordingRetentionDays,
                enabled = existingCamera.isEnabled
            ) else CameraDraft()
        )
    }
    var testResult by remember { mutableStateOf<RtspTestResult?>(null) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(RoundedLg)
                .background(colors.background)
                .border(1.dp, colors.input, RoundedLg)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (existingCamera == null) "Add Camera" else "Edit Camera",
                    style = FrigateType.lg.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = colors.mutedForeground,
                    modifier = Modifier.clip(RoundedMd).clickable(onClick = onDismiss).padding(4.dp).size(20.dp)
                )
            }

            // Step indicator
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                wizardSteps.forEachIndexed { index, title ->
                    val active = index == step
                    val done = index < step
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (active || done) colors.selected else colors.secondary),
                        contentAlignment = Alignment.Center
                    ) {
                        if (done) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                        } else {
                            Text(
                                "${index + 1}",
                                style = FrigateType.xs.copy(fontWeight = FontWeight.SemiBold),
                                color = if (active) Color.White else colors.mutedForeground
                            )
                        }
                    }
                    if (active) Text(title, style = FrigateType.sm.copy(fontWeight = FontWeight.Medium), color = colors.primary)
                    if (index < wizardSteps.lastIndex) Box(Modifier.width(16.dp).height(1.dp).background(colors.secondaryHighlight))
                }
            }

            when (step) {
                0 -> WizardStepIdentity(draft, isEditing = existingCamera != null) { draft = it; testResult = null }
                1 -> WizardStepSettings(draft) { draft = it }
                else -> WizardStepTest(draft, testing, testResult) {
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = testRtspConnection(draft.detectUrl.ifBlank { draft.rtspUrl })
                        testing = false
                    }
                }
            }

            saveError?.let { Text(it, style = FrigateType.sm, color = colors.danger) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                FrigateButton(onClick = { if (step > 0) step-- else onDismiss() }, small = false) {
                    Text(if (step > 0) "Back" else "Cancel")
                }
                if (step < wizardSteps.lastIndex) {
                    val valid = step != 0 || (draft.name.isNotBlank() && draft.cameraId.isNotEmpty() && draft.rtspUrl.trim().startsWith("rtsp://"))
                    FrigateButton(onClick = { step++ }, enabled = valid, variant = ButtonVariant.Select, small = false) { Text("Next") }
                } else {
                    FrigateButton(
                        onClick = {
                            saving = true
                            saveError = null
                            scope.launch {
                                try {
                                    onSave(CameraYamlEditor.upsertCamera(currentYaml, draft))
                                    onDismiss()
                                } catch (e: Exception) {
                                    saveError = "Failed to save: ${e.message}"
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = !saving,
                        variant = ButtonVariant.Select,
                        small = false
                    ) {
                        if (saving) ActivityIndicator(size = 16.dp)
                        Text(if (existingCamera == null) "Add Camera" else "Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = FrigateType.sm.copy(fontWeight = FontWeight.Medium), color = FrigateTheme.colors.primary)
}

@Composable
private fun FieldHelp(text: String) {
    Text(text, style = FrigateType.xs, color = FrigateTheme.colors.mutedForeground)
}

@Composable
private fun WizardStepIdentity(draft: CameraDraft, isEditing: Boolean, onChange: (CameraDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel("Camera name")
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            singleLine = true,
            textStyle = FrigateType.sm,
            shape = RoundedMd,
            modifier = Modifier.fillMaxWidth(),
            colors = frigateTextFieldColors()
        )
        if (!isEditing && draft.name.isNotBlank()) FieldHelp("ID: ${draft.cameraId}")

        Spacer(Modifier.height(4.dp))
        FieldLabel("Stream URL")
        OutlinedTextField(
            value = draft.rtspUrl,
            onValueChange = { onChange(draft.copy(rtspUrl = it)) },
            placeholder = { Text("rtsp://go2rtc-host:8554/camera", style = FrigateType.sm) },
            singleLine = true,
            textStyle = FrigateType.sm,
            shape = RoundedMd,
            modifier = Modifier.fillMaxWidth(),
            colors = frigateTextFieldColors()
        )
        FieldHelp("Point this at your go2rtc restream rather than the camera: cameras allow only one or two RTSP sessions.")

        Spacer(Modifier.height(4.dp))
        FieldLabel("Detect substream URL (optional)")
        OutlinedTextField(
            value = draft.detectUrl,
            onValueChange = { onChange(draft.copy(detectUrl = it)) },
            placeholder = { Text("rtsp://go2rtc-host:8554/camera_sub", style = FrigateType.sm) },
            singleLine = true,
            textStyle = FrigateType.sm,
            shape = RoundedMd,
            modifier = Modifier.fillMaxWidth(),
            colors = frigateTextFieldColors()
        )
        FieldHelp("A low-resolution stream to watch for motion. Decoding the full-resolution feed for detection is the largest single cost on a slow device.")
    }
}

@Composable
private fun WizardStepSettings(draft: CameraDraft, onChange: (CameraDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FieldLabel("Detect resolution")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(640 to 360, 1280 to 720).forEach { (w, h) ->
                val selected = draft.detectWidth == w && draft.detectHeight == h
                FrigateButton(
                    onClick = { onChange(draft.copy(detectWidth = w, detectHeight = h)) },
                    variant = if (selected) ButtonVariant.Select else ButtonVariant.Default
                ) { Text("${w}x$h") }
            }
        }

        FieldLabel("Detect FPS: ${draft.fps}")
        FrigateSlider(
            value = draft.fps.toFloat(),
            onValueChange = { onChange(draft.copy(fps = it.toInt().coerceIn(1, 15))) },
            valueRange = 1f..15f
        )
        FieldHelp("5 is recommended. The device's own limit is applied on top of this.")

        FieldLabel("Retain recordings: ${draft.retentionDays} days")
        FrigateSlider(
            value = draft.retentionDays,
            onValueChange = { onChange(draft.copy(retentionDays = (it * 2).toInt() / 2f)) },
            valueRange = 0.5f..14f
        )

        SettingRow("Enabled", description = "Connect to this camera") {
            FrigateSwitch(draft.enabled, { onChange(draft.copy(enabled = it)) })
        }
    }
}

@Composable
private fun WizardStepTest(draft: CameraDraft, testing: Boolean, testResult: RtspTestResult?, onTest: () -> Unit) {
    val colors = FrigateTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedLg)
                .background(colors.backgroundAlt)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(draft.name, style = FrigateType.sm.copy(fontWeight = FontWeight.Medium), color = colors.primary)
            // Shown masked: this dialog can be on screen while someone is looking over a shoulder.
            Text(CredentialRedactor.redact(draft.rtspUrl), style = FrigateType.xs, color = colors.mutedForeground)
            if (draft.detectUrl.isNotBlank()) {
                Text("Detect: " + CredentialRedactor.redact(draft.detectUrl), style = FrigateType.xs, color = colors.mutedForeground)
            }
            Text(
                "${draft.detectWidth}x${draft.detectHeight} @ ${draft.fps} FPS ∙ keep ${draft.retentionDays}d",
                style = FrigateType.xs,
                color = colors.mutedForeground
            )
        }

        FrigateButton(onClick = onTest, enabled = !testing, modifier = Modifier.fillMaxWidth(), small = false) {
            if (testing) {
                ActivityIndicator(size = 16.dp)
                Text("Testing…")
            } else {
                Icon(Icons.Filled.NetworkCheck, contentDescription = null, tint = colors.secondaryForeground, modifier = Modifier.size(18.dp))
                Text("Test connection")
            }
        }

        testResult?.let { result ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (result.reachable) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = null,
                    tint = if (result.reachable) colors.success else colors.danger,
                    modifier = Modifier.size(18.dp)
                )
                Text(result.message, style = FrigateType.sm, color = if (result.reachable) colors.success else colors.danger)
            }
            if (!result.reachable) {
                FieldHelp("You can still save. The camera shows as offline until it becomes reachable.")
            }
        }
    }
}
