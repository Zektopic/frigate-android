package com.zektopic.frigate.ui.live

import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.data.EventEntity
import com.zektopic.frigate.data.ReviewSeverity
import com.zektopic.frigate.media.StreamState
import com.zektopic.frigate.ui.CameraGroupSelector
import com.zektopic.frigate.ui.LiveGroups
import com.zektopic.frigate.ui.NvrActions
import com.zektopic.frigate.ui.NvrUiState
import com.zektopic.frigate.ui.components.ActivityIndicator
import com.zektopic.frigate.ui.components.Chip
import com.zektopic.frigate.ui.components.EmptyCard
import com.zektopic.frigate.ui.components.FrigateLogo
import com.zektopic.frigate.ui.components.RoundedLg
import com.zektopic.frigate.ui.components.RoundedMd
import com.zektopic.frigate.ui.components.rememberNow
import com.zektopic.frigate.ui.components.rememberStoredBitmap
import com.zektopic.frigate.ui.components.tileShape
import com.zektopic.frigate.ui.components.timeAgo
import com.zektopic.frigate.ui.theme.FrigateTheme
import com.zektopic.frigate.ui.theme.FrigateType

private const val LAYOUT_GRID = "grid"
private const val LAYOUT_LIST = "list"
private const val RECENT_ALERT_WINDOW_MS = 24 * 3_600_000L

@Composable
fun LiveScreen(
    state: NvrUiState,
    frames: Map<String, Bitmap>,
    actions: NvrActions,
    wide: Boolean,
    group: String,
    onGroupChange: (String) -> Unit,
    selectedCameraId: String?,
    onSelectCamera: (String?) -> Unit,
    onOpenEvent: (Long) -> Unit,
    onOpenHistory: (String) -> Unit,
    onAddCamera: () -> Unit
) {
    val selected = selectedCameraId?.let { id -> state.cameras.firstOrNull { it.id == id } }
    if (selected != null) {
        LiveCameraView(
            camera = selected,
            state = state,
            frame = frames[selected.id],
            actions = actions,
            wide = wide,
            onBack = { onSelectCamera(null) },
            onOpenHistory = { onOpenHistory(selected.id) }
        )
    } else {
        LiveDashboard(state, frames, actions, wide, group, onGroupChange, onSelectCamera, onOpenEvent, onAddCamera)
    }
}

@Composable
private fun LiveDashboard(
    state: NvrUiState,
    frames: Map<String, Bitmap>,
    actions: NvrActions,
    wide: Boolean,
    group: String,
    onGroupChange: (String) -> Unit,
    onSelectCamera: (String) -> Unit,
    onOpenEvent: (Long) -> Unit,
    onAddCamera: () -> Unit
) {
    // The web UI defaults to a grid on desktop and a list on a phone.
    val layout = state.ui.liveLayout ?: if (wide) LAYOUT_GRID else LAYOUT_LIST
    val now = rememberNow()

    val cameras = remember(state.cameras, group, state.activeMotion, state.activeAlerts) {
        if (group == LiveGroups.BIRDSEYE) {
            state.cameras.filter { it.id in state.activeMotion || it.id in state.activeAlerts }
        } else state.cameras
    }
    val recentAlerts = remember(state.events, now / 60_000) {
        state.events.asSequence()
            .filter { it.reviewSeverity == ReviewSeverity.ALERT && !it.hasBeenReviewed }
            .filter { now - it.timestamp < RECENT_ALERT_WINDOW_MS }
            .take(10)
            .toList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = if (wide) 8.dp else 4.dp,
                end = if (wide) 8.dp else 4.dp,
                top = 8.dp,
                bottom = if (wide) 8.dp else 0.dp
            )
    ) {
        if (!wide) {
            // Phone header: groups on the left, the logo centred, layout on the right
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                Box(Modifier.align(Alignment.CenterStart)) {
                    CameraGroupSelector(group, onGroupChange, vertical = false)
                }
                FrigateLogo(Modifier.align(Alignment.Center).size(32.dp))
                Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LayoutButton(Icons.Filled.GridView, "Use grid layout", layout == LAYOUT_GRID) {
                        actions.updateUi(state.ui.copy(liveLayout = LAYOUT_GRID))
                    }
                    LayoutButton(Icons.Filled.ViewAgenda, "Use list layout", layout == LAYOUT_LIST) {
                        actions.updateUi(state.ui.copy(liveLayout = LAYOUT_LIST))
                    }
                }
            }
        }

        when {
            state.cameras.isEmpty() -> CenteredEmpty {
                EmptyCard(
                    icon = Icons.Filled.VideocamOff,
                    title = "No Cameras Configured",
                    description = "Get started by connecting a camera to Frigate.",
                    buttonText = "Add Camera",
                    onButton = onAddCamera
                )
            }
            !state.isServiceRunning -> CenteredEmpty {
                EmptyCard(
                    icon = Icons.Filled.PowerSettingsNew,
                    title = "NVR service is stopped",
                    description = "Cameras are not being watched or recorded.",
                    buttonText = "Start NVR",
                    onButton = actions::startService
                )
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = when {
                    layout == LAYOUT_LIST -> 1
                    maxWidth >= 1600.dp -> 4
                    maxWidth >= 1280.dp -> 3
                    else -> 2
                }
                val gap = if (wide) 16.dp else 8.dp
                // Too narrow for the offline explanation: title and icon only
                val compact = maxWidth / columns < 280.dp
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = if (wide) 8.dp else 0.dp, vertical = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    if (recentAlerts.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "recent-alerts") {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                items(recentAlerts, key = { it.id }) { event ->
                                    AnimatedEventCard(event, now, wide) { onOpenEvent(event.id) }
                                }
                            }
                        }
                    }
                    if (cameras.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "no-activity") {
                            Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                                EmptyCard(
                                    icon = Icons.Filled.Visibility,
                                    title = "No cameras with activity",
                                    description = "Birdseye shows a camera while it sees motion."
                                )
                            }
                        }
                    }
                    items(cameras, key = { it.id }) { camera ->
                        LiveTile(
                            camera = camera,
                            frame = frames[camera.id],
                            streamState = state.streamState(camera.id),
                            overBudget = camera.name in state.camerasOverBudget,
                            motion = camera.id in state.activeMotion,
                            alerting = camera.id in state.activeAlerts,
                            showName = state.ui.alwaysShowCameraNames,
                            wide = wide,
                            compact = compact,
                            modifier = Modifier.aspectRatio(tileAspect(camera)),
                            onClick = { onSelectCamera(camera.id) }
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "bottom-space") { Box(Modifier.height(4.dp)) }
                }
            }
        }
    }
}

/**
 * `aspect-video` unless the camera is very wide (`aspect-wide`, 32:9) or portrait
 * (`aspect-tall`, 8:9), exactly as the dashboard classifies them.
 */
private fun tileAspect(camera: CameraConfigEntity): Float {
    val ratio = camera.detectWidth.toFloat() / camera.detectHeight.coerceAtLeast(1)
    return when {
        ratio > 2f -> 32f / 9f
        ratio < 1f -> 8f / 9f
        else -> 16f / 9f
    }
}

@Composable
private fun CenteredEmpty(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun LayoutButton(icon: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    val colors = FrigateTheme.colors
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 36.dp)
            .clip(RoundedMd)
            .background(if (selected) Color(0xFF1E3A8A).copy(alpha = 0.6f) else colors.secondary)
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (selected) colors.selected else colors.secondaryForeground,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** A recent, unreviewed alert in the strip above the cameras. */
@Composable
private fun AnimatedEventCard(event: EventEntity, now: Long, wide: Boolean, onClick: () -> Unit) {
    val snapshot = rememberStoredBitmap(event.snapshotPath)
    Box(
        modifier = Modifier
            .height(96.dp)
            .aspectRatio(16f / 9f)
            .clip(if (wide) RoundedLg else RoundedCornerShape(4.dp))
            // Dark in both themes, like the video it stands in for, so the white
            // time label stays readable when there is no snapshot.
            .background(Color(0xFF171717))
            .clickable(onClick = onClick)
    ) {
        if (snapshot != null) {
            Image(
                bitmap = snapshot.asImageBitmap(),
                contentDescription = "Alert on ${event.cameraId}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Filled.Sensors,
                contentDescription = null,
                tint = Color(0xFF6B6B6B),
                modifier = Modifier.align(Alignment.Center).size(24.dp)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(24.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF0F172A).copy(alpha = 0.5f))))
        ) {
            Text(
                timeAgo(now, event.timestamp),
                style = FrigateType.xs,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp)
            )
        }
    }
}

/**
 * One camera on the dashboard: the web UI's `LivePlayer`. Bare video with rounded
 * corners; everything else is an overlay.
 */
@Composable
fun LiveTile(
    camera: CameraConfigEntity,
    frame: Bitmap?,
    streamState: StreamState?,
    overBudget: Boolean,
    motion: Boolean,
    alerting: Boolean,
    showName: Boolean,
    wide: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onClick: (() -> Unit)? = null
) {
    val colors = FrigateTheme.colors
    val shape = tileShape(wide)
    val live = streamState == StreamState.LIVE && frame != null && !frame.isRecycled
    val offline = camera.isEnabled && (streamState == StreamState.OFFLINE || streamState == StreamState.RETRYING || overBudget)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.Black)
            // `outline-3 outline-severity_alert` while an alert is in progress
            .then(if (alerting && live) Modifier.border(3.dp, colors.severityAlert, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        when {
            !camera.isEnabled -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.backgroundAlt)
                    .border(1.dp, colors.secondaryForeground, shape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.VideocamOff, contentDescription = null, tint = colors.primary, modifier = Modifier.size(32.dp))
                    Text("Camera is off", style = FrigateType.sm, color = colors.primary)
                }
            }
            live -> {
                Image(
                    bitmap = frame!!.asImageBitmap(),
                    contentDescription = camera.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale
                )
                ImageShadowOverlay()
            }
            offline -> {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(12.dp)
                        .clip(RoundedLg)
                        .background(colors.background.copy(alpha = 0.5f))
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(if (overBudget) "Not Running" else "Stream Offline", style = FrigateType.base, color = colors.primary)
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
                    if (!compact) {
                        Text(
                            if (overBudget) "This device is already running as many streams as it can decode"
                            else "No frames have been received on the ${camera.name} detect stream, check error logs",
                            style = FrigateType.sm,
                            color = colors.primary,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            else -> ActivityIndicator(Modifier.align(Alignment.Center))
        }

        // Top-left: what is being seen, as the web UI lists tracked object icons
        if (live && alerting) {
            Chip(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 8.dp)) {
                Icon(Icons.Filled.Sensors, contentDescription = "Motion", modifier = Modifier.size(12.dp))
            }
        }

        // Top-right: camera name, and a pulsing dot while there is motion
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showName || offline || !camera.isEnabled) {
                Chip { Text(camera.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (live && motion) PulsingDot()
        }
    }
}

@Composable
private fun BoxScope.ImageShadowOverlay() {
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.3f)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Transparent)))
    )
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.1f)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.2f))))
    )
}

/** `size-2 animate-pulse text-danger`. */
@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "motion")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(1_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "motion-alpha"
    )
    Icon(
        Icons.Filled.Circle,
        contentDescription = "Motion",
        tint = FrigateTheme.colors.danger,
        modifier = Modifier.padding(end = 8.dp).size(8.dp).alpha(alpha)
    )
}
