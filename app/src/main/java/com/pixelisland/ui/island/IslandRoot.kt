package com.pixelisland.ui.island

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelisland.data.IslandSettings
import com.pixelisland.data.Settings
import com.pixelisland.media.MediaRepository
import com.pixelisland.media.MediaState
import com.pixelisland.overlay.IslandController
import com.pixelisland.system.BatteryInfo
import com.pixelisland.system.SystemRepository
import com.pixelisland.timer.TimerMode
import com.pixelisland.timer.TimerRepository
import com.pixelisland.timer.TimerState
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private enum class IslandVisual { IDLE, COMPACT, SPLIT, EXPANDED }
private enum class LeftSlot { TIMER, CLOCK, ART }
private enum class RightSlot { MEDIA, BATTERY }

/**
 * The whole island. Used by the overlay service and by the live preview in the app.
 * One container morphs its size/corner/color with spring physics while the content
 * inside cross-fades between states.
 */
@Composable
fun IslandRoot(controller: IslandController, maxExpandedWidth: Dp, modifier: Modifier = Modifier) {
    val settings by IslandSettings.state.collectAsState()
    val media by MediaRepository.state.collectAsState()
    val timer by TimerRepository.state.collectAsState()
    val battery by SystemRepository.battery.collectAsState()
    val view = LocalView.current
    val haptics = remember(view, settings.haptics) { IslandHaptics(view, settings.haptics) }

    val left = when {
        timer.isActive -> LeftSlot.TIMER
        settings.alwaysOnClock -> LeftSlot.CLOCK
        media.isActive -> LeftSlot.ART
        else -> null
    }
    val right = when {
        media.isActive -> RightSlot.MEDIA
        settings.showBattery && battery.known -> RightSlot.BATTERY
        else -> null
    }
    val visual = when {
        controller.expanded -> IslandVisual.EXPANDED
        controller.snoozed -> IslandVisual.IDLE
        left == null && right == null -> IslandVisual.IDLE
        settings.splitPill && media.isActive && left != null && left != LeftSlot.ART -> IslandVisual.SPLIT
        else -> IslandVisual.COMPACT
    }

    LaunchedEffect(controller.snoozed) {
        if (controller.snoozed) {
            delay(IslandController.SNOOZE_MS)
            controller.unsnooze()
        }
    }
    LaunchedEffect(timer.finishedAt) {
        if (timer.finishedAt > 0L) {
            haptics.confirm()
            controller.unsnooze()
        }
    }

    val scheme = MaterialTheme.colorScheme
    val palette = media.palette
    val expandedContainer = palette?.let { Color(it.container) } ?: scheme.surfaceContainerHigh
    val containerColor by animateColorAsState(
        targetValue = when (visual) {
            IslandVisual.EXPANDED -> expandedContainer
            IslandVisual.SPLIT -> Color.Black.copy(alpha = 0f)
            else -> Color.Black
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "container",
    )
    val accent by animateColorAsState(
        targetValue = palette?.let { Color(it.accent) } ?: scheme.primary,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "accent",
    )
    val onContainer by animateColorAsState(
        targetValue = palette?.let { Color(it.onContainer) } ?: scheme.onSurface,
        label = "onContainer",
    )

    val pillH = settings.pillHeight.dp
    val pillW = settings.pillWidth.dp
    val corner by animateDpAsState(
        targetValue = if (visual == IslandVisual.EXPANDED) 36.dp else pillH / 2,
        animationSpec = islandSpring(),
        label = "corner",
    )

    val onTap: () -> Unit = {
        haptics.click()
        controller.expand()
    }
    val onLongPress: () -> Unit = {
        haptics.longPress()
        controller.expand()
    }
    val onSwipe: (Int) -> Unit = { direction ->
        haptics.gesture()
        if (media.isActive) {
            if (direction < 0) MediaRepository.next() else MediaRepository.previous()
        } else {
            controller.snooze()
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(containerColor),
    ) {
        AnimatedContent(
            targetState = visual,
            transitionSpec = {
                (fadeIn(tween(200, delayMillis = 80)) +
                    scaleIn(tween(260, delayMillis = 60), initialScale = 0.9f)) togetherWith
                    fadeOut(tween(80)) using
                    SizeTransform(clip = false) { _, _ -> islandSpring() }
            },
            contentAlignment = Alignment.TopCenter,
            label = "islandState",
        ) { state ->
            when (state) {
                IslandVisual.IDLE -> Box(
                    Modifier
                        .size(pillH)
                        .islandGestures(onTap, onLongPress, onSwipe),
                )

                IslandVisual.COMPACT -> CompactPill(
                    width = pillW, height = pillH, left = left, right = right,
                    media = media, timer = timer, battery = battery, accent = accent,
                    modifier = Modifier.islandGestures(onTap, onLongPress, onSwipe),
                )

                IslandVisual.SPLIT -> SplitPills(
                    width = pillW, height = pillH, left = left, media = media, timer = timer,
                    accent = accent, gestures = Modifier.islandGestures(onTap, onLongPress, onSwipe),
                )

                IslandVisual.EXPANDED -> CompositionLocalProvider(LocalContentColor provides onContainer) {
                    ExpandedCard(
                        width = maxExpandedWidth, settings = settings, media = media, timer = timer,
                        battery = battery, accent = accent, content = onContainer, haptics = haptics,
                        onCollapse = { haptics.click(); controller.collapse() },
                        onSnooze = { haptics.gesture(); controller.snooze() },
                    )
                }
            }
        }
    }
}

// region Gestures ---------------------------------------------------------------------------

@Composable
private fun Modifier.islandGestures(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipe: (Int) -> Unit,
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    val swipe by rememberUpdatedState(onSwipe)
    return this
        .pointerInput(Unit) {
            detectTapGestures(onTap = { tap() }, onLongPress = { long() })
        }
        .pointerInput(Unit) {
            val threshold = 36.dp.toPx()
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    if (abs(total) > threshold) swipe(if (total < 0f) -1 else 1)
                    total = 0f
                },
                onDragCancel = { total = 0f },
            ) { change, dragAmount ->
                change.consume()
                total += dragAmount
            }
        }
}

// endregion

// region Compact + split ----------------------------------------------------------------------

@Composable
private fun CompactPill(
    width: Dp,
    height: Dp,
    left: LeftSlot?,
    right: RightSlot?,
    media: MediaState,
    timer: TimerState,
    battery: BatteryInfo,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .size(width, height)
            .padding(horizontal = height * 0.3f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            LeftContent(left, timer, media, accent, height)
        }
        // Keep the punch-hole camera clear.
        Spacer(Modifier.width(height))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            when (right) {
                RightSlot.MEDIA -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (left != LeftSlot.ART) {
                        SpinningArt(media.art, media.isPlaying, accent, height - 12.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Waveform(media.isPlaying, accent, Modifier.size(22.dp, height * 0.42f))
                }
                RightSlot.BATTERY -> BatteryChip(battery.level, battery.charging, Color.White, (height.value * 0.34f).sp)
                null -> Unit
            }
        }
    }
}

@Composable
private fun LeftContent(slot: LeftSlot?, timer: TimerState, media: MediaState, accent: Color, height: Dp) {
    when (slot) {
        LeftSlot.TIMER -> {
            val now = rememberElapsedTicker(timer.running, 200L)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (timer.mode == TimerMode.STOPWATCH) IslandIcons.Stopwatch else IslandIcons.Hourglass,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(height * 0.4f),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (timer.finished) "Done" else formatDuration(timer.displayMsAt(now)),
                    color = accent,
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = (height.value * 0.38f).sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
        LeftSlot.CLOCK -> ClockText(fontSize = (height.value * 0.42f).sp, color = Color.White)
        LeftSlot.ART -> SpinningArt(media.art, media.isPlaying, accent, height - 10.dp)
        null -> Unit
    }
}

/** Two detached pills flanking the camera: clock/timer on the left, media circle on the right. */
@Composable
private fun SplitPills(
    width: Dp,
    height: Dp,
    left: LeftSlot?,
    media: MediaState,
    timer: TimerState,
    accent: Color,
    gestures: Modifier,
) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        pop.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow))
    }
    Row(Modifier.size(width, height), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = (1f - pop.value) * 28.dp.toPx()
                        scaleX = 0.7f + 0.3f * pop.value
                        scaleY = 0.7f + 0.3f * pop.value
                    }
                    .height(height)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black)
                    .then(gestures)
                    .padding(horizontal = height * 0.36f),
                contentAlignment = Alignment.Center,
            ) {
                LeftContent(left, timer, media, accent, height)
            }
        }
        Spacer(Modifier.width(height + 10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = -(1f - pop.value) * 28.dp.toPx()
                        scaleX = 0.7f + 0.3f * pop.value
                        scaleY = 0.7f + 0.3f * pop.value
                    }
                    .size(height)
                    .clip(CircleShape)
                    .background(Color.Black)
                    .then(gestures),
                contentAlignment = Alignment.Center,
            ) {
                if (media.art != null) {
                    SpinningArt(media.art, media.isPlaying, accent, height - 6.dp)
                    Box(
                        Modifier
                            .size(height - 6.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f)),
                    )
                }
                Waveform(media.isPlaying, accent, Modifier.size(height * 0.5f, height * 0.4f))
            }
        }
    }
}

// endregion

// region Expanded card --------------------------------------------------------------------------

@Composable
private fun ExpandedCard(
    width: Dp,
    settings: Settings,
    media: MediaState,
    timer: TimerState,
    battery: BatteryInfo,
    accent: Color,
    content: Color,
    haptics: IslandHaptics,
    onCollapse: () -> Unit,
    onSnooze: () -> Unit,
) {
    val noRipple = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(width)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(interactionSource = noRipple, indication = null, onClick = onCollapse),
            ) {
                ExpressiveClock(accent, content)
                val now by rememberWallClock()
                val date = remember(now / 60_000L) {
                    zoned(now).format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
                }
                Text(date, style = MaterialTheme.typography.titleSmall, color = content.copy(alpha = 0.72f))
            }
            Column(horizontalAlignment = Alignment.End) {
                if (settings.showBattery && battery.known) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(content.copy(alpha = 0.1f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        BatteryChip(battery.level, battery.charging, content, 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(content.copy(alpha = 0.1f))
                        .clickable(onClick = onSnooze)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(IslandIcons.Bedtime, null, tint = content, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Hide 1 min", style = MaterialTheme.typography.labelMedium, color = content)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        TimerHub(timer, accent, content, haptics)

        Spacer(Modifier.height(14.dp))
        if (media.isActive) {
            MediaSection(media, accent, content, haptics)
        } else {
            Text(
                text = "Nothing playing. Start music in any app, or turn on the Demo Player in Pixel Island.",
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ExpressiveClock(accent: Color, content: Color) {
    val context = LocalContext.current
    val now by rememberWallClock()
    val is24 = DateFormat.is24HourFormat(context)
    val z = zoned(now)
    val hours = if (is24) "%02d".format(z.hour) else ((z.hour + 11) % 12 + 1).toString()
    val minutes = "%02d".format(z.minute)
    val big = TextStyle(fontSize = 58.sp, letterSpacing = (-2).sp, fontFeatureSettings = "tnum", lineHeight = 60.sp)
    Row(verticalAlignment = Alignment.Bottom) {
        RollingText(hours, big.copy(fontWeight = FontWeight.W800, color = accent))
        Text(":", style = big.copy(fontWeight = FontWeight.W300, color = content.copy(alpha = 0.5f)))
        RollingText(minutes, big.copy(fontWeight = FontWeight.W300, color = content))
        if (!is24) {
            Text(
                text = if (z.hour < 12) "AM" else "PM",
                style = MaterialTheme.typography.labelLarge,
                color = content.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 6.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun RollingText(text: String, style: TextStyle) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically(islandSpring()) { it } + fadeIn()) togetherWith
                (slideOutVertically(islandSpring()) { -it } + fadeOut()) using
                SizeTransform(clip = false)
        },
        label = "rolling",
    ) { value ->
        Text(value, style = style, maxLines = 1)
    }
}

@Composable
private fun TimerHub(timer: TimerState, accent: Color, content: Color, haptics: IslandHaptics) {
    val now = rememberElapsedTicker(timer.running, 100L)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(content.copy(alpha = 0.08f))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeChip("Stopwatch", timer.mode == TimerMode.STOPWATCH, accent, content) {
                haptics.tick()
                TimerRepository.setMode(TimerMode.STOPWATCH)
            }
            Spacer(Modifier.width(8.dp))
            ModeChip("Timer", timer.mode == TimerMode.TIMER, accent, content) {
                haptics.tick()
                TimerRepository.setMode(TimerMode.TIMER)
            }
            Spacer(Modifier.weight(1f))
            if (timer.mode == TimerMode.TIMER) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(content.copy(alpha = 0.1f))
                        .clickable {
                            haptics.tick()
                            TimerRepository.addMinute()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(IslandIcons.Add, null, tint = content, modifier = Modifier.size(14.dp))
                    Text("1 min", style = MaterialTheme.typography.labelMedium, color = content)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (timer.finished) "Time's up" else formatDuration(
                    timer.displayMsAt(now),
                    tenths = timer.mode == TimerMode.STOPWATCH,
                ),
                color = if (timer.running || timer.finished) accent else content,
                maxLines = 1,
                style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.W600, fontFeatureSettings = "tnum"),
                modifier = Modifier.weight(1f),
            )
            ExpressiveIconButton(IslandIcons.Reset, "Reset", content.copy(alpha = 0.12f), content, 46.dp) {
                haptics.click()
                TimerRepository.reset()
            }
            Spacer(Modifier.width(10.dp))
            MorphPlayButton(timer.running, accent, accent.readableOn(), 54.dp) {
                haptics.click()
                TimerRepository.toggle()
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, accent: Color, content: Color, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) accent else content.copy(alpha = 0.1f), label = "chipBg")
    val fg by animateColorAsState(if (selected) accent.readableOn() else content, label = "chipFg")
    val corner by animateDpAsState(if (selected) 12.dp else 18.dp, islandSpring(), label = "chipCorner")
    Box(
        Modifier
            .clip(RoundedCornerShape(corner))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaSection(media: MediaState, accent: Color, content: Color, haptics: IslandHaptics) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ArtImage(
            art = media.art,
            accent = accent,
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(22.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = media.title,
                style = MaterialTheme.typography.titleMedium,
                color = content,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
            )
            Text(
                text = media.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = content.copy(alpha = 0.75f),
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
            )
            Spacer(Modifier.height(2.dp))
            Text(media.appName, style = MaterialTheme.typography.labelSmall, color = accent, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Waveform(media.isPlaying, accent, Modifier.size(28.dp, 20.dp))
    }

    Spacer(Modifier.height(10.dp))
    val now = rememberElapsedTicker(media.isPlaying, 100L)
    val position = media.positionAt(now)
    val duration = media.durationMs
    SquigglySlider(
        progress = if (duration > 0) position.toFloat() / duration else 0f,
        playing = media.isPlaying,
        color = accent,
        trackColor = content.copy(alpha = 0.22f),
        onSeek = { fraction ->
            if (duration > 0) {
                haptics.tick()
                MediaRepository.seekTo((fraction * duration).toLong())
            }
        },
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(formatDuration(position), style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.7f))
        Spacer(Modifier.weight(1f))
        Text(
            if (duration > 0) formatDuration(duration) else "--:--",
            style = MaterialTheme.typography.labelSmall,
            color = content.copy(alpha = 0.7f),
        )
    }

    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpressiveIconButton(IslandIcons.SkipPrevious, "Previous", content.copy(alpha = 0.12f), content) {
            haptics.click()
            MediaRepository.previous()
        }
        MorphPlayButton(media.isPlaying, accent, accent.readableOn(), 64.dp) {
            haptics.confirm()
            MediaRepository.togglePlayPause()
        }
        ExpressiveIconButton(IslandIcons.SkipNext, "Next", content.copy(alpha = 0.12f), content) {
            haptics.click()
            MediaRepository.next()
        }
    }
}

// endregion
