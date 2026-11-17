package com.pixelisland.overlay

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.pixelisland.data.IslandSettings
import com.pixelisland.data.Settings
import com.pixelisland.media.MediaRepository
import com.pixelisland.ui.island.IslandRoot
import com.pixelisland.ui.theme.IslandOverlayTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Hosts the island as a TYPE_ACCESSIBILITY_OVERLAY window so it can sit on top of the
 * status bar and camera punch-hole without the "draw over other apps" permission.
 */
class IslandAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PixelIsland/Overlay"
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
    }

    private var windowManager: WindowManager? = null
    private var root: IslandRootLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var scope: CoroutineScope? = null
    private val controller = IslandController()

    override fun onServiceConnected() {
        super.onServiceConnected()
        _running.value = true
        IslandSettings.init(this)
        MediaRepository.attachSessions(this)
        try {
            addOverlay()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to add island overlay", t)
            removeOverlay()
            return
        }
        scope = MainScope().also { s ->
            s.launch {
                combine(IslandSettings.state, snapshotFlow { controller.expanded }) { settings, expanded ->
                    settings to expanded
                }.collect { (settings, expanded) -> applyLayout(settings, expanded) }
            }
        }
    }

    private fun addOverlay() {
        if (root != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val owner = OverlayLifecycleOwner().also { it.onCreate() }
        lifecycleOwner = owner

        val layout = IslandRootLayout(this) { controller.collapse() }
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                IslandOverlayTheme {
                    val width = (LocalConfiguration.current.screenWidthDp - 16).coerceIn(260, 420)
                    IslandRoot(controller = controller, maxExpandedWidth = width.dp)
                }
            }
        }
        layout.addView(
            composeView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        // Owners go on the root (view-tree lookup walks up parents) and on the ComposeView itself.
        owner.attachTo(layout)
        owner.attachTo(composeView)

        val settings = IslandSettings.state.value
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "PixelIsland"
            x = dp(settings.offsetX)
            y = dp(settings.offsetY)
        }
        params = lp

        wm.addView(layout, lp)
        root = layout
        owner.onResume()
        updateVisibility(settings)
    }

    private fun applyLayout(settings: Settings, expanded: Boolean) {
        val wm = windowManager ?: return
        val view = root ?: return
        val lp = params ?: return
        val newX = if (expanded) 0 else dp(settings.offsetX)
        val newY = dp(settings.offsetY)
        if (lp.x != newX || lp.y != newY) {
            lp.x = newX
            lp.y = newY
            runCatching { wm.updateViewLayout(view, lp) }
        }
        updateVisibility(settings)
    }

    private fun updateVisibility(settings: Settings = IslandSettings.state.value) {
        val portrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        root?.visibility = if (settings.islandEnabled && portrait) View.VISIBLE else View.GONE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) controller.collapse()
        updateVisibility()
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun removeOverlay() {
        scope?.cancel()
        scope = null
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        root?.let { view -> runCatching { windowManager?.removeViewImmediate(view) } }
        root = null
        params = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        removeOverlay()
        _running.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeOverlay()
        _running.value = false
        super.onDestroy()
    }
}
