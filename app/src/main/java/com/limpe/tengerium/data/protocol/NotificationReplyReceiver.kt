package com.limpe.tengerium.data.protocol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.limpe.tengerium.TengeriumApp

class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (MSNPService.ACTION_REPLY == intent.action) {
            val account = intent.getStringExtra("contact_account") ?: return
            val remoteInput = RemoteInput.getResultsFromIntent(intent)
            val replyText = remoteInput?.getCharSequence(MSNPService.KEY_TEXT_REPLY)?.toString()

            if (!replyText.isNullOrBlank()) {
                val repository = (context.applicationContext as TengeriumApp).repository
                repository.sendMessage(account, replyText)

                // Update notification to show it was sent or just dismiss it
                val notificationManager = NotificationManagerCompat.from(context)
                notificationManager.cancel(account.hashCode())
            }
        }
    }
}
