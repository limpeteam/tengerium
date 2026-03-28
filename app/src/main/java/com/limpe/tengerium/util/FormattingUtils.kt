package com.limpe.tengerium.util

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.limpe.tengerium.R
import java.text.SimpleDateFormat
import java.util.Calendar
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
     * Форматирует дату для списка чатов:
     * - Сегодня: HH:mm
     * - Вчера: "Вчера"
     * - Позавчера и далее: dd.MM (или MM.dd в зависимости от локали)
     */
    fun formatChatDate(context: Context, timestamp: Long): String {
        val now = Calendar.getInstance()
        val date = Calendar.getInstance().apply { timeInMillis = timestamp }

        return when {
            isSameDay(now, date) -> {
                timeFormatter.format(date.time)
            }
            isYesterday(now, date) -> {
                context.getString(R.string.yesterday)
            }
            else -> {
                val pattern = if (DateFormat.getDateFormatOrder(context).indexOf('d') < DateFormat.getDateFormatOrder(context).indexOf('M')) {
                    "dd.MM"
                } else {
                    "MM.dd"
                }
                SimpleDateFormat(pattern, Locale.getDefault()).format(date.time)
            }
        }
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(now: Calendar, date: Calendar): Boolean {
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = now.timeInMillis
            add(Calendar.DAY_OF_YEAR, -1)
        }
        return isSameDay(yesterday, date)
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
