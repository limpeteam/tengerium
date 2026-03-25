package com.limpe.tengerium.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

class SwipeBackLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var startX = 0f
    private var startY = 0f
    private var isSwiping = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var onSwipeBackListener: (() -> Unit)? = null

    fun setOnSwipeBackListener(listener: () -> Unit) {
        this.onSwipeBackListener = listener
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.rawX
                startY = ev.rawY
                isSwiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = ev.rawX - startX
                val deltaY = ev.rawY - startY
                // Start swipe if it's from the left edge and horizontal enough
                if (startX < 150 && deltaX > touchSlop && deltaX > abs(deltaY) * 1.5) {
                    isSwiping = true
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isSwiping) return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - startX
                translationX = if (deltaX > 0) deltaX else 0f
                alpha = 1f - (translationX / width).coerceAtMost(0.5f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (translationX > width * 0.3f) {
                    startExitAnimation()
                } else {
                    animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(250)
                        .start()
                }
                isSwiping = false
                performClick()
            }
        }
        return true
    }

    fun startExitAnimation() {
        animate()
            .translationX(width.toFloat())
            .alpha(0f)
            .setDuration(250)
            .withEndAction { onSwipeBackListener?.invoke() }
            .start()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
