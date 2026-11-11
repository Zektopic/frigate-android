package com.zektopic.frigate.ui.config

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zektopic.frigate.data.YamlConfigParser
import com.zektopic.frigate.ui.NvrActions
import com.zektopic.frigate.ui.NvrUiState
import com.zektopic.frigate.ui.components.ActivityIndicator
import com.zektopic.frigate.ui.components.ButtonVariant
import com.zektopic.frigate.ui.components.FrigateButton
import com.zektopic.frigate.ui.components.RoundedLg
import com.zektopic.frigate.ui.theme.FrigateTheme
import com.zektopic.frigate.ui.theme.FrigateType
import kotlinx.coroutines.launch

/**
 * The web UI's Configuration Editor: the whole config as text, with Copy and Save at
 * the top right. Saving applies it at once, since there is nothing here to restart.
 */
@Composable
fun ConfigEditorScreen(state: NvrUiState, actions: NvrActions, wide: Boolean) {
    val colors = FrigateTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // Keyed on the stored config so an edit made elsewhere shows up, but only while
    // this editor holds nothing unsaved.
    var text by rememberSaveable(state.configYaml) { mutableStateOf(state.configYaml) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val dirty = text != state.configYaml

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Configuration Editor",
                style = (if (wide) FrigateType.xl else FrigateType.lg).copy(fontWeight = FontWeight.SemiBold),
                color = colors.primary,
                modifier = Modifier.weight(1f)
            )
            FrigateButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, "Config copied to clipboard", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy Config", tint = colors.secondaryForeground, modifier = Modifier.size(16.dp))
                if (wide) Text("Copy Config")
            }
            if (dirty) {
                FrigateButton(onClick = { text = state.configYaml; error = null }) { Text("Reset") }
            }
            FrigateButton(
                onClick = {
                    error = null
                    val problem = validate(text)
                    if (problem != null) {
                        error = problem
                        return@FrigateButton
                    }
                    saving = true
                    scope.launch {
                        try {
                            actions.saveConfig(text)
                            Toast.makeText(context, "Config successfully saved", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            error = "Error saving config: ${e.message}"
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = dirty && !saving,
                variant = ButtonVariant.Select
            ) {
                if (saving) ActivityIndicator(size = 16.dp)
                Text("Save")
            }
        }

        error?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedLg)
                    .background(colors.backgroundAlt)
                    .border(1.dp, colors.danger, RoundedLg)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.danger, modifier = Modifier.size(18.dp))
                Text(message, style = FrigateType.mono, color = colors.primary)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedLg)
                .background(colors.backgroundAlt)
                .border(1.dp, colors.secondaryHighlight, RoundedLg)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = FrigateType.mono.copy(color = colors.primary),
                cursorBrush = SolidColor(colors.primary),
                // YAML is whitespace-sensitive: no autocorrect, no auto-capitals
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    keyboardType = KeyboardType.Ascii
                )
            )
        }
    }
}

/** Null when [yaml] can be saved, otherwise what is wrong with it. */
private fun validate(yaml: String): String? {
    if (yaml.isBlank()) return "Configuration cannot be empty"
    return try {
        // An empty camera list is allowed: it is how every camera is removed.
        YamlConfigParser.parseConfig(yaml)
        null
    } catch (e: Exception) {
        "YAML syntax error: ${e.message}"
    }
}
