package com.pixelisland.ui.island

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelisland.data.IslandSettings
import com.pixelisland.data.Settings
import com.pixelisland.media.ArtPalette
import com.pixelisland.media.MediaRepository
import com.pixelisland.media.MediaState
import com.pixelisland.overlay.IslandController
import com.pixelisland.system.BatteryInfo
import com.pixelisland.system.SystemRepository
import com.pixelisland.timer.TimerMode
import com.pixelisland.timer.TimerRepository
import com.pixelisland.timer.TimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private enum class IslandVisual { IDLE, COMPACT, SPLIT, EXPANDED }
private enum class LeftSlot { TIMER, CLOCK, ART }
private enum class RightSlot { MEDIA, BATTERY }

/** Material 3 emphasized easings. */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** Shape/size spring for the morph: fast with a gentle overshoot, like the iPhone island. */
private val MorphSpring = spring(dampingRatio = 0.74f, stiffness = 360f, visibilityThreshold = IntSize(1, 1))

@Immutable
data class IslandColors(
    val container: Color,
    val containerHigh: Color,
    val content: Color,
    val contentVariant: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
)

private fun colorsFor(palette: ArtPalette?, scheme: ColorScheme): IslandColors =
    if (palette != null) {
        IslandColors(
            container = Color(palette.container),
            containerHigh = Color(palette.containerHigh),
            content = Color(palette.onContainer),
            contentVariant = Color(palette.onContainerVariant),
            accent = Color(palette.accent),
            onAccent = Color(palette.onAccent),
            accentContainer = Color(palette.accentContainer),
            onAccentContainer = Color(palette.onAccentContainer),
        )
    } else {
        IslandColors(
            container = scheme.surfaceContainer,
            containerHigh = scheme.surfaceContainerHighest,
            content = scheme.onSurface,
            contentVariant = scheme.onSurfaceVariant,
            accent = scheme.primary,
            onAccent = scheme.onPrimary,
            accentContainer = scheme.primaryContainer,
            onAccentContainer = scheme.onPrimaryContainer,
        )
    }

@Composable
private fun animateIslandColors(target: IslandColors): IslandColors {
    val spec = spring<Color>(stiffness = Spring.StiffnessLow)
    val container by animateColorAsState(target.container, spec, label = "c1")
    val containerHigh by animateColorAsState(target.containerHigh, spec, label = "c2")
    val content by animateColorAsState(target.content, spec, label = "c3")
    val contentVariant by animateColorAsState(target.contentVariant, spec, label = "c4")
    val accent by animateColorAsState(target.accent, spec, label = "c5")
    val onAccent by animateColorAsState(target.onAccent, spec, label = "c6")
    val accentContainer by animateColorAsState(target.accentContainer, spec, label = "c7")
    val onAccentContainer by animateColorAsState(target.onAccentContainer, spec, label = "c8")
    return IslandColors(container, containerHigh, content, contentVariant, accent, onAccent, accentContainer, onAccentContainer)
}

/**
 * The whole island, used by the overlay service and by the live preview in the app.
 *
 * One container morphs size + corner radius on a spring (iPhone-style). The content inside
 * cross-fades with scale + blur; the outgoing content reports zero size so the container
 * immediately heads for the new size instead of waiting for the fade-out.
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
    val isExpanded = visual == IslandVisual.EXPANDED

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
    val targetColors = colorsFor(media.palette, scheme)
    val colors = animateIslandColors(targetColors)

    val containerColor by animateColorAsState(
        targetValue = when (visual) {
            IslandVisual.EXPANDED -> targetColors.container
            IslandVisual.SPLIT -> Color.Black.copy(alpha = 0f)
            else -> Color.Black
        },
        animationSpec = tween(320, easing = EmphasizedDecelerate),
        label = "container",
    )
    val pillH = settings.pillHeight.dp
    val pillW = settings.pillWidth.dp
    val corner by animateDpAsState(if (isExpanded) 42.dp else pillH / 2, islandSpring(), label = "corner")
    val shadow by animateDpAsState(if (isExpanded) 22.dp else 0.dp, tween(300), label = "shadow")

    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !isExpanded) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "press",
    )

    val scope = rememberCoroutineScope()
    val sizeAnim = remember { Animatable(IntSize.Zero, IntSize.VectorConverter) }

    // Tell the host when the collapse morph has fully settled so it can shrink the window.
    LaunchedEffect(controller) {
        snapshotFlow { !controller.expanded && !sizeAnim.isRunning }
            .distinctUntilChanged()
            .collect { settled -> if (settled) controller.notifyCollapseSettled() }
    }

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
    val gestures = Modifier.islandGestures(onTap, onLongPress, onSwipe) { pressed = it }

    // In the big window the host no longer offsets the window, so we offset the island instead.
    val hostOffset = if (controller.bigWindow) settings.offsetX.dp else 0.dp

    Box(
        modifier
            .offset(x = hostOffset)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                transformOrigin = TransformOrigin(0.5f, 0f)
                shadowElevation = shadow.toPx()
                shape = RoundedCornerShape(corner)
                clip = true
            }
            .background(containerColor)
            .morphSize(sizeAnim, scope),
    ) {
        AnimatedContent(
            targetState = visual,
            transitionSpec = {
                val expanding = targetState == IslandVisual.EXPANDED
                val collapsing = initialState == IslandVisual.EXPANDED
                val top = TransformOrigin(0.5f, 0f)
                val enter = when {
                    expanding ->
                        fadeIn(tween(220, delayMillis = 60)) +
                            scaleIn(tween(420, delayMillis = 20, easing = EmphasizedDecelerate), initialScale = 0.78f, transformOrigin = top)
                    collapsing ->
                        fadeIn(tween(200, delayMillis = 150)) +
                            scaleIn(tween(300, delayMillis = 120, easing = EmphasizedDecelerate), initialScale = 0.6f)
                    else ->
                        fadeIn(tween(200, delayMillis = 60)) +
                            scaleIn(tween(260, delayMillis = 40, easing = EmphasizedDecelerate), initialScale = 0.8f)
                }
                val exit = when {
                    collapsing ->
                        fadeOut(tween(150)) +
                            scaleOut(tween(240, easing = EmphasizedAccelerate), targetScale = 0.72f, transformOrigin = top)
                    else -> fadeOut(tween(90)) + scaleOut(tween(140), targetScale = 0.7f)
                }
                (enter togetherWith exit).using(null as SizeTransform?)
            },
            contentAlignment = Alignment.TopCenter,
            label = "islandState",
        ) { state ->
            val blur by transition.animateDp(
                transitionSpec = { tween(260) },
                label = "blur",
            ) { if (it == EnterExitState.Visible) 0.dp else 14.dp }
            Box(
                Modifier
                    .zeroSizeWhenExiting(transition)
                    .graphicsLayer {
                        val r = blur.toPx()
                        renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Decal) else null
                    },
            ) {
                when (state) {
                    IslandVisual.IDLE -> Box(Modifier.size(pillH).then(gestures))

                    IslandVisual.COMPACT -> CompactPill(
                        width = pillW, height = pillH, left = left, right = right,
                        media = media, timer = timer, battery = battery, accent = colors.accent,
                        modifier = gestures,
                    )

                    IslandVisual.SPLIT -> SplitPills(
                        width = pillW, height = pillH, left = left, media = media, timer = timer,
                        accent = colors.accent, gestures = gestures,
                    )

                    IslandVisual.EXPANDED -> CompositionLocalProvider(LocalContentColor provides colors.content) {
                        ExpandedCard(
                            width = maxExpandedWidth, settings = settings, media = media, timer = timer,
                            battery = battery, colors = colors, haptics = haptics,
                            onCollapse = {
                                haptics.click()
                                controller.collapse()
                            },
                            onSnooze = {
                                haptics.gesture()
                                controller.snooze()
                            },
                        )
                    }
                }
            }
        }
    }
}

// region Layout helpers ---------------------------------------------------------------------

/**
 * Lays the child out at its natural size but reports a spring-animated size, keeping the child
 * top-centered — so the container grows/shrinks around the content like the iPhone island.
 */
private fun Modifier.morphSize(
    anim: Animatable<IntSize, AnimationVector2D>,
    scope: CoroutineScope,
): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val target = IntSize(placeable.width, placeable.height)
    if (anim.targetValue != target) {
        val snap = anim.value == IntSize.Zero || target == IntSize.Zero
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (snap) anim.snapTo(target) else anim.animateTo(target, MorphSpring)
        }
    }
    val current = if (anim.value == IntSize.Zero) target else anim.value
    layout(current.width, current.height) {
        placeable.place((current.width - placeable.width) / 2, 0)
    }
}

/** Outgoing AnimatedContent children take no space, so the container targets the new size at once. */
private fun Modifier.zeroSizeWhenExiting(transition: Transition<EnterExitState>): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        if (transition.targetState == EnterExitState.PostExit) {
            layout(0, 0) { placeable.place(-placeable.width / 2, 0) }
        } else {
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
    }

// endregion

// region Gestures ---------------------------------------------------------------------------

@Composable
private fun Modifier.islandGestures(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipe: (Int) -> Unit,
    onPressChange: (Boolean) -> Unit,
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    val swipe by rememberUpdatedState(onSwipe)
    val press by rememberUpdatedState(onPressChange)
    return this
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    press(true)
                    tryAwaitRelease()
                    press(false)
                },
                onTap = { tap() },
                onLongPress = { long() },
            )
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
        modifier = Modifier
            .size(width, height)
            .then(modifier)
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
                        ArtImage(media.art, accent, Modifier.size(height - 12.dp).clip(CircleShape))
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
            val now = rememberElapsedTicker(timer.running, 500L)
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
        LeftSlot.ART -> ArtImage(media.art, accent, Modifier.size(height - 10.dp).clip(CircleShape))
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
                    ArtImage(media.art, accent, Modifier.size(height - 6.dp).clip(CircleShape))
                    Box(
                        Modifier
                            .size(height - 6.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.4f)),
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
    colors: IslandColors,
    haptics: IslandHaptics,
    onCollapse: () -> Unit,
    onSnooze: () -> Unit,
) {
    val noRipple = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(width)
            // Swallow taps on empty parts of the card so they don't collapse it.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(interactionSource = noRipple, indication = null, onClick = onCollapse),
            ) {
                ExpressiveClock(colors.accent, colors.content)
                val now by rememberWallClock()
                val date = remember(now / 60_000L) {
                    zoned(now).format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
                        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
                }
                Text(date, style = MaterialTheme.typography.titleSmall, color = colors.contentVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (settings.showBattery && battery.known) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.containerHigh)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        BatteryChip(battery.level, battery.charging, colors.content, 13.sp)
                    }
                }
                ExpressiveIconButton(IslandIcons.Bedtime, "Hide for a minute", colors.containerHigh, colors.content, 40.dp, onSnooze)
            }
        }

        if (media.isActive) {
            MediaCard(media, colors, haptics)
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(colors.containerHigh)
                    .padding(16.dp),
            ) {
                Text(
                    text = "Nothing playing. Start music in any app, or turn on the Demo player in Pixel Island.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.contentVariant,
                )
            }
        }

        TimerCard(timer, colors, haptics)
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
    val big = TextStyle(fontSize = 60.sp, letterSpacing = (-2).sp, fontFeatureSettings = "tnum", lineHeight = 62.sp)
    Row(verticalAlignment = Alignment.Bottom) {
        RollingText(hours, big.copy(fontWeight = FontWeight.W800, color = accent))
        Text(":", style = big.copy(fontWeight = FontWeight.W300, color = content.copy(alpha = 0.45f)))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCard(media: MediaState, colors: IslandColors, haptics: IslandHaptics) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(colors.containerHigh)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CookieArt(media.art, media.isPlaying, colors.accent, 84.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = media.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.content,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
                Text(
                    text = media.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.contentVariant,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.accentContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Waveform(media.isPlaying, colors.onAccentContainer, Modifier.size(14.dp, 10.dp), smooth = true)
                    Spacer(Modifier.width(6.dp))
                    Text(media.appName, style = MaterialTheme.typography.labelMedium, color = colors.onAccentContainer, maxLines = 1)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        val now = rememberElapsedTicker(media.isPlaying, 250L)
        val position = media.positionAt(now)
        val duration = media.durationMs
        SquigglySlider(
            progress = if (duration > 0) position.toFloat() / duration else 0f,
            playing = media.isPlaying,
            color = colors.accent,
            trackColor = colors.content.copy(alpha = 0.18f),
            onSeek = { fraction ->
                if (duration > 0) {
                    haptics.tick()
                    MediaRepository.seekTo((fraction * duration).toLong())
                }
            },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(formatDuration(position), style = MaterialTheme.typography.labelMedium, color = colors.contentVariant)
            Spacer(Modifier.weight(1f))
            Text(
                if (duration > 0) formatDuration(duration) else "--:--",
                style = MaterialTheme.typography.labelMedium,
                color = colors.contentVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        MediaButtonGroup(
            playing = media.isPlaying,
            accent = colors.accent,
            onAccent = colors.onAccent,
            tonal = colors.accentContainer,
            onTonal = colors.onAccentContainer,
            onPrevious = {
                haptics.click()
                MediaRepository.previous()
            },
            onToggle = {
                haptics.confirm()
                MediaRepository.togglePlayPause()
            },
            onNext = {
                haptics.click()
                MediaRepository.next()
            },
        )
    }
}

@Composable
private fun TimerCard(timer: TimerState, colors: IslandColors, haptics: IslandHaptics) {
    val now = rememberElapsedTicker(timer.running, 100L)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(colors.containerHigh)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SegmentedToggle(
                options = listOf("Stopwatch", "Timer"),
                selected = if (timer.mode == TimerMode.STOPWATCH) 0 else 1,
                accent = colors.accent,
                onAccent = colors.onAccent,
                tonal = colors.accentContainer,
                onTonal = colors.onAccentContainer,
            ) { index ->
                haptics.tick()
                TimerRepository.setMode(if (index == 0) TimerMode.STOPWATCH else TimerMode.TIMER)
            }
            Spacer(Modifier.weight(1f))
            if (timer.mode == TimerMode.TIMER) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.accentContainer)
                        .smokeClickable(colors.onAccentContainer) {
                            haptics.tick()
                            TimerRepository.addMinute()
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(IslandIcons.Add, null, tint = colors.onAccentContainer, modifier = Modifier.size(16.dp))
                    Text("1 min", style = MaterialTheme.typography.labelLarge, color = colors.onAccentContainer)
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
                color = if (timer.running || timer.finished) colors.accent else colors.content,
                maxLines = 1,
                style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.W600, fontFeatureSettings = "tnum"),
                modifier = Modifier.weight(1f),
            )
            ExpressiveIconButton(IslandIcons.Reset, "Reset", colors.accentContainer, colors.onAccentContainer, 48.dp) {
                haptics.click()
                TimerRepository.reset()
            }
            Spacer(Modifier.width(10.dp))
            MorphPlayButton(timer.running, colors.accent, colors.onAccent, 60.dp) {
                haptics.confirm()
                TimerRepository.toggle()
            }
        }
    }
}

// endregion
