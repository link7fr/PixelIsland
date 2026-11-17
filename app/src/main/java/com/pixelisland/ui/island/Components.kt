package com.pixelisland.ui.island

import android.graphics.Bitmap
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

// region Motion, haptics, colors ---------------------------------------------------------------

/** The spring for island shape/size changes: quick, with a small iOS-like overshoot. */
fun <T> islandSpring(): SpringSpec<T> =
    spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)

/** Springier variant for small pressed/selected morphs. */
fun <T> bouncySpring(): SpringSpec<T> = spring(dampingRatio = 0.55f, stiffness = 520f)

class IslandHaptics(private val view: View, private val enabled: Boolean) {
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)
    fun click() = perform(HapticFeedbackConstants.CONTEXT_CLICK)
    fun confirm() = perform(HapticFeedbackConstants.CONFIRM)
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)
    fun gesture() = perform(HapticFeedbackConstants.GESTURE_END)
    private fun perform(constant: Int) {
        if (enabled) view.performHapticFeedback(constant)
    }
}

fun Color.readableOn(): Color = if (luminance() > 0.45f) Color(0xFF15161A) else Color.White

/** clickable() with the smoke press effect. */
@Composable
fun Modifier.smokeClickable(
    color: Color,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val indication = remember(color) { SmokeIndication(color) }
    return clickable(interactionSource = source, indication = indication, onClick = onClick)
}

// endregion

// region Time helpers ----------------------------------------------------------------------

/** Wall-clock millis, ticking on [periodMs] boundaries (default: once a minute — battery friendly). */
@Composable
fun rememberWallClock(periodMs: Long = 60_000L): State<Long> = produceState(System.currentTimeMillis(), periodMs) {
    while (true) {
        val now = System.currentTimeMillis()
        value = now
        delay(periodMs - now % periodMs + 5L)
    }
}

/** elapsedRealtime that only ticks while [active]. */
@Composable
fun rememberElapsedTicker(active: Boolean, periodMs: Long): Long {
    val state = produceState(SystemClock.elapsedRealtime(), active, periodMs) {
        value = SystemClock.elapsedRealtime()
        while (active) {
            delay(periodMs)
            value = SystemClock.elapsedRealtime()
        }
    }
    return state.value
}

fun formatDuration(ms: Long, tenths: Boolean = false): String {
    val safe = ms.coerceAtLeast(0L)
    val total = safe / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val base = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    return if (tenths) "$base.${(safe % 1000) / 100}" else base
}

fun zoned(millis: Long): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

@Composable
fun ClockText(fontSize: TextUnit, color: Color, fontWeight: FontWeight = FontWeight.SemiBold) {
    val context = LocalContext.current
    val now by rememberWallClock()
    val is24 = DateFormat.is24HourFormat(context)
    val text = remember(now / 60_000L, is24) {
        val z = zoned(now)
        if (is24) "%02d:%02d".format(z.hour, z.minute) else "%d:%02d".format((z.hour + 11) % 12 + 1, z.minute)
    }
    Text(
        text = text,
        color = color,
        maxLines = 1,
        style = TextStyle(fontSize = fontSize, fontWeight = fontWeight, fontFeatureSettings = "tnum"),
    )
}

// endregion

// region Waveform --------------------------------------------------------------------------

private const val BAR_COUNT = 5
private val RestLevels = FloatArray(BAR_COUNT) { 0.26f }
private val BarSpeeds = floatArrayOf(7.1f, 9.3f, 6.2f, 10.4f, 8.2f)
private val BarDurations = intArrayOf(460, 620, 390, 700, 530)

/**
 * 5-bar visualizer.
 *  - paused: static dots, no frames rendered at all;
 *  - [smooth] = false (compact pill): stepped at ~15 fps so the display can stay at a low refresh rate;
 *  - [smooth] = true (expanded card, user is looking): full-rate spring-y animation.
 */
@Composable
fun Waveform(playing: Boolean, color: Color, modifier: Modifier, smooth: Boolean = false) {
    when {
        !playing -> Bars(RestLevels, color, modifier)
        smooth -> SmoothBars(color, modifier)
        else -> SteppedBars(color, modifier)
    }
}

@Composable
private fun SteppedBars(color: Color, modifier: Modifier) {
    val levels by produceState(RestLevels) {
        var t = 0f
        while (true) {
            delay(66L)
            t += 0.066f
            value = FloatArray(BAR_COUNT) { i ->
                val wave = 0.5f + 0.5f * sin(t * BarSpeeds[i] + i * 1.3f)
                (0.3f + 0.7f * wave) * (0.8f + 0.2f * Random.nextFloat())
            }
        }
    }
    Bars(levels, color, modifier)
}

@Composable
private fun SmoothBars(color: Color, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val bars = BarDurations.mapIndexed { i, d ->
        transition.animateFloat(
            initialValue = 0.28f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(d, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(i * 85),
            ),
            label = "bar$i",
        )
    }
    Canvas(modifier) { drawBars(FloatArray(BAR_COUNT) { bars[it].value }, color) }
}

@Composable
private fun Bars(levels: FloatArray, color: Color, modifier: Modifier) {
    Canvas(modifier) { drawBars(levels, color) }
}

private fun DrawScope.drawBars(levels: FloatArray, color: Color) {
    val barW = size.width / (BAR_COUNT * 2 - 1)
    for (i in 0 until BAR_COUNT) {
        val h = (size.height * levels[i].coerceIn(0f, 1f)).coerceAtLeast(barW)
        drawRoundRect(
            color = color,
            topLeft = Offset(i * barW * 2, (size.height - h) / 2f),
            size = Size(barW, h),
            cornerRadius = CornerRadius(barW / 2f),
        )
    }
}

// endregion

// region Squiggly slider -------------------------------------------------------------------

/**
 * Android-style media seekbar: the played part is a travelling sine wave, the rest a flat track.
 * The wave flattens (and stops rendering frames) when paused or while dragging.
 */
@Composable
fun SquigglySlider(
    progress: Float,
    playing: Boolean,
    color: Color,
    trackColor: Color,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = (dragFraction ?: progress).coerceIn(0f, 1f)
    val waving = playing && dragFraction == null
    val amplitude by animateFloatAsState(if (waving) 1f else 0f, islandSpring(), label = "amp")
    val phase = remember { Animatable(0f) }
    LaunchedEffect(waving) {
        if (waving) {
            phase.snapTo(phase.value % (2f * PI.toFloat()))
            while (true) {
                phase.animateTo(phase.value + 2f * PI.toFloat(), tween(1500, easing = LinearEasing))
            }
        }
    }
    val seek by rememberUpdatedState(onSeek)
    val thumbH by animateDpAsState(if (dragFraction != null) 24.dp else 18.dp, bouncySpring(), label = "thumb")

    Canvas(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> seek((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragFraction?.let { seek(it) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    change.consume()
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
    ) {
        val pad = 6.dp.toPx()
        val usable = size.width - pad * 2
        val cy = size.height / 2f
        val strokeW = 5.dp.toPx()
        val waveLen = 26.dp.toPx()
        val amp = 3.4.dp.toPx() * amplitude
        val ph = phase.value
        val endX = pad + usable * shown
        val twoPi = 2f * PI.toFloat()

        if (endX > pad) {
            val path = Path()
            var x = pad
            path.moveTo(x, cy + amp * sin(x / waveLen * twoPi - ph))
            while (x < endX) {
                x = (x + 2f).coerceAtMost(endX)
                path.lineTo(x, cy + amp * sin(x / waveLen * twoPi - ph))
            }
            drawPath(path, color, style = Stroke(width = strokeW, cap = StrokeCap.Round))
        }
        val gap = 8.dp.toPx()
        if (endX + gap < size.width - pad) {
            drawLine(trackColor, Offset(endX + gap, cy), Offset(size.width - pad, cy), strokeW, StrokeCap.Round)
        }
        val tw = 5.dp.toPx()
        val th = thumbH.toPx()
        drawRoundRect(color, Offset(endX - tw / 2f, cy - th / 2f), Size(tw, th), CornerRadius(tw / 2f))
    }
}

// endregion

// region Buttons ---------------------------------------------------------------------------

/** Play/Pause whose container morphs between a 9-lobe cookie (paused) and a squircle (playing). */
@Composable
fun MorphPlayButton(
    playing: Boolean,
    container: Color,
    content: Color,
    size: Dp = 64.dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val morph by animateFloatAsState(if (playing) 1f else 0f, spring(0.6f, 320f), label = "morph")
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, bouncySpring(), label = "playScale")
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = morph * 50f
                    shape = MorphShape(ExpressiveShapes.PlayPause, morph)
                    clip = true
                }
                .background(container)
                .smokeClickable(content, interaction, onClick),
        )
        PlayPauseIcon(playing, content, size * 0.44f)
    }
}

@Composable
fun PlayPauseIcon(playing: Boolean, tint: Color, size: Dp) {
    AnimatedContent(
        targetState = playing,
        transitionSpec = {
            (scaleIn(bouncySpring(), initialScale = 0.4f) + fadeIn(tween(120))) togetherWith
                (scaleOut(tween(120), targetScale = 0.4f) + fadeOut(tween(100)))
        },
        label = "playIcon",
    ) { isPlaying ->
        Icon(
            imageVector = if (isPlaying) IslandIcons.Pause else IslandIcons.Play,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}

/** Round icon button that squashes into a squircle when pressed, with the smoke effect. */
@Composable
fun ExpressiveIconButton(
    icon: ImageVector,
    description: String?,
    container: Color,
    content: Color,
    size: Dp = 52.dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) size * 0.26f else size / 2, bouncySpring(), label = "btnCorner")
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                shape = RoundedCornerShape(corner)
                clip = true
            }
            .background(container)
            .smokeClickable(content, interaction, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = content, modifier = Modifier.size(size * 0.46f))
    }
}

/**
 * Material 3 Expressive connected button group: ⏮ [ ▶ ] ⏭.
 * The pressed button widens and squares off, its neighbours make room — all on springs.
 */
@Composable
fun MediaButtonGroup(
    playing: Boolean,
    accent: Color,
    onAccent: Color,
    tonal: Color,
    onTonal: Color,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prevSrc = remember { MutableInteractionSource() }
    val playSrc = remember { MutableInteractionSource() }
    val nextSrc = remember { MutableInteractionSource() }
    val prevP by prevSrc.collectIsPressedAsState()
    val playP by playSrc.collectIsPressedAsState()
    val nextP by nextSrc.collectIsPressedAsState()

    val wPrev by animateFloatAsState(if (prevP) 1.3f else if (playP || nextP) 0.88f else 1f, bouncySpring(), label = "wPrev")
    val wPlay by animateFloatAsState(if (playP) 2.0f else if (prevP || nextP) 1.55f else 1.75f, bouncySpring(), label = "wPlay")
    val wNext by animateFloatAsState(if (nextP) 1.3f else if (playP || prevP) 0.88f else 1f, bouncySpring(), label = "wNext")

    Row(modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GroupButton(
            modifier = Modifier.weight(wPrev),
            shape = groupShape(GroupPos.START, prevP, false),
            container = tonal, content = onTonal, source = prevSrc, onClick = onPrevious,
        ) { Icon(IslandIcons.SkipPrevious, "Previous", tint = onTonal, modifier = Modifier.size(28.dp)) }
        GroupButton(
            modifier = Modifier.weight(wPlay),
            shape = groupShape(GroupPos.MIDDLE, playP, playing),
            container = accent, content = onAccent, source = playSrc, onClick = onToggle,
        ) { PlayPauseIcon(playing, onAccent, 32.dp) }
        GroupButton(
            modifier = Modifier.weight(wNext),
            shape = groupShape(GroupPos.END, nextP, false),
            container = tonal, content = onTonal, source = nextSrc, onClick = onNext,
        ) { Icon(IslandIcons.SkipNext, "Next", tint = onTonal, modifier = Modifier.size(28.dp)) }
    }
}

private enum class GroupPos { START, MIDDLE, END }

@Composable
private fun groupShape(pos: GroupPos, pressed: Boolean, playing: Boolean): Shape {
    val outer by animateDpAsState(if (pressed) 16.dp else 32.dp, bouncySpring(), label = "outer")
    val inner by animateDpAsState(
        when {
            pressed -> 16.dp
            pos == GroupPos.MIDDLE && !playing -> 32.dp
            else -> 10.dp
        },
        bouncySpring(),
        label = "inner",
    )
    return when (pos) {
        GroupPos.START -> RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = inner, bottomEnd = inner)
        GroupPos.END -> RoundedCornerShape(topStart = inner, bottomStart = inner, topEnd = outer, bottomEnd = outer)
        GroupPos.MIDDLE -> RoundedCornerShape(inner)
    }
}

@Composable
private fun GroupButton(
    modifier: Modifier,
    shape: Shape,
    container: Color,
    content: Color,
    source: MutableInteractionSource,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxHeight()
            .graphicsLayer {
                this.shape = shape
                clip = true
            }
            .background(container)
            .smokeClickable(content, source, onClick),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

/** Connected two-way segmented toggle (Stopwatch | Timer). */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selected: Int,
    accent: Color,
    onAccent: Color,
    tonal: Color,
    onTonal: Color,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEachIndexed { i, label ->
            val isSel = i == selected
            val bg by animateColorAsState(if (isSel) accent else tonal, label = "segBg")
            val fg by animateColorAsState(if (isSel) onAccent else onTonal, label = "segFg")
            val outer by animateDpAsState(if (isSel) 20.dp else 18.dp, bouncySpring(), label = "segOuter")
            val inner by animateDpAsState(if (isSel) 20.dp else 6.dp, bouncySpring(), label = "segInner")
            val shape = when (i) {
                0 -> RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = inner, bottomEnd = inner)
                options.lastIndex -> RoundedCornerShape(topStart = inner, bottomStart = inner, topEnd = outer, bottomEnd = outer)
                else -> RoundedCornerShape(inner)
            }
            Box(
                Modifier
                    .height(38.dp)
                    .graphicsLayer {
                        this.shape = shape
                        clip = true
                    }
                    .background(bg)
                    .smokeClickable(fg) { onSelect(i) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = fg, style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
            }
        }
    }
}

// endregion

// region Art + battery ---------------------------------------------------------------------

@Composable
fun ArtImage(art: Bitmap?, accent: Color, modifier: Modifier) {
    if (art != null && !art.isRecycled) {
        val image = remember(art) { art.asImageBitmap() }
        Image(bitmap = image, contentDescription = "Album art", modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(accent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Icon(IslandIcons.MusicNote, null, tint = accent, modifier = Modifier.fillMaxSize(0.5f))
        }
    }
}

/** Album art in a 12-lobe cookie whose outline slowly turns while playing (art stays upright). */
@Composable
fun CookieArt(art: Bitmap?, playing: Boolean, accent: Color, size: Dp) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            rotation.snapTo(rotation.value % 360f)
            while (true) {
                rotation.animateTo(rotation.value + 360f, tween(24_000, easing = LinearEasing))
            }
        }
    }
    val cookie = remember { PolygonShape(ExpressiveShapes.Cookie12) }
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                rotationZ = rotation.value
                shape = cookie
                clip = true
            },
    ) {
        ArtImage(
            art = art,
            accent = accent,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = -rotation.value },
        )
    }
}

@Composable
fun BatteryGlyph(level: Int, charging: Boolean, color: Color, modifier: Modifier = Modifier.size(20.dp, 11.dp)) {
    val fill = when {
        charging -> Color(0xFF6DD58C)
        level <= 15 -> Color(0xFFF2B8B5)
        else -> color
    }
    Canvas(modifier) {
        val stroke = 1.4.dp.toPx()
        val capW = 2.dp.toPx()
        val bodyW = size.width - capW
        drawRoundRect(
            color = color.copy(alpha = 0.85f),
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(bodyW - stroke, size.height - stroke),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(stroke),
        )
        drawRoundRect(
            color = color.copy(alpha = 0.85f),
            topLeft = Offset(bodyW, size.height * 0.3f),
            size = Size(capW, size.height * 0.4f),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        val inset = stroke * 2
        val fillW = (bodyW - inset * 2) * (level.coerceIn(0, 100) / 100f)
        drawRoundRect(
            color = fill,
            topLeft = Offset(inset, inset),
            size = Size(fillW, size.height - inset * 2),
            cornerRadius = CornerRadius(1.5.dp.toPx()),
        )
    }
}

@Composable
fun BatteryChip(level: Int, charging: Boolean, color: Color, textSize: TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (charging) {
            Icon(IslandIcons.Bolt, "Charging", tint = Color(0xFF6DD58C), modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(2.dp))
        }
        BatteryGlyph(level, charging, color)
        Spacer(Modifier.width(4.dp))
        Text(
            text = "$level%",
            color = color,
            maxLines = 1,
            style = TextStyle(fontSize = textSize, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        )
    }
}

// endregion
