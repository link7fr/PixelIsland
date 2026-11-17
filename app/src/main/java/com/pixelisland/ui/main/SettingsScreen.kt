@file:OptIn(ExperimentalMaterial3Api::class)

package com.pixelisland.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.pixelisland.data.IslandSettings
import com.pixelisland.data.Settings
import com.pixelisland.media.MediaRepository
import com.pixelisland.overlay.IslandAccessibilityService
import com.pixelisland.overlay.IslandController
import com.pixelisland.ui.island.ExpressiveShapes
import com.pixelisland.ui.island.IslandRoot
import com.pixelisland.ui.island.MorphShape
import com.pixelisland.ui.island.islandSpring
import com.pixelisland.ui.theme.IslandOverlayTheme
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onAutoDetect: () -> String) {
    val context = LocalContext.current
    val settings by IslandSettings.state.collectAsState()
    val serviceRunning by IslandAccessibilityService.running.collectAsState()

    var refreshTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refreshTick++
        onPauseOrDispose { }
    }
    val accessibilityOn = remember(refreshTick, serviceRunning) {
        serviceRunning || Permissions.isAccessibilityEnabled(context)
    }
    val notificationsOn = remember(refreshTick) { Permissions.isNotificationListenerEnabled(context) }
    LaunchedEffect(notificationsOn) {
        if (notificationsOn) MediaRepository.attachSessions(context)
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Pixel Island", fontWeight = FontWeight.W700) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PreviewCard(settings.pillHeight) }

            item { SectionLabel("Permissions") }
            item {
                PermissionCard(
                    title = "Island overlay",
                    description = "Accessibility service that draws the island over the status bar and camera.",
                    granted = accessibilityOn,
                    icon = Icons.Rounded.Settings,
                    onGrant = { Permissions.openAccessibilitySettings(context) },
                )
            }
            item {
                PermissionCard(
                    title = "Media access",
                    description = "Notification access lets the island read and control Spotify, YouTube Music and other players.",
                    granted = notificationsOn,
                    icon = Icons.Rounded.Notifications,
                    onGrant = { Permissions.openNotificationListenerSettings(context) },
                )
            }
            item {
                AnimatedVisibility(visible = !accessibilityOn) {
                    RestrictedSettingsHint(onOpenAppInfo = { Permissions.openAppInfo(context) })
                }
            }

            item { SectionLabel("Island") }
            item { TogglesCard(settings) }

            item { SectionLabel("Cutout calibration") }
            item {
                CalibrationCard(
                    settings = settings,
                    onAutoDetect = {
                        val message = onAutoDetect()
                        scope.launch { snackbar.showSnackbar(message) }
                    },
                )
            }

            item {
                Text(
                    text = "Gestures: tap or long-press the island to expand, tap outside to collapse. " +
                        "Swipe the pill sideways to skip tracks, or to hide the island for a minute when nothing is playing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp),
    )
}

/** Live, interactive copy of the island on a mock status bar. */
@Composable
private fun PreviewCard(pillHeight: Float) {
    val controller = remember { IslandController() }
    val noRipple = remember { MutableInteractionSource() }
    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0B0D)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(interactionSource = noRipple, indication = null) { controller.collapse() }
                .padding(top = 10.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                val previewWidth = maxWidth - 16.dp
                // Fake punch-hole camera, sitting underneath the island like on a real phone.
                Box(
                    Modifier
                        .padding(top = ((pillHeight - 12f) / 2f).coerceAtLeast(0f).dp)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF26272B)),
                )
                IslandOverlayTheme {
                    IslandRoot(controller = controller, maxExpandedWidth = previewWidth)
                }
            }
            Text(
                text = "Live preview — tap, long-press or swipe the island",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    icon: ImageVector,
    onGrant: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (granted) scheme.primaryContainer else scheme.surfaceContainerHigh,
        label = "permContainer",
    )
    val corner by animateDpAsState(if (granted) 32.dp else 20.dp, islandSpring(), label = "permCorner")
    val badgeMorph by animateFloatAsState(if (granted) 1f else 0f, spring(0.55f, 300f), label = "badge")
    Card(
        onClick = onGrant,
        shape = RoundedCornerShape(corner),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .graphicsLayer {
                        rotationZ = badgeMorph * 40f
                        shape = MorphShape(ExpressiveShapes.Badge, badgeMorph)
                        clip = true
                    }
                    .background(if (granted) scheme.primary else scheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (granted) Icons.Rounded.Check else icon,
                    contentDescription = null,
                    tint = if (granted) scheme.onPrimary else scheme.onTertiaryContainer,
                    modifier = Modifier.graphicsLayer { rotationZ = -badgeMorph * 40f },
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (granted) {
                Text("On", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
            } else {
                FilledTonalButton(onClick = onGrant) { Text("Grant") }
            }
        }
    }
}

@Composable
private fun RestrictedSettingsHint(onOpenAppInfo: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, tint = scheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Accessibility switch greyed out? Open App info → ⋮ → Allow restricted settings, then try again.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenAppInfo) { Text("App info") }
        }
    }
}

@Composable
private fun TogglesCard(settings: Settings) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            ToggleRow("Show island", "Master switch for the overlay", settings.islandEnabled) { v ->
                IslandSettings.update { it.copy(islandEnabled = v) }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ToggleRow("Always-on clock", "Show the time in the pill when nothing else is active", settings.alwaysOnClock) { v ->
                IslandSettings.update { it.copy(alwaysOnClock = v) }
            }
            ToggleRow("Split pill mode", "Detach clock and media into two pills around the camera", settings.splitPill) { v ->
                IslandSettings.update { it.copy(splitPill = v) }
            }
            ToggleRow("Demo player", "Fake tracks with album art to test the player and waveform", settings.demoMode) { v ->
                IslandSettings.update { it.copy(demoMode = v) }
            }
            ToggleRow("Battery chip", "Battery level on the right of the pill", settings.showBattery) { v ->
                IslandSettings.update { it.copy(showBattery = v) }
            }
            ToggleRow("Haptic feedback", "Crisp ticks on taps, swipes and timer events", settings.haptics) { v ->
                IslandSettings.update { it.copy(haptics = v) }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                thumbContent = if (checked) {
                    { Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) }
                } else null,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun CalibrationCard(settings: Settings, onAutoDetect: () -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            CalibrationSlider("Horizontal offset (X)", settings.offsetX, -200f..200f) { v ->
                IslandSettings.update { it.copy(offsetX = v) }
            }
            CalibrationSlider("Vertical offset (Y)", settings.offsetY, 0f..80f) { v ->
                IslandSettings.update { it.copy(offsetY = v) }
            }
            CalibrationSlider("Pill width", settings.pillWidth, 80f..360f) { v ->
                IslandSettings.update { it.copy(pillWidth = v) }
            }
            CalibrationSlider("Pill height", settings.pillHeight, 20f..64f) { v ->
                IslandSettings.update { it.copy(pillHeight = v) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onAutoDetect) { Text("Auto-detect cutout") }
                OutlinedButton(onClick = { IslandSettings.resetCalibration() }) { Text("Reset") }
            }
        }
    }
}

@Composable
private fun CalibrationSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                text = "%.0f dp".format(value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}
