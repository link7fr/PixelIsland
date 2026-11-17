package com.pixelisland.ui.island

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max

/**
 * Pixel-style "smoke" press effect: a soft turbulent cloud with sparkles that blooms from the
 * touch point and dissolves on release. AGSL on Android 13+, a soft circle fallback on 12.
 * It only renders frames while a press is animating — zero cost when idle.
 */
class SmokeIndication(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = SmokeNode(interactionSource, color)
    override fun equals(other: Any?): Boolean = other is SmokeIndication && other.color == color
    override fun hashCode(): Int = color.hashCode()
}

private class SmokeNode(
    private val source: InteractionSource,
    private val color: Color,
) : Modifier.Node(), DrawModifierNode {

    private var origin = Offset.Unspecified
    private var time = 0f
    private var progress = 0f
    private var alpha = 0f
    private var pressed = false
    private var releasedAt = 0f
    private var job: Job? = null

    private val shader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= 33) runCatching { RuntimeShader(SMOKE_AGSL) }.getOrNull() else null
    private val brush: ShaderBrush? = shader?.let { ShaderBrush(it) }

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> start(interaction.pressPosition)
                    is PressInteraction.Release, is PressInteraction.Cancel -> release()
                }
            }
        }
    }

    private fun start(position: Offset) {
        origin = position
        pressed = true
        releasedAt = 0f
        job?.cancel()
        job = coroutineScope.launch {
            val startNs = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                time = (now - startNs) / 1_000_000_000f
                progress = (time / 0.6f).coerceAtMost(1f)
                alpha = if (pressed) 1f else (1f - (time - releasedAt) / 0.55f).coerceIn(0f, 1f)
                invalidateDraw()
                if (!pressed && alpha <= 0f) break
            }
            alpha = 0f
            invalidateDraw()
        }
    }

    private fun release() {
        if (!pressed) return
        pressed = false
        // Quick taps still get a visible puff.
        releasedAt = max(time, 0.16f)
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (alpha <= 0f || origin == Offset.Unspecified) return
        val w = size.width
        val h = size.height
        val maxR = maxOf(
            hypot(origin.x, origin.y),
            hypot(w - origin.x, origin.y),
            hypot(origin.x, h - origin.y),
            hypot(w - origin.x, h - origin.y),
        )
        val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
        val radius = maxR * (0.2f + 0.95f * eased)
        val s = shader
        val b = brush
        if (s != null && b != null) {
            s.setFloatUniform("in_origin", origin.x, origin.y)
            s.setFloatUniform("in_radius", radius)
            s.setFloatUniform("in_time", time)
            s.setFloatUniform("in_alpha", alpha)
            s.setColorUniform("in_color", color.toArgb())
            drawRect(brush = b)
        } else {
            drawCircle(color = color.copy(alpha = 0.2f * alpha), radius = radius, center = origin)
        }
    }
}

private const val SMOKE_AGSL = """
uniform float2 in_origin;
uniform float in_radius;
uniform float in_time;
uniform float in_alpha;
layout(color) uniform half4 in_color;

float hash(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        v += amp * vnoise(p);
        p = p * 2.02 + float2(17.0, 9.0);
        amp *= 0.5;
    }
    return v;
}

half4 main(float2 fragCoord) {
    float d = distance(fragCoord, in_origin);
    float2 q = fragCoord / 22.0;
    float warp = fbm(q * 0.7 - float2(in_time * 0.3, in_time * 0.2));
    float n = fbm(q + float2(in_time * 0.6, -in_time * 0.45) + warp);
    float edge = in_radius * (0.75 + 0.5 * n);
    float body = 1.0 - smoothstep(edge * 0.3, edge, d);
    float wisps = smoothstep(0.45, 0.85, n) * (1.0 - smoothstep(edge * 0.6, edge * 1.3, d));
    float sparkleSeed = hash(floor(fragCoord / 2.0) + floor(in_time * 30.0));
    float sparkle = step(0.988, sparkleSeed) * (1.0 - smoothstep(0.0, edge * 1.1, d));
    float a = clamp(body * (0.16 + 0.22 * n) + wisps * 0.16 + sparkle * 0.55, 0.0, 1.0) * in_alpha;
    float3 c = float3(in_color.rgb);
    return half4(c * a, a);
}
"""
