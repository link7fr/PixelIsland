package com.pixelisland.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Root of the overlay window. With FLAG_WATCH_OUTSIDE_TOUCH the system sends ACTION_OUTSIDE
 * when the user taps anywhere outside the island — we use that to collapse the card.
 */
@SuppressLint("ViewConstructor")
class IslandRootLayout(
    context: Context,
    private val onOutsideTouch: () -> Unit,
) : FrameLayout(context) {

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch()
            return false
        }
        return super.dispatchTouchEvent(ev)
    }
}
