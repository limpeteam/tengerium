package com.limpe.tengerium.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

object SoundUtils {
    /**
     * Проигрывает звук из ресурсов.
     * Используется только для событий Nudge и Online.
     */
    fun playSound(context: Context, resId: Int) {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // Используем перегрузку create, которая принимает AudioAttributes,
            // чтобы они применились до подготовки (prepare) плеера.
            val mediaPlayer = MediaPlayer.create(context, resId, audioAttributes, 0)
            
            if (mediaPlayer == null) {
                Log.e("SoundUtils", "Не удалось создать MediaPlayer для ресурса: $resId")
                return
            }

            mediaPlayer.setOnCompletionListener { mp ->
                mp.stop()
                mp.release()
            }
            
            mediaPlayer.start()
            Log.d("SoundUtils", "Воспроизведение звука запущено: $resId")
        } catch (e: Exception) {
            Log.e("SoundUtils", "Ошибка при воспроизведении звука: ${e.message}", e)
        }
    }
}
