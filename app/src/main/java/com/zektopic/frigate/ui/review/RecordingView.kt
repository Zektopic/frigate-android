package com.zektopic.frigate.ui.review

import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zektopic.frigate.data.EventEntity
import com.zektopic.frigate.data.RecordingStore
import com.zektopic.frigate.ui.NvrActions
import com.zektopic.frigate.ui.NvrUiState
import com.zektopic.frigate.ui.components.ActivityIndicator
import com.zektopic.frigate.ui.components.ConfirmDialog
import com.zektopic.frigate.ui.components.FrigateButton
import com.zektopic.frigate.ui.components.FrigateSlider
import com.zektopic.frigate.ui.components.Panel
import com.zektopic.frigate.ui.components.RoundedLg
import com.zektopic.frigate.ui.components.rememberStoredBitmap
import com.zektopic.frigate.ui.components.tileShape
import com.zektopic.frigate.ui.theme.FrigateTheme
import com.zektopic.frigate.ui.theme.FrigateType
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One review item, opened: its clip, what it is, and what can be done with it. */
@Composable
fun RecordingView(
    event: EventEntity,
    state: NvrUiState,
    actions: NvrActions,
    wide: Boolean,
    onBack: () -> Unit
) {
    val colors = FrigateTheme.colors
    var confirmDelete by remember { mutableStateOf(false) }
    val camera = state.cameras.firstOrNull { it.id == event.cameraId }
    val cameraName = camera?.name ?: event.cameraId.replace('_', ' ')

    // Opening an item is reviewing it, as in the web UI.
    LaunchedEffect(event.id) {
        if (!event.hasBeenReviewed) actions.setReviewed(listOf(event.id), true)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(if (wide) 12.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            FrigateButton(onClick = onBack, shape = RoundedLg) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.secondaryForeground, modifier = Modifier.size(20.dp))
                if (wide) Text("Back")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FrigateButton(onClick = { actions.setReviewed(listOf(event.id), !event.hasBeenReviewed) }, shape = RoundedLg) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (event.hasBeenReviewed) colors.success else colors.secondaryForeground,
                        modifier = Modifier.size(18.dp)
                    )
                    if (wide) Text(if (event.hasBeenReviewed) "Mark as unreviewed" else "Mark as reviewed")
                }
                FrigateButton(onClick = { confirmDelete = true }, shape = RoundedLg) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = colors.secondaryForeground, modifier = Modifier.size(18.dp))
                    if (wide) Text("Delete")
                }
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val ratio = (camera?.let { it.detectWidth.toFloat() / it.detectHeight.coerceAtLeast(1) } ?: (16f / 9f))
            val containerRatio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
            Box(
                Modifier
                    .aspectRatio(ratio, matchHeightConstraintsFirst = containerRatio > ratio)
                    .clip(tileShape(wide))
                    .background(Color.Black)
            ) {
                val clip = event.videoPath
                if (!clip.isNullOrEmpty()) {
                    ClipPlayer(clip)
                } else {
                    val snapshot = rememberStoredBitmap(event.snapshotPath)
                    if (snapshot != null) {
                        Image(snapshot.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp)
                            .clip(RoundedLg)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (event.endTime == null) {
                            ActivityIndicator(size = 16.dp)
                            Text("Recording in progress", style = FrigateType.sm, color = Color.White)
                        } else {
                            Text("No recording was saved for this item", style = FrigateType.sm, color = Color.White)
                        }
                    }
                }
            }
        }

        Panel(Modifier.fillMaxWidth(), wide = wide) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Filled.Circle,
                    contentDescription = null,
                    tint = colors.severityColor(event.reviewSeverity),
                    modifier = Modifier.size(8.dp)
                )
                Text(
                    cameraName,
                    style = FrigateType.sm.copy(fontWeight = FontWeight.Medium),
                    color = colors.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    SimpleDateFormat(
                        if (state.ui.use24HourTime) "MMM d, yyyy HH:mm:ss" else "MMM d, yyyy h:mm:ss a",
                        Locale.getDefault()
                    ).format(Date(event.timestamp)),
                    style = FrigateType.sm,
                    color = colors.mutedForeground
                )
            }
            val details = buildList {
                add(event.reviewSeverity.key.replaceFirstChar { it.uppercase() })
                add(event.label.replaceFirstChar { it.uppercase() })
                if (event.zones.isNotEmpty()) add("Zones: " + event.zones.joinToString(", ") { it.replace('_', ' ') })
                event.endTime?.let { end -> add("${((end - event.timestamp) / 1000).coerceAtLeast(1)}s") }
            }
            Text(
                details.joinToString("  ∙  "),
                style = FrigateType.xs,
                color = colors.mutedForeground,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Confirm Delete",
            text = "This removes the item with its recording and snapshot. This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                actions.deleteEvents(listOf(event))
                onBack()
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

/**
 * Plays a finalized clip with Frigate-style controls laid over the video: tap to show
 * or hide them, a play button, the position, and a seek bar.
 */
@Composable
private fun ClipPlayer(ref: String) {
    var player by remember { mutableStateOf<VideoView?>(null) }
    var playing by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var controls by remember { mutableStateOf(true) }
    var seeking by remember { mutableStateOf(false) }

    // setVideoURI, not setVideoPath: clips recorded to a user-chosen folder are
    // content:// documents, which setVideoPath(String) mangles.
    val uri = remember(ref) { RecordingStore.toPlayableUri(ref) }

    LaunchedEffect(player, playing) {
        while (playing) {
            if (!seeking) player?.let { position = it.currentPosition }
            delay(200)
        }
    }
    DisposableEffect(Unit) { onDispose { player?.stopPlayback() } }

    Box(Modifier.fillMaxSize().clickable { controls = !controls }) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setVideoURI(uri)
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        duration = mp.duration.coerceAtLeast(0)
                        prepared = true
                        start()
                        playing = true
                    }
                    setOnErrorListener { _, _, _ ->
                        failed = true
                        playing = false
                        true
                    }
                    player = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (failed) {
            Text(
                "This recording could not be played",
                style = FrigateType.sm,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (!prepared) {
            ActivityIndicator(Modifier.align(Alignment.Center))
        }

        if (controls && prepared && !failed) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedLg)
                        .clickable {
                            player?.let {
                                if (playing) it.pause() else it.start()
                                playing = !playing
                            }
                        }
                        .padding(4.dp)
                )
                Text("${clock(position)} / ${clock(duration)}", style = FrigateType.xs, color = Color.White)
                FrigateSlider(
                    value = position.toFloat(),
                    onValueChange = {
                        seeking = true
                        position = it.toInt()
                    },
                    onValueChangeFinished = {
                        player?.seekTo(position)
                        seeking = false
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun clock(ms: Int): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
