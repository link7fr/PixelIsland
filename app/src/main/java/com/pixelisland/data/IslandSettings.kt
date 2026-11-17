package com.pixelisland.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/** All user-tunable island settings. Dimensions are in dp. */
data class Settings(
    val islandEnabled: Boolean = true,
    val offsetX: Float = DEFAULT_X,
    val offsetY: Float = DEFAULT_Y,
    val pillWidth: Float = DEFAULT_WIDTH,
    val pillHeight: Float = DEFAULT_HEIGHT,
    val alwaysOnClock: Boolean = true,
    val splitPill: Boolean = false,
    val demoMode: Boolean = false,
    val showBattery: Boolean = true,
    val haptics: Boolean = true,
) {
    companion object {
        const val DEFAULT_X = 0f
        const val DEFAULT_Y = 6f
        const val DEFAULT_WIDTH = 196f
        const val DEFAULT_HEIGHT = 34f
    }
}

/**
 * Process-wide settings store. The activity and both services live in the same process,
 * so a single StateFlow keeps the overlay in sync with the sliders in real time.
 */
object IslandSettings {
    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("pixel_island", Context.MODE_PRIVATE)
        prefs = p
        _state.value = read(p)
    }

    fun update(transform: (Settings) -> Settings) {
        val updated = _state.updateAndGet(transform)
        prefs?.let { write(it, updated) }
    }

    fun resetCalibration() = update {
        it.copy(
            offsetX = Settings.DEFAULT_X,
            offsetY = Settings.DEFAULT_Y,
            pillWidth = Settings.DEFAULT_WIDTH,
            pillHeight = Settings.DEFAULT_HEIGHT,
        )
    }

    private fun read(p: SharedPreferences): Settings {
        val d = Settings()
        return Settings(
            islandEnabled = p.getBoolean(K_ENABLED, d.islandEnabled),
            offsetX = p.getFloat(K_X, d.offsetX),
            offsetY = p.getFloat(K_Y, d.offsetY),
            pillWidth = p.getFloat(K_W, d.pillWidth),
            pillHeight = p.getFloat(K_H, d.pillHeight),
            alwaysOnClock = p.getBoolean(K_CLOCK, d.alwaysOnClock),
            splitPill = p.getBoolean(K_SPLIT, d.splitPill),
            demoMode = p.getBoolean(K_DEMO, d.demoMode),
            showBattery = p.getBoolean(K_BATTERY, d.showBattery),
            haptics = p.getBoolean(K_HAPTICS, d.haptics),
        )
    }

    private fun write(p: SharedPreferences, s: Settings) {
        p.edit()
            .putBoolean(K_ENABLED, s.islandEnabled)
            .putFloat(K_X, s.offsetX)
            .putFloat(K_Y, s.offsetY)
            .putFloat(K_W, s.pillWidth)
            .putFloat(K_H, s.pillHeight)
            .putBoolean(K_CLOCK, s.alwaysOnClock)
            .putBoolean(K_SPLIT, s.splitPill)
            .putBoolean(K_DEMO, s.demoMode)
            .putBoolean(K_BATTERY, s.showBattery)
            .putBoolean(K_HAPTICS, s.haptics)
            .apply()
    }

    private const val K_ENABLED = "enabled"
    private const val K_X = "x"
    private const val K_Y = "y"
    private const val K_W = "w"
    private const val K_H = "h"
    private const val K_CLOCK = "always_clock"
    private const val K_SPLIT = "split"
    private const val K_DEMO = "demo"
    private const val K_BATTERY = "battery"
    private const val K_HAPTICS = "haptics"
}
