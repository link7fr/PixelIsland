package com.pixelisland.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.pixelisland.data.IslandSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single source of truth for "what is playing". Merges the real system media session
 * (via MediaSessionManager) with the built-in Demo Player.
 */
object MediaRepository {
    private const val TAG = "PixelIsland/Media"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private val sessionState = MutableStateFlow(MediaState())
    private val demoState = MutableStateFlow(MediaState())

    val state: StateFlow<MediaState> = combine(
        sessionState,
        demoState,
        IslandSettings.state.map { it.demoMode }.distinctUntilChanged(),
    ) { session, demo, demoOn -> if (demoOn) demo else session }
        .stateIn(scope, SharingStarted.Eagerly, MediaState())

    private val demoActive get() = IslandSettings.state.value.demoMode

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            IslandSettings.state.map { it.demoMode }.distinctUntilChanged().collect { on ->
                if (on) startDemo() else stopDemo()
            }
        }
        attachSessions(context)
    }

    // region System media sessions ------------------------------------------------------

    private var sessionManager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var lastRawArt: Bitmap? = null
    private var sessionArtJob: Job? = null
    private val labelCache = HashMap<String, String>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        onSessionsChanged(list.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publishFromController()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publishFromController()
            if (state?.state != PlaybackState.STATE_PLAYING) refreshSessions()
        }
        override fun onSessionDestroyed() {
            selectController(null)
            refreshSessions()
        }
    }

    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Safe to call repeatedly; does nothing until notification access is granted. */
    fun attachSessions(context: Context) {
        val ctx = context.applicationContext
        if (appContext == null) appContext = ctx
        if (!hasNotificationAccess(ctx)) return
        val manager = ctx.getSystemService(MediaSessionManager::class.java) ?: return
        val component = ComponentName(ctx, MediaListenerService::class.java)
        try {
            if (sessionManager == null) {
                manager.addOnActiveSessionsChangedListener(sessionsListener, component, mainHandler)
                sessionManager = manager
            }
            onSessionsChanged(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification access not yet effective", e)
        } catch (e: Exception) {
            Log.e(TAG, "attachSessions failed", e)
        }
    }

    fun detachSessions() {
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        sessionManager = null
        selectController(null)
    }

    private fun refreshSessions() {
        val ctx = appContext ?: return
        val manager = sessionManager ?: return
        runCatching {
            onSessionsChanged(manager.getActiveSessions(ComponentName(ctx, MediaListenerService::class.java)))
        }
    }

    private fun onSessionsChanged(list: List<MediaController>) {
        val current = controller
        val pick = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { current != null && it.sessionToken == current.sessionToken }
            ?: list.firstOrNull()
        if (pick?.sessionToken != current?.sessionToken) selectController(pick) else publishFromController()
    }

    private fun selectController(new: MediaController?) {
        runCatching { controller?.unregisterCallback(controllerCallback) }
        controller = new
        lastRawArt = null
        runCatching { new?.registerCallback(controllerCallback, mainHandler) }
        publishFromController()
    }

    private fun publishFromController() {
        val c = controller
        val md = runCatching { c?.metadata }.getOrNull()
        val pb = runCatching { c?.playbackState }.getOrNull()
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        if (c == null || title.isBlank()) {
            sessionArtJob?.cancel()
            sessionState.value = MediaState()
            return
        }
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val duration = (md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
        val playing = pb?.state == PlaybackState.STATE_PLAYING || pb?.state == PlaybackState.STATE_BUFFERING
        val key = "${c.packageName}|$title|$artist"
        val prev = sessionState.value
        val sameTrack = prev.trackKey == key
        val speed = pb?.playbackSpeed?.takeIf { it > 0f } ?: 1f

        sessionState.value = MediaState(
            source = MediaSource.SESSION,
            title = title,
            artist = artist,
            appName = appLabel(c.packageName),
            art = if (sameTrack) prev.art else null,
            palette = if (sameTrack) prev.palette else null,
            isPlaying = playing,
            positionMs = pb?.position?.coerceAtLeast(0L) ?: 0L,
            positionUpdatedAt = pb?.lastPositionUpdateTime?.takeIf { it > 0L } ?: SystemClock.elapsedRealtime(),
            speed = speed,
            durationMs = duration,
            trackKey = key,
        )

        val rawArt = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (rawArt != null && (rawArt !== lastRawArt || !sameTrack || prev.art == null)) {
            lastRawArt = rawArt
            sessionArtJob?.cancel()
            sessionArtJob = processArt(rawArt, key, sessionState)
        }
    }

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        val pm = appContext?.packageManager ?: return@getOrPut pkg
        runCatching {
            @Suppress("DEPRECATION")
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    // endregion

    // region Transport controls ---------------------------------------------------------

    fun togglePlayPause() {
        if (demoActive) return demoToggle()
        val c = controller ?: return
        runCatching {
            if (sessionState.value.isPlaying) c.transportControls.pause() else c.transportControls.play()
        }
    }

    fun next() {
        if (demoActive) return loadDemoTrack(demoIndex + 1, playing = true)
        runCatching { controller?.transportControls?.skipToNext() }
    }

    fun previous() {
        if (demoActive) {
            val s = demoState.value
            if (s.positionAt(SystemClock.elapsedRealtime()) > 3_000L) demoSeek(0L)
            else loadDemoTrack(demoIndex - 1, playing = true)
            return
        }
        runCatching { controller?.transportControls?.skipToPrevious() }
    }

    fun seekTo(positionMs: Long) {
        if (demoActive) return demoSeek(positionMs)
        val c = controller ?: return
        runCatching { c.transportControls.seekTo(positionMs) }
        sessionState.update { it.copy(positionMs = positionMs, positionUpdatedAt = SystemClock.elapsedRealtime()) }
    }

    // endregion

    // region Demo player ----------------------------------------------------------------

    private class DemoTrack(
        val title: String,
        val artist: String,
        val durationMs: Long,
        val colors: IntArray,
    )

    private val demoTracks = listOf(
        DemoTrack("Monet Skies", "The Material Ensemble", 204_000L,
            intArrayOf(0xFF4F378B.toInt(), 0xFFD0BCFF.toInt(), 0xFFFFD8E4.toInt())),
        DemoTrack("Squiggle Drive", "Wave Theory", 178_000L,
            intArrayOf(0xFF00513F.toInt(), 0xFF6DD5B3.toInt(), 0xFFC8FFE8.toInt())),
        DemoTrack("Punch-Hole Sunset", "Neon Cutout", 245_000L,
            intArrayOf(0xFF8C1D18.toInt(), 0xFFFF8A50.toInt(), 0xFFFFE082.toInt())),
        DemoTrack("Haptic Rain", "Pixel Drift", 192_000L,
            intArrayOf(0xFF0B3D91.toInt(), 0xFF4FC3F7.toInt(), 0xFFE1F5FE.toInt())),
    )
    private var demoIndex = 0
    private var demoTicker: Job? = null
    private var demoArtJob: Job? = null

    private fun startDemo() {
        loadDemoTrack(demoIndex, playing = true)
        demoTicker?.cancel()
        demoTicker = scope.launch {
            while (true) {
                delay(500L)
                val s = demoState.value
                if (s.isPlaying && s.durationMs > 0 &&
                    s.positionAt(SystemClock.elapsedRealtime()) >= s.durationMs
                ) {
                    loadDemoTrack(demoIndex + 1, playing = true)
                }
            }
        }
    }

    private fun stopDemo() {
        demoTicker?.cancel()
        demoArtJob?.cancel()
        demoState.value = MediaState()
    }

    private fun loadDemoTrack(index: Int, playing: Boolean) {
        val size = demoTracks.size
        demoIndex = ((index % size) + size) % size
        val t = demoTracks[demoIndex]
        val key = "demo|$demoIndex"
        val art = runCatching { makeDemoArt(t.colors) }.getOrNull()
        demoState.value = MediaState(
            source = MediaSource.DEMO,
            title = t.title,
            artist = t.artist,
            appName = "Demo Player",
            art = art,
            palette = null,
            isPlaying = playing,
            positionMs = 0L,
            positionUpdatedAt = SystemClock.elapsedRealtime(),
            durationMs = t.durationMs,
            trackKey = key,
        )
        if (art != null) {
            demoArtJob?.cancel()
            demoArtJob = processArt(art, key, demoState)
        }
    }

    private fun demoToggle() {
        val now = SystemClock.elapsedRealtime()
        demoState.update { s ->
            if (!s.isActive) s
            else if (s.isPlaying) s.copy(isPlaying = false, positionMs = s.positionAt(now), positionUpdatedAt = now)
            else s.copy(isPlaying = true, positionUpdatedAt = now)
        }
    }

    private fun demoSeek(positionMs: Long) {
        demoState.update {
            it.copy(
                positionMs = positionMs.coerceIn(0L, it.durationMs),
                positionUpdatedAt = SystemClock.elapsedRealtime(),
            )
        }
    }

    private fun makeDemoArt(colors: IntArray): Bitmap {
        val size = 512
        val f = size.toFloat()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, f, f, colors[0], colors[1], Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, f, f, paint)
        paint.shader = RadialGradient(f * 0.72f, f * 0.28f, f * 0.5f, colors[2], Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(f * 0.72f, f * 0.28f, f * 0.5f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = f * 0.018f
        paint.color = Color.argb(70, 255, 255, 255)
        for (i in 1..5) canvas.drawCircle(f * 0.3f, f * 0.75f, f * 0.09f * i, paint)
        return bmp
    }

    // endregion

    // region Album art + Palette ---------------------------------------------------------

    private fun processArt(raw: Bitmap, key: String, target: MutableStateFlow<MediaState>): Job = scope.launch {
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val bmp = ownedCopy(raw)
                bmp to extractPalette(bmp)
            }.onFailure { Log.w(TAG, "Album art processing failed", it) }.getOrNull()
        } ?: return@launch
        target.update { if (it.trackKey == key) it.copy(art = result.first, palette = result.second) else it }
    }

    /** Copies into a software ARGB bitmap we own (hardware bitmaps can't be read by Palette). */
    private fun ownedCopy(raw: Bitmap): Bitmap {
        check(!raw.isRecycled) { "Bitmap recycled" }
        val software = if (raw.config == Bitmap.Config.HARDWARE || raw.config == null) {
            raw.copy(Bitmap.Config.ARGB_8888, false)
        } else raw
        val maxSide = maxOf(software.width, software.height)
        return when {
            maxSide > 512 -> {
                val scale = 512f / maxSide
                Bitmap.createScaledBitmap(
                    software,
                    (software.width * scale).toInt().coerceAtLeast(1),
                    (software.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            }
            software === raw -> raw.copy(Bitmap.Config.ARGB_8888, false)
            else -> software
        }
    }

    private fun extractPalette(bmp: Bitmap): ArtPalette {
        val p = Palette.from(bmp).maximumColorCount(16).generate()
        val dominant = p.getDominantColor(0xFF303030.toInt())
        val vibrant = p.getVibrantColor(p.getLightVibrantColor(p.getMutedColor(dominant)))

        val containerHsl = FloatArray(3).also { ColorUtils.colorToHSL(dominant, it) }
        containerHsl[1] = containerHsl[1].coerceAtMost(0.55f)
        containerHsl[2] = containerHsl[2].coerceIn(0.10f, 0.20f)
        val container = ColorUtils.HSLToColor(containerHsl)

        val accentHsl = FloatArray(3).also { ColorUtils.colorToHSL(vibrant, it) }
        accentHsl[1] = accentHsl[1].coerceAtLeast(0.45f)
        accentHsl[2] = accentHsl[2].coerceIn(0.62f, 0.80f)
        val accent = ColorUtils.HSLToColor(accentHsl)

        return ArtPalette(container = container, accent = accent, onContainer = 0xFFF2F0F4.toInt())
    }

    // endregion
}
