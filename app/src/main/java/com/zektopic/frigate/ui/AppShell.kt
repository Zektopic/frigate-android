package com.zektopic.frigate.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zektopic.frigate.ui.components.FrigateLogo
import com.zektopic.frigate.ui.components.IconSquareButton
import com.zektopic.frigate.ui.components.RoundedLg
import com.zektopic.frigate.ui.components.RoundedMd
import com.zektopic.frigate.ui.config.ConfigEditorScreen
import com.zektopic.frigate.ui.live.LiveScreen
import com.zektopic.frigate.ui.review.ReviewScreen
import com.zektopic.frigate.ui.settings.SettingsPages
import com.zektopic.frigate.ui.settings.SettingsScreen
import com.zektopic.frigate.ui.system.SystemScreen
import com.zektopic.frigate.ui.theme.FrigateTheme
import com.zektopic.frigate.ui.theme.FrigateType
import com.zektopic.frigate.ui.theme.ThemeMode

/** Camera groups on the Live page. */
object LiveGroups {
    const val ALL = "default"
    /** Only cameras with activity right now, like Birdseye in its `motion` mode. */
    const val BIRDSEYE = "birdseye"
}

/**
 * The frame every page sits in, laid out as the Frigate web UI lays itself out:
 * a 52dp sidebar and a status bar on a wide screen, a bottom bar on a phone held
 * upright.
 */
@Composable
fun FrigateAppShell(
    state: NvrUiState,
    frames: Map<String, Bitmap>,
    actions: NvrActions,
    openReviewRequest: Int = 0
) {
    val colors = FrigateTheme.colors
    var page by rememberSaveable { mutableStateOf(Page.LIVE) }
    var liveGroup by rememberSaveable { mutableStateOf(LiveGroups.ALL) }
    var liveCameraId by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewEventId by rememberSaveable { mutableStateOf<Long?>(null) }
    var reviewCamera by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }

    // An alert notification was tapped: land on Review.
    LaunchedEffect(openReviewRequest) {
        if (openReviewRequest > 0) {
            page = Page.REVIEW
            reviewEventId = null
        }
    }

    fun navigate(target: Page) {
        if (target == page) {
            // Tapping the current page's icon returns to its top level, as a link would.
            when (target) {
                Page.LIVE -> liveCameraId = null
                Page.REVIEW -> reviewEventId = null
                Page.SETTINGS -> settingsPage = null
                else -> Unit
            }
        }
        page = target
    }

    val inSubPage = when (page) {
        Page.LIVE -> liveCameraId != null
        Page.REVIEW -> reviewEventId != null
        Page.SETTINGS -> settingsPage != null
        else -> false
    }
    BackHandler(enabled = inSubPage || page != Page.LIVE) {
        when {
            page == Page.LIVE && liveCameraId != null -> liveCameraId = null
            page == Page.REVIEW && reviewEventId != null -> reviewEventId = null
            page == Page.SETTINGS && settingsPage != null -> settingsPage = null
            else -> page = Page.LIVE
        }
    }

    val messages = remember(state.isServiceRunning, state.streamStates, state.camerasOverBudget, state.cameras, state.stats.lastOrNull()?.cpuPercent?.let { it >= 80f }) {
        state.statusMessages()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        val wide = maxWidth > maxHeight || maxWidth >= 840.dp
        // A phone on its side has no height to spare for a status bar; its messages
        // move to a warning icon in the sidebar instead.
        val showStatusbar = wide && maxHeight >= 480.dp

        val content: @Composable () -> Unit = {
            when (page) {
                Page.LIVE -> LiveScreen(
                    state = state,
                    frames = frames,
                    actions = actions,
                    wide = wide,
                    group = liveGroup,
                    onGroupChange = { liveGroup = it },
                    selectedCameraId = liveCameraId,
                    onSelectCamera = { liveCameraId = it },
                    onOpenEvent = { id ->
                        reviewEventId = id
                        page = Page.REVIEW
                    },
                    onOpenHistory = { cameraId ->
                        reviewCamera = cameraId
                        reviewEventId = null
                        page = Page.REVIEW
                    },
                    onAddCamera = {
                        settingsPage = SettingsPages.CAMERA_MANAGEMENT
                        page = Page.SETTINGS
                    }
                )
                Page.REVIEW -> ReviewScreen(
                    state = state,
                    frames = frames,
                    actions = actions,
                    wide = wide,
                    openEventId = reviewEventId,
                    onOpenEvent = { reviewEventId = it },
                    cameraFilter = reviewCamera,
                    onCameraFilterChange = { reviewCamera = it }
                )
                Page.SYSTEM -> SystemScreen(state = state, wide = wide)
                Page.SETTINGS -> SettingsScreen(
                    state = state,
                    frames = frames,
                    actions = actions,
                    wide = wide,
                    pageKey = settingsPage,
                    onPageChange = { settingsPage = it }
                )
                Page.CONFIG -> ConfigEditorScreen(state = state, actions = actions, wide = wide)
            }
        }

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(
                    page = page,
                    showGroups = page == Page.LIVE && liveCameraId == null,
                    liveGroup = liveGroup,
                    onGroupChange = { liveGroup = it },
                    onNavigate = ::navigate,
                    state = state,
                    actions = actions,
                    messages = if (showStatusbar) emptyList() else messages
                )
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                    if (showStatusbar) Statusbar(state, messages, ::navigate)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                Bottombar(page, ::navigate, state, actions, messages)
            }
        }
    }
}

private data class NavEntry(val page: Page, val icon: ImageVector, val title: String)

private val navEntries = listOf(
    NavEntry(Page.LIVE, Icons.Filled.Videocam, "Live"),
    NavEntry(Page.REVIEW, Icons.Filled.VideoLibrary, "Review")
)

// ---------------------------------------------------------------------------
// Wide layout: sidebar and status bar
// ---------------------------------------------------------------------------

@Composable
private fun Sidebar(
    page: Page,
    showGroups: Boolean,
    liveGroup: String,
    onGroupChange: (String) -> Unit,
    onNavigate: (Page) -> Unit,
    state: NvrUiState,
    actions: NvrActions,
    messages: List<StatusMessage>
) {
    val colors = FrigateTheme.colors
    val borderColor = colors.secondaryHighlight
    Column(
        modifier = Modifier
            .width(52.dp)
            .fillMaxHeight()
            .background(colors.backgroundAlt)
            .drawBehind {
                val x = size.width - 0.5.dp.toPx()
                drawLine(borderColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FrigateLogo(
            modifier = Modifier
                .size(32.dp)
                .clickable { onNavigate(Page.LIVE) }
        )
        Spacer(Modifier.height(24.dp))
        for (entry in navEntries) {
            val groupsHere = showGroups && entry.page == Page.LIVE
            IconSquareButton(
                icon = entry.icon,
                contentDescription = entry.title,
                active = page == entry.page,
                onClick = { onNavigate(entry.page) }
            )
            Spacer(Modifier.height(if (groupsHere) 8.dp else 16.dp))
            if (groupsHere) {
                CameraGroupSelector(liveGroup, onGroupChange, vertical = true)
                Spacer(Modifier.height(16.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (messages.isNotEmpty()) StatusAlert(messages, onNavigate)
            GeneralSettings(page, onNavigate, state, actions, asSheet = false) { open ->
                IconSquareButton(
                    icon = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    active = page == Page.SYSTEM || page == Page.SETTINGS || page == Page.CONFIG,
                    onClick = open
                )
            }
        }
    }
}

/** The group buttons: `bg-blue-900/60` with a `text-selected` icon when chosen. */
@Composable
fun CameraGroupSelector(group: String, onGroupChange: (String) -> Unit, vertical: Boolean) {
    val buttons: @Composable () -> Unit = {
        GroupButton(Icons.Filled.Home, "All Cameras", group == LiveGroups.ALL) { onGroupChange(LiveGroups.ALL) }
        GroupButton(Icons.Filled.Visibility, "Birdseye", group == LiveGroups.BIRDSEYE) { onGroupChange(LiveGroups.BIRDSEYE) }
    }
    if (vertical) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) { buttons() }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
    }
}

@Composable
private fun GroupButton(icon: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    val colors = FrigateTheme.colors
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedMd)
            .background(if (selected) Color(0xFF1E3A8A).copy(alpha = 0.6f) else colors.secondary)
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (selected) colors.selected else colors.secondaryForeground,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun Statusbar(state: NvrUiState, messages: List<StatusMessage>, onNavigate: (Page) -> Unit) {
    val colors = FrigateTheme.colors
    val borderColor = colors.secondaryHighlight
    val textColor = if (colors.isDark) colors.secondaryForeground else colors.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(colors.backgroundAlt)
            .drawBehind { drawLine(borderColor, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val latest = state.stats.lastOrNull()
        if (state.isServiceRunning && latest != null) {
            val cpu = latest.cpuPercent.toInt()
            StatusbarStat("CPU $cpu%", colors.cpuColor(cpu), textColor) { onNavigate(Page.SYSTEM) }
            val memory = latest.memoryPercent.toInt()
            StatusbarStat("Memory $memory%", colors.cpuColor(memory), textColor) { onNavigate(Page.SYSTEM) }
        }
        Spacer(Modifier.weight(1f))
        if (messages.isEmpty()) {
            if (state.isServiceRunning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = colors.success, modifier = Modifier.size(12.dp))
                    Text("System is healthy", style = FrigateType.sm, color = textColor)
                }
            }
        } else {
            // The first problem inline; the rest behind it in the same popup the
            // phone layout uses.
            val first = messages.first()
            Row(
                modifier = Modifier.clickable { onNavigate(first.page) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (first.warning) Color(0xFFFB923C) else colors.danger,
                    modifier = Modifier.size(16.dp)
                )
                Text(first.text, style = FrigateType.sm, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (messages.size > 1) {
                StatusAlert(messages, onNavigate, label = "+${messages.size - 1} more")
            }
        }
    }
}

@Composable
private fun StatusbarStat(text: String, dot: Color, textColor: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Filled.Circle, contentDescription = null, tint = dot, modifier = Modifier.size(8.dp))
        Text(text, style = FrigateType.sm, color = textColor)
    }
}

// ---------------------------------------------------------------------------
// Phone layout: bottom bar
// ---------------------------------------------------------------------------

@Composable
private fun Bottombar(
    page: Page,
    onNavigate: (Page) -> Unit,
    state: NvrUiState,
    actions: NvrActions,
    messages: List<StatusMessage>
) {
    val colors = FrigateTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(colors.background)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        for (entry in navEntries) {
            BottombarItem(entry.icon, entry.title, page == entry.page) { onNavigate(entry.page) }
        }
        GeneralSettings(page, onNavigate, state, actions, asSheet = true) { open ->
            BottombarItem(
                Icons.Filled.Settings,
                "Settings",
                page == Page.SYSTEM || page == Page.SETTINGS || page == Page.CONFIG,
                open
            )
        }
        if (messages.isNotEmpty()) StatusAlert(messages, onNavigate, sheet = true)
    }
}

/** The `secondary` nav variant: no background, the icon itself takes `text-selected`. */
@Composable
private fun BottombarItem(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    val colors = FrigateTheme.colors
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedLg)
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) colors.selected else colors.secondaryForeground,
            modifier = Modifier.size(24.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Status messages
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusAlert(
    messages: List<StatusMessage>,
    onNavigate: (Page) -> Unit,
    sheet: Boolean = false,
    label: String? = null
) {
    val colors = FrigateTheme.colors
    var open by remember { mutableStateOf(false) }

    val list: @Composable ColumnScope.() -> Unit = {
        for (message in messages) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        open = false
                        onNavigate(message.page)
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (message.warning) Color(0xFFFB923C) else colors.danger,
                    modifier = Modifier.size(20.dp)
                )
                Text(message.text, style = FrigateType.xs, color = colors.primary)
            }
        }
    }

    Box {
        if (label != null) {
            Text(
                label,
                style = FrigateType.sm,
                color = colors.mutedForeground,
                modifier = Modifier.clickable { open = true }
            )
        } else {
            Box(
                modifier = Modifier
                    .size(if (sheet) 48.dp else 32.dp)
                    .clip(RoundedLg)
                    .clickable { open = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = "${messages.size} problems",
                    tint = colors.danger,
                    modifier = Modifier.size(if (sheet) 24.dp else 20.dp)
                )
            }
        }
        if (open && !sheet) {
            DropdownMenu(expanded = true, onDismissRequest = { open = false }, content = list)
        }
    }
    if (open && sheet) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            containerColor = colors.background,
            contentColor = colors.primary
        ) {
            Column(Modifier.padding(bottom = 24.dp), content = list)
        }
    }
}

// ---------------------------------------------------------------------------
// The gear menu
// ---------------------------------------------------------------------------

/**
 * The web UI's General Settings menu: system pages, configuration pages, appearance
 * and help. A dropdown beside the sidebar, a drawer on a phone. Where Frigate offers
 * "Restart Frigate", this offers what the app can actually do: stop or start the NVR.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneralSettings(
    page: Page,
    onNavigate: (Page) -> Unit,
    state: NvrUiState,
    actions: NvrActions,
    asSheet: Boolean,
    trigger: @Composable (open: () -> Unit) -> Unit
) {
    val colors = FrigateTheme.colors
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }

    fun go(target: Page) {
        open = false
        onNavigate(target)
    }

    val menu: @Composable ColumnScope.() -> Unit = {
        MenuLabel("System")
        MenuRow(Icons.Filled.MonitorHeart, "System metrics", selected = page == Page.SYSTEM) { go(Page.SYSTEM) }
        MenuSeparator()
        MenuLabel("Configuration")
        MenuRow(Icons.Filled.Settings, "Settings", selected = page == Page.SETTINGS) { go(Page.SETTINGS) }
        MenuRow(Icons.Filled.EditNote, "Configuration editor", selected = page == Page.CONFIG) { go(Page.CONFIG) }
        MenuSeparator()
        MenuLabel("Appearance")
        for (mode in ThemeMode.values()) {
            MenuRow(
                icon = if (mode == state.ui.themeMode) Icons.Filled.Check else Icons.Filled.DarkMode,
                text = "Dark Mode: ${mode.label}",
                selected = mode == state.ui.themeMode,
                iconHidden = mode != state.ui.themeMode
            ) { actions.updateUi(state.ui.copy(themeMode = mode)) }
        }
        MenuSeparator()
        MenuLabel("Help")
        MenuRow(Icons.AutoMirrored.Filled.MenuBook, "Documentation") {
            open = false
            openLink(context, "https://docs.frigate.video")
        }
        MenuRow(Icons.Filled.Code, "GitHub") {
            open = false
            openLink(context, "https://github.com/Zektopic/frigate-android")
        }
        MenuSeparator()
        MenuRow(Icons.Filled.NotificationsActive, "Send test notification") {
            open = false
            actions.sendTestNotification()
        }
        if (state.isServiceRunning) {
            MenuRow(Icons.Filled.Stop, "Stop NVR") {
                open = false
                actions.stopService()
            }
        } else {
            MenuRow(Icons.Filled.PlayArrow, "Start NVR") {
                open = false
                actions.startService()
            }
        }
    }

    Box {
        trigger { open = true }
        if (open && !asSheet) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { open = false },
                modifier = Modifier.width(288.dp),
                content = menu
            )
        }
    }
    if (open && asSheet) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            containerColor = colors.background,
            contentColor = colors.primary
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                content = menu
            )
        }
    }
}

private fun openLink(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "No app can open $url", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text,
        style = FrigateType.sm.copy(fontWeight = FontWeight.SemiBold),
        color = FrigateTheme.colors.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun MenuSeparator() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = FrigateTheme.colors.secondary
    )
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    text: String,
    selected: Boolean = false,
    iconHidden: Boolean = false,
    onClick: () -> Unit
) {
    val colors = FrigateTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.accent else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (iconHidden) Color.Transparent else colors.primary,
            modifier = Modifier.size(16.dp)
        )
        Text(text, style = FrigateType.sm, color = colors.primary)
    }
}
