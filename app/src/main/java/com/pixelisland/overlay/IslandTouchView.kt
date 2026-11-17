package com.pixelisland.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Invisible view filling the small touch window that sits exactly over the pill. It draws
 * nothing, so moving/resizing its window can never cause visual glitches.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class IslandTouchView(context: Context, private val controller: IslandController) : View(context) {

    private val swipeThreshold = 36f * resources.displayMetrics.density
    private var downX = 0f
    private var longPressed = false

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            controller.gestureHandler?.onTap()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            longPressed = true
            controller.gestureHandler?.onLongPress()
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                longPressed = false
                controller.pressed = true
            }
            MotionEvent.ACTION_UP -> {
                controller.pressed = false
                val dx = event.rawX - downX
                if (!longPressed && abs(dx) > swipeThreshold) {
                    controller.gestureHandler?.onSwipe(if (dx < 0) -1 else 1)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> controller.pressed = false
        }
        detector.onTouchEvent(event)
        return true
    }
}
