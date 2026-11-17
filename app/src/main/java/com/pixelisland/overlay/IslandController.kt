package com.pixelisland.overlay

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** UI state of a single island instance (the overlay, or the preview in the app). */
@Stable
class IslandController {

    /**
     * Implemented by the overlay service. The window is kept pill-sized while collapsed so it
     * never blocks the status bar; it is grown *before* the expand animation starts and shrunk
     * only *after* the collapse animation has fully settled, so the morph is never clipped.
     */
    interface Host {
        fun prepareExpand(proceed: () -> Unit)
        fun onCollapseSettled()
    }

    var host: Host? = null

    var expanded by mutableStateOf(false)
        private set
    var snoozed by mutableStateOf(false)
        private set

    /** True while the overlay window is in its large (expanded-capable) size. */
    var bigWindow by mutableStateOf(false)

    fun expand() {
        snoozed = false
        if (expanded) return
        val h = host
        if (h == null) expanded = true else h.prepareExpand { expanded = true }
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

    fun notifyCollapseSettled() {
        host?.onCollapseSettled()
    }

    companion object {
        const val SNOOZE_MS = 60_000L
    }
}
