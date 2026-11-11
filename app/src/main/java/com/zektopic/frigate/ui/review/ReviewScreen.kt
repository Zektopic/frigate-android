package com.zektopic.frigate.ui.review

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RuleFolder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zektopic.frigate.data.EventEntity
import com.zektopic.frigate.data.ReviewSeverity
import com.zektopic.frigate.ui.NvrActions
import com.zektopic.frigate.ui.NvrUiState
import com.zektopic.frigate.ui.components.ActivityIndicator
import com.zektopic.frigate.ui.components.ButtonVariant
import com.zektopic.frigate.ui.components.Chip
import com.zektopic.frigate.ui.components.ConfirmDialog
import com.zektopic.frigate.ui.components.FrigateButton
import com.zektopic.frigate.ui.components.FrigateLogo
import com.zektopic.frigate.ui.components.FrigateSwitch
import com.zektopic.frigate.ui.components.RoundedLg
import com.zektopic.frigate.ui.components.SettingRow
import com.zektopic.frigate.ui.components.ToggleItem
import com.zektopic.frigate.ui.components.rememberNow
import com.zektopic.frigate.ui.components.rememberStoredBitmap
import com.zektopic.frigate.ui.components.tileShape
import com.zektopic.frigate.ui.components.timeAgo
import com.zektopic.frigate.ui.live.LiveTile
import com.zektopic.frigate.ui.theme.ChipGray
import com.zektopic.frigate.ui.theme.ChipGreen
import com.zektopic.frigate.ui.theme.FrigateColors
import com.zektopic.frigate.ui.theme.FrigateTheme
import com.zektopic.frigate.ui.theme.FrigateType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val TAB_ALERT = "alert"
private const val TAB_DETECTION = "detection"
private const val TAB_MOTION = "motion"
private const val DAY_MS = 24 * 3_600_000L

fun FrigateColors.severityColor(severity: ReviewSeverity, dimmed: Boolean = false) = when (severity) {
    ReviewSeverity.ALERT -> if (dimmed) severityAlertDimmed else severityAlert
    ReviewSeverity.DETECTION -> if (dimmed) severityDetectionDimmed else severityDetection
}

fun eventDateFormat(use24Hour: Boolean): SimpleDateFormat =
    SimpleDateFormat(if (use24Hour) "MMM d, HH:mm" else "MMM d, h:mm a", Locale.getDefault())

/**
 * The web UI's Review page: alerts and detections as a grid of thumbnails beside a
 * timeline, filterable by camera and day, each item marked reviewed once looked at.
 */
@Composable
fun ReviewScreen(
    state: NvrUiState,
    frames: Map<String, Bitmap>,
    actions: NvrActions,
    wide: Boolean,
    openEventId: Long?,
    onOpenEvent: (Long?) -> Unit,
    cameraFilter: String?,
    onCameraFilterChange: (String?) -> Unit
) {
    val open = openEventId?.let { id -> state.events.firstOrNull { it.id == id } }
    if (open != null) {
        RecordingView(open, state, actions, wide, onBack = { onOpenEvent(null) })
        return
    }

    val colors = FrigateTheme.colors
    var tab by rememberSaveable { mutableStateOf(TAB_ALERT) }
    var showReviewed by rememberSaveable { mutableStateOf(false) }
    // Start of the chosen day, or null for the default "Last 24 Hours"
    var day by rememberSaveable { mutableStateOf<Long?>(null) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val now = rememberNow()

    val inRange = remember(state.events, cameraFilter, day, now / 60_000) {
        val from = day ?: (now - DAY_MS)
        val to = day?.plus(DAY_MS) ?: Long.MAX_VALUE
        state.events.filter { event ->
            (cameraFilter == null || event.cameraId == cameraFilter) && event.timestamp in from until to
        }
    }
    val alertCount = remember(inRange) { inRange.count { it.reviewSeverity == ReviewSeverity.ALERT && !it.hasBeenReviewed } }
    val detectionCount = remember(inRange) { inRange.count { it.reviewSeverity == ReviewSeverity.DETECTION && !it.hasBeenReviewed } }
    val items = remember(inRange, tab, showReviewed) {
        val severity = if (tab == TAB_DETECTION) ReviewSeverity.DETECTION else ReviewSeverity.ALERT
        inRange.filter { it.reviewSeverity == severity && (showReviewed || !it.hasBeenReviewed) }
    }

    Column(Modifier.fillMaxSize().padding(top = 8.dp, bottom = if (wide) 8.dp else 0.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(start = if (wide) 12.dp else 8.dp, end = 8.dp)
        ) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                ToggleItem(tab == TAB_ALERT, { tab = TAB_ALERT; selected = emptySet() }) {
                    SeverityTabLabel("Alerts", alertCount, colors.severityAlert, wide, tab == TAB_ALERT)
                }
                ToggleItem(tab == TAB_DETECTION, { tab = TAB_DETECTION; selected = emptySet() }) {
                    SeverityTabLabel("Detections", detectionCount, colors.severityDetection, wide, tab == TAB_DETECTION)
                }
                ToggleItem(tab == TAB_MOTION, { tab = TAB_MOTION; selected = emptySet() }) {
                    if (wide) {
                        Icon(Icons.Filled.Circle, contentDescription = null, tint = colors.severityMotion, modifier = Modifier.size(8.dp))
                        Text("Motion")
                    } else {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = "Motion",
                            tint = colors.severityMotion,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            if (!wide) FrigateLogo(Modifier.align(Alignment.Center).size(32.dp))

            Row(
                Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selected.isNotEmpty()) {
                    if (wide) Text("${selected.size} selected", style = FrigateType.sm, color = colors.primary)
                    val allReviewed = items.filter { it.id in selected }.all { it.hasBeenReviewed }
                    FrigateButton(onClick = {
                        actions.setReviewed(selected.toList(), !allReviewed)
                        selected = emptySet()
                    }) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.secondaryForeground, modifier = Modifier.size(18.dp))
                        if (wide) Text(if (allReviewed) "Mark as unreviewed" else "Mark as reviewed")
                    }
                    FrigateButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = colors.secondaryForeground, modifier = Modifier.size(18.dp))
                        if (wide) Text("Delete")
                    }
                } else {
                    CameraFilterButton(state, cameraFilter, onCameraFilterChange, wide)
                    if (tab != TAB_MOTION) {
                        DayFilterButton(state.events, day, { day = it }, wide)
                        ReviewedFilterButton(showReviewed, { showReviewed = it }, wide)
                    }
                }
            }
        }

        Box(Modifier.height(8.dp))

        if (tab == TAB_MOTION) {
            MotionReview(state, frames, cameraFilter, wide)
        } else {
            Row(Modifier.fillMaxSize()) {
                val gridState = rememberLazyGridState()
                val scope = rememberCoroutineScope()
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    if (items.isEmpty()) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.RuleFolder, contentDescription = null, tint = colors.primary, modifier = Modifier.size(64.dp))
                            Text(
                                if (tab == TAB_ALERT) "There are no alerts to review" else "There are no detections to review",
                                style = FrigateType.base,
                                color = colors.primary
                            )
                        }
                    } else {
                        // sm:grid-cols-2 md:grid-cols-3 3xl:grid-cols-4
                        val columns = when {
                            maxWidth >= 1500.dp -> 4
                            maxWidth >= 700.dp -> 3
                            maxWidth >= 560.dp -> 2
                            else -> 1
                        }
                        val format = remember(state.ui.use24HourTime) { eventDateFormat(state.ui.use24HourTime) }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = if (wide) 12.dp else 4.dp),
                            verticalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 8.dp)
                        ) {
                            items(items, key = { it.id }) { event ->
                                ReviewThumbnail(
                                    event = event,
                                    cameraName = state.cameras.firstOrNull { it.id == event.cameraId }?.name
                                        ?: event.cameraId.replace('_', ' ').replaceFirstChar { it.uppercase() },
                                    now = now,
                                    dateText = format.format(Date(event.timestamp)),
                                    selected = event.id in selected,
                                    onClick = {
                                        if (selected.isNotEmpty()) selected = selected.toggle(event.id)
                                        else onOpenEvent(event.id)
                                    },
                                    onLongClick = { selected = selected.toggle(event.id) }
                                )
                            }
                            if (items.any { !it.hasBeenReviewed }) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "mark-all") {
                                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                        FrigateButton(
                                            onClick = { actions.setReviewed(items.filter { !it.hasBeenReviewed }.map { it.id }, true) },
                                            variant = ButtonVariant.Select,
                                            small = false
                                        ) { Text("Mark these items as reviewed") }
                                    }
                                }
                            }
                        }
                    }
                }
                val firstVisible by remember { derivedStateOf { gridState.firstVisibleItemIndex } }
                ReviewTimeline(
                    events = items,
                    rangeStart = day ?: (now - DAY_MS),
                    rangeEnd = day?.plus(DAY_MS)?.coerceAtMost(now) ?: now,
                    currentTime = items.getOrNull(firstVisible)?.timestamp,
                    wide = wide,
                    use24Hour = state.ui.use24HourTime,
                    onSeek = { time ->
                        // Events are newest first: the first one at or before the tapped time
                        val index = items.indexOfFirst { it.timestamp <= time }.takeIf { it >= 0 } ?: items.lastIndex
                        if (index >= 0) scope.launch { gridState.animateScrollToItem(index) }
                    }
                )
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Confirm Delete",
            text = "Deleting ${selected.size} item(s) removes their recordings and snapshots. This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                actions.deleteEvents(state.events.filter { it.id in selected })
                selected = emptySet()
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

/** On a phone the tab is just its count in the severity's colour; wider, a dot and a label. */
@Composable
private fun SeverityTabLabel(label: String, count: Int, color: Color, wide: Boolean, selected: Boolean) {
    if (wide) {
        Icon(Icons.Filled.Circle, contentDescription = null, tint = color, modifier = Modifier.size(8.dp))
        Text("$label ∙ $count")
    } else {
        Text(
            "$count",
            style = FrigateType.sm.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = color
        )
    }
}

@Composable
private fun FilterButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    wide: Boolean,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit
) {
    val colors = FrigateTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        FrigateButton(onClick = { open = true }, variant = if (active) ButtonVariant.Select else ButtonVariant.Default) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (active) colors.selectedForeground else colors.secondaryForeground,
                modifier = Modifier.size(18.dp)
            )
            if (wide) Text(label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
    }
}

@Composable
private fun MenuOption(text: String, checked: Boolean, onClick: () -> Unit) {
    val colors = FrigateTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = if (checked) colors.primary else Color.Transparent,
            modifier = Modifier.size(16.dp)
        )
        Text(text, style = FrigateType.sm, color = colors.primary)
    }
}

@Composable
private fun CameraFilterButton(state: NvrUiState, cameraFilter: String?, onChange: (String?) -> Unit, wide: Boolean) {
    val current = state.cameras.firstOrNull { it.id == cameraFilter }
    FilterButton(Icons.Filled.Videocam, current?.name ?: "All Cameras", cameraFilter != null, wide) { close ->
        MenuOption("All Cameras", cameraFilter == null) { onChange(null); close() }
        for (camera in state.cameras) {
            MenuOption(camera.name, camera.id == cameraFilter) { onChange(camera.id); close() }
        }
    }
}

@Composable
private fun DayFilterButton(events: List<EventEntity>, day: Long?, onChange: (Long?) -> Unit, wide: Boolean) {
    // Only days that have something to show, newest first
    val days = remember(events) { events.map { startOfDay(it.timestamp) }.distinct().sortedDescending().take(31) }
    val label = day?.let { dayLabel(it) } ?: "Last 24 Hours"
    FilterButton(Icons.Filled.CalendarMonth, label, day != null, wide) { close ->
        MenuOption("Last 24 Hours", day == null) { onChange(null); close() }
        for (d in days) {
            MenuOption(dayLabel(d), d == day) { onChange(d); close() }
        }
    }
}

@Composable
private fun ReviewedFilterButton(showReviewed: Boolean, onChange: (Boolean) -> Unit, wide: Boolean) {
    FilterButton(Icons.Filled.FilterAlt, "Filter", showReviewed, wide) {
        SettingRow("Show Reviewed", modifier = Modifier.width(240.dp).padding(horizontal = 16.dp, vertical = 4.dp)) {
            FrigateSwitch(showReviewed, onChange)
        }
    }
}

private fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayLabel(dayStart: Long): String {
    val today = startOfDay(System.currentTimeMillis())
    return when (dayStart) {
        today -> "Today"
        today - DAY_MS -> "Yesterday"
        else -> SimpleDateFormat("MMMM d", Locale.getDefault()).format(Date(dayStart))
    }
}

/** One review item: the web UI's `PreviewThumbnailPlayer`. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReviewThumbnail(
    event: EventEntity,
    cameraName: String,
    now: Long,
    dateText: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colors = FrigateTheme.colors
    val snapshot = rememberStoredBitmap(event.snapshotPath)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedLg)
            .background(colors.backgroundAlt)
            .then(if (selected) Modifier.border(3.dp, colors.severityColor(event.reviewSeverity), RoundedLg) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        if (snapshot != null) {
            Image(
                bitmap = snapshot.asImageBitmap(),
                contentDescription = "${event.label} on ${event.cameraId}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Filled.Sensors,
                contentDescription = null,
                tint = colors.secondaryForeground,
                modifier = Modifier.align(Alignment.Center).size(32.dp)
            )
        }

        Chip(
            modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 8.dp),
            gradient = if (event.hasBeenReviewed) ChipGreen else ChipGray
        ) {
            Icon(Icons.Filled.Sensors, contentDescription = event.label, modifier = Modifier.size(12.dp))
            Text(cameraName)
            for (zone in event.zones) Text("∙ ${zone.replace('_', ' ')}")
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.2f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            // Still recording: a spinner where the age will be
            if (event.endTime == null) ActivityIndicator(size = 18.dp)
            else Text(timeAgo(now, event.timestamp), style = FrigateType.sm, color = Color.White)
            Text(dateText, style = FrigateType.sm, color = Color.White)
        }
    }
}

/**
 * The Motion tab. Frigate scrubs every camera through recorded motion; this app keeps
 * events rather than continuous footage, so the tab shows the cameras as they are now,
 * each outlined in the motion colour while it is moving.
 */
@Composable
private fun MotionReview(state: NvrUiState, frames: Map<String, Bitmap>, cameraFilter: String?, wide: Boolean) {
    val colors = FrigateTheme.colors
    val cameras = state.cameras.filter { it.isEnabled && (cameraFilter == null || it.id == cameraFilter) }
    if (cameras.isEmpty() || !state.isServiceRunning) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(64.dp).graphicsLayer { rotationZ = 90f }
                )
                Text("No motion data found", style = FrigateType.base, color = colors.primary)
            }
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = when {
            maxWidth >= 1500.dp -> 4
            maxWidth >= 900.dp -> 3
            maxWidth >= 560.dp -> 2
            else -> 1
        }
        val shape = tileShape(wide)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = if (wide) 12.dp else 4.dp),
            verticalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 8.dp)
        ) {
            items(cameras, key = { it.id }) { camera ->
                val moving = camera.id in state.activeMotion
                Box(
                    Modifier
                        .aspectRatio(16f / 9f)
                        .then(if (moving) Modifier.border(3.dp, colors.severityMotion, shape) else Modifier)
                ) {
                    LiveTile(
                        camera = camera,
                        frame = frames[camera.id],
                        streamState = state.streamState(camera.id),
                        overBudget = camera.name in state.camerasOverBudget,
                        motion = moving,
                        alerting = false,
                        showName = true,
                        wide = wide,
                        compact = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
