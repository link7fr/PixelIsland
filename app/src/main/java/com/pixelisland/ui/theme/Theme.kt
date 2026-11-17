package com.pixelisland.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val base = Typography()

/** Slightly heavier, tighter display/headline styles for an "expressive" feel. */
val ExpressiveTypography = base.copy(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.W300, letterSpacing = (-1.5).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.W400, letterSpacing = (-1).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.W600),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.W600),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.W600),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.W600),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.W600),
)

/** Companion app theme: Monet dynamic colors that follow the system light/dark setting. */
@Composable
fun PixelIslandTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
        typography = ExpressiveTypography,
        content = content,
    )
}

/** The island always sits on the black camera area, so it always uses the dark Monet scheme. */
@Composable
fun IslandOverlayTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = dynamicDarkColorScheme(context),
        typography = ExpressiveTypography,
        content = content,
    )
}
