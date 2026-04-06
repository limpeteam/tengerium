package com.limpe.tengerium.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.isNotEmpty
import androidx.customview.widget.ViewDragHelper
import androidx.slidingpanelayout.widget.SlidingPaneLayout

/**
 * SwipeBackLayout, который делегирует свайп родителю (SlidingPaneLayout) для полной бесшовности.
 */
class SwipeBackLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var onSwipeBackListener: (() -> Unit)? = null
    private var dragHelper: ViewDragHelper? = null
    
    private var isEdgeDragPossible = false
    private var isCapturedByParent = false
    
    private var initialX = 0f
    private var initialY = 0f
    
    var isSwipeEnabled: Boolean = true

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val edgeSize = (60 * resources.displayMetrics.density).toInt()

    init {
        dragHelper = ViewDragHelper.create(this, 1.0f, object : ViewDragHelper.Callback() {
            override fun tryCaptureView(child: View, pointerId: Int): Boolean {
                return isSwipeEnabled && isEdgeDragPossible && !shouldDelegateToParent() && isNotEmpty() && child == getChildAt(0)
            }
            override fun getViewHorizontalDragRange(child: View): Int = width
            override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int = left.coerceIn(0, width)
            override fun onViewPositionChanged(changedView: View, left: Int, top: Int, dx: Int, dy: Int) {
                if (left >= width && dragHelper?.viewDragState == ViewDragHelper.STATE_IDLE) {
                    onSwipeBackListener?.invoke()
                }
            }
            override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
                if (xvel > 500 || releasedChild.left > width / 3) {
                    dragHelper?.settleCapturedViewAt(width, 0)
                } else {
                    dragHelper?.settleCapturedViewAt(0, 0)
                }
                invalidate()
            }
            override fun onViewDragStateChanged(state: Int) {
                if (state == ViewDragHelper.STATE_IDLE && isNotEmpty() && getChildAt(0).left >= width) {
                    onSwipeBackListener?.invoke()
                }
            }
        })
    }

    private fun shouldDelegateToParent(): Boolean {
        val parentPane = findParentSlidingPane() ?: return false
        return parentPane.isSlideable && parentPane.lockMode == SlidingPaneLayout.LOCK_MODE_UNLOCKED
    }

    private fun findParentSlidingPane(): SlidingPaneLayout? {
        var p = parent
        while (p != null) {
            if (p is SlidingPaneLayout) return p
            p = p.parent
        }
        return null
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isSwipeEnabled) return super.dispatchTouchEvent(ev)

        val x = ev.x
        val y = ev.y

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = x
                initialY = y
                isEdgeDragPossible = x <= edgeSize
                isCapturedByParent = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (isEdgeDragPossible && !isCapturedByParent) {
                    val dx = x - initialX
                    val dy = Math.abs(y - initialY)
                    
                    if (dx > touchSlop && dx > dy) {
                        if (shouldDelegateToParent()) {
                            isCapturedByParent = true
                            parent?.requestDisallowInterceptTouchEvent(false)
                            
                            val cancelEvent = MotionEvent.obtain(ev)
                            cancelEvent.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                            return false
                        }
                    }
                }
            }
        }

        if (isCapturedByParent) return false

        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (isCapturedByParent) return false
        return dragHelper?.shouldInterceptTouchEvent(ev) ?: false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (isCapturedByParent) return false
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
        }
        try {
            dragHelper?.processTouchEvent(ev)
        } catch (_: Exception) {}
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setOnSwipeBackListener(listener: () -> Unit) {
        this.onSwipeBackListener = listener
    }

    override fun computeScroll() {
        if (dragHelper?.continueSettling(true) == true) {
            ViewCompat.postInvalidateOnAnimation(this)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (isNotEmpty() && !isCapturedByParent) {
            val child = getChildAt(0)
            if (child.left > 0) {
                val progress = child.left.toFloat() / width
                canvas.drawARGB((128 * (1 - progress)).toInt(), 0, 0, 0)
            }
        }
        super.dispatchDraw(canvas)
    }
}
