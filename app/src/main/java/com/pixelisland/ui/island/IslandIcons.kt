package com.pixelisland.ui.island

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Small set of Material Symbols paths, so we don't pull in material-icons-extended. */
object IslandIcons {
    val Play by lazy { icon("Play", "M8,5v14l11,-7z") }
    val Pause by lazy { icon("Pause", "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z") }
    val SkipNext by lazy { icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }
    val SkipPrevious by lazy { icon("SkipPrevious", "M6,6h2v12L6,18zM9.5,12l8.5,6L18,6z") }
    val Stopwatch by lazy {
        icon(
            "Stopwatch",
            "M15,1H9v2h6V1zM11,14h2V8h-2V14zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41" +
                "l-1.42,1.42C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9s9,-4.03 9,-9" +
                "C21,10.88 20.26,8.93 19.03,7.39zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7s7,3.13 7,7S15.87,20 12,20z",
        )
    }
    val Hourglass by lazy {
        icon(
            "Hourglass",
            "M6,2v6h0.01L6,8.01 10,12l-4,4 0.01,0.01H6V22h12v-5.99h-0.01L18,16l-4,-4 4,-3.99 -0.01,-0.01H18V2H6z" +
                "M16,16.5V20H8v-3.5l4,-4 4,4zM12,11.5l-4,-4V4h8v3.5l-4,4z",
        )
    }
    val Reset by lazy {
        icon(
            "Reset",
            "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6" +
                "h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78" +
                "L13,11h7V4l-2.35,2.35z",
        )
    }
    val Bolt by lazy {
        icon(
            "Bolt",
            "M11,21h-1l1,-7H7.5c-0.88,0 -0.33,-0.75 -0.31,-0.78C8.48,10.94 10.42,7.54 13.01,3h1l-1,7h3.51" +
                "c0.4,0 0.62,0.19 0.4,0.66C12.97,17.55 11,21 11,21z",
        )
    }
    val MusicNote by lazy {
        icon(
            "MusicNote",
            "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z",
        )
    }
    val Bedtime by lazy {
        icon(
            "Bedtime",
            "M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10c3.71,0 6.93,-2.02 8.66,-5.02" +
                "C13.15,16.73 8.57,8.55 12.34,2.02z",
        )
    }
    val Add by lazy { icon("Add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z") }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { addPath(pathData = PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.Black)) }
        }.build()
}
