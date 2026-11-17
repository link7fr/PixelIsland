package com.pixelisland.overlay

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** UI state of a single island instance (the overlay, or the preview in the app). */
@Stable
class IslandController {

    /** Island gesture actions, provided by the composable so they can use its haptics/state. */
    interface GestureHandler {
        fun onTap()
        fun onLongPress()
        fun onSwipe(direction: Int)
    }

    var gestureHandler: GestureHandler? = null

    var expanded by mutableStateOf(false)
        private set
    var snoozed by mutableStateOf(false)
        private set

    /** Finger is down on the pill (set by the overlay's touch window). */
    var pressed by mutableStateOf(false)

    /**
     * True for the overlay: its drawing window is full-width at x = 0, so the island applies the
     * horizontal calibration offset itself.
     */
    var offsetInsideWindow = false

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
