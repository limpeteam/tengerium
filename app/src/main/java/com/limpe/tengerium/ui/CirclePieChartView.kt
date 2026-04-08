package com.limpe.tengerium.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.limpe.tengerium.R

/**
 * Кастомная View для отрисовки анимированной круговой диаграммы.
 * Оптимизирована: мелкие сегменты рисуются ПОВЕРХ больших.
 */
class CirclePieChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()
    private var currentSlices: MutableList<PieSlice> = mutableListOf()
    private val drawQueue = mutableListOf<ArcOrder>()
    private var strokeWidthPx = 40f
    private var animator: ValueAnimator? = null

    data class PieSlice(val id: String, var percentage: Float, val colorRes: Int)

    private data class ArcOrder(val slice: PieSlice, val startAngle: Float, val sweepAngle: Float)

    fun setData(newData: List<PieSlice>, animate: Boolean = true) {
        val targetSlices = newData.map { it.copy() }

        if (!animate) {
            currentSlices = targetSlices.toMutableList()
            invalidate()
            return
        }

        if (currentSlices.isEmpty()) {
            currentSlices = targetSlices.map { it.copy(percentage = 0f) }.toMutableList()
        }

        animator?.cancel()

        val startMap = currentSlices.associate { it.id to it.percentage }
        val endMap = targetSlices.associate { it.id to it.percentage }
        val colorsMap = targetSlices.associate { it.id to it.colorRes }
        val allIds = (startMap.keys + endMap.keys).distinct()

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                val progress = anim.animatedValue as Float
                val updatedList = mutableListOf<PieSlice>()
                allIds.forEach { id ->
                    val start = startMap[id] ?: 0f
                    val end = endMap[id] ?: 0f
                    val color = colorsMap[id] ?: currentSlices.find { it.id == id }?.colorRes ?: R.color.status_offline
                    val currentVal = start + (end - start) * progress
                    if (currentVal > 0.01f || end > 0.01f) {
                        updatedList.add(PieSlice(id, currentVal, color))
                    }
                }
                currentSlices = updatedList
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val center = minOf(width, height) / 2f
        val radius = center - strokeWidthPx
        rect.set(center - radius, center - radius, center + radius, center + radius)
        
        paint.strokeWidth = strokeWidthPx
        
        // Фоновый трек
        paint.color = ContextCompat.getColor(context, R.color.status_offline)
        paint.alpha = 40
        canvas.drawCircle(center, center, radius, paint)
        paint.alpha = 255

        drawQueue.clear()
        var currentStartAngle = -90f
        
        // 1. Рассчитываем геометрию всех сегментов
        currentSlices.forEach { slice ->
            val sweepAngle = slice.percentage * 360f / 100f
            if (slice.percentage > 0f) {
                // Минимальный визуальный размер 5 градусов, чтобы сегмент был виден поверх стыков
                val effectiveSweep = if (sweepAngle < 5f) 5f else sweepAngle
                drawQueue.add(ArcOrder(slice, currentStartAngle, effectiveSweep))
            }
            currentStartAngle += sweepAngle
        }

        // 2. СОРТИРОВКА ДЛЯ СЛОЕВ:
        // Сначала рисуем те, что БОЛЬШЕ (они снизу).
        // В конце рисуем те, что МЕНЬШЕ (они сверху).
        drawQueue.sortByDescending { it.slice.percentage }

        // 3. Отрисовка
        for (arc in drawQueue) {
            paint.color = ContextCompat.getColor(context, arc.slice.colorRes)
            // Рисуем дугу. Поскольку мы идем от больших к меньшим, маленькие перекроют стыки больших.
            canvas.drawArc(rect, arc.startAngle, arc.sweepAngle, false, paint)
        }
    }
}
