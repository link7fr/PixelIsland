package com.pixelisland.media

import android.graphics.Bitmap

enum class MediaSource { NONE, SESSION, DEMO }

/** Colors extracted from the album art with Palette (ARGB ints). */
data class ArtPalette(
    val container: Int,
    val accent: Int,
    val onContainer: Int,
)

data class MediaState(
    val source: MediaSource = MediaSource.NONE,
    val title: String = "",
    val artist: String = "",
    val appName: String = "",
    val art: Bitmap? = null,
    val palette: ArtPalette? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    /** SystemClock.elapsedRealtime() when [positionMs] was sampled. */
    val positionUpdatedAt: Long = 0L,
    val speed: Float = 1f,
    val durationMs: Long = 0L,
    val trackKey: String = "",
) {
    val isActive: Boolean get() = source != MediaSource.NONE

    fun positionAt(now: Long): Long {
        val base = if (isPlaying && positionUpdatedAt > 0L) {
            positionMs + ((now - positionUpdatedAt) * speed).toLong()
        } else positionMs
        return if (durationMs > 0L) base.coerceIn(0L, durationMs) else base.coerceAtLeast(0L)
    }
}
