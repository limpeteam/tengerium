package com.limpe.tengerium.data.protocol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Vibrator
import android.os.VibrationEffect
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.limpe.tengerium.MainActivity
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.MSNPLoginState
import com.limpe.tengerium.data.security.SecurePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MSNPService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isForeground = false
    private var isRepositoryObserved = false
    
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var lastStatusBeforeCall: String = "NLN"
    
    private lateinit var securePrefs: SecurePrefs
    private val contactStatuses = mutableMapOf<String, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Вызываем startForeground немедленно в onCreate
        updateForegroundNotification(null)
        
        securePrefs = SecurePrefs(this)
        setupPhoneStateListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRepositoryObserved) {
            observeRepository()
            isRepositoryObserved = true
        }
        return START_STICKY
    }

    private fun setupPhoneStateListener() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm == null) return
        
        telephonyManager = tm
        try {
            phoneStateListener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    if (!securePrefs.phoneStatusEnabled) return

                    val repository = (application as TengeriumApp).repository
                    when (state) {
                        TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_RINGING -> {
                            val currentStatus = repository.currentStatus
                            if (currentStatus != "PHN") {
                                lastStatusBeforeCall = currentStatus
                                Log.d("MSNPService", "Call started. Changing status to PHN. Previous: $lastStatusBeforeCall")
                                repository.changeStatus("PHN")
                            }
                        }
                        TelephonyManager.CALL_STATE_IDLE -> {
                            if (repository.currentStatus == "PHN") {
                                Log.d("MSNPService", "Call ended. Restoring status to $lastStatusBeforeCall")
                                repository.changeStatus(lastStatusBeforeCall)
                            }
                        }
                    }
                }
            }
            tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: SecurityException) {
            Log.w("MSNPService", "READ_PHONE_STATE permission not granted. Call state listener disabled.")
            phoneStateListener = null
        } catch (e: Exception) {
            Log.e("MSNPService", "Failed to setup PhoneStateListener: ${e.message}")
            phoneStateListener = null
        }
    }

    private fun observeRepository() {
        val repository = (application as TengeriumApp).repository
        serviceScope.launch {
            repository.loginState.collectLatest { state ->
                when (state) {
                    is MSNPLoginState.Success -> {
                        updateForegroundNotification(state.nickname)
                    }
                    is MSNPLoginState.Loading, is MSNPLoginState.Reconnecting -> {
                        updateForegroundNotification(getString(R.string.status_trying_to_connect))
                    }
                    else -> {
                        updateForegroundNotification(null)
                    }
                }
            }
        }
        
        serviceScope.launch {
            repository.contacts.collectLatest { list ->
                if (!securePrefs.notifyLogin) {
                    list.forEach { contactStatuses[it.account.lowercase(Locale.ROOT)] = it.status }
                    return@collectLatest
                }
                
                list.forEach { contact ->
                    val account = contact.account.lowercase(Locale.ROOT)
                    val oldStatus = contactStatuses[account]
                    val newStatus = contact.status
                    
                    if (oldStatus != null && oldStatus == MSNPProto.Status.OFFLINE && newStatus != MSNPProto.Status.OFFLINE) {
                        val nickname = contact.nickname.ifEmpty { contact.account }
                        showEventNotification(
                            this@MSNPService,
                            getString(R.string.app_name),
                            getString(R.string.user_is_now_online, nickname),
                            "LOGIN_$account"
                        )
                    }
                    contactStatuses[account] = newStatus
                }
            }
        }
    }

    private fun updateForegroundNotification(statusText: String?) {
        val notification = createForegroundNotification(statusText)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID_FOREGROUND, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID_FOREGROUND, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e("MSNPService", "Failed to start foreground service: ${e.message}")
        }
    }

    private fun createForegroundNotification(statusText: String?): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (statusText != null) {
            if (statusText == getString(R.string.status_trying_to_connect)) statusText else getString(R.string.logged_in_as, statusText)
        } else {
            getString(R.string.status_offline)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_status_dot)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val fgChannel = NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                getString(R.string.channel_connection_status),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            manager.createNotificationChannel(fgChannel)

            val msgChannel = NotificationChannel(
                CHANNEL_ID_MESSAGES,
                getString(R.string.channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableLights(true)
                enableVibration(true)
            }
            manager.createNotificationChannel(msgChannel)

            val eventChannel = NotificationChannel(
                CHANNEL_ID_EVENTS,
                getString(R.string.channel_contact_events),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                enableLights(true)
                enableVibration(true)
            }
            manager.createNotificationChannel(eventChannel)
        }
    }

    override fun onDestroy() {
        try {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        } catch (e: Exception) {
            // Ignore
        }
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID_FOREGROUND = 1001
        private const val NOTIFICATION_ID_EVENT = 1002
        private const val CHANNEL_ID_FOREGROUND = "msnp_connection"
        private const val CHANNEL_ID_MESSAGES = "messages"
        private const val CHANNEL_ID_EVENTS = "events"
        
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val ACTION_REPLY = "com.limpe.tengerium.ACTION_REPLY"

        fun start(context: Context) {
            val intent = Intent(context, MSNPService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MSNPService::class.java)
            context.stopService(intent)
        }

        fun showMessageNotification(context: Context, nickname: String, message: String, sender: String) {
            val prefs = SecurePrefs(context)
            if (!prefs.notifyMessages) return

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("contact_account", sender)
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context, sender.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
                .setLabel(context.getString(R.string.type_reply))
                .build()

            val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
                action = ACTION_REPLY
                putExtra("contact_account", sender)
            }

            val replyPendingIntent = PendingIntent.getBroadcast(
                context, sender.hashCode(), replyIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val replyAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send,
                context.getString(R.string.reply),
                replyPendingIntent
            ).addRemoteInput(remoteInput).build()

            val effectiveNickname = nickname.ifEmpty { sender }
            val displayMessage = if (message == "[NUDGE]") {
                context.getString(R.string.nudge_received_sys, effectiveNickname)
            } else {
                message
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
                .setSmallIcon(R.drawable.ic_status_dot)
                .setContentTitle(effectiveNickname)
                .setContentText(displayMessage)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(replyAction)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

            if (prefs.vibrationEnabled) {
                builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            } else {
                builder.setDefaults(NotificationCompat.DEFAULT_LIGHTS or NotificationCompat.DEFAULT_SOUND)
                builder.setVibrate(longArrayOf(0L))
            }

            notificationManager.notify(sender.hashCode(), builder.build())

            if (message == "[NUDGE]" && prefs.notifyNudge && prefs.vibrationEnabled) {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 200), -1))
                } else {
                    vibrator.vibrate(longArrayOf(0, 200, 100, 200, 100, 200), -1)
                }
            }
        }

        fun showEventNotification(context: Context, title: String, message: String, tag: String) {
            val prefs = SecurePrefs(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, tag.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID_EVENTS)
                .setSmallIcon(R.drawable.ic_status_dot)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            if (prefs.vibrationEnabled) {
                builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            } else {
                builder.setDefaults(NotificationCompat.DEFAULT_LIGHTS or NotificationCompat.DEFAULT_SOUND)
                builder.setVibrate(longArrayOf(0L))
            }

            notificationManager.notify(tag, NOTIFICATION_ID_EVENT, builder.build())
            
            if (tag.startsWith("NUDGE") && prefs.notifyNudge && prefs.vibrationEnabled) {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 200), -1))
                } else {
                    vibrator.vibrate(longArrayOf(0, 200, 100, 200, 100, 200), -1)
                }
            }
        }
    }
}
