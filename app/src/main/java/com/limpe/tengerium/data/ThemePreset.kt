package com.limpe.tengerium.data

import android.graphics.Color
import androidx.core.graphics.toColorInt

/**
 * Пресеты оформления чата.
 */
data class ThemePreset(
    val name: String,
    val accentColor: Int,
    val incomingBubbleColor: Int,
    val outgoingBubbleColor: Int,
    val onIncomingTextColor: Int = Color.BLACK,
    val onOutgoingTextColor: Int = Color.WHITE
) {
    companion object {
        val presets = listOf(
            ThemePreset(
                "Classic MSN",
                "#0078D4".toColorInt(),
                "#E1E1E1".toColorInt(),
                "#0078D4".toColorInt()
            ),
            ThemePreset(
                "Emerald",
                "#2E7D32".toColorInt(),
                "#E8F5E9".toColorInt(),
                "#2E7D32".toColorInt()
            ),
            ThemePreset(
                "Lavender",
                "#7E57C2".toColorInt(),
                "#F3E5F5".toColorInt(),
                "#7E57C2".toColorInt()
            ),
            ThemePreset(
                "Dark Mode",
                "#BB86FC".toColorInt(),
                "#121212".toColorInt(),
                "#3700B3".toColorInt(),
                onIncomingTextColor = Color.WHITE,
                onOutgoingTextColor = Color.WHITE
            )
        )
    }
}