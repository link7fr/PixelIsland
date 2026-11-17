package com.pixelisland.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
 * Hosts the island with two TYPE_ACCESSIBILITY_OVERLAY windows. Neither ever changes size during
 * an animation (resizing a window makes Android stretch the stale frame → black rectangles):
 *
 *  - Visual window: full width, fixed height, draws the island. Pass-through (NOT_TOUCHABLE)
 *    while collapsed; touchable while expanded so the card's buttons work and taps beside the
 *    card collapse it.
 *  - Touch window: invisible, exactly over the pill; turns taps / long-press / swipes into
 *    island gestures while collapsed. Draws nothing, so it can't glitch.
 */
class IslandAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PixelIsland/Overlay"

        /** Room above the pill for the press "squish" and spring overshoot. */
        const val WINDOW_MARGIN_DP = 12f

        /** Tall enough for the expanded card plus its shadow. */
        private const val VISUAL_WINDOW_HEIGHT_DP = 340f

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
    }

    private var windowManager: WindowManager? = null
    private var visualRoot: IslandRootLayout? = null
    private var visualParams: WindowManager.LayoutParams? = null
    private var touchView: IslandTouchView? = null
    private var touchParams: WindowManager.LayoutParams? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var scope: CoroutineScope? = null
    private val controller = IslandController().apply { offsetInsideWindow = true }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _running.value = true
        IslandSettings.init(this)
        MediaRepository.attachSessions(this)
        try {
            addWindows()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to add island overlay", t)
            removeWindows()
            return
        }
        scope = MainScope().also { s ->
            s.launch {
                combine(IslandSettings.state, snapshotFlow { controller.expanded }) { a, b -> a to b }
                    .collect { (settings, expanded) -> applyLayout(settings, expanded) }
            }
        }
    }

    private fun addWindows() {
        if (visualRoot != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val settings = IslandSettings.state.value

        // --- Visual window -------------------------------------------------------------------
        val owner = OverlayLifecycleOwner().also { it.onCreate() }
        lifecycleOwner = owner

        val layout = IslandRootLayout(this) { controller.collapse() }
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                IslandOverlayTheme {
                    val width = (LocalConfiguration.current.screenWidthDp - 24).coerceIn(260, 384)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // Only reachable while expanded: taps beside the card collapse it.
                            .pointerInput(Unit) { detectTapGestures { controller.collapse() } },
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        IslandRoot(
                            controller = controller,
                            maxExpandedWidth = width.dp,
                            modifier = Modifier.padding(top = WINDOW_MARGIN_DP.dp),
                        )
                    }
                }
            }
        }
        layout.addView(
            composeView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        owner.attachTo(layout)
        owner.attachTo(composeView)

        val vp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(VISUAL_WINDOW_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            visualFlags(expanded = false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            windowAnimations = 0
            title = "PixelIsland"
            x = 0
            y = dp(settings.offsetY - WINDOW_MARGIN_DP)
        }
        wm.addView(layout, vp)
        visualRoot = layout
        visualParams = vp
        owner.onResume()

        // --- Touch window --------------------------------------------------------------------
        val tv = IslandTouchView(this, controller)
        val tp = WindowManager.LayoutParams(
            dp(settings.pillWidth),
            dp(settings.pillHeight),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            touchFlags(expanded = false),
            PixelFormat.TRANSPARENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            windowAnimations = 0
            title = "PixelIslandTouch"
            x = dp(settings.offsetX)
            y = dp(settings.offsetY)
        }
        wm.addView(tv, tp)
        touchView = tv
        touchParams = tp

        updateVisibility(settings)
    }

    private fun visualFlags(expanded: Boolean): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        f = if (expanded) f or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        else f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return f
    }

    private fun touchFlags(expanded: Boolean): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (expanded) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return f
    }

    /** Only flags and positions change here — the visual window's size is constant. */
    private fun applyLayout(settings: Settings, expanded: Boolean) {
        val wm = windowManager ?: return
        visualRoot?.let { view ->
            val lp = visualParams ?: return@let
            val flags = visualFlags(expanded)
            val y = dp(settings.offsetY - WINDOW_MARGIN_DP)
            if (lp.flags != flags || lp.y != y) {
                lp.flags = flags
                lp.y = y
                runCatching { wm.updateViewLayout(view, lp) }
            }
        }
        touchView?.let { view ->
            val lp = touchParams ?: return@let
            val flags = touchFlags(expanded)
            val w = dp(settings.pillWidth)
            val h = dp(settings.pillHeight)
            val x = dp(settings.offsetX)
            val y = dp(settings.offsetY)
            if (lp.flags != flags || lp.width != w || lp.height != h || lp.x != x || lp.y != y) {
                lp.flags = flags
                lp.width = w
                lp.height = h
                lp.x = x
                lp.y = y
                runCatching { wm.updateViewLayout(view, lp) }
            }
        }
        updateVisibility(settings)
    }

    private fun updateVisibility(settings: Settings = IslandSettings.state.value) {
        val portrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val v = if (settings.islandEnabled && portrait) View.VISIBLE else View.GONE
        visualRoot?.visibility = v
        touchView?.visibility = v
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) controller.collapse()
        updateVisibility()
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun removeWindows() {
        scope?.cancel()
        scope = null
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        visualRoot?.let { runCatching { windowManager?.removeViewImmediate(it) } }
        touchView?.let { runCatching { windowManager?.removeViewImmediate(it) } }
        visualRoot = null
        visualParams = null
        touchView = null
        touchParams = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        removeWindows()
        _running.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeWindows()
        _running.value = false
        super.onDestroy()
    }
}
