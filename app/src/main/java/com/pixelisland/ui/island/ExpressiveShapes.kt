package com.pixelisland.ui.island

import android.graphics.Matrix
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

/** Material 3 Expressive shapes (normalized to a 0..1 box) built with androidx.graphics.shapes. */
object ExpressiveShapes {
    /** 9-lobe "cookie" — the scalloped shape Pixel uses for play buttons and badges. */
    val Cookie9: RoundedPolygon by lazy {
        RoundedPolygon.star(numVerticesPerRadius = 9, innerRadius = 0.8f, rounding = CornerRounding(0.42f)).normalized()
    }

    /** Gentle 12-lobe cookie for album art. */
    val Cookie12: RoundedPolygon by lazy {
        RoundedPolygon.star(numVerticesPerRadius = 12, innerRadius = 0.89f, rounding = CornerRounding(0.5f)).normalized()
    }

    val Squircle: RoundedPolygon by lazy {
        RoundedPolygon.rectangle(width = 2f, height = 2f, rounding = CornerRounding(0.6f, smoothing = 0.8f)).normalized()
    }

    val Circle: RoundedPolygon by lazy { RoundedPolygon.circle(numVertices = 12).normalized() }

    /** Paused (cookie) → playing (squircle). */
    val PlayPause: Morph by lazy { Morph(Cookie9, Squircle) }

    /** Badge morph used on the permission cards: squircle → cookie when granted. */
    val Badge: Morph by lazy { Morph(Squircle, Cookie9) }
}

class PolygonShape(private val polygon: RoundedPolygon) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = polygon.toPath()
        path.transform(Matrix().apply { setScale(size.width, size.height) })
        return Outline.Generic(path.asComposePath())
    }
}

class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress.coerceIn(0f, 1f))
        path.transform(Matrix().apply { setScale(size.width, size.height) })
        return Outline.Generic(path.asComposePath())
    }
}
