package com.limpe.tengerium.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.CycleInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.PopupWindow
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.limpe.tengerium.R
import kotlinx.coroutines.*

/**
 * Централизованное хранилище анимаций приложения.
 */
object AnimationHelper {

    /**
     * Эффект "тряски" для Nudge.
     */
    fun animateNudge(view: View) {
        val density = view.resources.displayMetrics.density
        val offset = 12f * density // 12dp
        
        val pvhX = PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, offset)
        val pvhY = PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, offset / 2)
        
        ObjectAnimator.ofPropertyValuesHolder(view, pvhX, pvhY).apply {
            duration = 500
            // CycleInterpolator(5) заставит значение "сходить" туда-обратно 5 раз
            interpolator = CycleInterpolator(5f)
            start()
        }
    }

    /**
     * Анимация логотипа на экране входа.
     */
    fun animateLoginLogo(logoView: View?) {
        logoView ?: return
        logoView.alpha = 0f
        logoView.scaleX = 0.2f
        logoView.scaleY = 0.2f
        
        logoView.animate()
            .alpha(1f)
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(700)
            .setInterpolator(OvershootInterpolator(2f))
            .withEndAction {
                logoView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300)
                    .start()
            }
            .start()
    }

    /**
     * Анимация появления полей ввода на экране входа.
     */
    fun animateLoginInputs(views: List<View?>, baseDelay: Long = 500L) {
        views.filterNotNull().forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 100f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(600)
                .setStartDelay(baseDelay + (index * 100L))
                .setInterpolator(OvershootInterpolator(0.8f))
                .start()
        }
    }

    /**
     * Анимация появления сообщения.
     */
    fun animateMessagePop(view: View) {
        view.alpha = 0f
        view.scaleX = 0.8f
        view.scaleY = 0.8f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    /**
     * Анимация изменения статуса.
     */
    fun animateStatusChange(view: View, color: Int) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.5f)
            .scaleY(0.5f)
            .setDuration(150)
            .withEndAction {
                view.background?.setTint(color)
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(250)
                    .setInterpolator(AnticipateOvershootInterpolator())
                    .start()
            }
            .start()
    }

    /**
     * Анимация появления In-App уведомления.
     */
    fun animateNotificationIn(view: View) {
        view.translationY = -300f
        view.alpha = 0f
        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(UiConstants.ANIM_DURATION_LONG)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    /**
     * Анимация исчезновения In-App уведомления.
     */
    fun animateNotificationOut(view: View, onEnd: () -> Unit) {
        view.animate()
            .translationY(-300f)
            .alpha(0f)
            .setDuration(UiConstants.ANIM_DURATION_LONG)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { onEnd() }
            .start()
    }

    /**
     * Анимация набора текста (троеточие).
     * Возвращает Job, который нужно отменить при остановке.
     */
    fun startTypingAnimation(scope: CoroutineScope, textView: TextView, baseText: String): Job {
        return scope.launch {
            val isTypingSuffix = textView.context.getString(R.string.is_typing)
            val textWithoutDots = baseText.removeSuffix(isTypingSuffix).trim()
            var dots = 1
            while (isActive) {
                textView.text = "$textWithoutDots $isTypingSuffix${".".repeat(dots)}"
                dots = if (dots >= 3) 1 else dots + 1
                delay(500)
            }
        }
    }
    
    /**
     * Анимация появления/скрытия (Alpha).
     */
    fun fadeVisibility(view: View, isVisible: Boolean, duration: Long = UiConstants.ANIM_DURATION_SHORT) {
        if (isVisible) {
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(duration).start()
        } else {
            view.animate().alpha(0f).setDuration(duration).withEndAction {
                view.visibility = View.GONE
            }.start()
        }
    }

    /**
     * Анимация перехода для экрана загрузки/авторизации.
     */
    fun animateLoadingState(mainView: View?, loadingView: View?, isLoading: Boolean) {
        if (isLoading) {
            mainView?.isEnabled = false
            mainView?.animate()?.alpha(0f)?.setDuration(UiConstants.ANIM_DURATION_MEDIUM)?.start()
            
            loadingView?.apply {
                alpha = 0f
                scaleX = 0.5f
                scaleY = 0.5f
                visibility = View.VISIBLE
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(UiConstants.ANIM_DURATION_MEDIUM)
                    .setListener(null)
                    .start()
            }
        } else {
            mainView?.isEnabled = true
            mainView?.animate()?.alpha(1f)?.setDuration(UiConstants.ANIM_DURATION_MEDIUM)?.start()
            
            loadingView?.animate()
                ?.alpha(0f)
                ?.scaleX(0.5f)
                ?.scaleY(0.5f)
                ?.setDuration(UiConstants.ANIM_DURATION_SHORT)
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        loadingView.visibility = View.GONE
                    }
                })?.start()
        }
    }

    /**
     * Анимация подсветки элемента (Highlight).
     */
    fun animateHighlight(view: View): ValueAnimator? {
        val colorFrom = Color.TRANSPARENT
        val colorTo = try {
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary)
        } catch (e: Exception) {
            Color.CYAN
        }
        
        return ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo, colorFrom).apply {
            duration = 1000
            repeatCount = 2
            addUpdateListener { anim ->
                view.setBackgroundColor(anim.animatedValue as Int)
            }
            start()
        }
    }

    /**
     * Анимация появления списка сервисов (плавное выплывание снизу вверх).
     */
    fun animateServiceItemIn(view: View, delayMs: Long) {
        view.alpha = 0f
        view.translationY = 100f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(delayMs)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    /**
     * Анимация появления вкладки (Scale + Alpha).
     */
    fun animateTabIn(view: View) {
        view.alpha = 0f
        view.scaleX = 0.8f
        view.scaleY = 0.8f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setInterpolator(AnticipateOvershootInterpolator())
            .start()
    }

    /**
     * Чтоб новое контекстное меню не проебалось и не получило пизды.
     */
    fun showSmartPopup(popup: PopupWindow, anchor: View, preferredXOffset: Int = 0, preferredYOffset: Int = 0) {
        val menuView = popup.contentView
        menuView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val menuWidth = menuView.measuredWidth
        val menuHeight = menuView.measuredHeight

        val screenPos = IntArray(2)
        anchor.getLocationOnScreen(screenPos)
        val anchorX = screenPos[0]
        val anchorY = screenPos[1]

        val displayMetrics = anchor.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Рассчитываем идеальные координаты
        var finalX = anchorX + preferredXOffset
        var finalY = anchorY + anchor.height + preferredYOffset

        // Проверка по горизонтали
        if (finalX + menuWidth > screenWidth) {
            finalX = screenWidth - menuWidth - 16
        }
        if (finalX < 16) finalX = 16

        // Проверка по вертикали (если снизу не влезает - показываем сверху)
        if (finalY + menuHeight > screenHeight) {
            finalY = anchorY - menuHeight - preferredYOffset
        }
        
        // ГЛОБАЛЬНЫЙ ОГРАНИЧИТЕЛЬ: не залезать выше тулбара (примерно 80dp от верха)
        val topLimit = (80 * displayMetrics.density).toInt()
        if (finalY < topLimit) finalY = topLimit

        popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, finalX, finalY)
        
        // Добавляем микро-анимацию появления
        menuView.alpha = 0f
        menuView.scaleX = 0.9f
        menuView.scaleY = 0.9f
        menuView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(OvershootInterpolator(1.0f))
            .start()
    }
}
