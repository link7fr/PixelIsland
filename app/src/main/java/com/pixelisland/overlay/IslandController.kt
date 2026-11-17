package com.pixelisland.overlay

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** UI state of a single island instance (the overlay, or the preview in the app). */
@Stable
class IslandController {
    var expanded by mutableStateOf(false)
        private set
    var snoozed by mutableStateOf(false)
        private set

    fun expand() {
        snoozed = false
        expanded = true
    }

    fun collapse() {
        expanded = false
    }

    fun toggle() = if (expanded) collapse() else expand()

    fun snooze() {
        expanded = false
        snoozed = true
    }

    fun unsnooze() {
        snoozed = false
    }

    companion object {
        const val SNOOZE_MS = 60_000L
    }
}
