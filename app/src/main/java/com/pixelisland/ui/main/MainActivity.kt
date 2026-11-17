package com.pixelisland.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pixelisland.data.IslandSettings
import com.pixelisland.ui.theme.PixelIslandTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        IslandSettings.init(this)
        setContent {
            PixelIslandTheme {
                SettingsScreen(onAutoDetect = ::autoDetectCutout)
            }
        }
    }

    /** Reads the real display cutout and centers the island on it. Returns a user-facing message. */
    private fun autoDetectCutout(): String {
        val metrics = windowManager.currentWindowMetrics
        val cutout = window.decorView.rootWindowInsets?.displayCutout
            ?: metrics.windowInsets.displayCutout
        val rect = cutout?.boundingRects
            ?.filter { it.top < metrics.bounds.height() / 4 }
            ?.minByOrNull { it.top }
            ?: return "No camera cutout found on this display"
        val density = resources.displayMetrics.density
        val screenWidth = metrics.bounds.width()
        val offsetX = (rect.exactCenterX() - screenWidth / 2f) / density
        val height = (rect.height() / density + 10f).coerceIn(24f, 64f)
        val offsetY = (rect.exactCenterY() / density - height / 2f).coerceAtLeast(0f)
        IslandSettings.update {
            it.copy(offsetX = offsetX.coerceIn(-200f, 200f), offsetY = offsetY.coerceIn(0f, 80f), pillHeight = height)
        }
        return "Cutout found: ${rect.width()}×${rect.height()} px — island aligned"
    }
}
