package com.limpe.tengerium.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

class SwipeToCloseListener(
    private val view: View,
    private val onSwipeClose: () -> Unit
) : View.OnTouchListener {

    private var startX = 0f
    private var startY = 0f
    private var isSwiping = false
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val closeThreshold = 0.3f 

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                isSwiping = false
                return false // Allow children to handle touch initially
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - startX
                val deltaY = event.rawY - startY

                if (!isSwiping && deltaX > touchSlop && deltaX > abs(deltaY) * 1.5) {
                    isSwiping = true
                    // Disallow parent/children from intercepting further
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }

                if (isSwiping) {
                    val translation = if (deltaX > 0) deltaX else 0f
                    view.translationX = translation
                    view.alpha = 1f - (translation / v.width).coerceAtMost(0.5f)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSwiping) {
                    val deltaX = event.rawX - startX
                    if (deltaX > v.width * closeThreshold) {
                        view.animate()
                            .translationX(v.width.toFloat())
                            .alpha(0f)
                            .setDuration(200)
                            .withEndAction { onSwipeClose() }
                            .start()
                    } else {
                        view.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(200)
                            .start()
                    }
                    isSwiping = false
                    v.performClick()
                    return true
                }
                isSwiping = false
            }
        }
        return false
    }
}