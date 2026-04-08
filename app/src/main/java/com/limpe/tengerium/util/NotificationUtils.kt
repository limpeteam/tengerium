package com.limpe.tengerium.util

import android.content.Context
import android.content.pm.PackageManager
import com.limpe.tengerium.R

object NotificationUtils {
    fun getSmallIconId(context: Context): Int {
        return try {
            val ai = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            val bundle = ai.metaData
            if (bundle != null && bundle.containsKey("com.google.firebase.messaging.default_notification_icon")) {
                bundle.getInt("com.google.firebase.messaging.default_notification_icon")
            } else {
                R.drawable.ntficn
            }
        } catch (e: Exception) {
            R.drawable.ntficn
        }
    }
}
