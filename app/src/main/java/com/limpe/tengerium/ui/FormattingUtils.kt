package com.limpe.tengerium.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormattingUtils {
    
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    /**
     * Форматирует временную метку в строку HH:mm.
     */
    fun formatTime(timestamp: Long): String {
        return timeFormatter.format(Date(timestamp))
    }

    /**
     * Преобразует BBCode теги [b], [i], [u] в Spannable и полностью удаляет сами теги.
     */
    fun formatBBCode(text: String?): CharSequence {
        if (text.isNullOrEmpty()) return ""
        
        var ssb = SpannableStringBuilder(text)
        
        ssb = processTag(ssb, "[b]", "[/b]") { StyleSpan(Typeface.BOLD) }
        ssb = processTag(ssb, "[i]", "[/i]") { StyleSpan(Typeface.ITALIC) }
        ssb = processTag(ssb, "[u]", "[/u]") { UnderlineSpan() }
        
        return ssb
    }

    private fun processTag(sb: SpannableStringBuilder, open: String, close: String, spanCreator: () -> Any): SpannableStringBuilder {
        val ssb = SpannableStringBuilder(sb)
        var searchFrom = 0
        while (searchFrom < ssb.length) {
            val currentText = ssb.toString()
            val start = currentText.indexOf(open, searchFrom, ignoreCase = true)
            if (start == -1) break
            
            val end = currentText.indexOf(close, start + open.length, ignoreCase = true)
            if (end == -1) {
                searchFrom = start + open.length
                continue
            }
            
            val innerContent = ssb.subSequence(start + open.length, end)
            val replacement = SpannableStringBuilder(innerContent)
            replacement.setSpan(spanCreator(), 0, replacement.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            
            ssb.replace(start, end + close.length, replacement)
            searchFrom = start
        }
        return ssb
    }
}
