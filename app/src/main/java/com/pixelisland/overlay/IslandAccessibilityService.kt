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
 * Hosts the island as a TYPE_ACCESSIBILITY_OVERLAY window over the status bar and camera.
 *
 * The window has two fixed sizes and never resizes during an animation:
 *  - small: just around the pill, so the rest of the status bar stays touchable;
 *  - big: full width, tall enough for the card. Entered one frame *before* expanding and left
 *    only after the collapse spring has settled. All motion happens inside Compose.
 */
class IslandAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PixelIsland/Overlay"

        /** Room around the pill for the press "squish" and spring overshoot. */
        const val WINDOW_MARGIN_DP = 12f
        private const val BIG_WINDOW_HEIGHT_DP = 640f

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
    }

    private var windowManager: WindowManager? = null
    private var root: IslandRootLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var scope: CoroutineScope? = null
    private val controller = IslandController()

    private val host = object : IslandController.Host {
        override fun prepareExpand(proceed: () -> Unit) {
            val view = root ?: return proceed()
            if (!controller.bigWindow) {
                controller.bigWindow = true
                applyLayout()
            }
            // Let the window relayout at its new size before the morph begins.
            view.post { view.postOnAnimation { proceed() } }
        }

        override fun onCollapseSettled() {
            if (controller.bigWindow && !controller.expanded) {
                controller.bigWindow = false
                applyLayout()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _running.value = true
        IslandSettings.init(this)
        MediaRepository.attachSessions(this)
        controller.host = host
        try {
            addOverlay()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to add island overlay", t)
            removeOverlay()
            return
        }
        scope = MainScope().also { s ->
            s.launch {
                combine(IslandSettings.state, snapshotFlow { controller.bigWindow }) { a, b -> a to b }
                    .collect { applyLayout() }
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // Taps on the transparent part of the big window collapse the card.
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
        // Owners on the root (view-tree lookup walks up parents) and on the ComposeView itself.
        owner.attachTo(layout)
        owner.attachTo(composeView)

        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "PixelIsland"
        }
        computeLayout(lp, IslandSettings.state.value, big = false)
        params = lp

        wm.addView(layout, lp)
        root = layout
        owner.onResume()
        updateVisibility()
    }

    private fun computeLayout(lp: WindowManager.LayoutParams, s: Settings, big: Boolean) {
        val margin = WINDOW_MARGIN_DP
        if (big) {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = minOf(resources.displayMetrics.heightPixels, dp(BIG_WINDOW_HEIGHT_DP))
            lp.x = 0
        } else {
            lp.width = dp(s.pillWidth + margin * 2)
            lp.height = dp(s.pillHeight + margin * 2)
            lp.x = dp(s.offsetX)
        }
        lp.y = dp(s.offsetY - margin)
    }

    private fun applyLayout() {
        val wm = windowManager ?: return
        val view = root ?: return
        val lp = params ?: return
        val before = Triple(lp.width, lp.height, lp.x) to lp.y
        computeLayout(lp, IslandSettings.state.value, controller.bigWindow)
        if (before != (Triple(lp.width, lp.height, lp.x) to lp.y)) {
            runCatching { wm.updateViewLayout(view, lp) }
        }
        updateVisibility()
    }

    private fun updateVisibility() {
        val settings = IslandSettings.state.value
        val portrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val visible = settings.islandEnabled && portrait
        root?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) controller.collapse()
        applyLayout()
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun removeOverlay() {
        controller.host = null
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

    override fun onUnbind(intent: Intent?): Boolean {
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
