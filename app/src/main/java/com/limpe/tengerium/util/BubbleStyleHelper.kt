package com.limpe.tengerium.util

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.limpe.tengerium.data.ThemePreset
import com.limpe.tengerium.data.security.SecurePrefs

object BubbleStyleHelper {
    fun applyStyle(
        card: MaterialCardView,
        textView: TextView,
        timestampView: TextView?,
        isIncoming: Boolean,
        securePrefs: SecurePrefs
    ) {
        val context = card.context
        val useMaterialYou = securePrefs.useMaterialYou

        card.strokeWidth = 0
        card.cardElevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, context.resources.displayMetrics)
        
        val radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics)
        card.radius = radius

        if (useMaterialYou) {
            val colorAttr = if (isIncoming) com.google.android.material.R.attr.colorSecondaryContainer else com.google.android.material.R.attr.colorPrimaryContainer
            val onColorAttr = if (isIncoming) com.google.android.material.R.attr.colorOnSecondaryContainer else com.google.android.material.R.attr.colorOnPrimaryContainer
            
            val color = MaterialColors.getColor(context, colorAttr, 0)
            val onColor = MaterialColors.getColor(context, onColorAttr, 0)
            
            card.setCardBackgroundColor(color)
            textView.setTextColor(onColor)
            timestampView?.setTextColor(onColor)
            textView.setLinkTextColor(onColor)
        } else {
            val isDark = isDarkTheme(context, securePrefs)
            val preset = ThemePreset.presets.getOrNull(securePrefs.themePreset) ?: ThemePreset.presets[0]
            val themeColors = if (isDark) preset.dark else preset.light
            
            val bubbleColor = if (isIncoming) themeColors.incomingBubbleColor else themeColors.outgoingBubbleColor
            val textColor = if (isIncoming) themeColors.onIncomingTextColor else themeColors.onOutgoingTextColor
            
            card.setCardBackgroundColor(bubbleColor)
            textView.setTextColor(textColor)
            timestampView?.setTextColor(textColor)
            textView.setLinkTextColor(textColor)
        }
        timestampView?.alpha = 0.6f
    }

    fun isDarkTheme(context: Context, securePrefs: SecurePrefs): Boolean {
        return if (securePrefs.followSystemTheme) {
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        } else {
            securePrefs.darkTheme
        }
    }
}
