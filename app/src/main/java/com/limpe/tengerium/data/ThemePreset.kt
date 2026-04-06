package com.limpe.tengerium.data

import android.graphics.Color
import androidx.core.graphics.toColorInt

/**
 * Цвета для конкретной темы (светлой или темной).
 */
data class ThemeColors(
    val accentColor: Int,
    val incomingBubbleColor: Int,
    val outgoingBubbleColor: Int,
    val chatBackgroundColor: Int? = null,
    val onIncomingTextColor: Int = Color.BLACK,
    val onOutgoingTextColor: Int = Color.WHITE
)

/**
 * Пресеты оформления чата.
 */
data class ThemePreset(
    val name: String,
    val light: ThemeColors,
    val dark: ThemeColors
) {
    companion object {
        val presets = listOf(
            // Стандартная
            ThemePreset(
                "Standard",
                light = ThemeColors(
                    accentColor = "#409BD9".toColorInt(), // hsl(203, 67%, 55%)
                    incomingBubbleColor = "#CCCCCC".toColorInt(), // hsl(0, 0%, 80%)
                    outgoingBubbleColor = "#409BD9".toColorInt(), // hsl(203, 67%, 55%)
                    onIncomingTextColor = Color.BLACK,
                    onOutgoingTextColor = Color.WHITE
                ),
                dark = ThemeColors(
                    accentColor = "#2B5278".toColorInt(), // hsl(210, 47%, 32%)
                    incomingBubbleColor = "#182634".toColorInt(), // hsl(210, 36%, 15%)
                    outgoingBubbleColor = "#2B5278".toColorInt(), // hsl(210, 47%, 32%)
                    onIncomingTextColor = Color.WHITE,
                    onOutgoingTextColor = Color.WHITE
                )
            ),
            // Бирюза
            ThemePreset(
                "Turquoise",
                light = ThemeColors(
                    accentColor = "#DDF7FD".toColorInt(), // hsl(196, 88%, 93%)
                    incomingBubbleColor = "#FFFFFF".toColorInt(), // hsl(0, 0%, 100%)
                    outgoingBubbleColor = "#DDF7FD".toColorInt(), // hsl(196, 88%, 93%)
                    chatBackgroundColor = "#77C8E3".toColorInt(), // hsl(197, 66%, 68%)
                    onIncomingTextColor = Color.BLACK,
                    onOutgoingTextColor = Color.BLACK
                ),
                dark = ThemeColors(
                    accentColor = "#2D768F".toColorInt(), // hsl(196, 52%, 37%)
                    incomingBubbleColor = "#1A333C".toColorInt(), // hsl(197, 40%, 17%)
                    outgoingBubbleColor = "#2D768F".toColorInt(), // hsl(196, 52%, 37%)
                    chatBackgroundColor = "#102128".toColorInt(), // hsl(200, 44%, 11%)
                    onIncomingTextColor = Color.WHITE,
                    onOutgoingTextColor = Color.WHITE
                )
            )
        )
    }
}
