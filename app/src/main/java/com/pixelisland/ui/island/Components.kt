package com.pixelisland.ui.island

import android.graphics.Bitmap
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.sin

// region Motion + haptics ------------------------------------------------------------------

/** The one spring used for every island size/shape/position change. */
fun <T> islandSpring(): SpringSpec<T> =
    spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)

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

// endregion

// region Time helpers ----------------------------------------------------------------------

/** Wall-clock millis, ticking on each second boundary. */
@Composable
fun rememberWallClock(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        val now = System.currentTimeMillis()
        value = now
        delay(1_000L - now % 1_000L)
    }
}

/** elapsedRealtime that only ticks while [active]. */
@Composable
fun rememberElapsedTicker(active: Boolean, periodMs: Long): Long {
    val state = produceState(SystemClock.elapsedRealtime(), active) {
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
    val minuteBucket = now / 60_000L
    val text = remember(minuteBucket, is24) {
        val z = zoned(now)
        if (is24) "%02d:%02d".format(z.hour, z.minute)
        else "%d:%02d".format((z.hour + 11) % 12 + 1, z.minute)
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

private val BarDurations = intArrayOf(460, 620, 390, 700, 530)

/** 5-bar audio visualizer. Bars dance while playing and settle into dots when paused. */
@Composable
fun Waveform(playing: Boolean, color: Color, modifier: Modifier = Modifier.size(24.dp, 14.dp)) {
    val transition = rememberInfiniteTransition(label = "wave")
    val bars = BarDurations.mapIndexed { i, d ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(d, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(i * 85),
            ),
            label = "bar$i",
        )
    }
    val energy by animateFloatAsState(if (playing) 1f else 0f, islandSpring(), label = "energy")
    Canvas(modifier) {
        val count = bars.size
        val barW = size.width / (count * 2 - 1)
        val rest = 0.24f
        for (i in 0 until count) {
            val v = bars[i].value
            val fraction = rest + (v - rest).coerceAtLeast(0f) * energy
            val h = (size.height * fraction).coerceAtLeast(barW)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * barW * 2, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

// endregion

// region Squiggly slider -------------------------------------------------------------------

/**
 * Android 13+ style media seekbar: the played part is an animated sine wave, the rest a flat
 * track. The wave flattens when paused or while the user is dragging.
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
    val amplitude by animateFloatAsState(
        targetValue = if (playing && dragFraction == null) 1f else 0f,
        animationSpec = islandSpring(),
        label = "amp",
    )
    val phase by rememberInfiniteTransition(label = "squiggle").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "phase",
    )
    val seek by rememberUpdatedState(onSeek)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
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
        val strokeW = 4.dp.toPx()
        val waveLen = 24.dp.toPx()
        val amp = 3.2.dp.toPx() * amplitude
        val endX = pad + usable * shown

        if (endX > pad) {
            val path = Path()
            var x = pad
            path.moveTo(x, cy + amp * sin(x / waveLen * 2f * PI.toFloat() - phase))
            while (x < endX) {
                x = (x + 2f).coerceAtMost(endX)
                path.lineTo(x, cy + amp * sin(x / waveLen * 2f * PI.toFloat() - phase))
            }
            drawPath(path, color, style = Stroke(width = strokeW, cap = StrokeCap.Round))
        }
        val gap = 7.dp.toPx()
        if (endX + gap < size.width - pad) {
            drawLine(trackColor, Offset(endX + gap, cy), Offset(size.width - pad, cy), strokeW, StrokeCap.Round)
        }
        val thumbW = 5.dp.toPx()
        val thumbH = 18.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(endX - thumbW / 2f, cy - thumbH / 2f),
            size = Size(thumbW, thumbH),
            cornerRadius = CornerRadius(thumbW / 2f),
        )
    }
}

// endregion

// region Buttons ---------------------------------------------------------------------------

/** Play/Pause that morphs between a circle (paused) and a rounded square (playing). */
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
    val corner by animateDpAsState(if (playing) size * 0.3f else size / 2, islandSpring(), label = "playCorner")
    val scale by animateFloatAsState(
        if (pressed) 0.9f else 1f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "playScale",
    )
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(container)
            .clickable(interactionSource = interaction, indication = ripple(color = content), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = playing,
            transitionSpec = {
                (scaleIn(islandSpring(), initialScale = 0.6f) + fadeIn()) togetherWith
                    (scaleOut(targetScale = 0.6f) + fadeOut())
            },
            label = "playIcon",
        ) { isPlaying ->
            Icon(
                imageVector = if (isPlaying) IslandIcons.Pause else IslandIcons.Play,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = content,
                modifier = Modifier.size(size * 0.46f),
            )
        }
    }
}

/** Round icon button that squashes into a squircle when pressed. */
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
    val corner by animateDpAsState(
        if (pressed) size * 0.28f else size / 2,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "btnCorner",
    )
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(container)
            .clickable(interactionSource = interaction, indication = ripple(color = content), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = content, modifier = Modifier.size(size * 0.46f))
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
            Icon(IslandIcons.MusicNote, null, tint = accent, modifier = Modifier.fillMaxSize(0.55f))
        }
    }
}

/** Circular album art that spins like a record while playing and stops where it is when paused. */
@Composable
fun SpinningArt(art: Bitmap?, playing: Boolean, accent: Color, size: Dp) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            rotation.snapTo(rotation.value % 360f)
            while (true) {
                rotation.animateTo(rotation.value + 360f, tween(9_000, easing = LinearEasing))
            }
        }
    }
    ArtImage(
        art = art,
        accent = accent,
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation.value }
            .clip(CircleShape),
    )
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
